from __future__ import annotations

import asyncio
import re
import sqlite3
import stat
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from app.db import Store
from app.main import create_app


class MutableClock:
    def __init__(self) -> None:
        self.now = datetime(2026, 7, 25, 4, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        return self.now

    def advance(self, **delta: int) -> None:
        self.now += timedelta(**delta)


@pytest.fixture
def clock() -> MutableClock:
    return MutableClock()


@pytest.fixture
def data_dir(tmp_path: Path) -> Path:
    return tmp_path / "data"


class AsgiClient:
    def __init__(self, app) -> None:
        self.app = app

    def request(self, method: str, url: str, **kwargs) -> httpx.Response:
        async def send() -> httpx.Response:
            transport = httpx.ASGITransport(app=self.app)
            async with httpx.AsyncClient(
                transport=transport,
                base_url="http://testserver",
            ) as client:
                return await client.request(method, url, **kwargs)

        return asyncio.run(send())

    def get(self, url: str, **kwargs) -> httpx.Response:
        return self.request("GET", url, **kwargs)

    def post(self, url: str, **kwargs) -> httpx.Response:
        return self.request("POST", url, **kwargs)

    def put(self, url: str, **kwargs) -> httpx.Response:
        return self.request("PUT", url, **kwargs)


@pytest.fixture
def client(data_dir: Path, clock: MutableClock):
    app = create_app(
        data_dir=data_dir,
        clock=clock,
        max_media_bytes=8,
    )
    if sys.version_info < (3, 14):
        with TestClient(app) as test_client:
            yield test_client
    else:
        # Starlette's blocking portal currently deadlocks under the host's
        # Python 3.14. Exercise the identical ASGI seam until upstream catches up.
        yield AsgiClient(app)


@pytest.mark.skipif(
    sys.version_info >= (3, 14),
    reason="Starlette TestClient portal deadlocks on the host Python 3.14",
)
def test_fastapi_testclient_smoke(data_dir: Path, clock: MutableClock) -> None:
    with TestClient(create_app(data_dir=data_dir, clock=clock)) as client:
        assert client.get("/health").status_code == 200


def create_family(
    client: TestClient,
    device_id: str = "owner-device",
    create_request_id: str | None = None,
) -> dict:
    response = client.post(
        "/v1/family/create",
        json={
            "create_request_id": create_request_id or str(uuid4()),
            "device_id": device_id,
            "display_name": "妈妈",
        },
    )
    assert response.status_code == 201
    return response.json()


def auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def baby_payload(
    nickname: str = "年年",
    *,
    avatar_media_uuid: str | None = None,
) -> dict:
    return {
        "nickname": nickname,
        "sex": "female",
        "birthday": "2025-01-02",
        "due_date": None,
        "sort_order": 0,
        "avatar_media_uuid": avatar_media_uuid,
        "birth_weight_grams": 3200,
    }


def record_payload(baby_id: str, **overrides) -> dict:
    value = {
        "baby_client_uuid": baby_id,
        "type": "formula",
        "timestamp": 100,
        "end_timestamp": None,
        "note": None,
        "payload_json": {"amount_ml": 120},
        "schema_version": 1,
    }
    value.update(overrides)
    return value


def log_media_payload(record_id: str, **overrides) -> dict:
    value = {
        "kind": "log",
        "record_client_uuid": record_id,
        "mime": "image/jpeg",
        "byte_size": 3,
    }
    value.update(overrides)
    return value


def avatar_media_payload(baby_id: str, **overrides) -> dict:
    value = {
        "kind": "avatar",
        "baby_client_uuid": baby_id,
        "mime": "image/jpeg",
        "byte_size": 6,
    }
    value.update(overrides)
    return value


def invite_and_join(client: TestClient, owner_token: str) -> dict:
    invitation = client.post("/v1/invite", headers=auth(owner_token), json={})
    assert invitation.status_code == 201
    assert re.fullmatch(r"[A-Z0-9]{8,32}", invitation.json()["code"])
    joined = client.post(
        "/v1/join",
        json={"code": invitation.json()["code"], "device_id": "member-device"},
    )
    assert joined.status_code == 200
    return joined.json()


def test_store_push_reads_only_incoming_keys_and_validation_references(
    data_dir: Path,
) -> None:
    class QueryTracingStore(Store):
        def __init__(self, database_path: Path) -> None:
            self.statements: list[str] = []
            super().__init__(database_path)

        def connect(self) -> sqlite3.Connection:
            connection = super().connect()
            connection.set_trace_callback(self.statements.append)
            return connection

    store = QueryTracingStore(data_dir / "bounded-push.db")
    family_id, _ = store.create_family(
        now=1,
        create_request_id="bounded-push-request-id-0000000001",
        device_id="owner-device",
        display_name=None,
        derive_token=lambda _request_hash, _family_id: "owner-token",
    )
    baby_id = str(uuid4())
    seed_entities = [{
        "type": "baby",
        "client_uuid": baby_id,
        "updated_at": 1,
        "payload": baby_payload(),
    }]
    unrelated_ids: list[str] = []
    for index in range(24):
        record_id = str(uuid4())
        media_id = str(uuid4())
        unrelated_ids.extend((record_id, media_id))
        seed_entities.extend([
            {
                "type": "record",
                "client_uuid": record_id,
                "updated_at": index + 2,
                "payload": record_payload(baby_id),
            },
            {
                "type": "media",
                "client_uuid": media_id,
                "updated_at": index + 2,
                "payload": log_media_payload(record_id),
            },
        ])
    store.push(family_id, "owner", seed_entities)

    incoming_record_id = str(uuid4())
    store.statements.clear()
    result = store.push(
        family_id,
        "owner",
        [{
            "type": "record",
            "client_uuid": incoming_record_id,
            "updated_at": 100,
            "payload": record_payload(baby_id),
        }],
    )

    assert result["applied"] == 1
    entity_reads = [
        statement
        for statement in store.statements
        if statement.lstrip().upper().startswith("SELECT")
        and "FROM ENTITIES" in statement.upper()
    ]
    assert entity_reads
    assert all(
        "CLIENT_UUID" in statement.upper().split("WHERE", maxsplit=1)[1]
        for statement in entity_reads
    )
    reads = "\n".join(entity_reads)
    assert incoming_record_id in reads
    assert baby_id in reads
    assert all(unrelated_id not in reads for unrelated_id in unrelated_ids)


def test_health_creates_single_data_root(client: TestClient, data_dir: Path) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"ok": True, "version": "0.1.0"}
    assert (data_dir / "lezi.db").is_file()
    assert (data_dir / "media").is_dir()


def test_data_root_database_secret_and_media_are_private(
    client: TestClient,
    data_dir: Path,
) -> None:
    owner = create_family(client)
    baby_id = str(uuid4())
    record_id = str(uuid4())
    media_id = str(uuid4())
    assert client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 1,
                "payload": baby_payload(),
            },
            {
                "type": "record",
                "client_uuid": record_id,
                "updated_at": 1,
                "payload": record_payload(baby_id),
            },
            {
                "type": "media",
                "client_uuid": media_id,
                "updated_at": 1,
                "payload": log_media_payload(record_id),
            },
        ]},
    ).status_code == 200
    assert client.put(
        f"/v1/media/{media_id}",
        headers=auth(owner["token"]),
        content=b"log",
    ).status_code == 200

    private_directories = [
        data_dir,
        data_dir / "media",
        data_dir / "media" / owner["family_id"],
    ]
    private_files = [
        data_dir / "server.secret",
        data_dir / "lezi.db",
        data_dir / "media" / owner["family_id"] / media_id,
    ]
    private_files.extend(
        path
        for path in data_dir.glob("lezi.db-*")
        if path.is_file()
    )
    assert private_directories
    assert private_files
    assert all(stat.S_IMODE(path.stat().st_mode) == 0o700 for path in private_directories)
    assert all(stat.S_IMODE(path.stat().st_mode) == 0o600 for path in private_files)


def test_existing_data_root_and_server_secret_permissions_are_repaired(
    data_dir: Path,
    clock: MutableClock,
) -> None:
    data_dir.mkdir(mode=0o777)
    secret_path = data_dir / "server.secret"
    secret_path.write_bytes(b"S" * 32)
    database_path = data_dir / "lezi.db"
    database_path.touch()
    data_dir.chmod(0o777)
    secret_path.chmod(0o644)
    database_path.chmod(0o666)

    app = create_app(data_dir=data_dir, clock=clock)

    assert app is not None
    assert stat.S_IMODE(data_dir.stat().st_mode) == 0o700
    assert stat.S_IMODE(secret_path.stat().st_mode) == 0o600
    assert stat.S_IMODE((data_dir / "media").stat().st_mode) == 0o700
    assert stat.S_IMODE(database_path.stat().st_mode) == 0o600


def test_family_create_requires_high_entropy_request_id(client: TestClient) -> None:
    missing = client.post(
        "/v1/family/create",
        json={"device_id": "owner-device"},
    )
    too_short = client.post(
        "/v1/family/create",
        json={
            "create_request_id": "short",
            "device_id": "owner-device",
        },
    )

    assert missing.status_code == 422
    assert too_short.status_code == 422


def test_family_create_retry_survives_restart_without_storing_raw_credentials(
    data_dir: Path,
    clock: MutableClock,
) -> None:
    create_request_id = "Qk7Uj6hTH1xbqa9nYs8FQ2c4e5w7r9tB"
    request = {
        "create_request_id": create_request_id,
        "device_id": "owner-device",
        "display_name": "妈妈",
    }
    first_client = AsgiClient(create_app(data_dir=data_dir, clock=clock))
    first = first_client.post("/v1/family/create", json=request)
    assert first.status_code == 201

    restarted_client = AsgiClient(create_app(data_dir=data_dir, clock=clock))
    retry = restarted_client.post("/v1/family/create", json=request)
    conflicting = restarted_client.post(
        "/v1/family/create",
        json={
            **request,
            "create_request_id": "Bv4Na1mK9sQ8pR7tU6wX5yZ3cD2eF0gH",
        },
    )

    assert retry.status_code == 201
    assert retry.json() == first.json()
    assert conflicting.status_code == 409
    assert restarted_client.post(
        "/v1/family/create",
        json={**request, "display_name": "不是原请求"},
    ).status_code == 409
    persisted = b"".join(
        path.read_bytes()
        for path in data_dir.glob("lezi.db*")
        if path.is_file()
    )
    assert create_request_id.encode() not in persisted
    assert first.json()["token"].encode() not in persisted


def test_family_invite_join_roles_and_default_ttl(
    client: TestClient,
    clock: MutableClock,
) -> None:
    owner = create_family(client)
    invitation = client.post("/v1/invite", headers=auth(owner["token"]), json={})
    assert invitation.status_code == 201
    assert invitation.json()["expires_at"] == int(clock().timestamp()) + 24 * 60 * 60

    member = client.post(
        "/v1/join",
        json={"code": invitation.json()["code"], "device_id": "member-device"},
    )
    assert member.status_code == 200
    assert member.json()["family_id"] == owner["family_id"]
    assert member.json()["role"] == "member"
    assert member.json()["token"] != owner["token"]
    assert client.post(
        "/v1/invite",
        headers=auth(member.json()["token"]),
        json={},
    ).status_code == 403
    assert client.post(
        "/v1/join",
        json={"code": invitation.json()["code"], "device_id": "member-device"},
    ).json() == member.json()
    assert client.post(
        "/v1/join",
        json={"code": invitation.json()["code"], "device_id": "another-device"},
    ).status_code == 409


def test_default_invite_factory_uses_android_safe_alphabet(
    data_dir: Path,
    clock: MutableClock,
) -> None:
    client = AsgiClient(create_app(data_dir=data_dir, clock=clock))
    owner = create_family(client)
    codes = [
        client.post("/v1/invite", headers=auth(owner["token"]), json={}).json()["code"]
        for _ in range(8)
    ]
    assert len(set(codes)) == len(codes)
    assert all(re.fullmatch(r"[A-Z0-9]{8,32}", code) for code in codes)


def test_same_device_join_retry_survives_server_restart(
    data_dir: Path,
    clock: MutableClock,
) -> None:
    first_client = AsgiClient(create_app(data_dir=data_dir, clock=clock))
    owner = create_family(first_client)
    invitation = first_client.post(
        "/v1/invite",
        headers=auth(owner["token"]),
        json={},
    ).json()
    first_join = first_client.post(
        "/v1/join",
        json={"code": invitation["code"], "device_id": "retry-device"},
    )
    assert first_join.status_code == 200

    restarted_client = AsgiClient(create_app(data_dir=data_dir, clock=clock))
    retry = restarted_client.post(
        "/v1/join",
        json={"code": invitation["code"], "device_id": "retry-device"},
    )
    assert retry.status_code == 200
    assert retry.json() == first_join.json()


def test_tokens_are_high_entropy_and_raw_values_are_not_stored(
    client,
    data_dir: Path,
) -> None:
    owner = create_family(client)
    member = invite_and_join(client, owner["token"])
    assert len(owner["token"]) >= 32
    assert len(member["token"]) >= 32

    persisted = b"".join(
        path.read_bytes()
        for path in data_dir.glob("lezi.db*")
        if path.is_file()
    )
    assert owner["token"].encode() not in persisted
    assert member["token"].encode() not in persisted


def test_expired_invite_is_gone(client: TestClient, clock: MutableClock) -> None:
    owner = create_family(client)
    invitation = client.post("/v1/invite", headers=auth(owner["token"]), json={}).json()
    clock.advance(hours=25)

    response = client.post(
        "/v1/join",
        json={"code": invitation["code"], "device_id": "late-device"},
    )

    assert response.status_code == 410
    assert client.post(
        "/v1/join",
        json={"code": "AAAAAAAA", "device_id": "unknown"},
    ).status_code == 404


def test_leave_revokes_only_caller_and_keeps_entities(client: TestClient) -> None:
    owner = create_family(client)
    member = invite_and_join(client, owner["token"])
    baby_id = str(uuid4())
    pushed = client.post(
        "/v1/push",
        headers=auth(member["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": baby_id,
            "updated_at": 100,
            "payload": baby_payload(),
        }]},
    )
    assert pushed.status_code == 200

    assert client.post("/v1/leave", headers=auth(member["token"]), json={}).status_code == 200
    assert client.get("/v1/pull?cursor=0", headers=auth(member["token"])).status_code == 401
    pulled = client.get("/v1/pull?cursor=0", headers=auth(owner["token"]))
    assert [item["client_uuid"] for item in pulled.json()["entities"]] == [baby_id]


def test_owner_cannot_leave_and_member_leave_remains_available(
    client: TestClient,
) -> None:
    owner = create_family(client)
    member = invite_and_join(client, owner["token"])

    owner_leave = client.post(
        "/v1/leave",
        headers=auth(owner["token"]),
        json={},
    )

    assert owner_leave.status_code == 403
    assert owner_leave.json() == {
        "detail": "Owner must delete the family instead of leaving",
    }
    assert client.get(
        "/v1/pull?cursor=0",
        headers=auth(owner["token"]),
    ).status_code == 200

    member_leave = client.post(
        "/v1/leave",
        headers=auth(member["token"]),
        json={},
    )
    assert member_leave.status_code == 200
    assert client.get(
        "/v1/pull?cursor=0",
        headers=auth(member["token"]),
    ).status_code == 401
    assert client.get(
        "/v1/pull?cursor=0",
        headers=auth(owner["token"]),
    ).status_code == 200


def test_push_requires_token_and_strict_entity_contract(client: TestClient) -> None:
    response = client.post("/v1/push", json={"entities": []})
    assert response.status_code == 401

    owner = create_family(client)
    invalid_type = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "settings",
            "client_uuid": str(uuid4()),
            "updated_at": 1,
            "payload": {},
        }]},
    )
    assert invalid_type.status_code == 422

    missing_cross_device_id = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "record",
            "client_uuid": str(uuid4()),
            "updated_at": 1,
            "payload": {"baby_id": 7},
        }]},
    )
    assert missing_cross_device_id.status_code == 422
    wrong_device = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"device_id": "someone-else", "entities": []},
    )
    assert wrong_device.status_code == 403


def test_push_is_idempotent_lww_and_pull_uses_monotonic_cursor(client: TestClient) -> None:
    owner = create_family(client)
    baby_id = str(uuid4())
    first = {
        "type": "baby",
        "client_uuid": baby_id,
        "updated_at": 100,
        "payload": baby_payload(),
    }

    applied = client.post("/v1/push", headers=auth(owner["token"]), json={"entities": [first]})
    retry = client.post("/v1/push", headers=auth(owner["token"]), json={"entities": [first]})
    older = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{**first, "updated_at": 99, "payload": baby_payload("旧")}]},
    )
    newer = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{**first, "updated_at": 101, "deleted_at": 101}]},
    )

    assert applied.json() == {"applied": 1, "skipped": 0, "cursor": 1}
    assert retry.json() == {"applied": 0, "skipped": 1, "cursor": 1}
    assert older.json() == {"applied": 0, "skipped": 1, "cursor": 1}
    assert newer.json() == {"applied": 1, "skipped": 0, "cursor": 2}
    assert client.get("/v1/pull?cursor=1", headers=auth(owner["token"])).json() == {
        "entities": [{
            "type": "baby",
            "client_uuid": baby_id,
            "updated_at": 101,
            "deleted_at": 101,
            "payload": baby_payload(),
            "rev": 2,
        }],
        "cursor": 2,
    }
    assert client.get("/v1/pull?cursor=2", headers=auth(owner["token"])).json() == {
        "entities": [],
        "cursor": 2,
    }
    assert client.get("/v1/pull?cursor=3", headers=auth(owner["token"])).status_code == 409


def test_pull_reports_full_resync_contract_after_server_database_restore(
    client: TestClient,
    data_dir: Path,
) -> None:
    owner = create_family(client)
    first_baby_id = str(uuid4())
    second_baby_id = str(uuid4())
    first = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": first_baby_id,
            "updated_at": 100,
            "payload": baby_payload("备份内"),
        }]},
    )
    assert first.json()["cursor"] == 1

    database_path = data_dir / "lezi.db"
    backup_path = data_dir / "lezi-rollback.db"
    with (
        sqlite3.connect(database_path) as live_database,
        sqlite3.connect(backup_path) as backup_database,
    ):
        live_database.backup(backup_database)

    second = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": second_baby_id,
            "updated_at": 101,
            "payload": baby_payload("备份后"),
        }]},
    )
    assert second.json()["cursor"] == 2

    with (
        sqlite3.connect(backup_path) as backup_database,
        sqlite3.connect(database_path) as restored_database,
    ):
        backup_database.backup(restored_database)

    rollback = client.get("/v1/pull?cursor=2", headers=auth(owner["token"]))

    assert rollback.status_code == 409
    assert rollback.json() == {
        "detail": {
            "code": "cursor_ahead",
            "action": "full_resync",
            "reset_cursor": 0,
            "server_cursor": 1,
        },
    }
    full_pull = client.get("/v1/pull?cursor=0", headers=auth(owner["token"]))
    assert full_pull.status_code == 200
    assert [entity["client_uuid"] for entity in full_pull.json()["entities"]] == [
        first_baby_id,
    ]
    assert full_pull.json()["cursor"] == 1


def test_record_requires_valid_baby_client_uuid(client: TestClient) -> None:
    owner = create_family(client)
    record_id = str(uuid4())
    baby_id = str(uuid4())
    structurally_incomplete = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "record",
            "client_uuid": record_id,
            "updated_at": 1,
            "payload": {"baby_client_uuid": baby_id, "record_type": "formula"},
        }]},
    )
    string_payload_json = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "record",
            "client_uuid": record_id,
            "updated_at": 1,
            "payload": record_payload(baby_id, payload_json="{}"),
        }]},
    )
    bad = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "record",
            "client_uuid": str(uuid4()),
            "updated_at": 2,
            "payload": {"baby_client_uuid": "../escape"},
        }]},
    )
    unresolved = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "record",
            "client_uuid": record_id,
            "updated_at": 1,
            "payload": record_payload(baby_id),
        }]},
    )
    same_batch = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "record",
                "client_uuid": record_id,
                "updated_at": 1,
                "payload": record_payload(baby_id),
            },
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 1,
                "payload": baby_payload(),
            },
        ]},
    )
    assert structurally_incomplete.status_code == 422
    assert string_payload_json.status_code == 422
    assert bad.status_code == 422
    assert unresolved.status_code == 409
    assert same_batch.status_code == 200
    pulled = client.get("/v1/pull?cursor=0", headers=auth(owner["token"])).json()
    assert [entity["type"] for entity in pulled["entities"]] == ["baby", "record"]


def test_media_bytes_and_avatar_acl(client: TestClient, data_dir: Path) -> None:
    owner = create_family(client)
    member = invite_and_join(client, owner["token"])
    log_id = str(uuid4())
    avatar_id = str(uuid4())
    baby_id = str(uuid4())
    record_id = str(uuid4())
    metadata = [
        {
            "type": "baby",
            "client_uuid": baby_id,
            "updated_at": 1,
            "payload": baby_payload(avatar_media_uuid=avatar_id),
        },
        {
            "type": "record",
            "client_uuid": record_id,
            "updated_at": 1,
            "payload": record_payload(baby_id),
        },
        {
            "type": "media",
            "client_uuid": log_id,
            "updated_at": 2,
            "payload": log_media_payload(record_id),
        },
        {
            "type": "media",
            "client_uuid": avatar_id,
            "updated_at": 3,
            "payload": avatar_media_payload(baby_id),
        },
    ]
    assert client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": metadata},
    ).status_code == 200
    pulled_media = client.get("/v1/pull?cursor=0", headers=auth(member["token"]))
    assert [
        item["payload"]["kind"]
        for item in pulled_media.json()["entities"]
        if item["type"] == "media"
    ] == ["log", "avatar"]
    denied_metadata = client.post(
        "/v1/push",
        headers=auth(member["token"]),
        json={"entities": [{
            "type": "media",
            "client_uuid": str(uuid4()),
            "updated_at": 4,
            "payload": avatar_media_payload(baby_id),
        }]},
    )
    assert denied_metadata.status_code == 403

    uploaded = client.put(
        f"/v1/media/{log_id}",
        headers={**auth(member["token"]), "Content-Type": "application/octet-stream"},
        content=b"log",
    )
    assert uploaded.status_code == 200
    downloaded = client.get(f"/v1/media/{log_id}", headers=auth(member["token"]))
    assert downloaded.content == b"log"
    assert list((data_dir / "media" / owner["family_id"]).iterdir())

    denied = client.put(
        f"/v1/media/{avatar_id}",
        headers={**auth(member["token"]), "Content-Type": "application/octet-stream"},
        content=b"avatar",
    )
    assert denied.status_code == 403
    accepted = client.put(
        f"/v1/media/{avatar_id}",
        headers={**auth(owner["token"]), "Content-Type": "application/octet-stream"},
        content=b"avatar",
    )
    assert accepted.status_code == 200


def test_avatar_kind_and_association_are_immutable_for_every_role(client: TestClient) -> None:
    owner = create_family(client)
    member = invite_and_join(client, owner["token"])
    first_baby = str(uuid4())
    second_baby = str(uuid4())
    record_id = str(uuid4())
    avatar_id = str(uuid4())
    seeded = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "baby",
                "client_uuid": first_baby,
                "updated_at": 1,
                "payload": baby_payload(),
            },
            {
                "type": "baby",
                "client_uuid": second_baby,
                "updated_at": 1,
                "payload": baby_payload("二宝"),
            },
            {
                "type": "record",
                "client_uuid": record_id,
                "updated_at": 1,
                "payload": record_payload(first_baby),
            },
            {
                "type": "media",
                "client_uuid": avatar_id,
                "updated_at": 2,
                "payload": avatar_media_payload(first_baby),
            },
        ]},
    )
    assert seeded.status_code == 200

    member_kind_bypass = client.post(
        "/v1/push",
        headers=auth(member["token"]),
        json={"entities": [{
            "type": "media",
            "client_uuid": avatar_id,
            "updated_at": 3,
            "payload": log_media_payload(record_id),
        }]},
    )
    owner_reassociation = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "media",
            "client_uuid": avatar_id,
            "updated_at": 3,
            "payload": avatar_media_payload(second_baby),
        }]},
    )
    assert member_kind_bypass.status_code == 403
    assert owner_reassociation.status_code == 409
    pulled = client.get("/v1/pull?cursor=0", headers=auth(owner["token"])).json()
    avatar = next(item for item in pulled["entities"] if item["client_uuid"] == avatar_id)
    assert avatar["payload"] == avatar_media_payload(first_baby)


def test_member_may_edit_baby_but_cannot_mutate_avatar_reference(client: TestClient) -> None:
    owner = create_family(client)
    member = invite_and_join(client, owner["token"])
    baby_id = str(uuid4())
    avatar_id = str(uuid4())
    replacement_avatar_id = str(uuid4())
    assert client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "media",
                "client_uuid": avatar_id,
                "updated_at": 1,
                "payload": avatar_media_payload(baby_id),
            },
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 1,
                "payload": baby_payload(avatar_media_uuid=avatar_id),
            },
            {
                "type": "media",
                "client_uuid": replacement_avatar_id,
                "updated_at": 1,
                "payload": avatar_media_payload(baby_id),
            },
        ]},
    ).status_code == 200

    nickname_only = client.post(
        "/v1/push",
        headers=auth(member["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": baby_id,
            "updated_at": 2,
            "payload": baby_payload(
                "成员可改昵称",
                avatar_media_uuid=avatar_id,
            ),
        }]},
    )
    clear_avatar = client.post(
        "/v1/push",
        headers=auth(member["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": baby_id,
            "updated_at": 3,
            "payload": baby_payload("禁止清空", avatar_media_uuid=None),
        }]},
    )
    replace_avatar = client.post(
        "/v1/push",
        headers=auth(member["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": baby_id,
            "updated_at": 3,
            "payload": baby_payload(
                "禁止替换",
                avatar_media_uuid=replacement_avatar_id,
            ),
        }]},
    )
    new_baby_with_avatar = client.post(
        "/v1/push",
        headers=auth(member["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": str(uuid4()),
            "updated_at": 1,
            "payload": baby_payload(
                "禁止新设",
                avatar_media_uuid=avatar_id,
            ),
        }]},
    )

    assert nickname_only.status_code == 200
    assert clear_avatar.status_code == 403
    assert replace_avatar.status_code == 403
    assert new_baby_with_avatar.status_code == 403
    baby = next(
        entity
        for entity in client.get(
            "/v1/pull?cursor=0",
            headers=auth(owner["token"]),
        ).json()["entities"]
        if entity["client_uuid"] == baby_id
    )
    assert baby["payload"]["nickname"] == "成员可改昵称"
    assert baby["payload"]["avatar_media_uuid"] == avatar_id


def test_stale_member_baby_avatar_pointer_does_not_block_newer_record(
    client: TestClient,
) -> None:
    owner = create_family(client)
    member = invite_and_join(client, owner["token"])
    baby_id = str(uuid4())
    first_avatar_id = str(uuid4())
    current_avatar_id = str(uuid4())
    record_id = str(uuid4())
    seeded = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "media",
                "client_uuid": first_avatar_id,
                "updated_at": 100,
                "payload": avatar_media_payload(baby_id),
            },
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 100,
                "payload": baby_payload(avatar_media_uuid=first_avatar_id),
            },
            {
                "type": "media",
                "client_uuid": current_avatar_id,
                "updated_at": 300,
                "payload": avatar_media_payload(baby_id),
            },
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 300,
                "payload": baby_payload(avatar_media_uuid=current_avatar_id),
            },
        ]},
    )
    assert seeded.status_code == 200

    merged = client.post(
        "/v1/push",
        headers=auth(member["token"]),
        json={"entities": [
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 200,
                "payload": baby_payload(avatar_media_uuid=first_avatar_id),
            },
            {
                "type": "record",
                "client_uuid": record_id,
                "updated_at": 400,
                "payload": record_payload(baby_id),
            },
        ]},
    )

    assert merged.status_code == 200
    assert merged.json()["applied"] == 1
    assert merged.json()["skipped"] == 1
    pulled = client.get("/v1/pull?cursor=0", headers=auth(owner["token"])).json()
    record = next(
        entity for entity in pulled["entities"] if entity["client_uuid"] == record_id
    )
    baby = next(
        entity for entity in pulled["entities"] if entity["client_uuid"] == baby_id
    )
    assert record["payload"]["baby_client_uuid"] == baby_id
    assert baby["payload"]["avatar_media_uuid"] == current_avatar_id


def test_owner_avatar_reference_requires_matching_avatar_media(client: TestClient) -> None:
    owner = create_family(client)
    first_baby = str(uuid4())
    second_baby = str(uuid4())
    first_avatar = str(uuid4())
    first_replacement_avatar = str(uuid4())
    second_avatar = str(uuid4())
    same_batch = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "media",
                "client_uuid": first_avatar,
                "updated_at": 1,
                "payload": avatar_media_payload(first_baby),
            },
            {
                "type": "baby",
                "client_uuid": first_baby,
                "updated_at": 1,
                "payload": baby_payload(avatar_media_uuid=first_avatar),
            },
            {
                "type": "baby",
                "client_uuid": second_baby,
                "updated_at": 1,
                "payload": baby_payload("二宝", avatar_media_uuid=second_avatar),
            },
            {
                "type": "media",
                "client_uuid": second_avatar,
                "updated_at": 1,
                "payload": avatar_media_payload(second_baby),
            },
            {
                "type": "media",
                "client_uuid": first_replacement_avatar,
                "updated_at": 1,
                "payload": avatar_media_payload(first_baby),
            },
        ]},
    )
    valid_replacement = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": first_baby,
            "updated_at": 2,
            "payload": baby_payload(avatar_media_uuid=first_replacement_avatar),
        }]},
    )
    mismatched = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": first_baby,
            "updated_at": 3,
            "payload": baby_payload(avatar_media_uuid=second_avatar),
        }]},
    )
    unresolved = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "baby",
            "client_uuid": first_baby,
            "updated_at": 3,
            "payload": baby_payload(avatar_media_uuid=str(uuid4())),
        }]},
    )

    assert same_batch.status_code == 200
    assert valid_replacement.status_code == 200
    assert mismatched.status_code == 409
    assert unresolved.status_code == 409


def test_media_rejects_unresolved_association_but_accepts_same_batch_record(
    client: TestClient,
) -> None:
    owner = create_family(client)
    baby_id = str(uuid4())
    record_id = str(uuid4())
    media_id = str(uuid4())
    unresolved = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [{
            "type": "media",
            "client_uuid": media_id,
            "updated_at": 1,
            "payload": log_media_payload(record_id),
        }]},
    )
    same_batch = client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "media",
                "client_uuid": media_id,
                "updated_at": 1,
                "payload": log_media_payload(record_id),
            },
            {
                "type": "record",
                "client_uuid": record_id,
                "updated_at": 1,
                "payload": record_payload(baby_id),
            },
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 1,
                "payload": baby_payload(),
            },
        ]},
    )
    assert unresolved.status_code == 409
    assert same_batch.status_code == 200


def test_media_rejects_bad_uuid_and_size(client: TestClient) -> None:
    owner = create_family(client)
    assert client.put(
        "/v1/media/..%2Fescape",
        headers=auth(owner["token"]),
        content=b"x",
    ).status_code in {404, 422}

    media_id = str(uuid4())
    baby_id = str(uuid4())
    record_id = str(uuid4())
    assert client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 1,
                "payload": baby_payload(),
            },
            {
                "type": "record",
                "client_uuid": record_id,
                "updated_at": 1,
                "payload": record_payload(baby_id),
            },
            {
                "type": "media",
                "client_uuid": media_id,
                "updated_at": 1,
                "payload": log_media_payload(record_id),
            },
        ]},
    ).status_code == 200
    assert client.put(
        f"/v1/media/{media_id}",
        headers=auth(owner["token"]),
        content=b"123456789",
    ).status_code == 413


def test_media_size_limit_stops_consuming_stream(data_dir: Path, clock: MutableClock) -> None:
    async def scenario() -> tuple[int, list[int]]:
        app = create_app(data_dir=data_dir, clock=clock, max_media_bytes=8)
        transport = httpx.ASGITransport(app=app)
        consumed: list[int] = []
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            owner = (
                await client.post(
                    "/v1/family/create",
                    json={
                        "create_request_id": str(uuid4()),
                        "device_id": "stream-owner",
                    },
                )
            ).json()
            baby_id = str(uuid4())
            record_id = str(uuid4())
            media_id = str(uuid4())
            response = await client.post(
                "/v1/push",
                headers=auth(owner["token"]),
                json={"entities": [
                    {
                        "type": "baby",
                        "client_uuid": baby_id,
                        "updated_at": 1,
                        "payload": baby_payload(),
                    },
                    {
                        "type": "record",
                        "client_uuid": record_id,
                        "updated_at": 1,
                        "payload": record_payload(baby_id),
                    },
                    {
                        "type": "media",
                        "client_uuid": media_id,
                        "updated_at": 1,
                        "payload": log_media_payload(record_id),
                    },
                ]},
            )
            assert response.status_code == 200

            async def chunks():
                for index, value in enumerate((b"12345", b"6789", b"must-not-read")):
                    consumed.append(index)
                    if index == 2:
                        raise AssertionError("server consumed beyond the size limit")
                    yield value

            oversized = await client.put(
                f"/v1/media/{media_id}",
                headers=auth(owner["token"]),
                content=chunks(),
            )
            return oversized.status_code, consumed

    status_code, consumed = asyncio.run(scenario())
    assert status_code == 413
    assert consumed == [0, 1]


def test_owner_delete_cleans_family_and_media(client: TestClient, data_dir: Path) -> None:
    owner = create_family(client)
    member = invite_and_join(client, owner["token"])
    media_id = str(uuid4())
    baby_id = str(uuid4())
    record_id = str(uuid4())
    client.post(
        "/v1/push",
        headers=auth(owner["token"]),
        json={"entities": [
            {
                "type": "baby",
                "client_uuid": baby_id,
                "updated_at": 1,
                "payload": baby_payload(),
            },
            {
                "type": "record",
                "client_uuid": record_id,
                "updated_at": 1,
                "payload": record_payload(baby_id),
            },
            {
                "type": "media",
                "client_uuid": media_id,
                "updated_at": 1,
                "payload": log_media_payload(record_id),
            },
        ]},
    )
    client.put(f"/v1/media/{media_id}", headers=auth(owner["token"]), content=b"log")

    assert client.post(
        "/v1/family/delete",
        headers=auth(member["token"]),
        json={},
    ).status_code == 403
    assert client.post(
        "/v1/family/delete",
        headers=auth(owner["token"]),
        json={},
    ).status_code == 200
    assert not (data_dir / "media" / owner["family_id"]).exists()
    assert client.get("/v1/pull?cursor=0", headers=auth(owner["token"])).status_code == 401
    replacement = create_family(client, device_id="replacement-owner")
    assert client.get("/v1/pull?cursor=0", headers=auth(replacement["token"])).json() == {
        "entities": [],
        "cursor": 0,
    }
