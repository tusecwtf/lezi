//! Shared offline_migrate test fixtures (v3 schema seed + password constant).
//!
//! Used by `migrator`, `media`, and `cli` unit tests so schema-seed helpers stay aligned.

use std::path::Path;

use rusqlite::{params, Connection};

use super::inventory::SOURCE_V3_SCHEMA_SQL;

/// ≥16 chars; known literal for fingerprint / owner-login / data-dir seams.
pub(crate) const TEST_NEW_ROOT_PASSWORD: &str = "ops-new-root-pw!!";

/// Stable fixture family id used by migrator + cli seeds (non-UUID text is ok for pure DB tests).
pub(crate) const FIXTURE_FAMILY_ID: &str = "fam-1";
pub(crate) const FIXTURE_OWNER_MEMBERSHIP: &str = "mem-owner";
pub(crate) const FIXTURE_MEMBER_MEMBERSHIP: &str = "mem-member";
pub(crate) const FIXTURE_BABY_UUID: &str = "11111111-1111-1111-1111-111111111111";

pub(crate) fn open_v3_fixture(path: &Path) -> Connection {
    let conn = Connection::open(path).unwrap();
    conn.execute_batch("PRAGMA foreign_keys = ON;").unwrap();
    conn.execute_batch(SOURCE_V3_SCHEMA_SQL).unwrap();
    conn.pragma_update(None, "user_version", 3i64).unwrap();
    conn
}

pub(crate) fn baby_payload_json() -> String {
    serde_json::to_string(&serde_json::json!({
        "nickname": "年年",
        "sex": null,
        "birthday": "2025-01-02",
        "avatar_media_uuid": null,
        "birth_weight_grams": null,
    }))
    .unwrap()
}

/// Minimal v3 family: 1 family, owner+member, family_meta, legacy credentials/invites.
///
/// Counts after migrate (with one baby entity): families=1, memberships=2.
pub(crate) fn seed_minimal_family(conn: &Connection) {
    conn.execute(
        "INSERT INTO families(id, created_at, create_request_hash, name) VALUES (?1, 100, NULL, '我家')",
        params![FIXTURE_FAMILY_ID],
    )
    .unwrap();
    conn.execute(
        "
        INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
        VALUES (?1, ?2, 'owner', 'dev-old-1', '爸爸', NULL)
        ",
        params![FIXTURE_OWNER_MEMBERSHIP, FIXTURE_FAMILY_ID],
    )
    .unwrap();
    conn.execute(
        "
        INSERT INTO memberships(membership_id, family_id, role, device_id, display_name, left_at)
        VALUES (?1, ?2, 'member', 'dev-old-2', '妈妈', NULL)
        ",
        params![FIXTURE_MEMBER_MEMBERSHIP, FIXTURE_FAMILY_ID],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO family_meta(family_id, rev) VALUES (?1, 7)",
        params![FIXTURE_FAMILY_ID],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO membership_credentials(token_hash, membership_id, revoked_at) VALUES ('th1', ?1, NULL)",
        params![FIXTURE_OWNER_MEMBERSHIP],
    )
    .unwrap();
    conn.execute(
        "INSERT INTO invites(code_hash, family_id, expires_at, used_at, joined_device_id) VALUES ('ih1', ?1, 999, NULL, NULL)",
        params![FIXTURE_FAMILY_ID],
    )
    .unwrap();
}

/// Single baby entity under [`FIXTURE_FAMILY_ID`] (entities=1 when alone).
pub(crate) fn seed_baby_entity(conn: &Connection) {
    conn.execute(
        "
        INSERT INTO entities(
            family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
        ) VALUES (
            ?1, 'baby', ?2,
            200, NULL, ?3, 1
        )
        ",
        params![FIXTURE_FAMILY_ID, FIXTURE_BABY_UUID, baby_payload_json()],
    )
    .unwrap();
}
