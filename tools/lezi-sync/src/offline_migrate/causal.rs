//! Causal v12 finalization: deterministic base versions + closed-sleep WakeObservation.
//!
//! Used by both the historical v3→current path and the v11→v12 offline migrator.
//! Not a runtime / startup upgrade path.

use std::collections::{BTreeMap, BTreeSet};
use std::fs;
use std::path::Path;

use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use uuid::Uuid;

use super::inventory::AuthoritativeFailure;
use super::migrator::{MigrateError, MigrateReport};
use crate::store::VERSIONED_ENTITY_TYPES;

/// Frozen namespace for historical closed-sleep → WakeObservation IDs (wire §11).
pub(crate) const WAKE_MIGRATION_NAMESPACE: Uuid = Uuid::from_bytes([
    0x7c, 0x9e, 0x66, 0x79, 0x74, 0x25, 0x40, 0xde, 0x94, 0x4b, 0xe0, 0x7f, 0xc1, 0xf9, 0x0a, 0xe7,
]);

/// Frozen namespace for migration base `version_id` values (v12 offline only).
pub(crate) const VERSION_MIGRATION_NAMESPACE: Uuid = Uuid::from_bytes([
    0x3d, 0x4f, 0x8a, 0x21, 0x6b, 0x9c, 0x4e, 0x11, 0x9f, 0x2a, 0x0c, 0xd4, 0xe8, 0x71, 0x55, 0xb0,
]);

/// Deterministic WakeObservation `client_uuid` (wire §11).
pub(crate) fn wake_observation_client_uuid(
    sleep_client_uuid: &str,
    legacy_updated_at: i64,
) -> Uuid {
    let name = format!("wake_obs_v1:{sleep_client_uuid}:{legacy_updated_at}");
    Uuid::new_v5(&WAKE_MIGRATION_NAMESPACE, name.as_bytes())
}

/// Deterministic wake media `media_uuid` (wire §11).
pub(crate) fn wake_media_uuid(
    sleep_client_uuid: &str,
    legacy_media_uuid: &str,
    lowercase_hex_sha256: &str,
) -> Uuid {
    let name = format!(
        "wake_media_v1:{sleep_client_uuid}:{legacy_media_uuid}:{lowercase_hex_sha256}:wake"
    );
    Uuid::new_v5(&WAKE_MIGRATION_NAMESPACE, name.as_bytes())
}

/// Deterministic migration base version_id for one stable root.
pub(crate) fn migration_base_version_id(
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    rev: i64,
    content_hash: &str,
) -> Uuid {
    let name =
        format!("entity_version_v12:{family_id}:{entity_type}:{client_uuid}:{rev}:{content_hash}");
    Uuid::new_v5(&VERSION_MIGRATION_NAMESPACE, name.as_bytes())
}

fn content_hash_hex(parts: &[&str]) -> String {
    let mut hasher = Sha256::new();
    for part in parts {
        hasher.update(part.as_bytes());
        hasher.update([0xff]);
    }
    hex::encode(hasher.finalize())
}

/// After entities (and optional media files) are on the dest DB, mint base versions
/// and convert historical Sleep rows into SleepStart (+ WakeObservation when closed).
///
/// `media_root` is the data-dir root that contains `media/{family}/{uuid}` files
/// (source or dest). When present, wake media UUIDs derive from on-disk sha256 and
/// legacy→wake file copies are written under the same root.
pub(crate) fn finalize_causal_v12(
    dest: &Connection,
    media_root: Option<&Path>,
    report: &mut MigrateReport,
) -> Result<(), MigrateError> {
    transform_historical_sleeps(dest, media_root, report)?;
    mint_base_versions(dest, report)?;
    crate::store::rebuild_record_eligibility(dest)?;
    Ok(())
}

fn transform_historical_sleeps(
    dest: &Connection,
    media_root: Option<&Path>,
    report: &mut MigrateReport,
) -> Result<(), MigrateError> {
    let mut stmt = dest.prepare(
        "
        SELECT family_id, client_uuid, updated_at, deleted_at, payload_json, rev
        FROM entities
        WHERE entity_type = 'record'
        ",
    )?;
    let rows = stmt
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, Option<i64>>(3)?,
                row.get::<_, String>(4)?,
                row.get::<_, i64>(5)?,
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?;

    for (family_id, sleep_uuid, updated_at, deleted_at, payload_json, _rev) in rows {
        let mut payload: Map<String, Value> = serde_json::from_str(&payload_json)?;
        let record_type = payload
            .get("type")
            .and_then(Value::as_str)
            .unwrap_or_default();
        if record_type != "sleep" {
            continue;
        }

        // Capture legacy end before stripping (wire forbids end_timestamp on sleep).
        let end_timestamp =
            payload
                .get("end_timestamp")
                .and_then(|v| if v.is_null() { None } else { v.as_i64() });
        payload.remove("end_timestamp");

        if let Some(wake_ts) = end_timestamp {
            // Closed sleep → WakeObservation + transfer log media → wake media.
            let wake_uuid = wake_observation_client_uuid(&sleep_uuid, updated_at);
            let wake_uuid_str = wake_uuid.to_string();
            let observer = payload
                .get("created_by_membership_id")
                .and_then(Value::as_str)
                .map(str::to_owned);
            let note = payload.get("note").cloned().unwrap_or(Value::Null);

            let mut wake_payload = Map::new();
            wake_payload.insert(
                "sleep_record_client_uuid".to_owned(),
                Value::String(sleep_uuid.clone()),
            );
            wake_payload.insert("wake_timestamp".to_owned(), Value::Number(wake_ts.into()));
            wake_payload.insert("note".to_owned(), note);
            wake_payload.insert("withdrawn".to_owned(), Value::Bool(false));
            if let Some(obs) = observer {
                wake_payload.insert("observer_membership_id".to_owned(), Value::String(obs));
            }

            let mut next_rev: i64 = dest.query_row(
                "SELECT rev FROM family_meta WHERE family_id = ?1",
                params![family_id],
                |r| r.get(0),
            )?;

            next_rev += 1;
            dest.execute(
                "
                INSERT INTO entities(
                    family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                ) VALUES (?1, 'wake_observation', ?2, ?3, ?4, ?5, ?6)
                ",
                params![
                    family_id,
                    wake_uuid_str,
                    updated_at,
                    deleted_at,
                    serde_json::to_string(&wake_payload)?,
                    next_rev
                ],
            )?;
            report.wake_observations += 1;
            report.entities += 1;

            let log_media = load_log_media_for_record(dest, &family_id, &sleep_uuid)?;
            for (media_uuid, media_updated_at, media_deleted_at, media_payload_json) in log_media {
                let sha = resolve_media_sha256(dest, media_root, &family_id, &media_uuid)?;
                let wake_media_id = wake_media_uuid(&sleep_uuid, &media_uuid, &sha).to_string();
                let mut media_payload: Map<String, Value> =
                    serde_json::from_str(&media_payload_json)?;
                media_payload.insert("kind".to_owned(), Value::String("wake".to_owned()));
                media_payload.insert(
                    "record_client_uuid".to_owned(),
                    Value::String(wake_uuid_str.clone()),
                );
                media_payload.insert("baby_client_uuid".to_owned(), Value::Null);
                media_payload.insert("care_plan_client_uuid".to_owned(), Value::Null);

                next_rev += 1;
                dest.execute(
                    "
                    INSERT INTO entities(
                        family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
                    ) VALUES (?1, 'media', ?2, ?3, ?4, ?5, ?6)
                    ",
                    params![
                        family_id,
                        wake_media_id,
                        media_updated_at,
                        media_deleted_at,
                        serde_json::to_string(&media_payload)?,
                        next_rev
                    ],
                )?;
                report.entities += 1;

                let legacy_pub: Option<(String, Option<String>)> = dest
                    .query_row(
                        "
                        SELECT source, bundle_id FROM media_publications
                        WHERE family_id = ?1 AND media_uuid = ?2
                        ",
                        params![family_id, media_uuid],
                        |r| Ok((r.get(0)?, r.get(1)?)),
                    )
                    .optional()?;
                if let Some((source, bundle_id)) = legacy_pub {
                    dest.execute(
                        "
                        INSERT OR IGNORE INTO media_publications(
                            family_id, media_uuid, source, bundle_id
                        ) VALUES (?1, ?2, ?3, ?4)
                        ",
                        params![family_id, wake_media_id, source, bundle_id],
                    )?;
                }

                if let Some(root) = media_root {
                    copy_media_file(root, &family_id, &media_uuid, &wake_media_id)?;
                }

                // Transfer (not dual live): tombstone legacy log so sleep version
                // media membership no longer includes it; bytes retained for recovery.
                if media_deleted_at.is_none() {
                    dest.execute(
                        "
                        UPDATE entities
                        SET deleted_at = ?1
                        WHERE family_id = ?2 AND entity_type = 'media' AND client_uuid = ?3
                          AND deleted_at IS NULL
                        ",
                        params![updated_at, family_id, media_uuid],
                    )?;
                }
            }

            payload.insert(
                "effective_wake_observation_client_uuid".to_owned(),
                Value::String(wake_uuid_str),
            );
            dest.execute(
                "UPDATE family_meta SET rev = ?1 WHERE family_id = ?2",
                params![next_rev, family_id],
            )?;
        } else {
            // Open sleep: SleepStart only; effective wake explicitly null.
            payload.insert(
                "effective_wake_observation_client_uuid".to_owned(),
                Value::Null,
            );
        }

        dest.execute(
            "
            UPDATE entities
            SET payload_json = ?1
            WHERE family_id = ?2 AND entity_type = 'record' AND client_uuid = ?3
            ",
            params![serde_json::to_string(&payload)?, family_id, sleep_uuid],
        )?;
    }
    Ok(())
}

type LogMediaRow = (String, i64, Option<i64>, String);
type MediaMember = (String, String, String);
type MediaIndex = BTreeMap<(String, String, String), Vec<MediaMember>>;

fn load_log_media_for_record(
    dest: &Connection,
    family_id: &str,
    record_uuid: &str,
) -> Result<Vec<LogMediaRow>, MigrateError> {
    let mut stmt = dest.prepare(
        "
        SELECT client_uuid, updated_at, deleted_at, payload_json
        FROM entities
        WHERE family_id = ?1 AND entity_type = 'media'
        ",
    )?;
    let rows = stmt.query_map(params![family_id], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, i64>(1)?,
            row.get::<_, Option<i64>>(2)?,
            row.get::<_, String>(3)?,
        ))
    })?;
    let mut out = Vec::new();
    for row in rows {
        let (uuid, updated_at, deleted_at, payload_json) = row?;
        let payload: Map<String, Value> = serde_json::from_str(&payload_json)?;
        let kind = payload.get("kind").and_then(Value::as_str);
        let record = payload.get("record_client_uuid").and_then(Value::as_str);
        if kind == Some("log") && record == Some(record_uuid) {
            out.push((uuid, updated_at, deleted_at, payload_json));
        }
    }
    out.sort_by(|a, b| a.0.cmp(&b.0));
    Ok(out)
}

fn resolve_media_sha256(
    dest: &Connection,
    media_root: Option<&Path>,
    family_id: &str,
    media_uuid: &str,
) -> Result<String, MigrateError> {
    if let Some(root) = media_root {
        let path = root.join("media").join(family_id).join(media_uuid);
        if path.is_file() {
            let bytes = fs::read(&path)?;
            return Ok(hex::encode(Sha256::digest(&bytes)));
        }
    }
    // Fall back to any staged_sha256 on retained bundle media.
    let staged: Option<String> = dest
        .query_row(
            "
            SELECT staged_sha256 FROM sync_bundle_media
            WHERE family_id = ?1 AND media_uuid = ?2 AND staged_sha256 IS NOT NULL
            LIMIT 1
            ",
            params![family_id, media_uuid],
            |r| r.get(0),
        )
        .optional()?;
    if let Some(sha) = staged {
        let lower = sha.to_ascii_lowercase();
        if lower.len() == 64 && lower.chars().all(|c| c.is_ascii_hexdigit()) {
            return Ok(lower);
        }
    }
    Err(MigrateError::authoritative_failure(
        AuthoritativeFailure::MediaFileMissingOrMismatch,
        format!(
            "cannot resolve sha256 for media `{media_uuid}` in family `{family_id}` (needed for wake media UUID)"
        ),
        MigrateReport::default(),
    ))
}

fn copy_media_file(
    media_root: &Path,
    family_id: &str,
    from_uuid: &str,
    to_uuid: &str,
) -> Result<(), MigrateError> {
    let from = media_root.join("media").join(family_id).join(from_uuid);
    let to_dir = media_root.join("media").join(family_id);
    let to = to_dir.join(to_uuid);
    if !from.is_file() {
        return Err(MigrateError::authoritative_failure(
            AuthoritativeFailure::MediaFileMissingOrMismatch,
            format!(
                "missing source media file for wake copy: {}",
                from.display()
            ),
            MigrateReport::default(),
        ));
    }
    fs::create_dir_all(&to_dir)?;
    if !to.exists() {
        fs::copy(&from, &to)?;
    }
    Ok(())
}

fn mint_base_versions(dest: &Connection, report: &mut MigrateReport) -> Result<(), MigrateError> {
    // Index media by association for version media membership.
    let media_index = load_media_index(dest)?;

    let mut stmt = dest.prepare(
        "
        SELECT family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev
        FROM entities
        ORDER BY family_id, entity_type, client_uuid
        ",
    )?;
    let rows = stmt
        .query_map([], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
                row.get::<_, Option<i64>>(4)?,
                row.get::<_, String>(5)?,
                row.get::<_, i64>(6)?,
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?;

    for (family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev) in rows {
        if !VERSIONED_ENTITY_TYPES.contains(&entity_type.as_str()) {
            continue;
        }
        let media_members = media_index
            .get(&(family_id.clone(), entity_type.clone(), client_uuid.clone()))
            .cloned()
            .unwrap_or_default();

        let mut hash_parts: Vec<String> = vec![
            updated_at.to_string(),
            deleted_at.map(|v| v.to_string()).unwrap_or_default(),
            payload_json.clone(),
        ];
        for (media_uuid, media_payload, _) in &media_members {
            hash_parts.push(media_uuid.clone());
            hash_parts.push(media_payload.clone());
        }
        let refs: Vec<&str> = hash_parts.iter().map(String::as_str).collect();
        let content_hash = content_hash_hex(&refs);
        let version_id =
            migration_base_version_id(&family_id, &entity_type, &client_uuid, rev, &content_hash)
                .to_string();

        dest.execute(
            "
            INSERT INTO entity_versions(
                family_id, version_id, entity_type, client_uuid,
                updated_at, deleted_at, payload_json, content_hash,
                mutation_id, origin, created_at
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, NULL, 'migration_base', ?5)
            ",
            params![
                family_id,
                version_id,
                entity_type,
                client_uuid,
                updated_at,
                deleted_at,
                payload_json,
                content_hash,
            ],
        )?;

        for (media_uuid, media_payload, media_hash) in &media_members {
            dest.execute(
                "
                INSERT INTO entity_version_media(
                    family_id, version_id, media_uuid, media_payload_json, content_hash
                ) VALUES (?1, ?2, ?3, ?4, ?5)
                ",
                params![family_id, version_id, media_uuid, media_payload, media_hash],
            )?;
        }

        dest.execute(
            "
            INSERT INTO entity_stable_heads(
                family_id, entity_type, client_uuid, version_id
            ) VALUES (?1, ?2, ?3, ?4)
            ",
            params![family_id, entity_type, client_uuid, version_id],
        )?;
        report.base_versions += 1;
    }
    Ok(())
}

/// (family, root_type, root_uuid) → sorted media members (uuid, payload_json, content_hash)
fn load_media_index(dest: &Connection) -> Result<MediaIndex, MigrateError> {
    // Live media only — transferred/tombstoned legacy log rows stay out of stable heads.
    let mut stmt = dest.prepare(
        "
        SELECT family_id, client_uuid, payload_json
        FROM entities
        WHERE entity_type = 'media' AND deleted_at IS NULL
        ",
    )?;
    let rows = stmt.query_map([], |row| {
        Ok((
            row.get::<_, String>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;

    let mut index: MediaIndex = BTreeMap::new();
    for row in rows {
        let (family_id, media_uuid, payload_json) = row?;
        let payload: Map<String, Value> = serde_json::from_str(&payload_json)?;
        let kind = payload.get("kind").and_then(Value::as_str).unwrap_or("");
        let media_hash = content_hash_hex(&[&media_uuid, &payload_json]);
        let association = match kind {
            "avatar" => payload
                .get("baby_client_uuid")
                .and_then(Value::as_str)
                .map(|id| ("baby".to_owned(), id.to_owned())),
            "log" => payload
                .get("record_client_uuid")
                .and_then(Value::as_str)
                .map(|id| ("record".to_owned(), id.to_owned()))
                .or_else(|| {
                    payload
                        .get("care_plan_client_uuid")
                        .and_then(Value::as_str)
                        .map(|id| ("care_plan".to_owned(), id.to_owned()))
                }),
            "wake" => payload
                .get("record_client_uuid")
                .and_then(Value::as_str)
                .map(|id| ("wake_observation".to_owned(), id.to_owned())),
            _ => None,
        };
        if let Some((root_type, root_uuid)) = association {
            index
                .entry((family_id, root_type, root_uuid))
                .or_default()
                .push((media_uuid, payload_json, media_hash));
        }
    }
    for members in index.values_mut() {
        members.sort_by(|a, b| a.0.cmp(&b.0));
    }
    Ok(index)
}

/// Post-migrate causal integrity for offline v12 (DB-only; optional media_root).
///
/// Proves: versioned entity set == stable heads; each head is `migration_base` with
/// matching projection payload; version media membership closes over live associated
/// media; family_meta.rev ≥ max entity rev; conflicts/branches empty; sleep wire shape;
/// wake→sleep refs; deterministic wake UUID replay.
pub(crate) fn validate_causal_integrity(dest: &Connection) -> Result<(), String> {
    validate_causal_integrity_with_media(dest, None)
}

/// Same as [`validate_causal_integrity`], plus authority media file presence when
/// `media_root` is set (data-dir validate path).
pub(crate) fn validate_causal_integrity_with_media(
    dest: &Connection,
    media_root: Option<&Path>,
) -> Result<(), String> {
    let versioned_filter: BTreeSet<&str> = VERSIONED_ENTITY_TYPES.iter().copied().collect();

    let versioned: BTreeSet<(String, String, String)> = {
        let mut stmt = dest
            .prepare("SELECT family_id, entity_type, client_uuid FROM entities")
            .map_err(|e| e.to_string())?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(|e| e.to_string())?
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| e.to_string())?;
        rows.into_iter()
            .filter(|(_, ty, _)| versioned_filter.contains(ty.as_str()))
            .collect()
    };

    let heads: BTreeSet<(String, String, String)> = {
        let mut stmt = dest
            .prepare("SELECT family_id, entity_type, client_uuid FROM entity_stable_heads")
            .map_err(|e| e.to_string())?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(|e| e.to_string())?
            .collect::<Result<_, _>>()
            .map_err(|e| e.to_string())?;
        rows
    };

    if versioned != heads {
        return Err(format!(
            "stable heads ↔ versioned entities mismatch: entities={} heads={}",
            versioned.len(),
            heads.len()
        ));
    }

    let eligible_records: BTreeSet<(String, String)> = {
        let mut stmt = dest
            .prepare(
                "SELECT family_id, client_uuid
                 FROM entities
                 WHERE entity_type = 'record' AND deleted_at IS NULL
                   AND json_type(payload_json, '$.baby_client_uuid') = 'text'
                   AND json_extract(payload_json, '$.baby_client_uuid') != ''
                   AND json_type(payload_json, '$.type') = 'text'
                   AND json_extract(payload_json, '$.type') != ''
                   AND json_type(payload_json, '$.timestamp') = 'integer'
                   AND json_type(payload_json, '$.created_by_membership_id') = 'text'
                   AND json_extract(payload_json, '$.created_by_membership_id') != ''",
            )
            .map_err(|e| e.to_string())?;
        let rows = stmt
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
            .map_err(|e| e.to_string())?
            .collect::<Result<_, _>>()
            .map_err(|e| e.to_string())?;
        rows
    };
    let projected_eligibility: BTreeSet<(String, String)> = {
        let mut stmt = dest
            .prepare(
                "SELECT family_id, record_client_uuid
                 FROM source_relation_record_eligibility",
            )
            .map_err(|e| e.to_string())?;
        let rows = stmt
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
            .map_err(|e| e.to_string())?
            .collect::<Result<_, _>>()
            .map_err(|e| e.to_string())?;
        rows
    };
    if eligible_records != projected_eligibility {
        return Err(format!(
            "source-relation eligibility mismatch: records={} projection={}",
            eligible_records.len(),
            projected_eligibility.len()
        ));
    }

    let mut stmt = dest
        .prepare(
            "
            SELECT h.family_id, h.entity_type, h.client_uuid, h.version_id,
                   e.payload_json, e.updated_at, e.deleted_at, e.rev,
                   v.payload_json, v.updated_at, v.deleted_at, v.entity_type, v.client_uuid,
                   v.origin, v.content_hash
            FROM entity_stable_heads h
            JOIN entities e
              ON e.family_id = h.family_id
             AND e.entity_type = h.entity_type
             AND e.client_uuid = h.client_uuid
            JOIN entity_versions v
              ON v.family_id = h.family_id
             AND v.version_id = h.version_id
            ",
        )
        .map_err(|e| e.to_string())?;
    let mut rows = stmt.query([]).map_err(|e| e.to_string())?;
    while let Some(row) = rows.next().map_err(|e| e.to_string())? {
        let family_id: String = row.get(0).map_err(|e| e.to_string())?;
        let entity_type: String = row.get(1).map_err(|e| e.to_string())?;
        let client_uuid: String = row.get(2).map_err(|e| e.to_string())?;
        let version_id: String = row.get(3).map_err(|e| e.to_string())?;
        let e_payload: String = row.get(4).map_err(|e| e.to_string())?;
        let e_updated: i64 = row.get(5).map_err(|e| e.to_string())?;
        let e_deleted: Option<i64> = row.get(6).map_err(|e| e.to_string())?;
        let e_rev: i64 = row.get(7).map_err(|e| e.to_string())?;
        let v_payload: String = row.get(8).map_err(|e| e.to_string())?;
        let v_updated: i64 = row.get(9).map_err(|e| e.to_string())?;
        let v_deleted: Option<i64> = row.get(10).map_err(|e| e.to_string())?;
        let v_type: String = row.get(11).map_err(|e| e.to_string())?;
        let v_uuid: String = row.get(12).map_err(|e| e.to_string())?;
        let origin: String = row.get(13).map_err(|e| e.to_string())?;
        let content_hash: String = row.get(14).map_err(|e| e.to_string())?;
        if entity_type != v_type || client_uuid != v_uuid {
            return Err(format!(
                "version identity drift for {entity_type}/{client_uuid}"
            ));
        }
        if origin != "migration_base" {
            return Err(format!(
                "post-migrate head for {entity_type}/{client_uuid} origin={origin}, expected migration_base"
            ));
        }
        if e_payload != v_payload || e_updated != v_updated || e_deleted != v_deleted {
            return Err(format!(
                "version/projection payload mismatch for {entity_type}/{client_uuid}"
            ));
        }
        // Deterministic version_id replay.
        let expected =
            migration_base_version_id(&family_id, &entity_type, &client_uuid, e_rev, &content_hash)
                .to_string();
        if expected != version_id {
            return Err(format!(
                "version_id not deterministic for {entity_type}/{client_uuid}: got {version_id} expected {expected}"
            ));
        }
    }

    // family_meta.rev ≥ max entity rev per family.
    let mut stmt = dest
        .prepare(
            "
            SELECT f.family_id, f.rev, COALESCE(MAX(e.rev), 0)
            FROM family_meta f
            LEFT JOIN entities e ON e.family_id = f.family_id
            GROUP BY f.family_id, f.rev
            ",
        )
        .map_err(|e| e.to_string())?;
    let mut rows = stmt.query([]).map_err(|e| e.to_string())?;
    while let Some(row) = rows.next().map_err(|e| e.to_string())? {
        let family_id: String = row.get(0).map_err(|e| e.to_string())?;
        let meta_rev: i64 = row.get(1).map_err(|e| e.to_string())?;
        let max_entity: i64 = row.get(2).map_err(|e| e.to_string())?;
        if meta_rev < max_entity {
            return Err(format!(
                "family_meta.rev={meta_rev} < max entity rev={max_entity} for {family_id}"
            ));
        }
    }

    // Post-migrate: no open conflicts or branches.
    for (table, label) in [
        ("conflicts", "conflicts"),
        ("conflict_branches", "conflict_branches"),
        ("conflict_resolutions", "conflict_resolutions"),
    ] {
        let n: i64 = dest
            .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0))
            .map_err(|e| e.to_string())?;
        if n != 0 {
            return Err(format!("post-migrate {label} must be empty, found {n}"));
        }
    }

    // Sleep wire shape + wake refs + deterministic wake UUID.
    let mut stmt = dest
        .prepare(
            "
            SELECT family_id, client_uuid, updated_at, payload_json FROM entities
            WHERE entity_type = 'record'
            ",
        )
        .map_err(|e| e.to_string())?;
    let mut rows = stmt.query([]).map_err(|e| e.to_string())?;
    while let Some(row) = rows.next().map_err(|e| e.to_string())? {
        let family_id: String = row.get(0).map_err(|e| e.to_string())?;
        let sleep_uuid: String = row.get(1).map_err(|e| e.to_string())?;
        let updated_at: i64 = row.get(2).map_err(|e| e.to_string())?;
        let payload_json: String = row.get(3).map_err(|e| e.to_string())?;
        let payload: Map<String, Value> =
            serde_json::from_str(&payload_json).map_err(|e| e.to_string())?;
        if payload.get("type").and_then(Value::as_str) != Some("sleep") {
            continue;
        }
        if payload.contains_key("end_timestamp") {
            return Err(format!(
                "sleep {sleep_uuid} still has end_timestamp after migrate"
            ));
        }
        if !payload.contains_key("effective_wake_observation_client_uuid") {
            return Err(format!(
                "sleep {sleep_uuid} missing effective_wake_observation_client_uuid"
            ));
        }
        if let Some(wake) = payload
            .get("effective_wake_observation_client_uuid")
            .and_then(Value::as_str)
        {
            let expected = wake_observation_client_uuid(&sleep_uuid, updated_at).to_string();
            if wake != expected {
                return Err(format!(
                    "sleep {sleep_uuid} effective wake {wake} != deterministic {expected}"
                ));
            }
            let exists: i64 = dest
                .query_row(
                    "
                    SELECT COUNT(*) FROM entities
                    WHERE family_id = ?1 AND entity_type = 'wake_observation' AND client_uuid = ?2
                    ",
                    params![family_id, wake],
                    |r| r.get(0),
                )
                .map_err(|e| e.to_string())?;
            if exists != 1 {
                return Err(format!(
                    "sleep {sleep_uuid} effective wake {wake} missing as entity"
                ));
            }
        }
    }

    let mut stmt = dest
        .prepare(
            "
            SELECT family_id, client_uuid, payload_json FROM entities
            WHERE entity_type = 'wake_observation'
            ",
        )
        .map_err(|e| e.to_string())?;
    let mut rows = stmt.query([]).map_err(|e| e.to_string())?;
    while let Some(row) = rows.next().map_err(|e| e.to_string())? {
        let family_id: String = row.get(0).map_err(|e| e.to_string())?;
        let wake_uuid: String = row.get(1).map_err(|e| e.to_string())?;
        let payload_json: String = row.get(2).map_err(|e| e.to_string())?;
        let payload: Map<String, Value> =
            serde_json::from_str(&payload_json).map_err(|e| e.to_string())?;
        let sleep_uuid = payload
            .get("sleep_record_client_uuid")
            .and_then(Value::as_str)
            .ok_or_else(|| format!("wake {wake_uuid} missing sleep_record_client_uuid"))?;
        let exists: i64 = dest
            .query_row(
                "
                SELECT COUNT(*) FROM entities
                WHERE family_id = ?1 AND entity_type = 'record' AND client_uuid = ?2
                ",
                params![family_id, sleep_uuid],
                |r| r.get(0),
            )
            .map_err(|e| e.to_string())?;
        if exists != 1 {
            return Err(format!(
                "wake {wake_uuid} references missing sleep {sleep_uuid}"
            ));
        }
    }

    // Live media publications must have entity rows.
    let mut stmt = dest
        .prepare(
            "
            SELECT family_id, media_uuid FROM media_publications
            WHERE source IN ('ordinary', 'bundle')
            ",
        )
        .map_err(|e| e.to_string())?;
    let mut rows = stmt.query([]).map_err(|e| e.to_string())?;
    while let Some(row) = rows.next().map_err(|e| e.to_string())? {
        let family_id: String = row.get(0).map_err(|e| e.to_string())?;
        let media_uuid: String = row.get(1).map_err(|e| e.to_string())?;
        let exists: i64 = dest
            .query_row(
                "
                SELECT COUNT(*) FROM entities
                WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2
                ",
                params![family_id, media_uuid],
                |r| r.get(0),
            )
            .map_err(|e| e.to_string())?;
        if exists != 1 {
            return Err(format!(
                "publication {family_id}/{media_uuid} has no media entity"
            ));
        }
        if let Some(root) = media_root {
            let path = root.join("media").join(&family_id).join(&media_uuid);
            // Only require file for live (non-tombstone) media.
            let deleted: Option<i64> = dest
                .query_row(
                    "
                    SELECT deleted_at FROM entities
                    WHERE family_id = ?1 AND entity_type = 'media' AND client_uuid = ?2
                    ",
                    params![family_id, media_uuid],
                    |r| r.get(0),
                )
                .map_err(|e| e.to_string())?;
            if deleted.is_none() && !path.is_file() {
                return Err(format!(
                    "missing media file for live publication: {}",
                    path.display()
                ));
            }
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wake_observation_uuid_is_deterministic_wire_formula() {
        let sleep = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        let a = wake_observation_client_uuid(sleep, 1_700_000_000_000);
        let b = wake_observation_client_uuid(sleep, 1_700_000_000_000);
        assert_eq!(a, b);
        let c = wake_observation_client_uuid(sleep, 1_700_000_000_001);
        assert_ne!(a, c);
        assert_eq!(a.get_version(), Some(uuid::Version::Sha1));
        assert_eq!(a.to_string(), "420ef57e-f8ac-5704-871e-e3cc857ceba2");
    }

    #[test]
    fn migration_base_version_id_stable() {
        let v1 = migration_base_version_id("fam", "record", "uuid", 3, "abc");
        let v2 = migration_base_version_id("fam", "record", "uuid", 3, "abc");
        assert_eq!(v1, v2);
        assert_eq!(v1.to_string(), "6889ea37-f11d-50be-b32f-118459107281");
        assert_ne!(
            v1,
            migration_base_version_id("fam", "record", "uuid", 4, "abc")
        );
    }

    #[test]
    fn versioned_entity_types_const_is_the_validator_set() {
        assert_eq!(
            VERSIONED_ENTITY_TYPES,
            &[
                "baby",
                "record",
                "care_plan",
                "custom_item",
                "wake_observation"
            ]
        );
    }
}
