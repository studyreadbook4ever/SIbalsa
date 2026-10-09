# SPDX-License-Identifier: Unlicense
"""Bounded HTTPS transport. There is deliberately no plaintext server mode."""

from __future__ import annotations

import socket
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

from .coordinator import Coordinator, ProtocolError, decode_json, encode_json


class _DeadlineInput:
    """A small buffered reader with an absolute wall-clock deadline per request."""

    def __init__(self, connection: ssl.SSLSocket, deadline: float):
        self.connection, self.deadline = connection, deadline
        self.buffer = bytearray()
        self.header_mode = True
        self.header_bytes = 0
        self.closed = False

    def _fill(self) -> bool:
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("request deadline")
        self.connection.settimeout(remaining)
        chunk = self.connection.recv(8192)
        self.buffer.extend(chunk)
        return bool(chunk)

    def readline(self, size: int = -1) -> bytes:
        bound = 8192 if size < 0 else min(size, 8192)
        while True:
            newline = self.buffer.find(b"\n", 0, bound)
            if newline >= 0:
                count = newline + 1
                break
            if len(self.buffer) >= bound:
                count = bound
                break
            if not self._fill():
                count = len(self.buffer)
                break
        result = bytes(self.buffer[:count])
        del self.buffer[:count]
        self.header_bytes += count
        if self.header_mode and self.header_bytes > 8192:
            raise ProtocolError("headers_too_large", 431)
        return result

    def read(self, size: int = -1) -> bytes:
        if size < 0:
            raise ValueError("unbounded reads are disabled")
        while len(self.buffer) < size:
            if not self._fill():
                break
        result = bytes(self.buffer[:size])
        del self.buffer[:size]
        return result

    def close(self) -> None:
        self.closed = True
        self.buffer.clear()


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "SIbalsa"
    sys_version = ""

    def setup(self) -> None:
        super().setup()
        self.rfile.close()
        self.rfile = _DeadlineInput(self.connection, self.request._sibalsa_deadline)

    def log_message(self, *_: object) -> None:
        pass

    def log_error(self, *_: object) -> None:
        pass

    def parse_request(self) -> bool:
        if not super().parse_request():
            return False
        self.close_connection = True
        self.rfile.header_mode = False
        if len(self.path) > 256 or len(self.headers) > 32:
            self._reply({"error": "headers_too_large"}, 431)
            return False
        if len(self.headers.get_all("Content-Length", [])) > 1:
            self._reply({"error": "invalid_headers"}, 400)
            return False
        if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Encoding"):
            self._reply({"error": "invalid_headers"}, 400)
            return False
        return True

    def send_error(self, code: int, message: str | None = None, explain: str | None = None) -> None:
        self._reply({"error": "invalid_request"}, code)

    def _reply(self, value: object, status: int = 200) -> None:
        encoded = encode_json(value)
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Connection", "close")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(encoded)
        self.close_connection = True

    def do_GET(self) -> None:
        try:
            if self.headers.get("Content-Length", "0") != "0":
                raise ProtocolError("unexpected_body")
            if self.path == "/v1/policy":
                result = self.server.coordinator.public_policy()
            elif self.path == "/v1/adapter":
                result = self.server.coordinator.adapter()
            else:
                raise ProtocolError("not_found", 404)
            self._reply(result)
        except ProtocolError as error:
            self._reply({"error": error.code}, error.status)
        except Exception:
            self._reply({"error": "internal_error"}, 500)

    def do_POST(self) -> None:
        try:
            if self.path not in {"/v1/lease", "/v1/update"}:
                raise ProtocolError("not_found", 404)
            if self.headers.get("Content-Type", "").lower() not in {"application/json", "application/json; charset=utf-8"}:
                raise ProtocolError("content_type_required", 415)
            length = self.headers.get("Content-Length", "")
            if not length or not length.isascii() or not length.isdecimal() or len(length) > 10:
                raise ProtocolError("content_length_required", 411)
            count = int(length)
            if count > self.server.coordinator.policy.max_body_bytes:
                raise ProtocolError("body_too_large", 413)
            raw = self.rfile.read(count)
            if len(raw) != count:
                raise ProtocolError("incomplete_body")
            payload = decode_json(raw)
            if self.path == "/v1/lease":
                result = self.server.coordinator.issue_lease(payload)
            else:
                result = self.server.coordinator.accept_update(payload)
            self._reply(result)
        except (TimeoutError, socket.timeout):
            self._reply({"error": "request_timeout"}, 408)
        except ProtocolError as error:
            self._reply({"error": error.code}, error.status)
        except Exception:
            self._reply({"error": "internal_error"}, 500)


class _TLSServer(ThreadingMixIn, HTTPServer):
    daemon_threads = False
    block_on_close = True

    def __init__(self, address: tuple[str, int], coordinator: Coordinator, context: ssl.SSLContext):
        self.coordinator, self.context = coordinator, context
        self._slots = threading.BoundedSemaphore(coordinator.policy.max_connections)
        super().__init__(address, _Handler)

    def get_request(self) -> tuple[ssl.SSLSocket, object]:
        raw, address = super().get_request()
        try:
            connection = self.context.wrap_socket(raw, server_side=True, do_handshake_on_connect=False)
            return connection, address
        except Exception:
            raw.close()
            raise

    def process_request(self, request: ssl.SSLSocket, client_address: object) -> None:
        if not self._slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._slots.release()
            self.shutdown_request(request)
            raise

    def process_request_thread(self, request: ssl.SSLSocket, client_address: object) -> None:
        try:
            request._sibalsa_deadline = time.monotonic() + self.coordinator.policy.request_seconds
            request.settimeout(self.coordinator.policy.request_seconds)
            request.do_handshake()
            super().process_request_thread(request, client_address)
        except (OSError, ProtocolError, ValueError):
            self.shutdown_request(request)
        finally:
            self._slots.release()

    def handle_error(self, request: object, client_address: object) -> None:
        # Never print a client address, submitted body, traceback, or identifier.
        pass

    def service_actions(self) -> None:
        self.coordinator.prune()


def make_server(address: tuple[str, int], coordinator: Coordinator, certfile: str, keyfile: str) -> _TLSServer:
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certfile, keyfile)
    return _TLSServer(address, coordinator, context)
