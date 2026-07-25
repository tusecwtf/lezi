use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use rusqlite::{params, Connection, OptionalExtension};
use serde_json::Value;
use thiserror::Error;

use crate::models::EntityDto;

#[derive(Debug, Error)]
pub enum StoreError {
    #[error("sqlite: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("invalid invite")]
    InvalidInvite,
    #[error("invite expired")]
    ExpiredInvite,
    #[error("family_id required")]
    FamilyRequired,
}

pub struct Store {
    conn: Mutex<Connection>,
}

impl Store {
    pub fn open(db_path: &Path) -> Result<Self, StoreError> {
        if let Some(parent) = db_path.parent() {
            std::fs::create_dir_all(parent).ok();
        }
        let conn = Connection::open(db_path)?;
        conn.execute_batch(
            "
            PRAGMA journal_mode=WAL;
            PRAGMA foreign_keys=ON;
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
            ",
        )?;
        {
            let mut stmt = conn.prepare("SELECT value FROM meta WHERE key='rev'")?;
            let existing: Option<String> = stmt
                .query_row([], |r| r.get(0))
                .optional()?;
            if existing.is_none() {
                conn.execute("INSERT INTO meta(key, value) VALUES('rev', '0')", [])?;
            }
        }
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    fn now_ms() -> i64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0)
    }

    fn next_rev(conn: &Connection) -> Result<i64, StoreError> {
        let cur: String = conn.query_row("SELECT value FROM meta WHERE key='rev'", [], |r| {
            r.get(0)
        })?;
        let rev = cur.parse::<i64>().unwrap_or(0) + 1;
        conn.execute("UPDATE meta SET value=?1 WHERE key='rev'", params![rev.to_string()])?;
        Ok(rev)
    }

    fn row_to_entity(
        entity_type: String,
        client_uuid: String,
        payload: String,
        updated_at: i64,
        deleted_at: Option<i64>,
        rev: i64,
    ) -> Result<EntityDto, StoreError> {
        Ok(EntityDto {
            entity_type,
            client_uuid,
            payload: serde_json::from_str(&payload).unwrap_or(Value::Object(Default::default())),
            updated_at,
            deleted_at,
            rev: Some(rev),
        })
    }

    pub fn push(&self, family_id: &str, entities: &[EntityDto]) -> Result<u32, StoreError> {
        if family_id.is_empty() {
            return Err(StoreError::FamilyRequired);
        }
        let conn = self.conn.lock().expect("db lock");
        let mut applied = 0u32;
        for ent in entities {
            if ent.entity_type.is_empty() || ent.client_uuid.is_empty() {
                continue;
            }
            let updated_at = if ent.updated_at == 0 {
                Self::now_ms()
            } else {
                ent.updated_at
            };
            let existing: Option<i64> = conn
                .query_row(
                    "SELECT updated_at FROM entities WHERE family_id=?1 AND entity_type=?2 AND client_uuid=?3",
                    params![family_id, ent.entity_type, ent.client_uuid],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(server_updated) = existing {
                if server_updated > updated_at {
                    // LWW: keep server
                    continue;
                }
            }
            let rev = Self::next_rev(&conn)?;
            let payload = serde_json::to_string(&ent.payload)?;
            conn.execute(
                "
                INSERT INTO entities(family_id, entity_type, client_uuid, payload, updated_at, deleted_at, rev)
                VALUES(?1,?2,?3,?4,?5,?6,?7)
                ON CONFLICT(family_id, entity_type, client_uuid) DO UPDATE SET
                  payload=excluded.payload,
                  updated_at=excluded.updated_at,
                  deleted_at=excluded.deleted_at,
                  rev=excluded.rev
                ",
                params![
                    family_id,
                    ent.entity_type,
                    ent.client_uuid,
                    payload,
                    updated_at,
                    ent.deleted_at,
                    rev,
                ],
            )?;
            applied += 1;
        }
        Ok(applied)
    }

    pub fn pull(&self, family_id: &str, cursor: i64) -> Result<(Vec<EntityDto>, i64), StoreError> {
        if family_id.is_empty() {
            return Err(StoreError::FamilyRequired);
        }
        let conn = self.conn.lock().expect("db lock");
        let mut stmt = conn.prepare(
            "
            SELECT entity_type, client_uuid, payload, updated_at, deleted_at, rev
            FROM entities
            WHERE family_id=?1 AND rev > ?2
            ORDER BY rev ASC
            ",
        )?;
        let rows = stmt.query_map(params![family_id, cursor], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, i64>(3)?,
                r.get::<_, Option<i64>>(4)?,
                r.get::<_, i64>(5)?,
            ))
        })?;
        let mut entities = Vec::new();
        for row in rows {
            let (t, u, p, ua, da, rev) = row?;
            entities.push(Self::row_to_entity(t, u, p, ua, da, rev)?);
        }
        let next_cursor = entities
            .last()
            .and_then(|e| e.rev)
            .unwrap_or(cursor);
        Ok((entities, next_cursor))
    }

    pub fn create_invite(&self, family_id: &str) -> Result<(String, i64), StoreError> {
        if family_id.is_empty() {
            return Err(StoreError::FamilyRequired);
        }
        let code = uuid::Uuid::new_v4()
            .simple()
            .to_string()
            .get(..8)
            .unwrap_or("00000000")
            .to_uppercase();
        let expires_at = Self::now_ms() + 24 * 3600 * 1000;
        let conn = self.conn.lock().expect("db lock");
        conn.execute(
            "INSERT INTO invites(code, family_id, expires_at) VALUES(?1,?2,?3)",
            params![code, family_id, expires_at],
        )?;
        Ok((code, expires_at))
    }

    pub fn join(&self, code: &str) -> Result<(String, Vec<EntityDto>, i64), StoreError> {
        let code = code.to_uppercase();
        let conn = self.conn.lock().expect("db lock");
        let inv: Option<(String, i64)> = conn
            .query_row(
                "SELECT family_id, expires_at FROM invites WHERE code=?1",
                params![code],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        let Some((family_id, expires_at)) = inv else {
            return Err(StoreError::InvalidInvite);
        };
        if expires_at < Self::now_ms() {
            return Err(StoreError::ExpiredInvite);
        }
        let mut stmt = conn.prepare(
            "
            SELECT entity_type, client_uuid, payload, updated_at, deleted_at, rev
            FROM entities WHERE family_id=?1 ORDER BY rev ASC
            ",
        )?;
        let rows = stmt.query_map(params![family_id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, i64>(3)?,
                r.get::<_, Option<i64>>(4)?,
                r.get::<_, i64>(5)?,
            ))
        })?;
        let mut entities = Vec::new();
        for row in rows {
            let (t, u, p, ua, da, rev) = row?;
            entities.push(Self::row_to_entity(t, u, p, ua, da, rev)?);
        }
        let cursor = entities.last().and_then(|e| e.rev).unwrap_or(0);
        Ok((family_id, entities, cursor))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use tempfile::tempdir;

    fn test_store() -> Store {
        let dir = tempdir().unwrap();
        // leak path for test lifetime — store holds open file
        let path = dir.path().join("t.db");
        // Keep dir alive by forgetting — use open with path string owned
        let s = Store::open(&path).unwrap();
        // prevent tempdir drop by storing in a static-ish way: re-open via same path after
        // Actually tempdir drops at end of fn and deletes db — keep box
        std::mem::forget(dir);
        s
    }

    #[test]
    fn lww_keeps_newer_server() {
        let store = test_store();
        let family = "fam-1";
        let e1 = EntityDto {
            entity_type: "record".into(),
            client_uuid: "u1".into(),
            payload: json!({"ml": 100}),
            updated_at: 2000,
            deleted_at: None,
            rev: None,
        };
        assert_eq!(store.push(family, &[e1]).unwrap(), 1);

        let older = EntityDto {
            entity_type: "record".into(),
            client_uuid: "u1".into(),
            payload: json!({"ml": 50}),
            updated_at: 1000,
            deleted_at: None,
            rev: None,
        };
        assert_eq!(store.push(family, &[older]).unwrap(), 0);

        let (ents, _) = store.pull(family, 0).unwrap();
        assert_eq!(ents.len(), 1);
        assert_eq!(ents[0].payload["ml"], 100);
        assert_eq!(ents[0].updated_at, 2000);
    }

    #[test]
    fn lww_applies_newer_client() {
        let store = test_store();
        let family = "fam-2";
        let e1 = EntityDto {
            entity_type: "record".into(),
            client_uuid: "u1".into(),
            payload: json!({"ml": 100}),
            updated_at: 1000,
            deleted_at: None,
            rev: None,
        };
        store.push(family, &[e1]).unwrap();
        let newer = EntityDto {
            entity_type: "record".into(),
            client_uuid: "u1".into(),
            payload: json!({"ml": 150}),
            updated_at: 3000,
            deleted_at: None,
            rev: None,
        };
        assert_eq!(store.push(family, &[newer]).unwrap(), 1);
        let (ents, cur) = store.pull(family, 0).unwrap();
        assert_eq!(ents.len(), 1);
        assert_eq!(ents[0].payload["ml"], 150);
        assert_eq!(cur, ents[0].rev.unwrap());
    }

    #[test]
    fn pull_incremental_cursor() {
        let store = test_store();
        let family = "fam-3";
        for i in 0..3 {
            store
                .push(
                    family,
                    &[EntityDto {
                        entity_type: "record".into(),
                        client_uuid: format!("u{i}"),
                        payload: json!({"i": i}),
                        updated_at: 1000 + i,
                        deleted_at: None,
                        rev: None,
                    }],
                )
                .unwrap();
        }
        let (all, c1) = store.pull(family, 0).unwrap();
        assert_eq!(all.len(), 3);
        let (none, c2) = store.pull(family, c1).unwrap();
        assert!(none.is_empty());
        assert_eq!(c2, c1);
    }

    #[test]
    fn invite_and_join() {
        let store = test_store();
        let family = "fam-join";
        store
            .push(
                family,
                &[EntityDto {
                    entity_type: "baby".into(),
                    client_uuid: "b1".into(),
                    payload: json!({"nickname": "豆豆"}),
                    updated_at: 1,
                    deleted_at: None,
                    rev: None,
                }],
            )
            .unwrap();
        let (code, exp) = store.create_invite(family).unwrap();
        assert_eq!(code.len(), 8);
        assert!(exp > Store::now_ms());
        let (fid, ents, cursor) = store.join(&code.to_lowercase()).unwrap();
        assert_eq!(fid, family);
        assert_eq!(ents.len(), 1);
        assert!(cursor > 0);
    }

    #[test]
    fn join_invalid_code() {
        let store = test_store();
        let err = store.join("DEADBEEF").unwrap_err();
        assert!(matches!(err, StoreError::InvalidInvite));
    }
}
