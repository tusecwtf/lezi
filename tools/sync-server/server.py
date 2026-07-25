#!/usr/bin/env python3
"""Minimal lezi family sync backend — stdlib only.

Endpoints:
  POST /v1/push          JSON body {family_id, device_id, entities:[...]}
  GET  /v1/pull?family_id=&cursor=
  POST /v1/invite        {family_id} -> {code, expires_at}
  POST /v1/join          {code, device_id, display_name?} -> {family_id, entities}

Storage (single data root — db + media side by side):
  $LEZI_DATA_DIR/lezi.db
  $LEZI_DATA_DIR/media/

  LEZI_DATA_DIR default: directory of this script (local dev).
  Docker: LEZI_DATA_DIR=/data (compose volume).

  Legacy: LEZI_SYNC_DB still overrides the SQLite file path if set.
"""
from __future__ import annotations

import json
import os
import sqlite3
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.environ.get("LEZI_DATA_DIR", _SCRIPT_DIR)
DB_PATH = os.environ.get("LEZI_SYNC_DB", os.path.join(DATA_DIR, "lezi.db"))
MEDIA_DIR = os.path.join(DATA_DIR, "media")
HOST = os.environ.get("LEZI_SYNC_HOST", "0.0.0.0")
PORT = int(os.environ.get("LEZI_SYNC_PORT", "8765"))
VERSION = os.environ.get("LEZI_SYNC_VERSION", "0.1.0-dev")


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    return conn


def ensure_data_dirs() -> None:
    os.makedirs(DATA_DIR, exist_ok=True)
    os.makedirs(MEDIA_DIR, exist_ok=True)


def init_db() -> None:
    ensure_data_dirs()
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS entities (
              family_id TEXT NOT NULL,
              entity_type TEXT NOT NULL,
              client_uuid TEXT NOT NULL,
              payload TEXT NOT NULL,
              updated_at INTEGER NOT NULL,
              deleted_at INTEGER,
              rev INTEGER NOT NULL,
              PRIMARY KEY (family_id, entity_type, client_uuid)
            );
            CREATE INDEX IF NOT EXISTS idx_entities_pull
              ON entities(family_id, rev);
            CREATE TABLE IF NOT EXISTS invites (
              code TEXT PRIMARY KEY,
              family_id TEXT NOT NULL,
              expires_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS meta (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            );
            """
        )
        row = conn.execute("SELECT value FROM meta WHERE key='rev'").fetchone()
        if row is None:
            conn.execute("INSERT INTO meta(key, value) VALUES('rev', '0')")


def next_rev(conn: sqlite3.Connection) -> int:
    cur = conn.execute("SELECT value FROM meta WHERE key='rev'").fetchone()
    rev = int(cur["value"]) + 1
    conn.execute("UPDATE meta SET value=? WHERE key='rev'", (str(rev),))
    return rev


def json_response(handler: BaseHTTPRequestHandler, code: int, obj) -> None:
    data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(data)))
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.end_headers()
    handler.wfile.write(data)


def read_json(handler: BaseHTTPRequestHandler):
    length = int(handler.headers.get("Content-Length", "0"))
    raw = handler.rfile.read(length) if length else b"{}"
    return json.loads(raw.decode("utf-8") or "{}")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):  # quieter
        print("[sync]", self.address_string(), fmt % args)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            return json_response(
                self,
                200,
                {"ok": True, "version": VERSION, "data_dir": DATA_DIR},
            )
        if parsed.path == "/v1/pull":
            qs = parse_qs(parsed.query)
            family_id = (qs.get("family_id") or [""])[0]
            cursor = int((qs.get("cursor") or ["0"])[0] or 0)
            if not family_id:
                return json_response(self, 400, {"error": "family_id required"})
            with db() as conn:
                rows = conn.execute(
                    """
                    SELECT entity_type, client_uuid, payload, updated_at, deleted_at, rev
                    FROM entities
                    WHERE family_id=? AND rev > ?
                    ORDER BY rev ASC
                    """,
                    (family_id, cursor),
                ).fetchall()
            entities = [
                {
                    "type": r["entity_type"],
                    "client_uuid": r["client_uuid"],
                    "payload": json.loads(r["payload"]),
                    "updated_at": r["updated_at"],
                    "deleted_at": r["deleted_at"],
                    "rev": r["rev"],
                }
                for r in rows
            ]
            next_cursor = entities[-1]["rev"] if entities else cursor
            return json_response(self, 200, {"entities": entities, "cursor": next_cursor})
        return json_response(self, 404, {"error": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)
        body = read_json(self)
        if parsed.path == "/v1/push":
            family_id = body.get("family_id")
            entities = body.get("entities") or []
            if not family_id:
                return json_response(self, 400, {"error": "family_id required"})
            applied = 0
            with db() as conn:
                for ent in entities:
                    etype = ent.get("type")
                    cuuid = ent.get("client_uuid")
                    payload = ent.get("payload") or {}
                    updated_at = int(ent.get("updated_at") or int(time.time() * 1000))
                    deleted_at = ent.get("deleted_at")
                    if not etype or not cuuid:
                        continue
                    existing = conn.execute(
                        "SELECT updated_at, client_uuid FROM entities WHERE family_id=? AND entity_type=? AND client_uuid=?",
                        (family_id, etype, cuuid),
                    ).fetchone()
                    if existing and int(existing["updated_at"]) > updated_at:
                        # LWW keep server
                        continue
                    rev = next_rev(conn)
                    conn.execute(
                        """
                        INSERT INTO entities(family_id, entity_type, client_uuid, payload, updated_at, deleted_at, rev)
                        VALUES(?,?,?,?,?,?,?)
                        ON CONFLICT(family_id, entity_type, client_uuid) DO UPDATE SET
                          payload=excluded.payload,
                          updated_at=excluded.updated_at,
                          deleted_at=excluded.deleted_at,
                          rev=excluded.rev
                        """,
                        (
                            family_id,
                            etype,
                            cuuid,
                            json.dumps(payload, ensure_ascii=False),
                            updated_at,
                            deleted_at,
                            rev,
                        ),
                    )
                    applied += 1
            return json_response(self, 200, {"applied": applied})

        if parsed.path == "/v1/invite":
            family_id = body.get("family_id")
            if not family_id:
                return json_response(self, 400, {"error": "family_id required"})
            code = uuid.uuid4().hex[:8].upper()
            expires = int(time.time() * 1000) + 24 * 3600 * 1000
            with db() as conn:
                conn.execute(
                    "INSERT INTO invites(code, family_id, expires_at) VALUES(?,?,?)",
                    (code, family_id, expires),
                )
            return json_response(self, 200, {"code": code, "expires_at": expires})

        if parsed.path == "/v1/join":
            code = (body.get("code") or "").upper()
            with db() as conn:
                inv = conn.execute(
                    "SELECT family_id, expires_at FROM invites WHERE code=?",
                    (code,),
                ).fetchone()
                if inv is None:
                    return json_response(self, 404, {"error": "invalid code"})
                if int(inv["expires_at"]) < int(time.time() * 1000):
                    return json_response(self, 410, {"error": "expired"})
                family_id = inv["family_id"]
                rows = conn.execute(
                    """
                    SELECT entity_type, client_uuid, payload, updated_at, deleted_at, rev
                    FROM entities WHERE family_id=? ORDER BY rev ASC
                    """,
                    (family_id,),
                ).fetchall()
            entities = [
                {
                    "type": r["entity_type"],
                    "client_uuid": r["client_uuid"],
                    "payload": json.loads(r["payload"]),
                    "updated_at": r["updated_at"],
                    "deleted_at": r["deleted_at"],
                    "rev": r["rev"],
                }
                for r in rows
            ]
            cursor = entities[-1]["rev"] if entities else 0
            return json_response(
                self,
                200,
                {"family_id": family_id, "entities": entities, "cursor": cursor},
            )

        return json_response(self, 404, {"error": "not found"})


def main():
    init_db()
    httpd = ThreadingHTTPServer((HOST, PORT), Handler)
    print(
        f"lezi sync server on http://{HOST}:{PORT} "
        f"data_dir={DATA_DIR} db={DB_PATH} media={MEDIA_DIR}"
    )
    httpd.serve_forever()


if __name__ == "__main__":
    main()
