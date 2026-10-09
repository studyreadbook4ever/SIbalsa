# SPDX-License-Identifier: Unlicense
"""Behavioral tests for replay, numerical integrity, persistence, and TLS."""

from __future__ import annotations

import concurrent.futures
import contextlib
import dataclasses
import hashlib
import http.client
import io
import json
import math
import os
import shutil
import socket
import sqlite3
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import warnings
from pathlib import Path

from sibalsa_coordinator import Coordinator, Policy, ProtocolError, make_server
from sibalsa_coordinator.coordinator import decode_json, encode_json, valid_proof


MODEL = "a" * 64
SPEC = "lm_head.lora.v1.r4.d32.v259"


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sibalsa-tests-")
        self.db = Path(self.temp.name) / "state.sqlite"
        self.now = 1700000000
        self.policy = Policy(MODEL, SPEC, 4, min_clients=2, max_l2=0.15)
        self.coordinator = Coordinator(self.db, self.policy, [0.12, -0.03, 0, 0], lambda: self.now)

    def tearDown(self):
        self.coordinator.close()
        self.temp.cleanup()

    def lease(self):
        return self.coordinator.issue_lease({"model_id": MODEL, "adapter_spec": SPEC,
                                             "base_version": self.coordinator.adapter()["base_version"], "dimension": 4})

    def update(self, lease=None, delta=None, samples=1):
        lease = self.lease() if lease is None else lease
        return {"lease_id": lease["lease_id"], "nonce": lease["nonce"], "proof": "0",
                "sample_count": samples, "steps": 1, "delta": delta or [0.02, 0, 0, 0]}

    def stage(self):
        self.coordinator.accept_update(self.update())
        return self.coordinator.accept_update(self.update())["candidate_version"]

    def assertProtocol(self, code, operation):
        with self.assertRaises(ProtocolError) as caught:
            operation()
        self.assertEqual(caught.exception.code, code)

    def test_json_rejects_duplicate_nonfinite_invalid_utf8_and_trailing(self):
        for raw in (b'{"a":1,"a":2}', b'{"x":NaN}', b'{"x":Infinity}', b'\xff', b'{} trailing', b'[' * 2000):
            with self.subTest(raw=raw[:20]):
                with self.assertRaises(ProtocolError):
                    decode_json(raw)

    def test_exact_fields_reject_conversation_and_extra_update_data(self):
        request = {"model_id": MODEL, "adapter_spec": SPEC, "base_version": 1, "dimension": 4, "conversation": "private sentence"}
        self.assertProtocol("invalid_fields", lambda: self.coordinator.issue_lease(request))
        update = self.update()
        update["text"] = "private sentence"
        self.assertProtocol("invalid_fields", lambda: self.coordinator.accept_update(update))

    def test_lease_model_spec_version_dimension_and_types_are_bound(self):
        original = {"model_id": MODEL, "adapter_spec": SPEC, "base_version": 1, "dimension": 4}
        for key, value, code in (("model_id", "b" * 64, "model_mismatch"),
                                 ("adapter_spec", "other", "adapter_mismatch"),
                                 ("base_version", 99, "stale_version"),
                                 ("base_version", True, "invalid_integer"),
                                 ("dimension", 5, "invalid_integer"),
                                 ("dimension", 4.0, "invalid_integer")):
            with self.subTest(key=key, value=value):
                request = dict(original, **{key: value})
                self.assertProtocol(code, lambda: self.coordinator.issue_lease(request))

    def test_update_limits_are_server_controlled(self):
        original = self.update()
        cases = (("sample_count", 0, "invalid_integer"), ("sample_count", 65, "invalid_integer"),
                 ("sample_count", True, "invalid_integer"), ("steps", 17, "invalid_integer"),
                 ("steps", 1.0, "invalid_integer"), ("delta", [0, 0], "invalid_dimension"),
                 ("delta", ["secret", 0, 0, 0], "invalid_vector"),
                 ("delta", [True, 0, 0, 0], "invalid_vector"),
                 ("delta", [float("nan"), 0, 0, 0], "invalid_vector"),
                 ("delta", [float("inf"), 0, 0, 0], "invalid_vector"),
                 ("delta", [10**400, 0, 0, 0], "invalid_vector"),
                 ("delta", [0.11, 0, 0, 0], "coordinate_limit"),
                 ("delta", [0.1, 0.1, 0.1, 0.1], "norm_limit"),
                 ("proof", 0, "invalid_proof"), ("proof", "0" * 21, "invalid_proof"),
                 ("lease_id", "guess", "invalid_lease"))
        for key, value, code in cases:
            with self.subTest(key=key, value=value):
                request = dict(original, **{key: value})
                self.assertProtocol(code, lambda: self.coordinator.accept_update(request))
        # A rejected malformed attempt never consumes the legitimate lease.
        self.assertTrue(self.coordinator.accept_update(original)["accepted"])

    def test_nonce_binding_and_single_use_survive_restart(self):
        first, second = self.lease(), self.lease()
        original = self.update(first)
        bad = dict(original, nonce=second["nonce"])
        self.assertProtocol("invalid_lease", lambda: self.coordinator.accept_update(bad))
        receipt = self.coordinator.accept_update(original)
        self.coordinator.close()
        self.coordinator = Coordinator(self.db, clock=lambda: self.now)
        self.assertEqual(self.coordinator.status()["pending_contributions"], 1)
        self.assertEqual(self.coordinator.accept_update(original), receipt)
        self.assertEqual(self.coordinator.status()["pending_contributions"], 1)

    def test_lease_expires_at_boundary_and_pruning_deletes_it(self):
        lease = self.lease()
        self.now = lease["expires_at"]
        self.assertProtocol("invalid_lease", lambda: self.coordinator.accept_update(self.update(lease)))
        self.coordinator.prune()
        with contextlib.closing(sqlite3.connect(self.db)) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM leases").fetchone()[0], 0)

    def test_capacity_is_bounded_and_recovers_after_expiration(self):
        self.coordinator.close()
        os.unlink(self.db)
        self.policy = dataclasses.replace(self.policy, max_leases=2)
        self.coordinator = Coordinator(self.db, self.policy, [0, 0, 0, 0], lambda: self.now)
        self.lease()
        self.lease()
        self.assertProtocol("lease_capacity", self.lease)
        self.now += self.policy.lease_seconds
        self.assertEqual(self.lease()["base_version"], 1)

    def test_pow_is_bound_to_nonce_and_work_is_required(self):
        self.coordinator.close()
        os.unlink(self.db)
        self.policy = dataclasses.replace(self.policy, pow_bits=8)
        self.coordinator = Coordinator(self.db, self.policy, [0, 0, 0, 0], lambda: self.now)
        lease = self.lease()
        bad = next(str(i) for i in range(1000) if not valid_proof(lease["lease_id"], lease["nonce"], str(i), 8))
        self.assertProtocol("invalid_proof", lambda: self.coordinator.accept_update(dict(self.update(lease), proof=bad)))
        proof = next(str(i) for i in range(100000) if valid_proof(lease["lease_id"], lease["nonce"], str(i), 8))
        self.assertTrue(self.coordinator.accept_update(dict(self.update(lease), proof=proof))["accepted"])

    def test_candidates_require_evaluation_then_explicit_promotion(self):
        candidate = self.stage()
        self.assertEqual(self.coordinator.adapter()["base_version"], 1)
        self.assertEqual(self.coordinator.adapter()["adapter"], [0.12, -0.03, 0, 0])
        self.assertProtocol("round_closed", self.lease)
        self.assertProtocol("evaluation_required", lambda: self.coordinator.promote(candidate))
        seen = []
        self.coordinator.evaluate_candidate(candidate, lambda snapshot: seen.append(snapshot) or True)
        self.assertEqual(seen[0]["candidate_version"], candidate)
        self.assertAlmostEqual(seen[0]["candidate"][0], 0.14)
        # A passing evaluator does not distribute the candidate until promotion.
        self.assertEqual(self.coordinator.adapter()["base_version"], 1)
        self.coordinator.promote(candidate)
        self.assertEqual(self.coordinator.adapter()["base_version"], candidate)

    def test_failure_exception_and_non_boolean_verdict_reject(self):
        for evaluator in (lambda _: False, lambda _: "pass", lambda _: 1,
                          lambda _: (_ for _ in ()).throw(RuntimeError("sensitive text"))):
            with self.subTest(evaluator=evaluator):
                candidate = self.stage()
                self.assertFalse(self.coordinator.evaluate_candidate(candidate, evaluator)["passed"])
                self.assertIsNone(self.coordinator.status()["candidate_version"])
                self.assertEqual(self.coordinator.adapter()["base_version"], 1)
                self.assertProtocol("evaluation_required", lambda: self.coordinator.promote(candidate))

    def test_staging_deletes_individual_deltas_and_old_unused_leases(self):
        unused = self.lease()
        self.stage()
        with contextlib.closing(sqlite3.connect(self.db)) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM contributions").fetchone()[0], 0)
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM leases WHERE used=0").fetchone()[0], 0)
        self.assertProtocol("invalid_lease", lambda: self.coordinator.accept_update(self.update(unused)))

    def test_rollback_copies_released_weights_under_fresh_version(self):
        old = self.lease()
        candidate = self.stage()
        self.coordinator.evaluate_candidate(candidate, lambda _: True)
        self.coordinator.promote(candidate)
        issued_after_promotion = self.lease()
        restored = self.coordinator.rollback(1)
        self.assertGreater(restored["base_version"], candidate)
        self.assertEqual(self.coordinator.adapter()["adapter"], [0.12, -0.03, 0, 0])
        self.assertProtocol("invalid_lease", lambda: self.coordinator.accept_update(self.update(old)))
        self.assertProtocol("invalid_lease", lambda: self.coordinator.accept_update(self.update(issued_after_promotion)))
        request = {"model_id": MODEL, "adapter_spec": SPEC, "base_version": 1, "dimension": 4}
        self.assertProtocol("stale_version", lambda: self.coordinator.issue_lease(request))

    def test_cannot_rollback_to_unreleased_or_rejected_candidate(self):
        candidate = self.stage()
        self.assertProtocol("invalid_rollback", lambda: self.coordinator.rollback(candidate))
        self.coordinator.evaluate_candidate(candidate, lambda _: False)
        self.assertProtocol("invalid_rollback", lambda: self.coordinator.rollback(candidate))

    def test_candidate_and_evaluation_survive_database_restart(self):
        candidate = self.stage()
        self.coordinator.evaluate_candidate(candidate, lambda _: True)
        self.coordinator.close()
        self.coordinator = Coordinator(self.db, clock=lambda: self.now)
        self.assertTrue(self.coordinator.status()["candidate_evaluated"])
        self.coordinator.promote(candidate)
        self.assertAlmostEqual(self.coordinator.adapter()["adapter"][0], 0.14)

    def test_concurrent_replay_accepts_exactly_once(self):
        update = self.update()
        def submit(_):
            try:
                return self.coordinator.accept_update(update)
            except ProtocolError:
                return False
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
            results = list(pool.map(submit, range(30)))
        self.assertTrue(all(result == results[0] for result in results))
        self.assertEqual(self.coordinator.status()["pending_contributions"], 1)

    def test_replay_is_atomic_between_separate_database_connections(self):
        other = Coordinator(self.db, clock=lambda: self.now)
        update = self.update()
        barrier = threading.Barrier(2)
        def submit(coordinator):
            barrier.wait(timeout=3)
            try:
                return coordinator.accept_update(update)
            except ProtocolError:
                return False
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(submit, (self.coordinator, other)))
            self.assertEqual(results[0], results[1])
            self.assertEqual(self.coordinator.status()["pending_contributions"], 1)
        finally:
            other.close()

    def test_coordinate_robust_aggregation_has_joint_norm_bound(self):
        policy = dataclasses.replace(self.policy, dimension=3, min_clients=5, aggregation="coordinate_median")
        coordinator = Coordinator(Path(self.temp.name) / "robust.sqlite", policy, [0, 0, 0])
        try:
            for delta in ([0.1, 0.1, 0], [0.1, 0.1, 0], [0.1, 0, 0.1], [0, 0.1, 0.1], [0, 0, 0.1]):
                lease = coordinator.issue_lease({"model_id": MODEL, "adapter_spec": SPEC, "base_version": 1, "dimension": 3})
                receipt = coordinator.accept_update({"lease_id": lease["lease_id"], "nonce": lease["nonce"], "proof": "0",
                                                      "sample_count": 1, "steps": 1, "delta": delta})
            snapshots = []
            coordinator.evaluate_candidate(receipt["candidate_version"], lambda s: snapshots.append(s) or True)
            self.assertAlmostEqual(math.hypot(*snapshots[0]["candidate"]), policy.max_l2)
            self.assertTrue(all(v < 0.1 for v in snapshots[0]["candidate"]))
        finally:
            coordinator.close()

    def test_large_language_model_head_vector_stages_and_promotes(self):
        # A realistic vocabulary head exceeds the old 16384-dimensional cap.
        dimension = 70000
        policy = dataclasses.replace(self.policy, dimension=dimension)
        coordinator = Coordinator(Path(self.temp.name) / "large.sqlite", policy, [0.0] * dimension)
        try:
            for value in (0.01, 0.03):
                lease = coordinator.issue_lease({"model_id": MODEL, "adapter_spec": SPEC, "base_version": 1, "dimension": dimension})
                delta = [0.0] * dimension
                delta[-1] = value
                receipt = coordinator.accept_update({"lease_id": lease["lease_id"], "nonce": lease["nonce"], "proof": "0",
                                                      "sample_count": 1, "steps": 1, "delta": delta})
            coordinator.evaluate_candidate(receipt["candidate_version"], lambda s: abs(s["candidate"][-1] - 0.02) < 1e-9)
            coordinator.promote(receipt["candidate_version"])
            self.assertEqual(len(coordinator.adapter()["adapter"]), dimension)
            self.assertAlmostEqual(coordinator.adapter()["adapter"][-1], 0.02)
        finally:
            coordinator.close()

    def test_identical_receipt_retry_survives_stage_promotion_expiry_restart(self):
        first, second = self.update(), self.update()
        original = self.coordinator.accept_update(first)
        staged = self.coordinator.accept_update(second)
        candidate = staged["candidate_version"]
        self.coordinator.evaluate_candidate(candidate, lambda _: True)
        self.coordinator.promote(candidate)
        self.now += self.policy.lease_seconds + 1
        self.coordinator.close()
        self.coordinator = Coordinator(self.db, clock=lambda: self.now)
        self.assertEqual(self.coordinator.accept_update(first), original)
        self.assertEqual(self.coordinator.accept_update(second), staged)
        self.assertEqual(self.coordinator.status()["pending_contributions"], 0)
        self.assertEqual(self.coordinator.status()["retained_receipts"], 2)
        self.assertProtocol("replay_mismatch", lambda: self.coordinator.accept_update(dict(first, steps=2)))
        self.assertProtocol("replay_mismatch", lambda: self.coordinator.accept_update(dict(first, nonce="b" * 32)))
        self.assertProtocol("replay_mismatch", lambda: self.coordinator.accept_update(dict(first, delta=[0.03, 0, 0, 0])))

    def test_receipt_retention_is_bounded_and_pruned_without_vectors(self):
        self.coordinator.close()
        os.unlink(self.db)
        self.policy = dataclasses.replace(self.policy, max_receipts=2, receipt_seconds=100)
        self.coordinator = Coordinator(self.db, self.policy, [0, 0, 0, 0], lambda: self.now)
        first = self.update()
        self.coordinator.accept_update(first)
        self.lease()  # Reserve the remaining receipt slot for one in-flight update.
        self.assertProtocol("receipt_capacity", self.lease)
        with contextlib.closing(sqlite3.connect(self.db)) as connection:
            columns = {row[1] for row in connection.execute("PRAGMA table_info(receipts)")}
            self.assertEqual(columns, {"token_hash", "payload_hash", "response", "expires_at"})
            row = connection.execute("SELECT * FROM receipts").fetchone()
            self.assertNotIn(first["nonce"], str(row))
            self.assertNotIn(first["lease_id"], str(row))
            self.assertNotIn("delta", str(row))
        self.now += self.policy.lease_seconds + 1
        self.coordinator.prune()
        self.assertEqual(self.coordinator.status()["retained_receipts"], 0)
        self.assertProtocol("invalid_lease", lambda: self.coordinator.accept_update(first))
        self.assertEqual(self.lease()["base_version"], 1)

    def test_cli_evaluation_gate_uses_bounded_local_external_verdict(self):
        candidate = self.stage()
        command = [sys.executable, "-m", "sibalsa_coordinator"]
        before = subprocess.run(command + ["promote", "--db", str(self.db), "--candidate", str(candidate)], capture_output=True)
        self.assertEqual(before.returncode, 2)
        evaluator = "import json,sys; s=json.load(sys.stdin); print(json.dumps({'passed':s['candidate'][0]>s['baseline'][0]}))"
        evaluated = subprocess.run(command + ["evaluate", "--db", str(self.db), "--candidate", str(candidate),
                                               "--timeout", "5", "--command", sys.executable, "-c", evaluator], capture_output=True)
        self.assertEqual(evaluated.returncode, 0, evaluated.stderr)
        self.assertTrue(json.loads(evaluated.stdout)["passed"])
        promoted = subprocess.run(command + ["promote", "--db", str(self.db), "--candidate", str(candidate)], capture_output=True)
        self.assertEqual(promoted.returncode, 0, promoted.stderr)
        self.assertEqual(self.coordinator.adapter()["base_version"], candidate)

    def test_storage_contains_no_raw_tokens_or_arbitrary_text(self):
        lease = self.lease()
        self.coordinator.accept_update(self.update(lease))
        with contextlib.closing(sqlite3.connect(self.db)) as connection:
            dump = "\n".join(connection.iterdump())
        for forbidden in (lease["lease_id"], lease["nonce"], "client_ip", "device_id", "conversation", "private sentence"):
            self.assertNotIn(forbidden, dump)
        self.assertEqual(os.stat(self.db).st_mode & 0o777, 0o600)
        policy = self.coordinator.public_policy()["privacy"]
        self.assertFalse(policy["secure_aggregation"])
        self.assertFalse(policy["differential_privacy"])
        self.assertTrue(policy["individual_updates_visible_to_server"])

    def test_policy_is_immutable_and_initial_adapter_is_required(self):
        with self.assertRaises(ValueError):
            Coordinator(self.db, dataclasses.replace(self.policy, max_steps=2), [0] * 4)
        with self.assertRaises(ValueError):
            Coordinator(Path(self.temp.name) / "fresh.sqlite")

    def test_aggregation_strategies_and_sample_weighting(self):
        expected = {"equal": 0.022, "fedavg": (0.01 * 4 + 0.14) / 6,
                    "coordinate_median": 0.01, "trimmed_mean": 0.01}
        for strategy, target in expected.items():
            with self.subTest(strategy=strategy):
                path = Path(self.temp.name) / (strategy + ".sqlite")
                policy = dataclasses.replace(self.policy, dimension=1, min_clients=5, aggregation=strategy)
                with contextlib.ExitStack() as cleanup:
                    coordinator = Coordinator(path, policy, [0])
                    cleanup.callback(coordinator.close)
                    for index, value in enumerate([0.01, 0.01, 0.01, 0.01, 0.07]):
                        # For the last row test sample weighting with two samples.
                        lease = coordinator.issue_lease({"model_id": MODEL, "adapter_spec": SPEC, "base_version": 1, "dimension": 1})
                        result = coordinator.accept_update({"lease_id": lease["lease_id"], "nonce": lease["nonce"], "proof": "0",
                                                             "sample_count": 2 if index == 4 else 1, "steps": 1, "delta": [value]})
                    candidate = result["candidate_version"]
                    snapshots = []
                    coordinator.evaluate_candidate(candidate, lambda s: snapshots.append(s) or True)
                    self.assertAlmostEqual(snapshots[0]["candidate"][0], target)
                    self.assertLessEqual(math.hypot(*snapshots[0]["candidate"]), policy.max_l2)


class TLSTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if shutil.which("openssl") is None:
            raise unittest.SkipTest("OpenSSL CLI is required to generate ephemeral test certificates")
        cls.cert_temp = tempfile.TemporaryDirectory(prefix="sibalsa-tls-")
        cls.cert = str(Path(cls.cert_temp.name) / "cert.pem")
        cls.key = str(Path(cls.cert_temp.name) / "key.pem")
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-noenc", "-days", "1",
                        "-keyout", cls.key, "-out", cls.cert, "-subj", "/CN=localhost",
                        "-addext", "subjectAltName=DNS:localhost"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    @classmethod
    def tearDownClass(cls):
        cls.cert_temp.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sibalsa-http-")
        self.coordinator = Coordinator(Path(self.temp.name) / "state.sqlite",
                                       Policy(MODEL, SPEC, 4, min_clients=2, max_body_bytes=1024,
                                              request_seconds=1.5, max_connections=2), [0.01, 0, 0, 0])
        self.server = make_server(("127.0.0.1", 0), self.coordinator, self.cert, self.key)
        self.port = self.server.server_port
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.thread.start()
        self.context = ssl.create_default_context(cafile=self.cert)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.coordinator.close()
        self.temp.cleanup()

    def request(self, method, path, payload=None, raw=None, headers=None, context=None, host="localhost"):
        connection = http.client.HTTPSConnection(host, self.port, context=context or self.context, timeout=3)
        body = raw if raw is not None else encode_json(payload) if payload is not None else None
        request_headers = {"Content-Type": "application/json", **(headers or {})}
        try:
            connection.request(method, path, body=body, headers=request_headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def lease(self):
        status, result = self.request("POST", "/v1/lease", {"model_id": MODEL, "adapter_spec": SPEC, "base_version": 1, "dimension": 4})
        self.assertEqual(status, 200)
        return result

    def test_tls_end_to_end_round_evaluation_promotion_and_replay(self):
        self.assertEqual(self.request("GET", "/v1/policy")[1]["protocol_version"], 1)
        self.assertEqual(self.request("GET", "/v1/adapter")[1]["adapter"], [0.01, 0, 0, 0])
        for index in range(2):
            lease = self.lease()
            update = {"lease_id": lease["lease_id"], "nonce": lease["nonce"], "proof": "0",
                      "sample_count": 1, "steps": 1, "delta": [0.02, 0, 0, 0]}
            status, result = self.request("POST", "/v1/update", update)
            self.assertEqual(status, 200)
            self.assertEqual(result["round_size"], index + 1)
            retry_status, retry = self.request("POST", "/v1/update", update)
            self.assertEqual(retry_status, 200)
            self.assertEqual(retry, result)
        candidate = result["candidate_version"]
        self.assertEqual(self.request("GET", "/v1/adapter")[1]["base_version"], 1)
        self.coordinator.evaluate_candidate(candidate, lambda _: True)
        self.coordinator.promote(candidate)
        self.assertAlmostEqual(self.request("GET", "/v1/adapter")[1]["adapter"][0], 0.03)
        self.assertEqual(self.request("POST", "/v1/update", update), (200, result))

    def test_dropped_tls_response_recovers_original_receipt_without_reaggregation(self):
        lease = self.lease()
        update = {"lease_id": lease["lease_id"], "nonce": lease["nonce"], "proof": "0",
                  "sample_count": 1, "steps": 1, "delta": [0.02, 0, 0, 0]}
        accepted, release = threading.Event(), threading.Event()
        receipts = []
        original_accept = self.coordinator.accept_update
        def delay_response(payload):
            result = original_accept(payload)
            receipts.append(result)
            accepted.set()
            release.wait(timeout=2)
            return result
        self.coordinator.accept_update = delay_response
        try:
            body = encode_json(update)
            raw = socket.create_connection(("localhost", self.port), timeout=3)
            connection = self.context.wrap_socket(raw, server_hostname="localhost")
            connection.sendall(b"POST /v1/update HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: "
                               + str(len(body)).encode("ascii") + b"\r\n\r\n" + body)
            self.assertTrue(accepted.wait(timeout=2))
            connection.close()  # Server committed, but the client never received the response.
            release.set()
        finally:
            release.set()
            self.coordinator.accept_update = original_accept
        self.assertEqual(self.request("POST", "/v1/update", update), (200, receipts[0]))
        self.assertEqual(self.coordinator.status()["pending_contributions"], 1)
        self.assertEqual(self.request("POST", "/v1/update", dict(update, steps=2))[0], 409)

    def test_hostname_and_certificate_validation_are_required(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.request("GET", "/v1/policy", host="127.0.0.1")
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.request("GET", "/v1/policy", context=ssl.create_default_context())

    def test_plaintext_and_pre_tls12_connections_cannot_exchange_http(self):
        connection = http.client.HTTPConnection("localhost", self.port, timeout=2)
        try:
            with self.assertRaises((OSError, http.client.HTTPException)):
                connection.request("GET", "/v1/policy")
                connection.getresponse()
        finally:
            connection.close()
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            context.maximum_version = ssl.TLSVersion.TLSv1_1
        with self.assertRaises(ssl.SSLError):
            with socket.create_connection(("localhost", self.port), timeout=2) as raw:
                context.wrap_socket(raw, server_hostname="localhost")
        self.assertEqual(self.server.context.minimum_version, ssl.TLSVersion.TLSv1_2)

    def test_body_type_size_json_fields_and_routes_rejected(self):
        self.assertEqual(self.request("POST", "/v1/lease", raw=b"x" * 1025)[0], 413)
        self.assertEqual(self.request("POST", "/v1/lease", raw=b"{}")[0], 400)
        self.assertEqual(self.request("POST", "/v1/lease", raw=b'{"x":1,"x":2}')[0], 400)
        self.assertEqual(self.request("POST", "/v1/lease", raw=b'{"x":NaN}')[0], 400)
        self.assertEqual(self.request("POST", "/v1/lease", raw=b"{}", headers={"Content-Type": "text/plain"})[0], 415)
        self.assertEqual(self.request("POST", "/v1/lease", raw=b"{}", headers={"Transfer-Encoding": "chunked"})[0], 400)
        self.assertEqual(self.request("GET", "/v1/policy?text=secret")[0], 404)
        self.assertEqual(self.request("POST", "/admin/promote", raw=b"{}")[0], 404)

    def test_duplicate_lengths_and_headers_limit_fail_closed(self):
        with socket.create_connection(("localhost", self.port), timeout=3) as raw:
            with self.context.wrap_socket(raw, server_hostname="localhost") as connection:
                connection.sendall(b"POST /v1/lease HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}")
                self.assertIn(b"400", connection.recv(4096).split(b"\r\n")[0])
        with socket.create_connection(("localhost", self.port), timeout=3) as raw:
            with self.context.wrap_socket(raw, server_hostname="localhost") as connection:
                connection.sendall(b"GET /v1/policy HTTP/1.1\r\nHost: localhost\r\n" + b"X: 1\r\n" * 40 + b"\r\n")
                self.assertIn(b"431", connection.recv(4096).split(b"\r\n")[0])

    def test_absolute_body_deadline_not_reset_by_trickle(self):
        with socket.create_connection(("localhost", self.port), timeout=3) as raw:
            with self.context.wrap_socket(raw, server_hostname="localhost") as connection:
                start = time.monotonic()
                connection.sendall(b"POST /v1/lease HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{")
                time.sleep(0.5)
                connection.sendall(b" ")
                reply = connection.recv(4096)
                self.assertIn(b"408", reply.split(b"\r\n")[0])
                self.assertLess(time.monotonic() - start, 2.5)

    def test_bounded_handshakes_recover_after_deadline(self):
        sockets = []
        try:
            for _ in range(2):
                sockets.append(socket.create_connection(("localhost", self.port), timeout=3))
            time.sleep(0.1)
            # Two uncompleted TLS handshakes occupy exactly two permitted slots.
            with socket.create_connection(("localhost", self.port), timeout=3) as third:
                third.settimeout(1)
                self.assertEqual(third.recv(1), b"")
            time.sleep(1.5)
            self.assertEqual(self.request("GET", "/v1/policy")[0], 200)
        finally:
            for connection in sockets:
                connection.close()

    def test_no_default_logs_identifiers_or_request_text(self):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            self.request("GET", "/v1/policy")
            self.request("POST", "/v1/update", {"conversation": "DO_NOT_PERSIST"})
        self.assertEqual(output.getvalue(), "")
        self.assertEqual(errors.getvalue(), "")


if __name__ == "__main__":
    unittest.main()
