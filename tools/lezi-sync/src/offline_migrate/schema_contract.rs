//! Immutable target contracts for historical offline migration paths.
//!
//! A legacy migrator must never follow the live Store schema authority: doing
//! so would silently turn an audited v3/v11→12 command into a future migration.
//! The SQL snapshot is intentionally duplicated as version history.

use std::collections::BTreeMap;
use std::path::Path;

use rusqlite::Connection;
use thiserror::Error;

use super::immutable::open_immutable;

#[derive(Debug, Clone, Copy)]
pub(crate) struct SchemaContract {
    user_version: i64,
    sql: &'static str,
}

#[derive(Debug, Error)]
pub(crate) enum SchemaContractError {
    #[error("schema access failed: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("target user_version={found}, expected legacy target {expected}")]
    WrongVersion { found: i64, expected: i64 },
    #[error("target schema shape does not match frozen legacy schema {expected}")]
    WrongShape { expected: i64 },
}

pub(crate) const LEGACY_SCHEMA_V12: SchemaContract = SchemaContract {
    user_version: 12,
    sql: include_str!("schema_v12.sql"),
};

impl SchemaContract {
    pub(crate) const fn user_version(self) -> i64 {
        self.user_version
    }

    pub(crate) const fn sql(self) -> &'static str {
        self.sql
    }

    pub(crate) fn initialize(self, connection: &Connection) -> Result<(), SchemaContractError> {
        connection.execute_batch(self.sql)?;
        connection.pragma_update(None, "user_version", self.user_version)?;
        Ok(())
    }

    pub(crate) fn validate_path(self, path: &Path) -> Result<(), SchemaContractError> {
        let connection = open_immutable(path)?;
        self.validate(&connection)
    }

    pub(crate) fn validate(self, connection: &Connection) -> Result<(), SchemaContractError> {
        let found = connection.query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))?;
        if found != self.user_version {
            return Err(SchemaContractError::WrongVersion {
                found,
                expected: self.user_version,
            });
        }
        if normalized_schema_objects(connection)? != self.expected_schema_objects()? {
            return Err(SchemaContractError::WrongShape {
                expected: self.user_version,
            });
        }
        Ok(())
    }

    fn expected_schema_objects(
        self,
    ) -> Result<BTreeMap<(String, String), String>, SchemaContractError> {
        let connection = Connection::open_in_memory()?;
        connection.execute_batch(self.sql)?;
        normalized_schema_objects(&connection)
    }
}

fn normalized_schema_objects(
    connection: &Connection,
) -> Result<BTreeMap<(String, String), String>, SchemaContractError> {
    let mut statement = connection.prepare(
        "SELECT type, name, sql
           FROM sqlite_schema
          WHERE type IN ('table', 'index', 'view', 'trigger')
            AND name NOT LIKE 'sqlite_%' AND sql IS NOT NULL
          ORDER BY type COLLATE BINARY, name COLLATE BINARY",
    )?;
    let rows = statement.query_map([], |row| {
        let sql: String = row.get(2)?;
        Ok((
            (row.get::<_, String>(0)?, row.get::<_, String>(1)?),
            sql.split_whitespace().collect::<Vec<_>>().join(" "),
        ))
    })?;
    rows.collect::<Result<_, _>>()
        .map_err(SchemaContractError::from)
}

#[cfg(test)]
mod tests {
    use super::*;

    const FUTURE_SCHEMA: SchemaContract = SchemaContract {
        user_version: 13,
        sql: "CREATE TABLE future_only(id INTEGER PRIMARY KEY);",
    };

    #[test]
    fn versioned_contracts_initialize_independently() {
        let legacy = Connection::open_in_memory().unwrap();
        LEGACY_SCHEMA_V12.initialize(&legacy).unwrap();
        let future = Connection::open_in_memory().unwrap();
        FUTURE_SCHEMA.initialize(&future).unwrap();

        assert_eq!(LEGACY_SCHEMA_V12.user_version(), 12);
        assert_eq!(FUTURE_SCHEMA.user_version(), 13);
        LEGACY_SCHEMA_V12.validate(&legacy).unwrap();
        FUTURE_SCHEMA.validate(&future).unwrap();
        assert!(matches!(
            LEGACY_SCHEMA_V12.validate(&future),
            Err(SchemaContractError::WrongVersion {
                found: 13,
                expected: 12,
            })
        ));
    }

    #[test]
    fn frozen_v12_contract_rejects_wrong_version_and_shape() {
        let connection = Connection::open_in_memory().unwrap();
        LEGACY_SCHEMA_V12.initialize(&connection).unwrap();
        LEGACY_SCHEMA_V12.validate(&connection).unwrap();

        connection.pragma_update(None, "user_version", 13).unwrap();
        assert!(matches!(
            LEGACY_SCHEMA_V12.validate(&connection),
            Err(SchemaContractError::WrongVersion {
                found: 13,
                expected: 12,
            })
        ));
        connection.pragma_update(None, "user_version", 12).unwrap();
        connection
            .execute("DROP INDEX entity_versions_root", [])
            .unwrap();
        assert!(matches!(
            LEGACY_SCHEMA_V12.validate(&connection),
            Err(SchemaContractError::WrongShape { expected: 12 })
        ));
    }
}
