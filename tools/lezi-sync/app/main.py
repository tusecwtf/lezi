import base64
import asyncio
import hashlib
import hmac
import os
import secrets
import shutil
import string
from datetime import date
from datetime import datetime, timezone
from pathlib import Path
from typing import Annotated, Callable, Literal
from uuid import UUID

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .db import (
    FamilyAlreadyExists,
    CursorAhead,
    ForbiddenAvatar,
    ImmutableMediaAssociation,
    InviteAlreadyUsed,
    InviteExpired,
    InviteNotFound,
    Principal,
    Store,
    UnresolvedReference,
)

VERSION = "0.1.0"
DEFAULT_INVITE_TTL_HOURS = 24
DEFAULT_MAX_MEDIA_BYTES = 10 * 1024 * 1024
Clock = Callable[[], datetime]
InviteCodeFactory = Callable[[], str]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class FamilyCreateRequest(StrictModel):
    create_request_id: str = Field(
        min_length=32,
        max_length=128,
        pattern=r"^[A-Za-z0-9_-]+$",
    )
    device_id: str = Field(min_length=1, max_length=128)
    display_name: str | None = Field(default=None, max_length=128)


class EmptyRequest(StrictModel):
    pass


class InviteRequest(StrictModel):
    family_id: UUID | None = None


class JoinRequest(StrictModel):
    code: str = Field(pattern=r"^[A-Z0-9]{8,32}$")
    device_id: str = Field(min_length=1, max_length=128)
    display_name: str | None = Field(default=None, max_length=128)


class BabyPayload(StrictModel):
    nickname: str = Field(min_length=1, max_length=128)
    sex: str | None
    birthday: date
    due_date: date | None
    # Kept optional for backward compatibility with older clients. Ordering is
    # device-local and new clients deliberately omit it from the wire.
    sort_order: int | None = None
    avatar_media_uuid: UUID | None
    birth_weight_grams: int | None = Field(default=None, ge=0, le=100_000)


class RecordPayload(StrictModel):
    baby_client_uuid: UUID
    type: str = Field(min_length=1, max_length=64)
    timestamp: int = Field(ge=0)
    end_timestamp: int | None = Field(default=None, ge=0)
    note: str | None = Field(default=None, max_length=20_000)
    payload_json: dict
    schema_version: int = Field(default=1, ge=1)
    created_by_device_id: str | None = Field(default=None, min_length=1, max_length=128)


class MediaPayload(StrictModel):
    kind: Literal["log", "avatar"]
    record_client_uuid: UUID | None = None
    baby_client_uuid: UUID | None = None
    mime: str | None = Field(default=None, max_length=255)
    width: int | None = Field(default=None, ge=1)
    height: int | None = Field(default=None, ge=1)
    byte_size: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def validate_association(self) -> "MediaPayload":
        if self.kind == "log" and self.record_client_uuid is None:
            raise ValueError("log media requires record_client_uuid")
        if self.kind == "avatar" and self.baby_client_uuid is None:
            raise ValueError("avatar media requires baby_client_uuid")
        if self.kind == "avatar" and self.record_client_uuid is not None:
            raise ValueError("avatar media must not reference a record")
        return self


class EntityInput(StrictModel):
    type: Literal["baby", "record", "media"]
    client_uuid: UUID
    updated_at: int = Field(ge=0)
    deleted_at: int | None = Field(default=None, ge=0)
    payload: dict = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_payload_contract(self) -> "EntityInput":
        model = {
            "baby": BabyPayload,
            "record": RecordPayload,
            "media": MediaPayload,
        }[self.type]
        validated = model.model_validate(self.payload)
        self.payload = validated.model_dump(mode="json", exclude_unset=True)
        if self.type == "baby":
            # Accept the legacy key without propagating a device-local order.
            self.payload.pop("sort_order", None)
        return self

    def storage_dict(self) -> dict:
        data = self.model_dump(mode="json")
        data["client_uuid"] = str(self.client_uuid)
        return data


class PushRequest(StrictModel):
    device_id: str | None = Field(default=None, min_length=1, max_length=128)
    generation: str | None = Field(default=None, min_length=1, max_length=128)
    entities: list[EntityInput] = Field(max_length=1000)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def secure_invite_code() -> str:
    alphabet = string.ascii_uppercase + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(12))


def load_or_create_server_secret(data_dir: Path) -> bytes:
    path = data_dir / "server.secret"
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        secret = path.read_bytes()
    else:
        try:
            secret = secrets.token_bytes(32)
            os.write(descriptor, secret)
        finally:
            os.close(descriptor)
    path.chmod(0o600)
    if len(secret) < 32:
        raise ValueError("server.secret must contain at least 32 bytes")
    return secret


def ensure_private_directory(path: Path) -> None:
    path.mkdir(parents=True, mode=0o700, exist_ok=True)
    path.chmod(0o700)


def write_private_file(path: Path, content: bytes) -> None:
    temporary = path.with_suffix(".tmp")
    descriptor = os.open(
        temporary,
        os.O_WRONLY | os.O_CREAT | os.O_TRUNC,
        0o600,
    )
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        temporary.chmod(0o600)
        temporary.replace(path)
        path.chmod(0o600)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def create_app(
    *,
    data_dir: str | Path | None = None,
    clock: Clock = utc_now,
    invite_code_factory: InviteCodeFactory = secure_invite_code,
    server_secret: bytes | None = None,
    max_media_bytes: int | None = None,
    invite_ttl_hours: int | None = None,
) -> FastAPI:
    os.umask(0o077)
    resolved_data_dir = Path(
        data_dir if data_dir is not None else os.environ.get("LEZI_DATA_DIR", "/data")
    ).expanduser().resolve()
    ensure_private_directory(resolved_data_dir)
    media_root = (resolved_data_dir / "media").resolve()
    ensure_private_directory(media_root)
    store = Store(resolved_data_dir / "lezi.db")
    media_limit = max_media_bytes or int(
        os.environ.get("LEZI_MAX_MEDIA_BYTES", DEFAULT_MAX_MEDIA_BYTES)
    )
    invite_ttl = invite_ttl_hours or int(
        os.environ.get("LEZI_INVITE_TTL_HOURS", DEFAULT_INVITE_TTL_HOURS)
    )
    if not 1 <= invite_ttl <= 168:
        raise ValueError("invite TTL must be between 1 and 168 hours")
    service_version = os.environ.get("LEZI_SYNC_VERSION", VERSION)
    signing_secret = server_secret or load_or_create_server_secret(resolved_data_dir)
    bearer = HTTPBearer(auto_error=False)
    app = FastAPI(title="lezi-sync", version=service_version)
    server_generation = secrets.token_urlsafe(24)
    family_mutation_locks: dict[str, asyncio.Lock] = {}

    def family_mutation_lock(family_id: str) -> asyncio.Lock:
        return family_mutation_locks.setdefault(family_id, asyncio.Lock())

    def epoch_now() -> int:
        value = clock()
        if value.tzinfo is None:
            raise RuntimeError("clock must return a timezone-aware datetime")
        return int(value.timestamp())

    def derive_member_token(code_hash: str, device_id: str) -> str:
        digest = hmac.new(
            signing_secret,
            f"join:{code_hash}:{device_id}".encode("utf-8"),
            hashlib.sha256,
        ).digest()
        return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")

    def derive_owner_token(create_request_hash: str, family_id: str) -> str:
        digest = hmac.new(
            signing_secret,
            f"owner:{create_request_hash}:{family_id}".encode("utf-8"),
            hashlib.sha256,
        ).digest()
        return base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")

    async def authenticate(
        credentials: Annotated[
            HTTPAuthorizationCredentials | None,
            Depends(bearer),
        ],
    ) -> Principal:
        if credentials is None or credentials.scheme.lower() != "bearer":
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Bearer token required",
                headers={"WWW-Authenticate": "Bearer"},
            )
        principal = store.authenticate(credentials.credentials)
        if principal is None:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid or revoked token",
                headers={"WWW-Authenticate": "Bearer"},
            )
        return principal

    async def owner(principal: Annotated[Principal, Depends(authenticate)]) -> Principal:
        if principal.role != "owner":
            raise HTTPException(status_code=403, detail="Owner role required")
        return principal

    def media_path(family_id: str, client_uuid: UUID) -> Path:
        family_component = str(UUID(family_id))
        family_dir = (media_root / family_component).resolve()
        path = (family_dir / str(client_uuid)).resolve()
        if media_root not in path.parents or family_dir not in path.parents:
            raise HTTPException(status_code=422, detail="Invalid media path")
        return path

    @app.get("/health")
    async def health() -> dict:
        return {"ok": True, "version": service_version}

    @app.post("/v1/family/create", status_code=201)
    async def create_family(request: FamilyCreateRequest) -> dict:
        try:
            family_id, token = store.create_family(
                now=epoch_now(),
                create_request_id=request.create_request_id,
                device_id=request.device_id,
                display_name=request.display_name,
                derive_token=derive_owner_token,
            )
        except FamilyAlreadyExists as error:
            raise HTTPException(status_code=409, detail="Family already exists") from error
        return {
            "family_id": family_id,
            "token": token,
            "role": "owner",
            "generation": server_generation,
        }

    @app.post("/v1/invite", status_code=201)
    async def create_invite(
        request: InviteRequest,
        principal: Annotated[Principal, Depends(owner)],
    ) -> dict:
        if request.family_id is not None and str(request.family_id) != principal.family_id:
            raise HTTPException(status_code=403, detail="family_id does not match token")
        code, expires_at = store.create_invite(
            family_id=principal.family_id,
            now=epoch_now(),
            ttl_seconds=invite_ttl * 60 * 60,
            new_code=invite_code_factory,
        )
        return {"code": code, "expires_at": expires_at}

    @app.post("/v1/join")
    async def join(request: JoinRequest) -> dict:
        try:
            family_id, token = store.join_family(
                code=request.code,
                device_id=request.device_id,
                display_name=request.display_name,
                now=epoch_now(),
                derive_token=derive_member_token,
            )
        except InviteNotFound as error:
            raise HTTPException(status_code=404, detail="Invitation not found") from error
        except InviteAlreadyUsed as error:
            raise HTTPException(
                status_code=409,
                detail="Invitation already used by another device",
            ) from error
        except InviteExpired as error:
            raise HTTPException(status_code=410, detail="Invitation expired") from error
        return {
            "family_id": family_id,
            "token": token,
            "role": "member",
            "entities": [],
            "cursor": 0,
            "generation": server_generation,
        }

    @app.post("/v1/leave")
    async def leave(
        request: EmptyRequest,
        principal: Annotated[Principal, Depends(authenticate)],
    ) -> dict:
        del request
        if principal.role == "owner":
            raise HTTPException(
                status_code=403,
                detail="Owner must delete the family instead of leaving",
            )
        store.revoke(principal.token_hash, epoch_now())
        return {"ok": True}

    @app.post("/v1/family/delete")
    async def delete_family(
        request: EmptyRequest,
        principal: Annotated[Principal, Depends(owner)],
    ) -> dict:
        del request
        async with family_mutation_lock(principal.family_id):
            family_media = media_root / principal.family_id
            if family_media.exists():
                shutil.rmtree(family_media)
            store.delete_family(principal.family_id)
        return {"ok": True}

    @app.post("/v1/push")
    async def push(
        request: PushRequest,
        principal: Annotated[Principal, Depends(authenticate)],
    ) -> dict:
        if request.device_id is not None and request.device_id != principal.device_id:
            raise HTTPException(status_code=403, detail="device_id does not match token")
        if request.generation is not None and request.generation != server_generation:
            raise HTTPException(
                status_code=409,
                detail={
                    "code": "generation_changed",
                    "action": "full_resync",
                    "reset_cursor": 0,
                    "server_cursor": store.current_revision(principal.family_id),
                    "server_generation": server_generation,
                },
            )
        try:
            async with family_mutation_lock(principal.family_id):
                return store.push(
                    principal.family_id,
                    principal.role,
                    [entity.storage_dict() for entity in request.entities],
                )
        except ForbiddenAvatar as error:
            raise HTTPException(status_code=403, detail="Only owner may change avatar") from error
        except ImmutableMediaAssociation as error:
            raise HTTPException(
                status_code=409,
                detail="Media kind and association are immutable",
            ) from error
        except UnresolvedReference as error:
            raise HTTPException(status_code=409, detail=str(error)) from error

    @app.get("/v1/pull")
    async def pull(
        principal: Annotated[Principal, Depends(authenticate)],
        cursor: Annotated[int, Query(ge=0)] = 0,
        generation: str | None = None,
    ) -> dict:
        if generation is not None and generation != server_generation:
            raise HTTPException(
                status_code=409,
                detail={
                    "code": "generation_changed",
                    "action": "full_resync",
                    "reset_cursor": 0,
                    "server_cursor": store.current_revision(principal.family_id),
                    "server_generation": server_generation,
                },
            )
        try:
            entities, current = store.pull(principal.family_id, cursor)
        except CursorAhead as error:
            raise HTTPException(
                status_code=409,
                detail={
                    "code": "cursor_ahead",
                    "action": "full_resync",
                    "reset_cursor": 0,
                    "server_cursor": error.server_cursor,
                    "server_generation": server_generation,
                },
            ) from error
        return {
            "entities": entities,
            "cursor": current,
            "generation": server_generation,
        }

    @app.put("/v1/media/{client_uuid}")
    async def put_media(
        client_uuid: UUID,
        request: Request,
        principal: Annotated[Principal, Depends(authenticate)],
    ) -> dict:
        async with family_mutation_lock(principal.family_id):
            kind = store.media_kind(principal.family_id, str(client_uuid))
            if kind is None:
                raise HTTPException(status_code=404, detail="Media metadata not found")
            if kind == "avatar" and principal.role != "owner":
                raise HTTPException(status_code=403, detail="Only owner may change avatar")
            declared_length = request.headers.get("content-length")
            if declared_length is not None:
                try:
                    if int(declared_length) > media_limit:
                        raise HTTPException(status_code=413, detail="Media is too large")
                except ValueError as error:
                    raise HTTPException(status_code=400, detail="Invalid Content-Length") from error
            content = bytearray()
            async for chunk in request.stream():
                if len(content) + len(chunk) > media_limit:
                    raise HTTPException(status_code=413, detail="Media is too large")
                content.extend(chunk)
            path = media_path(principal.family_id, client_uuid)
            ensure_private_directory(path.parent)
            write_private_file(path, bytes(content))
        return {"ok": True, "size": len(content)}

    @app.get("/v1/media/{client_uuid}")
    async def get_media(
        client_uuid: UUID,
        principal: Annotated[Principal, Depends(authenticate)],
    ) -> Response:
        if store.media_kind(principal.family_id, str(client_uuid)) is None:
            raise HTTPException(status_code=404, detail="Media metadata not found")
        path = media_path(principal.family_id, client_uuid)
        if not path.is_file():
            raise HTTPException(status_code=404, detail="Media bytes not found")
        return Response(content=path.read_bytes(), media_type="application/octet-stream")

    return app
