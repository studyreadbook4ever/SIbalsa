# SPDX-License-Identifier: Unlicense
"""Numerical coordination only: no language model, account, or conversation store.

TLS terminates at this coordinator. Individual updates are visible to its operator.
This implementation provides neither secure aggregation nor differential privacy.
"""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
import secrets
import sqlite3
import statistics
import threading
import time
from dataclasses import asdict, dataclass
from contextlib import contextmanager
from pathlib import Path
from typing import Callable


class ProtocolError(Exception):
    """An intentionally opaque, machine-readable protocol failure."""

    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code, self.status = code, status


def exact_object(value: object, keys: set[str]) -> dict:
    if type(value) is not dict or set(value) != keys:
        raise ProtocolError("invalid_fields")
    return value


def integer(value: object, low: int, high: int) -> int:
    if type(value) is not int or not low <= value <= high:
        raise ProtocolError("invalid_integer")
    return value


def finite_vector(value: object, dimension: int) -> list[float]:
    if type(value) is not list or len(value) != dimension:
        raise ProtocolError("invalid_dimension")
    result = []
    for entry in value:
        if type(entry) not in (int, float):
            raise ProtocolError("invalid_vector")
        try:
            number = float(entry)
        except (ValueError, OverflowError):
            raise ProtocolError("invalid_vector") from None
        if not math.isfinite(number):
            raise ProtocolError("invalid_vector")
        result.append(number)
    return result


def _unique_object(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ProtocolError("duplicate_fields")
        result[key] = value
    return result


def decode_json(raw: bytes) -> object:
    """Reject UTF-8 errors, duplicate keys, nonstandard constants, and deep JSON."""
    def bad_constant(_: str) -> None:
        raise ProtocolError("invalid_json")
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_unique_object,
                          parse_constant=bad_constant)
    except ProtocolError:
        raise
    except (ValueError, UnicodeError, RecursionError):
        raise ProtocolError("invalid_json") from None


def encode_json(value: object) -> bytes:
    return json.dumps(value, allow_nan=False, separators=(",", ":")).encode("utf-8")


def _digest(token: str) -> str:
    return hashlib.sha256(token.encode("ascii")).hexdigest()


def _clip(vector: list[float], limit: float) -> list[float]:
    norm = math.hypot(*vector)
    if norm <= limit:
        return list(vector)
    return [entry * (limit / norm) for entry in vector]


def valid_proof(lease_id: str, nonce: str, proof: str, bits: int) -> bool:
    digest = hashlib.sha256(f"{lease_id}:{nonce}:{proof}".encode("ascii")).digest()
    return int.from_bytes(digest, "big") < 1 << (256 - bits)


@dataclass(frozen=True)
class Policy:
    model_id: str
    adapter_spec: str
    dimension: int
    min_clients: int = 5
    aggregation: str = "equal"
    max_samples: int = 64
    max_steps: int = 16
    max_l2: float = 0.25
    max_coordinate: float = 0.1
    lease_seconds: int = 900
    pow_bits: int = 0
    max_leases: int = 2048
    max_body_bytes: int = 33554432
    request_seconds: float = 10.0
    max_connections: int = 4
    receipt_seconds: int = 86400
    max_receipts: int = 8192

    def __post_init__(self) -> None:
        if type(self.model_id) is not str or not re.fullmatch(r"[0-9a-f]{64}", self.model_id):
            raise ValueError("model_id must be a lowercase SHA-256 digest")
        if type(self.adapter_spec) is not str or not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", self.adapter_spec):
            raise ValueError("adapter_spec must be a short machine identifier")
        if self.aggregation not in {"equal", "fedavg", "coordinate_median", "trimmed_mean"}:
            raise ValueError("unknown aggregation strategy")
        checks = ((self.dimension, 1, 1000000), (self.min_clients, 2, 256),
                  (self.max_samples, 1, 1000000), (self.max_steps, 1, 100000),
                  (self.lease_seconds, 1, 86400), (self.pow_bits, 0, 24),
                  (self.max_leases, self.min_clients, 100000),
                  (self.max_body_bytes, 1024, 33554432), (self.max_connections, 1, 256),
                  (self.receipt_seconds, 1, 604800), (self.max_receipts, self.min_clients, 100000))
        if any(type(v) is not int or not low <= v <= high for v, low, high in checks):
            raise ValueError("invalid policy bound")
        for bound in (self.max_l2, self.max_coordinate):
            if type(bound) not in (int, float) or not math.isfinite(bound) or not 0 < bound <= 1000000:
                raise ValueError("invalid numerical bound")
        if type(self.request_seconds) not in (int, float) or not math.isfinite(self.request_seconds) or not 0 < self.request_seconds <= 60:
            raise ValueError("invalid request deadline")
        if self.aggregation == "trimmed_mean" and self.min_clients < 5:
            raise ValueError("trimmed_mean requires at least five clients")

    @property
    def limits(self) -> dict:
        return {"max_samples": self.max_samples, "max_steps": self.max_steps,
                "max_l2": self.max_l2, "max_coordinate": self.max_coordinate}


class Coordinator:
    """Thread-safe SQLite-backed coordinator; operators use local methods only.

    Pending numerical deltas are stored until their cohort becomes a candidate.
    Expired lease hashes are deleted and pending deltas are deleted on staging.
    sqlite secure_delete and DELETE journaling reduce retained deleted payloads;
    filesystem backups and media-level erasure remain outside this guarantee.
    """

    def __init__(self, database: str | Path, policy: Policy | None = None,
                 initial_adapter: list[float] | None = None,
                 clock: Callable[[], float] = time.time):
        self._lock = threading.RLock()
        self._clock = clock
        if str(database) != ":memory:":
            descriptor = os.open(str(database), os.O_CREAT | os.O_RDWR, 0o600)
            try:
                os.fchmod(descriptor, 0o600)
            finally:
                os.close(descriptor)
        self._db = sqlite3.connect(str(database), timeout=10, check_same_thread=False)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode=DELETE")
        self._db.execute("PRAGMA secure_delete=ON")
        self._db.execute("PRAGMA foreign_keys=ON")
        self._db.executescript("""
            CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS versions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                parent INTEGER REFERENCES versions(id),
                state TEXT NOT NULL CHECK(state IN ('active','historical','candidate','rejected')),
                evaluation INTEGER NOT NULL DEFAULT 0 CHECK(evaluation IN (-1,0,1)),
                adapter TEXT NOT NULL, created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS leases (
                token_hash TEXT PRIMARY KEY, nonce_hash TEXT NOT NULL,
                base_version INTEGER NOT NULL REFERENCES versions(id),
                expires_at INTEGER NOT NULL,
                used INTEGER NOT NULL DEFAULT 0 CHECK(used IN (0,1))
            );
            CREATE TABLE IF NOT EXISTS contributions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                base_version INTEGER NOT NULL REFERENCES versions(id),
                sample_count INTEGER NOT NULL, delta TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS receipts (
                token_hash TEXT PRIMARY KEY, payload_hash TEXT NOT NULL,
                response TEXT NOT NULL, expires_at INTEGER NOT NULL
            );
        """)
        try:
            existing = self._db.execute("SELECT value FROM metadata WHERE key='policy'").fetchone()
            if existing:
                self.policy = Policy(**json.loads(existing[0]))
                if policy is not None and policy != self.policy:
                    raise ValueError("database policy is immutable; create a new deployment")
            else:
                if policy is None or initial_adapter is None:
                    raise ValueError("new database requires policy and initial adapter")
                self.policy = policy
                initial = finite_vector(initial_adapter, policy.dimension)
                with self._db:
                    self._db.execute("INSERT INTO metadata VALUES ('policy',?)", (encode_json(asdict(policy)).decode(),))
                    self._db.execute("INSERT INTO versions(parent,state,evaluation,adapter,created_at) VALUES(NULL,'active',1,?,?)",
                                     (encode_json(initial).decode(), int(clock())))
            with self._db:
                self._prune()
        except Exception:
            self._db.close()
            raise

    def close(self) -> None:
        with self._lock:
            self._db.close()

    @contextmanager
    def _transaction(self):
        """Serialize checks and writes across threads AND separate operator processes."""
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                yield
                self._db.commit()
            except BaseException:
                self._db.rollback()
                raise

    def _prune(self) -> None:
        self._db.execute("DELETE FROM leases WHERE expires_at<=?", (int(self._clock()),))
        self._db.execute("DELETE FROM receipts WHERE expires_at<=?", (int(self._clock()),))

    def prune(self) -> None:
        with self._transaction():
            self._prune()

    def _active(self) -> sqlite3.Row:
        row = self._db.execute("SELECT * FROM versions WHERE state='active'").fetchone()
        if row is None:
            raise RuntimeError("missing active model")
        return row

    def _candidate(self) -> sqlite3.Row | None:
        return self._db.execute("SELECT * FROM versions WHERE state='candidate'").fetchone()

    def adapter(self) -> dict:
        with self._lock:
            active = self._active()
            return {"model_id": self.policy.model_id, "adapter_spec": self.policy.adapter_spec,
                    "base_version": active["id"], "dimension": self.policy.dimension,
                    "adapter": json.loads(active["adapter"])}

    def public_policy(self) -> dict:
        return {"protocol_version": 1, **self.adapter(), "limits": self.policy.limits,
                "pow_bits": self.policy.pow_bits, "lease_seconds": self.policy.lease_seconds,
                "aggregation": self.policy.aggregation,
                "privacy": {"secure_aggregation": False, "differential_privacy": False,
                            "individual_updates_visible_to_server": True}}

    def issue_lease(self, request: object) -> dict:
        data = exact_object(request, {"model_id", "adapter_spec", "base_version", "dimension"})
        if type(data["model_id"]) is not str or data["model_id"] != self.policy.model_id:
            raise ProtocolError("model_mismatch", 409)
        if type(data["adapter_spec"]) is not str or data["adapter_spec"] != self.policy.adapter_spec:
            raise ProtocolError("adapter_mismatch", 409)
        integer(data["dimension"], self.policy.dimension, self.policy.dimension)
        base = integer(data["base_version"], 1, 2**63 - 1)
        with self._transaction():
            self._prune()
            active = self._active()
            if base != active["id"]:
                raise ProtocolError("stale_version", 409)
            if self._candidate():
                raise ProtocolError("round_closed", 409)
            if self._db.execute("SELECT COUNT(*) FROM leases").fetchone()[0] >= self.policy.max_leases:
                raise ProtocolError("lease_capacity", 429)
            reservation = self._db.execute("SELECT COUNT(*) FROM receipts").fetchone()[0]
            reservation += self._db.execute("SELECT COUNT(*) FROM leases WHERE used=0").fetchone()[0]
            if reservation >= self.policy.max_receipts:
                raise ProtocolError("receipt_capacity", 429)
            lease_id, nonce = secrets.token_urlsafe(24), secrets.token_urlsafe(24)
            expires = int(self._clock()) + self.policy.lease_seconds
            self._db.execute("INSERT INTO leases VALUES(?,?,?,?,0)",
                             (_digest(lease_id), _digest(nonce), base, expires))
            return {"lease_id": lease_id, "nonce": nonce, "expires_at": expires,
                    "base_version": base, "adapter": json.loads(active["adapter"]),
                    "limits": self.policy.limits, "pow_bits": self.policy.pow_bits}

    def accept_update(self, request: object) -> dict:
        data = exact_object(request, {"lease_id", "nonce", "proof", "sample_count", "steps", "delta"})
        for key in ("lease_id", "nonce"):
            if type(data[key]) is not str or not re.fullmatch(r"[A-Za-z0-9_-]{32}", data[key]):
                raise ProtocolError("invalid_lease")
        proof = data["proof"]
        if type(proof) is not str or not re.fullmatch(r"0|[1-9][0-9]{0,19}", proof):
            raise ProtocolError("invalid_proof")
        samples = integer(data["sample_count"], 1, self.policy.max_samples)
        integer(data["steps"], 1, self.policy.max_steps)
        delta = finite_vector(data["delta"], self.policy.dimension)
        if any(abs(entry) > self.policy.max_coordinate for entry in delta):
            raise ProtocolError("coordinate_limit")
        if math.hypot(*delta) > self.policy.max_l2:
            raise ProtocolError("norm_limit")
        delta = _clip(delta, self.policy.max_l2)
        token_hash = _digest(data["lease_id"])
        canonical = json.dumps(data, sort_keys=True, allow_nan=False, separators=(",", ":")).encode("utf-8")
        payload_hash = hashlib.sha256(canonical).hexdigest()
        with self._transaction():
            self._prune()
            receipt = self._db.execute("SELECT * FROM receipts WHERE token_hash=?", (token_hash,)).fetchone()
            if receipt:
                if not secrets.compare_digest(receipt["payload_hash"], payload_hash):
                    raise ProtocolError("replay_mismatch", 409)
                return json.loads(receipt["response"])
            lease = self._db.execute("SELECT * FROM leases WHERE token_hash=?", (token_hash,)).fetchone()
            if lease is None or not secrets.compare_digest(lease["nonce_hash"], _digest(data["nonce"])):
                raise ProtocolError("invalid_lease", 403)
            if lease["expires_at"] <= int(self._clock()):
                self._db.execute("DELETE FROM leases WHERE token_hash=?", (lease["token_hash"],))
                # Committed cleanup happens on the next successful request/status.
                raise ProtocolError("lease_expired", 403)
            if lease["used"]:
                raise ProtocolError("lease_used", 409)
            if lease["base_version"] != self._active()["id"]:
                raise ProtocolError("stale_version", 409)
            if self._candidate():
                raise ProtocolError("round_closed", 409)
            if not valid_proof(data["lease_id"], data["nonce"], proof, self.policy.pow_bits):
                raise ProtocolError("invalid_proof", 403)
            self._db.execute("UPDATE leases SET used=1 WHERE token_hash=?", (lease["token_hash"],))
            self._db.execute("INSERT INTO contributions(base_version,sample_count,delta) VALUES(?,?,?)",
                             (lease["base_version"], samples, encode_json(delta).decode()))
            rows = self._db.execute("SELECT sample_count,delta FROM contributions WHERE base_version=? ORDER BY id",
                                    (lease["base_version"],)).fetchall()
            candidate_id = None
            count = len(rows)
            if count >= self.policy.min_clients:
                active = self._active()
                aggregate = self._aggregate(rows)
                adapter = [a + b for a, b in zip(json.loads(active["adapter"]), aggregate)]
                finite_vector(adapter, self.policy.dimension)
                cursor = self._db.execute("INSERT INTO versions(parent,state,adapter,created_at) VALUES(?,'candidate',?,?)",
                                          (active["id"], encode_json(adapter).decode(), int(self._clock())))
                candidate_id = cursor.lastrowid
                self._db.execute("DELETE FROM contributions")
                # Old issued leases cannot be reused after a candidate rejection.
                self._db.execute("DELETE FROM leases WHERE used=0")
            response = {"accepted": True, "base_version": lease["base_version"],
                        "round_size": count, "candidate_version": candidate_id}
            self._db.execute("INSERT INTO receipts VALUES(?,?,?,?)", (token_hash, payload_hash,
                             encode_json(response).decode(), int(self._clock()) + self.policy.receipt_seconds))
            return response

    def _aggregate(self, rows: list[sqlite3.Row]) -> list[float]:
        vectors = [json.loads(row["delta"]) for row in rows]
        strategy = self.policy.aggregation
        if strategy in {"equal", "fedavg"}:
            weights = [row["sample_count"] if strategy == "fedavg" else 1 for row in rows]
            total = sum(weights)
            result = [math.fsum(vector[j] * (weight / total) for vector, weight in zip(vectors, weights))
                      for j in range(self.policy.dimension)]
        elif strategy == "coordinate_median":
            result = [statistics.median(vector[j] for vector in vectors) for j in range(self.policy.dimension)]
        else:
            trim = len(vectors) // 5
            result = []
            for j in range(self.policy.dimension):
                ordered = sorted(vector[j] for vector in vectors)
                middle = ordered[trim:len(ordered) - trim]
                result.append(math.fsum(middle) / len(middle))
        # Coordinate-wise robust methods also require a final joint norm bound.
        return _clip(result, self.policy.max_l2)

    def status(self) -> dict:
        with self._transaction():
            self._prune()
            active, candidate = self._active(), self._candidate()
            return {"base_version": active["id"],
                    "candidate_version": candidate["id"] if candidate else None,
                    "candidate_evaluated": bool(candidate and candidate["evaluation"] == 1),
                    "pending_contributions": self._db.execute("SELECT COUNT(*) FROM contributions").fetchone()[0],
                    "unexpired_leases": self._db.execute("SELECT COUNT(*) FROM leases").fetchone()[0],
                    "retained_receipts": self._db.execute("SELECT COUNT(*) FROM receipts").fetchone()[0]}

    def evaluate_candidate(self, candidate_id: int, evaluator: Callable[[dict], bool]) -> dict:
        """Invoke a local evaluator; exceptions and non-boolean verdicts reject.

        The evaluator receives numerical snapshots, never submitted text. It must
        test the actual integrated model; the coordinator cannot verify quality.
        """
        integer(candidate_id, 1, 2**63 - 1)
        with self._lock:
            candidate, active = self._candidate(), self._active()
            if candidate is None or candidate["id"] != candidate_id:
                raise ProtocolError("invalid_candidate", 409)
            snapshot = {"model_id": self.policy.model_id, "adapter_spec": self.policy.adapter_spec,
                        "base_version": active["id"], "candidate_version": candidate_id,
                        "baseline": json.loads(active["adapter"]), "candidate": json.loads(candidate["adapter"])}
        try:
            verdict = evaluator(snapshot)
            passed = type(verdict) is bool and verdict
        except Exception:
            passed = False
        with self._transaction():
            current = self._candidate()
            if current is None or current["id"] != candidate_id or self._active()["id"] != snapshot["base_version"]:
                raise ProtocolError("candidate_changed", 409)
            self._db.execute("UPDATE versions SET evaluation=?,state=? WHERE id=?",
                             (1 if passed else -1, "candidate" if passed else "rejected", candidate_id))
            return {"candidate_version": candidate_id, "passed": passed}

    def promote(self, candidate_id: int) -> dict:
        integer(candidate_id, 1, 2**63 - 1)
        with self._transaction():
            candidate = self._candidate()
            if candidate is None or candidate["id"] != candidate_id or candidate["evaluation"] != 1:
                raise ProtocolError("evaluation_required", 409)
            if candidate["parent"] != self._active()["id"]:
                raise ProtocolError("stale_candidate", 409)
            self._db.execute("UPDATE versions SET state='historical' WHERE state='active'")
            self._db.execute("UPDATE versions SET state='active' WHERE id=?", (candidate_id,))
            self._db.execute("DELETE FROM leases")
            self._db.execute("DELETE FROM contributions")
            return {"base_version": candidate_id}

    def rollback(self, target_version: int) -> dict:
        """Copy a previously released vector into a NEW monotonically increasing version."""
        integer(target_version, 1, 2**63 - 1)
        with self._transaction():
            target = self._db.execute("SELECT * FROM versions WHERE id=? AND state IN ('active','historical')", (target_version,)).fetchone()
            if target is None:
                raise ProtocolError("invalid_rollback", 409)
            active = self._active()
            self._db.execute("UPDATE versions SET state='rejected',evaluation=-1 WHERE state='candidate'")
            self._db.execute("UPDATE versions SET state='historical' WHERE state='active'")
            cursor = self._db.execute("INSERT INTO versions(parent,state,evaluation,adapter,created_at) VALUES(?,'active',1,?,?)",
                                      (active["id"], target["adapter"], int(self._clock())))
            self._db.execute("DELETE FROM leases")
            self._db.execute("DELETE FROM contributions")
            return {"base_version": cursor.lastrowid, "restored_from": target_version}
