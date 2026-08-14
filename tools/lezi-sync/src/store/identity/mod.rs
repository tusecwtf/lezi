//! Family identity: memberships, devices, sessions, login/rename grants.
//!
//! Partitioned by cohesive lifecycle surfaces (not thin adapters):
//! - [`session`] — create family, owner login, authenticate/refresh, device lists
//! - [`login`] — member login requests and owner grants
//! - [`membership_admin`] — rename, device-less add, hard-delete, delete family
//! - [`anonymize`] — authorship redaction for hard-delete (entity path)

mod anonymize;
mod login;
mod membership_admin;
pub(in crate::store) mod session;

use rusqlite::{params, OptionalExtension, Transaction};

use super::{CreatedDeviceSession, StoreError};

const ACCESS_TOKEN_TTL_SECONDS: i64 = 15 * 60;

pub(crate) use anonymize::anonymize_membership_authorship_fields;

fn active_device_name_conflicts(
    transaction: &Transaction<'_>,
    membership_id: &str,
    device_name_key: &str,
    excluded_device_id: Option<&str>,
) -> Result<bool, StoreError> {
    Ok(transaction
        .query_row(
            "SELECT 1 FROM devices WHERE membership_id = ?1 AND device_name_key = ?2 AND status = 'active' AND (?3 IS NULL OR device_id != ?3) LIMIT 1",
            params![membership_id, device_name_key, excluded_device_id],
            |_| Ok(()),
        )
        .optional()?
        .is_some())
}

fn replay_active_device_session<F>(
    transaction: &Transaction<'_>,
    family_id: &str,
    membership_id: &str,
    device_id: &str,
    expected_device_name: &str,
    request_hash: &str,
    derive_tokens: &F,
) -> Result<Option<CreatedDeviceSession>, StoreError>
where
    F: Fn(&str, &str, &str) -> (String, String),
{
    let stored = transaction
        .query_row(
            "
            SELECT devices.device_name, device_sessions.session_id,
                   device_sessions.access_expires_at,
                   device_sessions.access_token_hash,
                   device_sessions.refresh_token_hash, families.name
            FROM devices
            JOIN memberships ON memberships.membership_id = devices.membership_id
            JOIN families ON families.id = memberships.family_id
            JOIN device_sessions ON device_sessions.device_id = devices.device_id
            WHERE memberships.family_id = ?1
              AND memberships.membership_id = ?2
              AND memberships.left_at IS NULL
              AND devices.device_id = ?3
              AND devices.status = 'active'
              AND device_sessions.revoked_at IS NULL
            ",
            params![family_id, membership_id, device_id],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, i64>(2)?,
                    row.get::<_, String>(3)?,
                    row.get::<_, String>(4)?,
                    row.get::<_, Option<String>>(5)?,
                ))
            },
        )
        .optional()?;
    let Some((device_name, session_id, access_expires_at, access_hash, refresh_hash, family_name)) =
        stored
    else {
        return Ok(None);
    };
    if device_name != expected_device_name {
        return Ok(None);
    }
    let (access_token, refresh_token) = derive_tokens(request_hash, family_id, device_id);
    if crate::hash_secret(&access_token) != access_hash
        || crate::hash_secret(&refresh_token) != refresh_hash
    {
        return Ok(None);
    }
    Ok(Some(CreatedDeviceSession {
        family_id: family_id.to_owned(),
        membership_id: membership_id.to_owned(),
        device_id: device_id.to_owned(),
        session_id,
        access_token,
        access_expires_at,
        refresh_token,
        family_name,
        role: "member".to_owned(),
    }))
}
