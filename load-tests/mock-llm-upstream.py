#!/usr/bin/env python3
"""Dependency-free SSE upstream for capacity tests; never calls a real provider."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json, os, threading, time

DELAY = float(os.environ.get("MOCK_TOKEN_DELAY_MS", "50")) / 1000
TOKENS = int(os.environ.get("MOCK_TOKENS", "80"))
PORT = int(os.environ.get("MOCK_LLM_PORT", "8003"))
lock = threading.Lock(); active = 0; max_active = 0; completed = 0; disconnected = 0


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *_): pass
    def do_GET(self):
        if self.path != "/stats": self.send_error(404); return
        with lock: payload = json.dumps({"active": active, "max_active": max_active,
                                         "completed": completed, "disconnected": disconnected}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload))); self.end_headers(); self.wfile.write(payload)
    def do_POST(self):
        global active, max_active, completed, disconnected
        if self.path != "/chat/stream": self.send_error(404); return
        length = int(self.headers.get("Content-Length", 0)); self.rfile.read(length)
        self.send_response(200); self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache"); self.send_header("Connection", "close"); self.end_headers()
        with lock: active += 1; max_active = max(max_active, active)
        try:
            for _ in range(TOKENS):
                self.wfile.write(b'event: delta\ndata: {"content":"x"}\n\n'); self.wfile.flush(); time.sleep(DELAY)
            self.wfile.write(b'event: done\ndata: {"status":"completed","usage_source":"estimate"}\n\n')
            with lock: completed += 1
        except (BrokenPipeError, ConnectionResetError):
            with lock: disconnected += 1
        finally:
            with lock: active -= 1


if __name__ == "__main__":
    print(f"mock llm listening on 127.0.0.1:{PORT}, token_delay_ms={DELAY * 1000:g}, tokens={TOKENS}")
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
