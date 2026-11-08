"""Loopback pgvector lookup for the public knowledge catalog."""

import argparse
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path

from retrieval import CATALOG, DEFAULT_MODEL, DEFAULT_MODEL_CACHE, load_index, open_pgvector, query_vector


TOP_K = 4
MAX_REQUEST_BYTES = 4096


def handler_for(model, dimension, fingerprint, lookup):
    """Expose only the ranked public IDs and the catalog version."""
    import psycopg

    class Handler(BaseHTTPRequestHandler):
        timeout = 2

        def do_POST(self):
            if self.path != "/retrieve":
                self.send_error(404)
                return

            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 1 <= size <= MAX_REQUEST_BYTES:
                    raise ValueError("Invalid request size")
                data = json.loads(self.rfile.read(size))
                if not isinstance(data, dict) or set(data) != {"question"}:
                    raise ValueError("Invalid request")
                question = data["question"]
                if not isinstance(question, str) or not 1 <= len(question) <= 300 or not question.strip():
                    raise ValueError("Invalid question")
            except (ValueError, OSError):
                self.send_error(400)
                return

            try:
                vector = query_vector(model, question, dimension)
                source_ids = lookup(vector, TOP_K)
            except (psycopg.Error, RuntimeError, ValueError):
                self.send_error(503)
                return

            body = json.dumps({"catalogSha256": fingerprint, "sourceIds": source_ids}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            # Never write user questions to the pilot log.
            pass

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--pg-port", type=int, required=True)
    parser.add_argument("--port", type=int, default=18584)
    parser.add_argument("--model-path", type=Path, help="Load the model files prepared in the image")
    args = parser.parse_args()
    if not 1 <= args.pg_port <= 65535 or not 1 <= args.port <= 65535:
        parser.error("Use valid loopback ports")
    if not os.environ.get("KNOWLEDGE_EVAL_PG_PASSWORD"):
        parser.error("Set KNOWLEDGE_EVAL_PG_PASSWORD for the dedicated eval database")

    catalog = CATALOG.read_bytes()
    fingerprint = sha256(catalog.replace(b"\r\n", b"\n")).hexdigest()
    model, indexed, dimension = load_index(
        json.loads(catalog), DEFAULT_MODEL, DEFAULT_MODEL_CACHE, model_path=args.model_path
    )
    # One local worker owns the model and temporary database table for its lifetime.
    with open_pgvector(indexed, args.pg_port) as lookup:
        with HTTPServer(("127.0.0.1", args.port), handler_for(model, dimension, fingerprint, lookup)) as server:
            print(f"Knowledge retrieval is listening on 127.0.0.1:{args.port}", flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass


if __name__ == "__main__":
    main()
