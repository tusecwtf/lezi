#!/usr/bin/env bash
# Create synthetic schema-11/12 data roots for the local-only H30 rehearsal.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
schema="${1:-}"
root="${2:-}"

die() {
  echo "error: rehearsal fixture: $*" >&2
  exit 1
}

[[ "${LEZI_ISOLATED_SCHEMA_CUTOVER_REHEARSAL:-0}" == 1 ]] \
  || die "set LEZI_ISOLATED_SCHEMA_CUTOVER_REHEARSAL=1"
[[ "${schema}" == 11 || "${schema}" == 12 ]] || die "schema must be 11 or 12"
[[ "${root}" == /* ]] || die "fixture root must be absolute"
root="$(realpath -m -- "${root}")"
case "${root}/" in
  /tmp/*|/var/tmp/*) ;;
  *) die "fixture root must be under /tmp or /var/tmp" ;;
esac
[[ ! -e "${root}" ]] || die "fixture root must not exist"

sqlite3_bin="${LEZI_REHEARSAL_SQLITE3_BIN:-$(command -v sqlite3 || true)}"
[[ -x "${sqlite3_bin}" ]] || die "set LEZI_REHEARSAL_SQLITE3_BIN to sqlite3"
command -v openssl >/dev/null 2>&1 || die "openssl is required"
source_apk="${LEZI_REHEARSAL_SOURCE_APK:-}"
[[ -f "${source_apk}" && ! -L "${source_apk}" ]] \
  || die "LEZI_REHEARSAL_SOURCE_APK must be the signed 0.3.13 release APK"
[[ "$(od -An -N2 -tx1 "${source_apk}" | tr -d ' \n')" == 504b ]] \
  || die "source APK is not a ZIP/APK"

install -d -m 700 -- "${root}" "${root}/media" "${root}/tls"
printf 'lezi-h30-synthetic-fixture-v1 schema=%s\n' "${schema}" \
  >"${root}/.isolated-schema-cutover-fixture"
openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "${root}/tls/server.key" -out "${root}/tls/server.crt" \
  -days 2 -subj '/CN=127.0.0.1' >/dev/null 2>&1
openssl rand 32 >"${root}/server.secret"
secret_hex="$(od -An -v -tx1 "${root}/server.secret" | tr -d ' \n')"
owner_root_fingerprint="$(printf '%s' 'owner-root:isolated-rehearsal-only' \
  | openssl dgst -sha256 -mac HMAC -macopt "hexkey:${secret_hex}" -binary \
  | openssl base64 -A | tr '+/' '-_' | tr -d '=')"
cp -- "${source_apk}" "${root}/app-release.apk"
apk_sha="$(sha256sum "${root}/app-release.apk" | awk '{print $1}')"
cat >"${root}/app-update.json" <<EOF
{
  "package_name": "com.lezi.babylog",
  "version_code": 20,
  "version_name": "0.3.13",
  "min_supported_version_code": 20,
  "sha256": "${apk_sha}",
  "release_notes": "synthetic isolated rollback fixture"
}
EOF

schema_file="${SCRIPT_DIR}/../src/offline_migrate/source_v11_schema.sql"
if [[ "${schema}" == 12 ]]; then
  schema_file="${SCRIPT_DIR}/../src/offline_migrate/schema_v12.sql"
fi
"${sqlite3_bin}" "${root}/lezi.db" <"${schema_file}"
"${sqlite3_bin}" "${root}/lezi.db" "PRAGMA user_version = ${schema};"

family='11111111-1111-1111-1111-111111111111'
member='22222222-2222-2222-2222-222222222222'
baby='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
record='33333333-3333-3333-3333-333333333333'
stable='44444444-4444-4444-4444-444444444444'
branch='55555555-5555-5555-5555-555555555555'
conflict='66666666-6666-6666-6666-666666666666'
media='77777777-7777-7777-7777-777777777777'

"${sqlite3_bin}" "${root}/lezi.db" <<SQL
PRAGMA foreign_keys = ON;
INSERT INTO families(id, created_at, name, owner_root_fingerprint)
VALUES ('${family}', 1, 'isolated-family', '${owner_root_fingerprint}');
INSERT INTO memberships(membership_id, family_id, role, display_name, display_name_key, left_at)
VALUES ('${member}', '${family}', 'owner', 'Owner', 'owner', NULL);
INSERT INTO devices(device_id, membership_id, device_name, device_name_key, status, created_at, last_used_at)
VALUES ('device', '${member}', 'phone', 'phone', 'active', 1, 2);
INSERT INTO device_sessions(session_id, device_id, access_token_hash, access_expires_at,
  refresh_token_hash, refresh_generation, revoked_at, revoked_reason)
VALUES ('session', 'device',
  '5b11dc2faa66996fb747b702fb4e751889ba8fc2de1b47dc35271d6283c9a5b4',
  4102444800, 'refresh-hash', 3, NULL, NULL);
INSERT INTO family_meta(family_id, rev) VALUES ('${family}', 2);
INSERT INTO entities(family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev)
VALUES
  ('${family}', 'baby', '${baby}', 10, NULL,
   '{"nickname":"Baby","sex":null,"birthday":"2024-01-01","birth_weight_grams":null,"avatar_media_uuid":null}', 1),
  ('${family}', 'record', '${record}', 20, NULL,
   '{"baby_client_uuid":"${baby}","type":"formula","custom_item_client_uuid":null,"timestamp":20,"end_timestamp":null,"note":"source","payload_json":{"amount_ml":30},"schema_version":2,"created_by_membership_id":"${member}"}', 2);
SQL

install -d -m 700 -- "${root}/media/${family}"
printf 'media-bytes' >"${root}/media/${family}/${media}"
if [[ "${schema}" == 12 ]]; then
  media_hash="$(sha256sum "${root}/media/${family}/${media}" | awk '{print $1}')"
  "${sqlite3_bin}" "${root}/lezi.db" <<SQL
PRAGMA foreign_keys = ON;
INSERT INTO entity_versions(family_id, version_id, entity_type, client_uuid, updated_at,
  deleted_at, payload_json, content_hash, mutation_id, origin, created_at)
VALUES
  ('${family}', '${stable}', 'record', '${record}', 20, NULL,
   '{"baby_client_uuid":"${baby}","type":"formula","custom_item_client_uuid":null,"timestamp":20,"end_timestamp":null,"note":"source","payload_json":{"amount_ml":30},"schema_version":2,"created_by_membership_id":"${member}"}',
   'stable-hash', 'stable-mutation', 'accepted', 20),
  ('${family}', '${branch}', 'record', '${record}', 21, NULL,
   '{"baby_client_uuid":"${baby}","type":"formula","custom_item_client_uuid":null,"timestamp":20,"end_timestamp":null,"note":"branch","payload_json":{"amount_ml":30},"schema_version":2,"created_by_membership_id":"${member}"}',
   'branch-hash', 'branch-mutation', 'branched', 21);
INSERT INTO entity_stable_heads(family_id, entity_type, client_uuid, version_id)
VALUES ('${family}', 'record', '${record}', '${stable}');
INSERT INTO conflicts(family_id, conflict_id, entity_type, client_uuid, base_version_id,
  stable_version_id, status, kind, created_at, resolved_at)
VALUES ('${family}', '${conflict}', 'record', '${record}', NULL,
  '${stable}', 'open', 'concurrent', 22, NULL);
INSERT INTO conflict_branches(family_id, conflict_id, branch_version_id)
VALUES ('${family}', '${conflict}', '${branch}');
INSERT INTO entity_version_media(family_id, version_id, media_uuid, media_payload_json, content_hash)
VALUES ('${family}', '${branch}', '${media}', '{}', 'media-hash');
INSERT INTO media_publications(family_id, media_uuid, source, bundle_id)
VALUES ('${family}', '${media}', 'ordinary', NULL);
INSERT INTO causal_media_staging(family_id, membership_id, media_uuid, sha256, byte_size,
  created_at, expires_at, status, consumed_at)
VALUES ('${family}', '${member}', '${media}', '${media_hash}', 11,
  1, 4102444800, 'consumed', 2);
SQL
fi

chmod 600 "${root}/lezi.db" "${root}/server.secret" "${root}/app-release.apk" \
  "${root}/app-update.json" "${root}/tls/server.crt" "${root}/tls/server.key" \
  "${root}/.isolated-schema-cutover-fixture"
echo "created synthetic isolated schema-${schema} fixture: ${root}"
