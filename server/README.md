<!-- SPDX-License-Identifier: Unlicense -->

# SIbalsa reference coordinator

This is an original, dependency-free Python reference implementation of the
SIbalsa numeric update protocol. It receives bounded adapter deltas, aggregates
a cohort, stages a candidate, and lets a local operator evaluate and publish it.
It does not contain an LLM, model weights, feedback text, or an inference engine.
The repository's original source uses the Unlicense.

TLS 1.2 or newer is mandatory. Individual updates and network source addresses
are visible at the server. There is **no secure aggregation, differential
privacy, or guarantee that model updates cannot reveal training information**.
The application never writes source addresses, account/device identifiers,
request bodies, or access logs. Pending numeric deltas are temporarily persisted
for crash recovery; they are removed after staging a cohort. Model versions,
short-lived hashed lease credentials and bounded success receipts persist in
SQLite. Receipts contain only token/payload hashes, the original numerical
response, and their expiry.

## Run

Python 3.10+ with its standard library and system OpenSSL support is sufficient.
No pip package is required. Obtain a certificate for the server's actual DNS
hostname and its private key. Clients must validate the chain and hostname. A
development CA may be explicitly trusted on the client; never disable checks.

Export the initialized adapter from the integrator's model using the **exact
same parameter ordering** as Android. In particular, do not initialize both
LoRA factors to zero: export the native engine's initial A and B instead.
The adapter file is a UTF-8 JSON array of finite numbers; no model is included.
The model ID is the SHA-256 of the model file used on Android. The adapter spec
identifies the layer, format, rank, hidden dimension, and vocabulary layout.

```sh
cd server
python3 -m sibalsa_coordinator init \
  --db state.sqlite \
  --model-id YOUR_64_CHARACTER_LOWERCASE_MODEL_SHA256 \
  --adapter-spec lm_head.lora.v1.r4.d32.v259 \
  --initial-adapter exported-adapter.json
python3 -m sibalsa_coordinator serve \
  --db state.sqlite --cert server-cert.pem --key server-key.pem \
  --host 127.0.0.1 --port 8443
```

Use `--host 0.0.0.0` only when intentionally making the TLS port reachable.
Protect the private key, SQLite database, filesystem backups, and the operator
account. The database is created with mode 0600. SQLite DELETE journals and
secure_delete are enabled; deletion is not a promise of media-level erasure.
One process normally serves HTTP. Local operator commands can safely use the
same database; mutations start a SQLite immediate transaction, so lease checks
and updates also serialize across separate coordinator instances.

The protocol starts at version 1. The policy is immutable for a database;
changing model format or bounds requires a separate deployment/database.
`init` does not overwrite an existing initialized database.

## Protocol v1

All responses and POST bodies are JSON over HTTPS. POST requires an explicit
Content-Length and application/json. Transfer/content encodings, duplicate
Content-Length headers, duplicate JSON keys, non-finite numbers, unknown JSON
fields, request query strings, and extra conversation/feedback fields are
rejected. Requests have bounded bytes, headers, time, and concurrent connections.

| Endpoint | Purpose |
| --- | --- |
| GET /v1/policy | Model identity, current adapter, server-controlled limits and privacy properties |
| GET /v1/adapter | Current released model ID, adapter spec, version, dimension and numerical adapter |
| POST /v1/lease | Obtain one expiring contribution credential for the current model/version |
| POST /v1/update | Submit one numerical delta against that lease's adapter snapshot |

`GET /v1/policy` returns these fields:

```json
{
  "protocol_version": 1,
  "model_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "adapter_spec": "lm_head.lora.v1.r4.d32.v259",
  "base_version": 1,
  "dimension": 4,
  "adapter": [0.02, 0.0, 0.0, 0.0],
  "limits": {"max_samples": 64, "max_steps": 16, "max_l2": 0.25, "max_coordinate": 0.1},
  "pow_bits": 0,
  "lease_seconds": 900,
  "aggregation": "equal",
  "privacy": {"secure_aggregation": false, "differential_privacy": false, "individual_updates_visible_to_server": true}
}
```

The four-element vector above is solely a compact protocol illustration.
The actual dimension is derived from the exported adapter, normally
`rank * hidden_dimension + vocabulary_size * rank` for the current native format.
`GET /v1/adapter` returns only model_id, adapter_spec, base_version, dimension,
and adapter.

The lease request's **exact** fields are:

```json
{"model_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","adapter_spec":"lm_head.lora.v1.r4.d32.v259","base_version":1,"dimension":4}
```

The response contains lease_id, nonce, expires_at (UTC epoch seconds),
base_version, adapter, limits, and pow_bits. Tokens are opaque 32-character
base64url strings. Only their SHA-256 digests are stored. A lease identifies
one temporary learning round contribution, not a verified person/device.

The update request's **exact** fields are:

```json
{"lease_id":"RETURNED_32_CHARACTER_TOKEN______","nonce":"RETURNED_32_CHARACTER_NONCE______","proof":"0","sample_count":3,"steps":4,"delta":[0.01,0.0,0.0,0.0]}
```

`delta = trained_adapter - lease_adapter`, with unchanged model ID, layout and
base version. Proof is a canonical decimal integer **string**, at most 20
digits. SHA-256 of the UTF-8 string `lease_id + ":" + nonce + ":" + proof` must
start with `pow_bits` zero bits. When pow_bits is zero, `"0"` suffices.
Clients should check cancellation and device scheduling conditions during work.

The response is `{accepted, base_version, round_size, candidate_version}`.
Candidate version is null until the cohort fills. A successful response marks
the lease used atomically. Identical retries return the original successful
receipt within receipt_seconds (24 hours by default), without another
contribution. This works after staging, promotion, lease expiry and server
restart. The canonical payload digest binds nonce, proof, counters and delta;
a different payload with the same token returns replay_mismatch. Receipt
retrieval precedes current-version and lease checks, so its base_version may
legitimately precede the currently released adapter. Clients must compare it
with the submitted lease version. An unaccepted expired lease is invalid at
exactly expires_at. Error responses contain only a fixed
machine-readable `{error}` code and an HTTP status. Operators have no HTTP
administration endpoints.

## Aggregation, evaluation, and rollback

The default is five accepted contributions with equal weight. Server-controlled
sample_count and steps bounds limit accepted claims; the server cannot verify
that those counters describe actual local work. Deltas must satisfy both the
coordinate and L2 limits. Numerical inputs and the final aggregate are bounded.

| Strategy | Weighting and limitation |
| --- | --- |
| equal | One accepted contribution, one weight; anonymous participants can still create multiple leases |
| fedavg | Weight by claimed sample_count, bounded by policy; claims remain unverified |
| coordinate_median | Per-coordinate median; resilience depends on the contribution distribution |
| trimmed_mean | Drop the lowest and highest 20% per coordinate; requires at least five contributions |

These strategies average an adapter parameter vector. Averaging separate LoRA
factors is not mathematically equivalent to averaging their multiplied weight
updates. Evaluate the integrated model and do not infer convergence, accuracy,
or poisoning resistance from the selected aggregation name.

When a cohort fills, the candidate is persisted and new leases close. Individual
pending deltas and unused leases are removed. The released model stays unchanged.
An operator evaluator receives JSON on stdin with model_id, adapter_spec,
base_version, candidate_version, baseline, and candidate. It must load the actual
model/tokenizer, apply each adapter, and evaluate the resulting behavior on
held-out examples and operator-defined quality criteria. Its only stdout must
be `{"passed":true}` or `{"passed":false}`. This server does not supply a fake
quality evaluator or judge quality by vector shape.

```sh
python3 -m sibalsa_coordinator status --db state.sqlite
python3 -m sibalsa_coordinator evaluate --db state.sqlite --candidate 2 \
  --timeout 60 --command python3 /path/to/your_real_model_evaluator.py
python3 -m sibalsa_coordinator promote --db state.sqlite --candidate 2
python3 -m sibalsa_coordinator rollback --db state.sqlite --version 1
```

Missing/invalid evaluator output, a failed process, timeout, exception, or false
verdict rejects the candidate and reopens collection. A passing verdict enables
an explicit promotion command; it does not publish automatically. Both promotion
and rollback invalidate previous leases. Rollback copies a previously released
adapter into a **new, monotonically increasing** version to prevent old updates
being replayed against restored weights. Unreleased candidates cannot be rollback
targets. Tests use a boolean evaluator solely to exercise the administrative
state machine; those tests are not LLM quality evidence.

## Configuration and limits

`init --help` lists policy options. Defaults are min_clients=5, equal aggregation,
max_samples=64, max_steps=16, max_l2=0.25, max_coordinate=0.1, lease_seconds=900,
pow_bits=0, max_leases=2048, max_body_bytes=33554432 (32 MiB), request_seconds=10,
max_connections=4, receipt_seconds=86400, max_receipts=8192. Dimensions are capped
at 1000000; min_clients at 256. Initial adapter JSON is also capped at 32 MiB.
Large vectors allocate substantially more RAM than their encoded JSON size;
choose dimension, cohort, body and connection limits for the server's memory.
The
request deadline is absolute across handshake, headers and body, so trickled
bytes do not restart a timeout. Excess connections close before request handling.
The server periodically deletes expired lease digests and receipts even without
new requests. Issued leases reserve receipt slots, bounding retained success
records. Receipt retention can be configured up to seven days; retries never
extend it. Within the window only the exact accepted payload recovers its
receipt. After it expires the server provides no indefinite delivery recovery.

Anonymous enrollment and optional proof of work do not prevent a determined
participant from submitting multiple poisoned updates or exhausting capacity.
Clipping constrains magnitude, not semantics. The model evaluation/release gate
is necessary but depends on the evaluator's coverage. This is a reference
coordinator: public production operations need deployment-specific capacity,
monitoring that preserves the documented data policy, and quality evaluation.

## Verification

```sh
python3 -m unittest discover -s tests -v
```

The tests generate temporary development certificates using the OpenSSL CLI.
They exercise real TLS exchanges, trust and hostname failures, plaintext
rejection, minimum TLS version, exact payloads, size and numerical bounds,
proof-of-work, durable single-use leases, concurrency, absolute deadlines,
weighted/robust aggregation, fail-closed evaluation, explicit promotion,
fresh-version rollback, and deletion/privacy properties of persisted state.
There are no model or end-user conversation fixtures.
