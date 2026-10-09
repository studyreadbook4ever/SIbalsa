# SPDX-License-Identifier: Unlicense
"""Local operator CLI. Remote HTTP exposes no administrative endpoints."""

from __future__ import annotations

import argparse
import os
import sqlite3
import subprocess
import sys

from .coordinator import Coordinator, Policy, ProtocolError, decode_json, encode_json
from .http import make_server


def parser() -> argparse.ArgumentParser:
    cli = argparse.ArgumentParser(description="SIbalsa TLS-only numerical coordinator")
    actions = cli.add_subparsers(dest="action", required=True)
    init = actions.add_parser("init", help="initialize a database from an actual exported adapter")
    init.add_argument("--db", required=True)
    init.add_argument("--model-id", required=True)
    init.add_argument("--adapter-spec", required=True)
    init.add_argument("--initial-adapter", required=True, help="UTF-8 JSON array, same ordering as Android")
    init.add_argument("--min-clients", type=int, default=5)
    init.add_argument("--aggregation", choices=["equal", "fedavg", "coordinate_median", "trimmed_mean"], default="equal")
    init.add_argument("--max-samples", type=int, default=64)
    init.add_argument("--max-steps", type=int, default=16)
    init.add_argument("--max-l2", type=float, default=0.25)
    init.add_argument("--max-coordinate", type=float, default=0.1)
    init.add_argument("--lease-seconds", type=int, default=900)
    init.add_argument("--pow-bits", type=int, default=0)
    init.add_argument("--max-leases", type=int, default=2048)
    init.add_argument("--max-body-bytes", type=int, default=33554432)
    init.add_argument("--request-seconds", type=float, default=10)
    init.add_argument("--max-connections", type=int, default=4)
    init.add_argument("--receipt-seconds", type=int, default=86400)
    init.add_argument("--max-receipts", type=int, default=8192)
    serve = actions.add_parser("serve", help="serve HTTPS; certificate and key are mandatory")
    serve.add_argument("--db", required=True)
    serve.add_argument("--cert", required=True)
    serve.add_argument("--key", required=True)
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8443)
    status = actions.add_parser("status", help="inspect round metadata locally")
    status.add_argument("--db", required=True)
    evaluate = actions.add_parser("evaluate", help="run an external MODEL evaluator; strict JSON verdict on stdout")
    evaluate.add_argument("--db", required=True)
    evaluate.add_argument("--candidate", type=int, required=True)
    evaluate.add_argument("--timeout", type=float, default=60)
    evaluate.add_argument("--command", nargs=argparse.REMAINDER, required=True,
                          help="last argument; executable + args, receives snapshot JSON on stdin")
    promote = actions.add_parser("promote", help="publish an evaluated candidate")
    promote.add_argument("--db", required=True)
    promote.add_argument("--candidate", type=int, required=True)
    rollback = actions.add_parser("rollback", help="restore released weights under a new version number")
    rollback.add_argument("--db", required=True)
    rollback.add_argument("--version", type=int, required=True)
    return cli


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    os.umask(0o077)
    coordinator = None
    server = None
    try:
        if args.action == "init":
            with open(args.initial_adapter, "rb") as source:
                raw = source.read(33554433)
            if len(raw) > 33554432:
                raise ValueError("initial adapter exceeds input limit")
            initial = decode_json(raw)
            if type(initial) is not list:
                raise ValueError("initial adapter must be an array")
            policy = Policy(model_id=args.model_id, adapter_spec=args.adapter_spec, dimension=len(initial),
                            min_clients=args.min_clients, aggregation=args.aggregation,
                            max_samples=args.max_samples, max_steps=args.max_steps,
                            max_l2=args.max_l2, max_coordinate=args.max_coordinate,
                            lease_seconds=args.lease_seconds, pow_bits=args.pow_bits,
                            max_leases=args.max_leases, max_body_bytes=args.max_body_bytes,
                            request_seconds=args.request_seconds, max_connections=args.max_connections,
                            receipt_seconds=args.receipt_seconds, max_receipts=args.max_receipts)
            coordinator = Coordinator(args.db, policy, initial)
            result = coordinator.status()
        else:
            coordinator = Coordinator(args.db)
            if args.action == "serve":
                server = make_server((args.host, args.port), coordinator, args.cert, args.key)
                print("SIbalsa HTTPS coordinator listening; individual updates are visible to the server.", flush=True)
                server.serve_forever(poll_interval=0.5)
                return 0
            if args.action == "status":
                result = coordinator.status()
            elif args.action == "evaluate":
                if not args.command or not 0 < args.timeout <= 3600:
                    raise ValueError("evaluator command and bounded timeout are required")
                def evaluate(snapshot: dict) -> bool:
                    # Commands are local operator configuration, never client-supplied.
                    completed = subprocess.run(args.command, input=encode_json(snapshot),
                                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                               timeout=args.timeout, check=False)
                    if completed.returncode != 0 or len(completed.stdout) > 16384:
                        return False
                    verdict = decode_json(completed.stdout)
                    return type(verdict) is dict and set(verdict) == {"passed"} and type(verdict["passed"]) is bool and verdict["passed"]
                result = coordinator.evaluate_candidate(args.candidate, evaluate)
            elif args.action == "promote":
                result = coordinator.promote(args.candidate)
            else:
                result = coordinator.rollback(args.version)
        print(encode_json(result).decode())
        return 0
    except KeyboardInterrupt:
        return 0
    except (OSError, ValueError, ProtocolError, sqlite3.Error):
        # No filenames, database contents, evaluator output, or client data leak.
        print("Operation failed: verify local configuration and round state.", file=sys.stderr)
        return 2
    finally:
        if server is not None:
            server.server_close()
        if coordinator is not None:
            coordinator.close()


if __name__ == "__main__":
    raise SystemExit(main())
