#!/usr/bin/env python3
"""Validate and wrap an exact Docker inspect start contract without logging it."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import re
import sys
from typing import Any


FORMAT = "LEZI_PRE_TLS_CUTOVER_STATE_V1"
MAX_INSPECT_BYTES = 4 * 1024 * 1024
MAX_BUNDLE_BYTES = 8 * 1024 * 1024
SHA256_ID = re.compile(r"^sha256:[0-9a-f]{64}$")
CONTAINER_ID = re.compile(r"^[0-9a-f]{64}$")
HEX_SHA256 = re.compile(r"^[0-9a-f]{64}$")
CONTAINER_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]*$")


class ValidationError(Exception):
    """A safe, non-secret-bearing validation failure."""


def fail(message: str) -> None:
    raise ValidationError(message)


def read_stdin(maximum_bytes: int) -> bytes:
    payload = sys.stdin.buffer.read(maximum_bytes + 1)
    if len(payload) > maximum_bytes:
        fail("input exceeds the maximum supported size")
    if not payload:
        fail("input is empty")
    return payload


def string_list_or_none(value: Any, field: str) -> None:
    if value is None:
        return
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        fail(f"{field} is not a string list or null")


def validate_inspect(
    payload: bytes,
    expected_name: str,
) -> tuple[list[Any], dict[str, Any], bytes, str]:
    try:
        decoded = payload.decode("utf-8", errors="strict")
        state = json.loads(decoded)
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("docker inspect output is not one valid UTF-8 JSON document")

    if not isinstance(state, list) or len(state) != 1 or not isinstance(state[0], dict):
        fail("docker inspect output must contain exactly one container")
    container = state[0]
    if container.get("Name") != f"/{expected_name}":
        fail("docker inspect output is for an unexpected container")

    container_id = container.get("Id")
    image_id = container.get("Image")
    if not isinstance(container_id, str) or not CONTAINER_ID.fullmatch(container_id):
        fail("container id is missing or invalid")
    if not isinstance(image_id, str) or not SHA256_ID.fullmatch(image_id):
        fail("image id is missing or invalid")
    state_status = container.get("State")
    if not isinstance(state_status, dict) or state_status.get("Running") is not True:
        fail("container is not running and cannot be captured as the live rollback source")
    if not isinstance(container.get("Path"), str) or not container["Path"]:
        fail("container executable path is missing")
    if not isinstance(container.get("Args"), list) or not all(
        isinstance(item, str) for item in container["Args"]
    ):
        fail("container process arguments are invalid")

    config = container.get("Config")
    if not isinstance(config, dict):
        fail("container Config is missing")
    image_ref = config.get("Image")
    if not isinstance(image_ref, str) or not image_ref or any(
        ord(character) < 32 for character in image_ref
    ):
        fail("container image reference is invalid")
    if not isinstance(config.get("User"), str) or not config["User"]:
        fail("container user is missing")
    string_list_or_none(config.get("Entrypoint"), "container entrypoint")
    string_list_or_none(config.get("Cmd"), "container command")
    if not isinstance(config.get("WorkingDir"), str):
        fail("container working directory is invalid")

    environment = config.get("Env")
    if not isinstance(environment, list) or not all(
        isinstance(item, str) for item in environment
    ):
        fail("container environment is invalid")
    secret_entries = [
        item for item in environment if item.startswith("LEZI_BOOTSTRAP_SECRET=")
    ]
    if len(secret_entries) != 1:
        fail("container must have exactly one bootstrap secret environment entry")
    secret = secret_entries[0].removeprefix("LEZI_BOOTSTRAP_SECRET=")
    if len(secret) < 16 or any(ord(character) < 32 for character in secret):
        fail("container bootstrap secret is invalid")

    host_config = container.get("HostConfig")
    if not isinstance(host_config, dict):
        fail("container HostConfig is missing")
    if not isinstance(host_config.get("NetworkMode"), str) or not host_config[
        "NetworkMode"
    ]:
        fail("container network mode is missing")
    restart_policy = host_config.get("RestartPolicy")
    if not isinstance(restart_policy, dict) or not isinstance(
        restart_policy.get("Name"), str
    ):
        fail("container restart policy is invalid")
    port_bindings = host_config.get("PortBindings")
    port_8765 = (
        port_bindings.get("8765/tcp") if isinstance(port_bindings, dict) else None
    )
    if not isinstance(port_8765, list) or not port_8765 or not any(
        isinstance(binding, dict) and binding.get("HostPort") == "8765"
        for binding in port_8765
    ):
        fail("container does not publish the pre-TLS port 8765 contract")
    binds = host_config.get("Binds")
    if binds is not None and (
        not isinstance(binds, list) or not all(isinstance(item, str) for item in binds)
    ):
        fail("container bind list is invalid")

    mounts = container.get("Mounts")
    if not isinstance(mounts, list):
        fail("container mount contract is missing")
    data_mounts = [
        mount
        for mount in mounts
        if isinstance(mount, dict) and mount.get("Destination") == "/data"
    ]
    if len(data_mounts) != 1:
        fail("container must have exactly one /data mount")
    data_mount = data_mounts[0]
    if (
        data_mount.get("Type") != "bind"
        or data_mount.get("RW") is not True
        or not isinstance(data_mount.get("Source"), str)
        or not data_mount["Source"].startswith("/")
    ):
        fail("container /data mount is not the required writable host bind")
    if not isinstance(container.get("NetworkSettings"), dict):
        fail("container NetworkSettings is missing")

    canonical = (
        json.dumps(state, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")
    return state, container, canonical, secret


def bundle_from_inspect(
    payload: bytes,
    expected_name: str,
    expected_image_id: str,
) -> bytes:
    _, container, canonical, _ = validate_inspect(payload, expected_name)
    if container["Image"] != expected_image_id:
        fail("live container image id does not match the independent rollback pin")
    encoded = base64.b64encode(canonical).decode("ascii")
    digest = hashlib.sha256(canonical).hexdigest()
    lines = [
        FORMAT,
        f"docker_inspect_b64={encoded}",
        f"inspect_sha256={digest}",
        f"container_id={container['Id']}",
        f"image_id={container['Image']}",
        f"container_name={expected_name}",
    ]
    return ("\n".join(lines) + "\n").encode("ascii")


def inspect_from_bundle(
    payload: bytes,
    expected_name: str,
    expected_image_id: str,
) -> tuple[bytes, str]:
    try:
        decoded = payload.decode("ascii", errors="strict")
    except UnicodeDecodeError:
        fail("encrypted state bundle is not ASCII")
    lines = decoded.splitlines()
    if (
        len(lines) != 6
        or lines[0] != FORMAT
        or not lines[1].startswith("docker_inspect_b64=")
        or not lines[2].startswith("inspect_sha256=")
        or not lines[3].startswith("container_id=")
        or not lines[4].startswith("image_id=")
        or lines[5] != f"container_name={expected_name}"
    ):
        fail("encrypted state bundle has an invalid or unsupported format")
    encoded = lines[1].removeprefix("docker_inspect_b64=")
    recorded_digest = lines[2].removeprefix("inspect_sha256=")
    recorded_container_id = lines[3].removeprefix("container_id=")
    recorded_image_id = lines[4].removeprefix("image_id=")
    if not HEX_SHA256.fullmatch(recorded_digest):
        fail("encrypted state bundle has an invalid inspect digest")
    if not CONTAINER_ID.fullmatch(recorded_container_id) or not SHA256_ID.fullmatch(
        recorded_image_id
    ):
        fail("encrypted state bundle has an invalid container or image id")
    try:
        inspect_payload = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error):
        fail("encrypted state bundle has invalid base64 inspect data")
    _, container, canonical, secret = validate_inspect(inspect_payload, expected_name)
    if canonical != inspect_payload:
        fail("encrypted state bundle does not contain canonical inspect data")
    if hashlib.sha256(canonical).hexdigest() != recorded_digest:
        fail("encrypted state bundle inspect digest does not match")
    if container["Id"] != recorded_container_id or container["Image"] != recorded_image_id:
        fail("encrypted state bundle ids do not match the inspect data")
    if recorded_image_id != expected_image_id:
        fail("encrypted state bundle image id does not match the independent rollback pin")
    return canonical, secret


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(add_help=True)
    parser.add_argument("mode", choices=("pack", "unpack", "secret"))
    parser.add_argument("--container-name", default="lezi-sync")
    parser.add_argument("--expected-image-id")
    args = parser.parse_args()
    if not CONTAINER_NAME.fullmatch(args.container_name):
        parser.error("--container-name is invalid")
    if args.expected_image_id is None or not SHA256_ID.fullmatch(args.expected_image_id):
        parser.error("all modes require --expected-image-id=sha256:<64 lowercase hex>")
    return args


def main() -> int:
    args = parse_args()
    maximum_bytes = MAX_INSPECT_BYTES if args.mode == "pack" else MAX_BUNDLE_BYTES
    payload = read_stdin(maximum_bytes)
    if args.mode == "pack":
        sys.stdout.buffer.write(
            bundle_from_inspect(payload, args.container_name, args.expected_image_id)
        )
        return 0
    inspect_payload, secret = inspect_from_bundle(
        payload,
        args.container_name,
        args.expected_image_id,
    )
    if args.mode == "unpack":
        sys.stdout.buffer.write(inspect_payload)
    else:
        sys.stdout.write(secret)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ValidationError as error:
        print(f"error: pre-TLS cutover state validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
