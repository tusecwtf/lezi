from __future__ import annotations

import hashlib
import json
import os
import sqlite3
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from uuid import uuid4


# Keep the UUID placeholders below SQLite's legacy 999-parameter limit.
ENTITY_QUERY_CHUNK_SIZE = 400


def hash_secret(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class Principal:
    family_id: str
    role: str
    token_hash: str
    device_id: str


class Store:
    def __init__(self, database_path: Path) -> None:
        self.database_path = database_path
        self.database_path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        self.database_path.parent.chmod(0o700)
        self._initialize()
        self._secure_database_files()

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=10)
        os.chmod(self.database_path, 0o600)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA journal_mode = WAL")
        self._secure_database_files()
        return connection

    def _secure_database_files(self) -> None:
        for suffix in ("", "-wal", "-shm"):
            path = Path(f"{self.database_path}{suffix}")
            if path.exists():
                path.chmod(0o600)

    def _initialize(self) -> None:
        with self.connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS families (
                    id TEXT PRIMARY KEY,
                    created_at INTEGER NOT NULL,
                    create_request_hash TEXT
                );

                CREATE TABLE IF NOT EXISTS memberships (
                    token_hash TEXT PRIMARY KEY,
                    family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                    role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
                    device_id TEXT NOT NULL,
                    display_name TEXT,
                    revoked_at INTEGER
                );
                CREATE INDEX IF NOT EXISTS memberships_family
                    ON memberships(family_id);

                CREATE TABLE IF NOT EXISTS invites (
                    code_hash TEXT PRIMARY KEY,
                    family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                    expires_at INTEGER NOT NULL,
                    used_at INTEGER,
                    joined_device_id TEXT
                );

                CREATE TABLE IF NOT EXISTS family_meta (
                    family_id TEXT PRIMARY KEY REFERENCES families(id) ON DELETE CASCADE,
                    rev INTEGER NOT NULL DEFAULT 0
                );

                CREATE TABLE IF NOT EXISTS entities (
                    family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                    entity_type TEXT NOT NULL CHECK(entity_type IN ('baby', 'record', 'media')),
                    client_uuid TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    deleted_at INTEGER,
                    payload_json TEXT NOT NULL,
                    rev INTEGER NOT NULL,
                    PRIMARY KEY (family_id, entity_type, client_uuid)
                );
                CREATE INDEX IF NOT EXISTS entities_family_rev
                    ON entities(family_id, rev);
                """
            )
            invite_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(invites)").fetchall()
            }
            if "joined_device_id" not in invite_columns:
                connection.execute("ALTER TABLE invites ADD COLUMN joined_device_id TEXT")
            family_columns = {
                row["name"]
                for row in connection.execute("PRAGMA table_info(families)").fetchall()
            }
            if "create_request_hash" not in family_columns:
                connection.execute(
                    "ALTER TABLE families ADD COLUMN create_request_hash TEXT"
                )
            connection.execute(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS families_create_request
                ON families(create_request_hash)
                """
            )

    def create_family(
        self,
        *,
        now: int,
        create_request_id: str,
        device_id: str,
        display_name: str | None,
        derive_token: Callable[[str, str], str],
    ) -> tuple[str, str]:
        create_request_hash = hash_secret(create_request_id)
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            retry = connection.execute(
                """
                SELECT families.id, memberships.device_id,
                       memberships.display_name, memberships.revoked_at
                FROM families
                JOIN memberships
                  ON memberships.family_id = families.id
                 AND memberships.role = 'owner'
                WHERE families.create_request_hash = ?
                """,
                (create_request_hash,),
            ).fetchone()
            if retry is not None:
                if (
                    retry["device_id"] != device_id
                    or retry["display_name"] != display_name
                    or retry["revoked_at"] is not None
                ):
                    raise FamilyAlreadyExists
                family_id = str(retry["id"])
                return family_id, derive_token(create_request_hash, family_id)
            if connection.execute("SELECT 1 FROM families LIMIT 1").fetchone():
                raise FamilyAlreadyExists
            family_id = str(uuid4())
            token = derive_token(create_request_hash, family_id)
            connection.execute(
                """
                INSERT INTO families(id, created_at, create_request_hash)
                VALUES (?, ?, ?)
                """,
                (family_id, now, create_request_hash),
            )
            connection.execute(
                "INSERT INTO family_meta(family_id, rev) VALUES (?, 0)",
                (family_id,),
            )
            connection.execute(
                """
                INSERT INTO memberships(
                    token_hash, family_id, role, device_id, display_name
                ) VALUES (?, ?, 'owner', ?, ?)
                """,
                (hash_secret(token), family_id, device_id, display_name),
            )
            connection.commit()
        return family_id, token

    def authenticate(self, token: str) -> Principal | None:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT token_hash, family_id, role, device_id
                FROM memberships
                WHERE token_hash = ? AND revoked_at IS NULL
                """,
                (hash_secret(token),),
            ).fetchone()
        return Principal(**dict(row)) if row else None

    def create_invite(
        self,
        *,
        family_id: str,
        now: int,
        ttl_seconds: int,
        new_code: Callable[[], str],
    ) -> tuple[str, int]:
        code = new_code()
        expires_at = now + ttl_seconds
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO invites(code_hash, family_id, expires_at)
                VALUES (?, ?, ?)
                """,
                (hash_secret(code), family_id, expires_at),
            )
        return code, expires_at

    def join_family(
        self,
        *,
        code: str,
        device_id: str,
        display_name: str | None,
        now: int,
        derive_token: Callable[[str, str], str],
    ) -> tuple[str, str]:
        code_hash = hash_secret(code)
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            invite = connection.execute(
                """
                SELECT family_id, expires_at, used_at, joined_device_id
                FROM invites WHERE code_hash = ?
                """,
                (code_hash,),
            ).fetchone()
            if invite is None:
                raise InviteNotFound
            token = derive_token(code_hash, device_id)
            token_hash = hash_secret(token)
            if invite["used_at"] is not None:
                if invite["joined_device_id"] != device_id:
                    raise InviteAlreadyUsed
                active = connection.execute(
                    """
                    SELECT 1 FROM memberships
                    WHERE token_hash = ? AND family_id = ? AND revoked_at IS NULL
                    """,
                    (token_hash, invite["family_id"]),
                ).fetchone()
                if active is None:
                    raise InviteNotFound
                return str(invite["family_id"]), token
            if invite["expires_at"] <= now:
                raise InviteExpired
            connection.execute(
                """
                INSERT INTO memberships(
                    token_hash, family_id, role, device_id, display_name
                ) VALUES (?, ?, 'member', ?, ?)
                """,
                (token_hash, invite["family_id"], device_id, display_name),
            )
            connection.execute(
                """
                UPDATE invites
                SET used_at = ?, joined_device_id = ?
                WHERE code_hash = ?
                """,
                (now, device_id, code_hash),
            )
            connection.commit()
        return str(invite["family_id"]), token

    def revoke(self, token_hash: str, now: int) -> None:
        with self.connect() as connection:
            connection.execute(
                "UPDATE memberships SET revoked_at = ? WHERE token_hash = ?",
                (now, token_hash),
            )

    def delete_family(self, family_id: str) -> None:
        with self.connect() as connection:
            connection.execute("DELETE FROM families WHERE id = ?", (family_id,))

    def push(
        self,
        family_id: str,
        role: str,
        entities: list[dict[str, Any]],
    ) -> dict[str, int]:
        applied = 0
        skipped = 0
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            incoming_keys = {
                (entity["type"], entity["client_uuid"])
                for entity in entities
            }
            existing = self._load_existing_entities(
                connection,
                family_id,
                incoming_keys,
            )
            effective_entities = self._effective_lww_winners(entities, existing)
            reference_keys = self._validation_reference_keys(effective_entities)
            existing.update(
                self._load_existing_entities(
                    connection,
                    family_id,
                    reference_keys - incoming_keys,
                ),
            )
            self._validate_push(
                connection,
                family_id,
                role,
                effective_entities,
                existing,
            )
            skipped = len(entities) - len(effective_entities)
            cursor = int(
                connection.execute(
                    "SELECT rev FROM family_meta WHERE family_id = ?",
                    (family_id,),
                ).fetchone()["rev"]
            )
            order = {"baby": 0, "record": 1, "media": 2}
            for entity in sorted(
                effective_entities,
                key=lambda item: order[item["type"]],
            ):
                cursor += 1
                connection.execute(
                    """
                    INSERT INTO entities(
                        family_id, entity_type, client_uuid, updated_at,
                        deleted_at, payload_json, rev
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(family_id, entity_type, client_uuid) DO UPDATE SET
                        updated_at = excluded.updated_at,
                        deleted_at = excluded.deleted_at,
                        payload_json = excluded.payload_json,
                        rev = excluded.rev
                    """,
                    (
                        family_id,
                        entity["type"],
                        entity["client_uuid"],
                        entity["updated_at"],
                        entity.get("deleted_at"),
                        json.dumps(
                            entity.get("payload", {}),
                            ensure_ascii=False,
                            separators=(",", ":"),
                            sort_keys=True,
                        ),
                        cursor,
                    ),
                )
                applied += 1
                existing[(entity["type"], entity["client_uuid"])] = {
                    "updated_at": entity["updated_at"],
                    "payload_json": json.dumps(entity["payload"]),
                }
            connection.execute(
                "UPDATE family_meta SET rev = ? WHERE family_id = ?",
                (cursor, family_id),
            )
            connection.commit()
        return {"applied": applied, "skipped": skipped, "cursor": cursor}

    @staticmethod
    def _load_existing_entities(
        connection: sqlite3.Connection,
        family_id: str,
        keys: set[tuple[str, str]],
    ) -> dict[tuple[str, str], sqlite3.Row]:
        existing: dict[tuple[str, str], sqlite3.Row] = {}
        keys_by_type: dict[str, set[str]] = {}
        for entity_type, client_uuid in keys:
            keys_by_type.setdefault(entity_type, set()).add(client_uuid)
        for entity_type, client_uuids in sorted(keys_by_type.items()):
            ordered_uuids = sorted(client_uuids)
            for offset in range(0, len(ordered_uuids), ENTITY_QUERY_CHUNK_SIZE):
                chunk = ordered_uuids[offset : offset + ENTITY_QUERY_CHUNK_SIZE]
                placeholders = ", ".join("?" for _ in chunk)
                rows = connection.execute(
                    f"""
                    SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json
                    FROM entities
                    WHERE family_id = ? AND entity_type = ?
                      AND client_uuid IN ({placeholders})
                    """,
                    (family_id, entity_type, *chunk),
                ).fetchall()
                existing.update({
                    (row["entity_type"], row["client_uuid"]): row
                    for row in rows
                })
        return existing

    @staticmethod
    def _validation_reference_keys(
        entities: list[dict[str, Any]],
    ) -> set[tuple[str, str]]:
        references: set[tuple[str, str]] = set()
        for entity in entities:
            payload = entity["payload"]
            if entity["type"] == "record":
                references.add(("baby", payload["baby_client_uuid"]))
            elif entity["type"] == "media":
                if payload["kind"] == "avatar":
                    references.add(("baby", payload["baby_client_uuid"]))
                else:
                    references.add(("record", payload["record_client_uuid"]))
            elif payload.get("avatar_media_uuid") is not None:
                references.add(("media", payload["avatar_media_uuid"]))
        return references

    @staticmethod
    def _effective_lww_winners(
        entities: list[dict[str, Any]],
        existing: dict[tuple[str, str], sqlite3.Row],
    ) -> list[dict[str, Any]]:
        winners: dict[tuple[str, str], tuple[int, dict[str, Any]]] = {}
        for index, entity in enumerate(entities):
            key = (entity["type"], entity["client_uuid"])
            current = existing.get(key)
            if (
                current is not None
                and int(current["updated_at"]) >= entity["updated_at"]
            ):
                continue
            previous = winners.get(key)
            if (
                previous is None
                or entity["updated_at"] > previous[1]["updated_at"]
            ):
                winners[key] = (index, entity)
        return [
            entity
            for _, entity in sorted(winners.values(), key=lambda value: value[0])
        ]

    def _validate_push(
        self,
        connection: sqlite3.Connection,
        family_id: str,
        role: str,
        entities: list[dict[str, Any]],
        existing: dict[tuple[str, str], sqlite3.Row],
    ) -> None:
        del connection, family_id
        baby_ids = {
            client_uuid
            for entity_type, client_uuid in existing
            if entity_type == "baby"
        }
        baby_ids.update(
            entity["client_uuid"] for entity in entities if entity["type"] == "baby"
        )

        effective_records: dict[str, dict[str, Any]] = {
            client_uuid: json.loads(row["payload_json"])
            for (entity_type, client_uuid), row in existing.items()
            if entity_type == "record"
        }
        incoming_records: dict[str, dict[str, Any]] = {}
        incoming_record_versions: dict[str, int] = {}
        for entity in entities:
            if entity["type"] != "record":
                continue
            baby_id = entity["payload"]["baby_client_uuid"]
            if baby_id not in baby_ids:
                raise UnresolvedReference("record baby_client_uuid does not exist")
            if entity["updated_at"] >= incoming_record_versions.get(entity["client_uuid"], -1):
                incoming_records[entity["client_uuid"]] = entity["payload"]
                incoming_record_versions[entity["client_uuid"]] = entity["updated_at"]
        for record_id, payload in incoming_records.items():
            current = existing.get(("record", record_id))
            if current is None or incoming_record_versions[record_id] > int(current["updated_at"]):
                effective_records[record_id] = payload

        record_ids = set(effective_records)
        record_ids.update(incoming_records)
        effective_media: dict[
            str,
            tuple[tuple[str, str | None, str | None], int | None, int],
        ] = {
            client_uuid: (
                _media_association(json.loads(row["payload_json"])),
                row["deleted_at"],
                int(row["updated_at"]),
            )
            for (entity_type, client_uuid), row in existing.items()
            if entity_type == "media"
        }
        seen_media: dict[str, tuple[str, str | None, str | None]] = {}
        for entity in entities:
            if entity["type"] != "media":
                continue
            payload = entity["payload"]
            incoming_association = _media_association(payload)
            previous_in_batch = seen_media.get(entity["client_uuid"])
            if previous_in_batch is not None and previous_in_batch != incoming_association:
                raise ImmutableMediaAssociation
            seen_media[entity["client_uuid"]] = incoming_association

            current = existing.get(("media", entity["client_uuid"]))
            existing_association = (
                _media_association(json.loads(current["payload_json"]))
                if current is not None
                else None
            )
            if role != "owner" and (
                incoming_association[0] == "avatar"
                or (existing_association is not None and existing_association[0] == "avatar")
            ):
                raise ForbiddenAvatar
            if (
                existing_association is not None
                and existing_association != incoming_association
            ):
                raise ImmutableMediaAssociation

            effective = effective_media.get(entity["client_uuid"])
            if effective is None or entity["updated_at"] > effective[2]:
                effective_media[entity["client_uuid"]] = (
                    incoming_association,
                    entity.get("deleted_at"),
                    entity["updated_at"],
                )

            kind, record_id, baby_id = incoming_association
            if kind == "avatar":
                if baby_id not in baby_ids:
                    raise UnresolvedReference("avatar baby_client_uuid does not exist")
                continue
            if record_id not in record_ids:
                raise UnresolvedReference("log media record_client_uuid does not exist")
            record_baby_id = effective_records[record_id]["baby_client_uuid"]
            if baby_id is not None and baby_id != record_baby_id:
                raise UnresolvedReference("log media baby does not match record baby")

        for entity in entities:
            if entity["type"] != "baby":
                continue
            avatar_media_uuid = entity["payload"].get("avatar_media_uuid")
            current = existing.get(("baby", entity["client_uuid"]))
            current_avatar_media_uuid = (
                json.loads(current["payload_json"]).get("avatar_media_uuid")
                if current is not None
                else None
            )
            if role != "owner" and (
                (current is None and avatar_media_uuid is not None)
                or (
                    current is not None
                    and avatar_media_uuid != current_avatar_media_uuid
                )
            ):
                raise ForbiddenAvatar
            if avatar_media_uuid is None:
                continue
            avatar_media = effective_media.get(avatar_media_uuid)
            if (
                avatar_media is None
                or avatar_media[1] is not None
                or avatar_media[0][0] != "avatar"
                or avatar_media[0][2] != entity["client_uuid"]
            ):
                raise UnresolvedReference(
                    "baby avatar_media_uuid must reference avatar media for the same baby"
                )

    def pull(self, family_id: str, cursor: int) -> tuple[list[dict[str, Any]], int]:
        with self.connect() as connection:
            current = int(
                connection.execute(
                    "SELECT rev FROM family_meta WHERE family_id = ?",
                    (family_id,),
                ).fetchone()["rev"]
            )
            if cursor > current:
                raise CursorAhead(current)
            rows = connection.execute(
                """
                SELECT entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                FROM entities
                WHERE family_id = ? AND rev > ?
                ORDER BY rev ASC
                """,
                (family_id, cursor),
            ).fetchall()
        entities = [
            {
                "type": row["entity_type"],
                "client_uuid": row["client_uuid"],
                "updated_at": row["updated_at"],
                "deleted_at": row["deleted_at"],
                "payload": json.loads(row["payload_json"]),
                "rev": row["rev"],
            }
            for row in rows
        ]
        return entities, current

    def media_kind(self, family_id: str, client_uuid: str) -> str | None:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT payload_json, deleted_at FROM entities
                WHERE family_id = ? AND entity_type = 'media' AND client_uuid = ?
                """,
                (family_id, client_uuid),
            ).fetchone()
        if row is None or row["deleted_at"] is not None:
            return None
        return json.loads(row["payload_json"]).get("kind")


class FamilyAlreadyExists(Exception):
    pass


class InviteNotFound(Exception):
    pass


class InviteExpired(Exception):
    pass


class InviteAlreadyUsed(Exception):
    pass


class CursorAhead(Exception):
    def __init__(self, server_cursor: int) -> None:
        self.server_cursor = server_cursor
        super().__init__(f"cursor is ahead of server cursor {server_cursor}")


class ForbiddenAvatar(Exception):
    pass


class ImmutableMediaAssociation(Exception):
    pass


class UnresolvedReference(Exception):
    pass


def _media_association(payload: dict[str, Any]) -> tuple[str, str | None, str | None]:
    return (
        payload["kind"],
        payload.get("record_client_uuid"),
        payload.get("baby_client_uuid"),
    )
