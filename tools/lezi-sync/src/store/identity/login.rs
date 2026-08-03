//! Member login requests and owner-issued login grants.

use rusqlite::{params, OptionalExtension, TransactionBehavior};
use uuid::Uuid;

use crate::model::normalized_device_name_key;

use super::super::{
    CreateMemberLoginRequestInput, CreatedDeviceSession, CreatedMemberLoginGrant,
    PendingMemberLoginRequest, Store, StoreError,
};
use super::{active_device_name_conflicts, replay_active_device_session, ACCESS_TOKEN_TTL_SECONDS};

impl Store {
    pub fn create_member_login_request(
        &self,
        input: CreateMemberLoginRequestInput<'_>,
    ) -> Result<PendingMemberLoginRequest, StoreError> {
        let CreateMemberLoginRequestInput {
            now,
            ttl_seconds,
            max_pending,
            display_name,
            display_name_key,
            device_name,
            pending_secret,
        } = input;
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let family_id = transaction
            .query_row("SELECT id FROM families LIMIT 1", [], |row| {
                row.get::<_, String>(0)
            })
            .optional()?
            .ok_or(StoreError::FamilyNotConfigured)?;
        transaction.execute(
        "UPDATE member_login_requests SET status = 'expired' WHERE family_id = ?1 AND status IN ('pending', 'approved') AND expires_at <= ?2",
        params![family_id, now],
    )?;
        let open_request_count = transaction.query_row(
        "SELECT COUNT(*) FROM member_login_requests WHERE family_id = ?1 AND status IN ('pending', 'approved') AND expires_at > ?2",
        params![family_id, now],
        |row| row.get::<_, i64>(0),
    )?;
        if open_request_count >= i64::try_from(max_pending).unwrap_or(i64::MAX) {
            return Err(StoreError::MemberRequestLimit);
        }
        let request_id = Uuid::new_v4().to_string();
        let expires_at = now + ttl_seconds;
        transaction.execute(
            "
        INSERT INTO member_login_requests(
            request_id, family_id, pending_secret_hash, display_name,
            display_name_key, device_name, status, created_at, expires_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'pending', ?7, ?8)
        ",
            params![
                request_id,
                family_id,
                crate::hash_secret(pending_secret),
                display_name,
                display_name_key,
                device_name,
                now,
                expires_at,
            ],
        )?;
        transaction.commit()?;
        Ok(PendingMemberLoginRequest {
            request_id,
            display_name: display_name.to_owned(),
            device_name: device_name.to_owned(),
            status: "pending".to_owned(),
            created_at: now,
            expires_at,
        })
    }

    pub fn member_login_request_status(
        &self,
        pending_secret: &str,
        now: i64,
    ) -> Result<String, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let secret_hash = crate::hash_secret(pending_secret);
        let (mut status, expires_at) = transaction
        .query_row(
            "SELECT status, expires_at FROM member_login_requests WHERE pending_secret_hash = ?1",
            params![secret_hash],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
        )
        .optional()?
        .ok_or(StoreError::MemberRequestNotFound)?;
        if matches!(status.as_str(), "pending" | "approved") && expires_at <= now {
            transaction.execute(
            "UPDATE member_login_requests SET status = 'expired' WHERE pending_secret_hash = ?1",
            params![secret_hash],
        )?;
            status = "expired".to_owned();
        }
        transaction.commit()?;
        Ok(status)
    }

    pub fn cancel_member_login_request(
        &self,
        pending_secret: &str,
        now: i64,
    ) -> Result<String, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let secret_hash = crate::hash_secret(pending_secret);
        let (status, expires_at) = transaction
        .query_row(
            "SELECT status, expires_at FROM member_login_requests WHERE pending_secret_hash = ?1",
            params![secret_hash],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
        )
        .optional()?
        .ok_or(StoreError::MemberRequestNotFound)?;
        if matches!(status.as_str(), "pending" | "approved") && expires_at <= now {
            transaction.execute(
            "UPDATE member_login_requests SET status = 'expired' WHERE pending_secret_hash = ?1",
            params![secret_hash],
        )?;
            transaction.commit()?;
            return Err(StoreError::MemberRequestExpired);
        }
        if status == "expired" {
            return Err(StoreError::MemberRequestExpired);
        }
        match status.as_str() {
            "pending" => {
                transaction.execute(
                "UPDATE member_login_requests SET status = 'cancelled', decided_at = ?1 WHERE pending_secret_hash = ?2",
                params![now, secret_hash],
            )?;
                transaction.commit()?;
                Ok("cancelled".to_owned())
            }
            "cancelled" => Ok(status),
            _ => Err(StoreError::MemberRequestStateConflict),
        }
    }

    /// Legacy compatibility name for the Owner open-request store view.
    /// The result contains only unexpired pending and approved-but-unclaimed rows.
    pub fn pending_member_login_requests(
        &self,
        family_id: &str,
        now: i64,
    ) -> Result<Vec<PendingMemberLoginRequest>, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute(
        "UPDATE member_login_requests SET status = 'expired' WHERE family_id = ?1 AND status IN ('pending', 'approved') AND expires_at <= ?2",
        params![family_id, now],
    )?;
        let rows = {
            let mut statement = transaction.prepare(
                "
            SELECT request_id, display_name, device_name, status, created_at, expires_at
            FROM member_login_requests
            WHERE family_id = ?1
              AND status IN ('pending', 'approved')
              AND expires_at > ?2
            ORDER BY created_at, request_id COLLATE BINARY
            ",
            )?;
            let requests = statement
                .query_map(params![family_id, now], |row| {
                    Ok(PendingMemberLoginRequest {
                        request_id: row.get(0)?,
                        display_name: row.get(1)?,
                        device_name: row.get(2)?,
                        status: row.get(3)?,
                        created_at: row.get(4)?,
                        expires_at: row.get(5)?,
                    })
                })?
                .collect::<Result<Vec<_>, _>>()?;
            requests
        };
        transaction.commit()?;
        Ok(rows)
    }

    pub fn approve_new_member_login_request(
        &self,
        family_id: &str,
        request_id: &str,
        now: i64,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let (status, expires_at, display_name_key, approval_kind) = transaction
        .query_row(
            "SELECT status, expires_at, display_name_key, approval_kind FROM member_login_requests WHERE family_id = ?1 AND request_id = ?2",
            params![family_id, request_id],
            |row| Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<String>>(3)?,
            )),
        )
        .optional()?
        .ok_or(StoreError::MemberRequestNotFound)?;
        if expires_at <= now && matches!(status.as_str(), "pending" | "approved") {
            transaction.execute(
                "UPDATE member_login_requests SET status = 'expired' WHERE request_id = ?1",
                params![request_id],
            )?;
            transaction.commit()?;
            return Err(StoreError::MemberRequestExpired);
        }
        if status == "expired" {
            return Err(StoreError::MemberRequestExpired);
        }
        if status == "approved" {
            return if approval_kind.as_deref() == Some("new") {
                Ok(())
            } else {
                Err(StoreError::MemberRequestStateConflict)
            };
        }
        if status != "pending" {
            return Err(StoreError::MemberRequestStateConflict);
        }
        let name_exists = transaction
        .query_row(
            "SELECT 1 FROM memberships WHERE family_id = ?1 AND display_name_key = ?2 AND left_at IS NULL LIMIT 1",
            params![family_id, display_name_key],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        let name_reserved = transaction
        .query_row(
            "SELECT 1 FROM member_login_requests WHERE family_id = ?1 AND request_id != ?2 AND display_name_key = ?3 AND status = 'approved' AND expires_at > ?4 LIMIT 1",
            params![family_id, request_id, display_name_key, now],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        if name_exists || name_reserved {
            return Err(StoreError::DisplayNameConflict);
        }
        transaction.execute(
        "UPDATE member_login_requests SET status = 'approved', decided_at = ?1, approval_kind = 'new' WHERE request_id = ?2",
        params![now, request_id],
    )?;
        transaction.commit()?;
        Ok(())
    }

    pub fn bind_existing_member_login_request(
        &self,
        family_id: &str,
        request_id: &str,
        membership_id: &str,
        now: i64,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let (status, expires_at, approval_kind, current_target) = transaction
        .query_row(
            "SELECT status, expires_at, approval_kind, membership_id FROM member_login_requests WHERE family_id = ?1 AND request_id = ?2",
            params![family_id, request_id],
            |row| Ok((
                row.get::<_, String>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, Option<String>>(2)?,
                row.get::<_, Option<String>>(3)?,
            )),
        )
        .optional()?
        .ok_or(StoreError::MemberRequestNotFound)?;
        if expires_at <= now && matches!(status.as_str(), "pending" | "approved") {
            transaction.execute(
                "UPDATE member_login_requests SET status = 'expired' WHERE request_id = ?1",
                params![request_id],
            )?;
            transaction.commit()?;
            return Err(StoreError::MemberRequestExpired);
        }
        if status == "expired" {
            return Err(StoreError::MemberRequestExpired);
        }
        if status == "approved" {
            return if approval_kind.as_deref() == Some("existing")
                && current_target.as_deref() == Some(membership_id)
            {
                Ok(())
            } else {
                Err(StoreError::MemberRequestStateConflict)
            };
        }
        if status != "pending" {
            return Err(StoreError::MemberRequestStateConflict);
        }
        let target_is_active_member = transaction
        .query_row(
            "SELECT role FROM memberships WHERE family_id = ?1 AND membership_id = ?2 AND left_at IS NULL",
            params![family_id, membership_id],
            |row| row.get::<_, String>(0),
        )
        .optional()?
        .is_some_and(|role| role == "member");
        if !target_is_active_member {
            return Err(StoreError::MembershipNotFound);
        }
        transaction.execute(
        "UPDATE member_login_requests SET status = 'approved', decided_at = ?1, approval_kind = 'existing', membership_id = ?2 WHERE request_id = ?3",
        params![now, membership_id, request_id],
    )?;
        transaction.commit()?;
        Ok(())
    }

    pub fn reject_member_login_request(
        &self,
        family_id: &str,
        request_id: &str,
        now: i64,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let (status, expires_at) = transaction
        .query_row(
            "SELECT status, expires_at FROM member_login_requests WHERE family_id = ?1 AND request_id = ?2",
            params![family_id, request_id],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
        )
        .optional()?
        .ok_or(StoreError::MemberRequestNotFound)?;
        if expires_at <= now && matches!(status.as_str(), "pending" | "approved") {
            transaction.execute(
                "UPDATE member_login_requests SET status = 'expired' WHERE request_id = ?1",
                params![request_id],
            )?;
            transaction.commit()?;
            return Err(StoreError::MemberRequestExpired);
        }
        if status == "expired" {
            return Err(StoreError::MemberRequestExpired);
        }
        match status.as_str() {
            "pending" | "approved" => {
                transaction.execute(
                "UPDATE member_login_requests SET status = 'rejected', decided_at = ?1 WHERE request_id = ?2",
                params![now, request_id],
            )?;
                transaction.commit()?;
                Ok(())
            }
            "rejected" => Ok(()),
            _ => Err(StoreError::MemberRequestStateConflict),
        }
    }

    pub fn claim_member_login_request<F>(
        &self,
        pending_secret: &str,
        now: i64,
        derive_tokens: F,
    ) -> Result<CreatedDeviceSession, StoreError>
    where
        F: Fn(&str, &str, &str) -> (String, String),
    {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let secret_hash = crate::hash_secret(pending_secret);
        let request = transaction
            .query_row(
                "
            SELECT requests.status, requests.expires_at, requests.family_id,
                   requests.display_name, requests.display_name_key,
                   requests.device_name, families.name,
                   requests.approval_kind, requests.membership_id,
                   requests.device_id
            FROM member_login_requests AS requests
            JOIN families ON families.id = requests.family_id
            WHERE requests.pending_secret_hash = ?1
            ",
                params![secret_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                        row.get::<_, String>(5)?,
                        row.get::<_, Option<String>>(6)?,
                        row.get::<_, Option<String>>(7)?,
                        row.get::<_, Option<String>>(8)?,
                        row.get::<_, Option<String>>(9)?,
                    ))
                },
            )
            .optional()?
            .ok_or(StoreError::MemberRequestNotFound)?;
        let (
            status,
            expires_at,
            family_id,
            display_name,
            display_name_key,
            device_name,
            family_name,
            approval_kind,
            approved_membership_id,
            claimed_device_id,
        ) = request;
        if expires_at <= now && matches!(status.as_str(), "pending" | "approved") {
            transaction.execute(
            "UPDATE member_login_requests SET status = 'expired' WHERE pending_secret_hash = ?1",
            params![secret_hash],
        )?;
            transaction.commit()?;
            return Err(StoreError::MemberRequestExpired);
        }
        if status == "expired" {
            return Err(StoreError::MemberRequestExpired);
        }
        if status == "claimed" {
            let membership_id = approved_membership_id
                .as_deref()
                .ok_or(StoreError::MemberRequestStateConflict)?;
            let device_id = claimed_device_id
                .as_deref()
                .ok_or(StoreError::MemberRequestStateConflict)?;
            return replay_active_device_session(
                &transaction,
                &family_id,
                membership_id,
                device_id,
                &device_name,
                &secret_hash,
                &derive_tokens,
            )?
            .ok_or(StoreError::MemberRequestStateConflict);
        }
        if status != "approved" {
            return Err(StoreError::MemberRequestStateConflict);
        }
        let membership_id = if approval_kind.as_deref() == Some("existing") {
            let membership_id = approved_membership_id.ok_or(StoreError::MembershipNotFound)?;
            let target_is_active_member = transaction
            .query_row(
                "SELECT role FROM memberships WHERE family_id = ?1 AND membership_id = ?2 AND left_at IS NULL",
                params![family_id, membership_id],
                |row| row.get::<_, String>(0),
            )
            .optional()?
            .is_some_and(|role| role == "member");
            if !target_is_active_member {
                return Err(StoreError::MembershipNotFound);
            }
            membership_id
        } else if approval_kind.as_deref() == Some("new") {
            if transaction
            .query_row(
                "SELECT 1 FROM memberships WHERE family_id = ?1 AND display_name_key = ?2 AND left_at IS NULL LIMIT 1",
                params![family_id, display_name_key],
                |_| Ok(()),
            )
            .optional()?
            .is_some()
        {
            return Err(StoreError::DisplayNameConflict);
        }
            let membership_id = Uuid::new_v4().to_string();
            transaction.execute(
            "INSERT INTO memberships(membership_id, family_id, role, display_name, display_name_key) VALUES (?1, ?2, 'member', ?3, ?4)",
            params![membership_id, family_id, display_name, display_name_key],
        )?;
            membership_id
        } else {
            return Err(StoreError::MemberRequestStateConflict);
        };
        if active_device_name_conflicts(
            &transaction,
            &membership_id,
            &normalized_device_name_key(&device_name),
            None,
        )? {
            return Err(StoreError::DeviceNameConflict);
        }
        let device_id = Uuid::new_v4().to_string();
        let session_id = Uuid::new_v4().to_string();
        let access_expires_at = now + ACCESS_TOKEN_TTL_SECONDS;
        let (access_token, refresh_token) = derive_tokens(&secret_hash, &family_id, &device_id);
        transaction.execute(
        "INSERT INTO devices(device_id, membership_id, device_name, device_name_key, status, created_at, last_used_at) VALUES (?1, ?2, ?3, ?4, 'active', ?5, ?5)",
        params![device_id, membership_id, device_name, normalized_device_name_key(&device_name), now],
    )?;
        transaction.execute(
        "INSERT INTO device_sessions(session_id, device_id, access_token_hash, access_expires_at, refresh_token_hash) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            session_id,
            device_id,
            crate::hash_secret(&access_token),
            access_expires_at,
            crate::hash_secret(&refresh_token),
        ],
    )?;
        transaction.execute(
        "UPDATE member_login_requests SET status = 'claimed', claimed_at = ?1, membership_id = ?2, device_id = ?3 WHERE pending_secret_hash = ?4",
        params![now, membership_id, device_id, secret_hash],
    )?;
        transaction.commit()?;
        Ok(CreatedDeviceSession {
            family_id,
            membership_id,
            device_id,
            session_id,
            access_token,
            access_expires_at,
            refresh_token,
            family_name,
        })
    }

    pub fn create_member_login_grant(
        &self,
        family_id: &str,
        membership_id: &str,
        grant: &str,
        now: i64,
        ttl_seconds: i64,
    ) -> Result<CreatedMemberLoginGrant, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let target = transaction
            .query_row(
                "
            SELECT memberships.role, memberships.display_name, families.name
            FROM memberships
            JOIN families ON families.id = memberships.family_id
            WHERE memberships.family_id = ?1
              AND memberships.membership_id = ?2
              AND memberships.left_at IS NULL
            ",
                params![family_id, membership_id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, Option<String>>(2)?,
                    ))
                },
            )
            .optional()?
            .filter(|(role, _, _)| role == "member")
            .ok_or(StoreError::MembershipNotFound)?;
        let expires_at = now + ttl_seconds;
        transaction.execute(
            "
        INSERT INTO member_login_grants(
            grant_hash, family_id, membership_id, created_at, expires_at
        ) VALUES (?1, ?2, ?3, ?4, ?5)
        ",
            params![
                crate::hash_secret(grant),
                family_id,
                membership_id,
                now,
                expires_at,
            ],
        )?;
        transaction.commit()?;
        Ok(CreatedMemberLoginGrant {
            family_name: target.2,
            member_display_name: target.1,
            expires_at,
        })
    }

    pub fn claim_member_login_grant<F>(
        &self,
        grant: &str,
        device_name: &str,
        now: i64,
        derive_tokens: F,
    ) -> Result<CreatedDeviceSession, StoreError>
    where
        F: Fn(&str, &str, &str) -> (String, String),
    {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let grant_hash = crate::hash_secret(grant);
        let stored = transaction
            .query_row(
                "
            SELECT grants.family_id, grants.membership_id, grants.expires_at,
                   grants.used_at, families.name, grants.claimed_device_id
            FROM member_login_grants AS grants
            JOIN families ON families.id = grants.family_id
            WHERE grants.grant_hash = ?1
            ",
                params![grant_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, Option<i64>>(3)?,
                        row.get::<_, Option<String>>(4)?,
                        row.get::<_, Option<String>>(5)?,
                    ))
                },
            )
            .optional()?
            .ok_or(StoreError::MemberLoginGrantNotFound)?;
        let (family_id, membership_id, expires_at, used_at, family_name, claimed_device_id) =
            stored;
        if used_at.is_some() {
            let Some(device_id) = claimed_device_id.as_deref() else {
                return Err(StoreError::MemberLoginGrantAlreadyUsed);
            };
            return replay_active_device_session(
                &transaction,
                &family_id,
                &membership_id,
                device_id,
                device_name,
                &grant_hash,
                &derive_tokens,
            )?
            .ok_or(StoreError::MemberLoginGrantAlreadyUsed);
        }
        if expires_at <= now {
            return Err(StoreError::MemberLoginGrantExpired);
        }
        let target_is_active_member = transaction
            .query_row(
                "
            SELECT role FROM memberships
            WHERE family_id = ?1 AND membership_id = ?2 AND left_at IS NULL
            ",
                params![family_id, membership_id],
                |row| row.get::<_, String>(0),
            )
            .optional()?
            .is_some_and(|role| role == "member");
        if !target_is_active_member {
            return Err(StoreError::MemberLoginGrantNotFound);
        }
        if active_device_name_conflicts(
            &transaction,
            &membership_id,
            &normalized_device_name_key(device_name),
            None,
        )? {
            return Err(StoreError::DeviceNameConflict);
        }
        let device_id = Uuid::new_v4().to_string();
        let session_id = Uuid::new_v4().to_string();
        let access_expires_at = now + ACCESS_TOKEN_TTL_SECONDS;
        let (access_token, refresh_token) = derive_tokens(&grant_hash, &family_id, &device_id);
        transaction.execute(
        "INSERT INTO devices(device_id, membership_id, device_name, device_name_key, status, created_at, last_used_at) VALUES (?1, ?2, ?3, ?4, 'active', ?5, ?5)",
        params![device_id, membership_id, device_name, normalized_device_name_key(device_name), now],
    )?;
        transaction.execute(
        "INSERT INTO device_sessions(session_id, device_id, access_token_hash, access_expires_at, refresh_token_hash) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            session_id,
            device_id,
            crate::hash_secret(&access_token),
            access_expires_at,
            crate::hash_secret(&refresh_token),
        ],
    )?;
        transaction.execute(
        "UPDATE member_login_grants SET used_at = ?1, claimed_device_id = ?2 WHERE grant_hash = ?3 AND used_at IS NULL",
        params![now, device_id, grant_hash],
    )?;
        transaction.commit()?;
        Ok(CreatedDeviceSession {
            family_id,
            membership_id,
            device_id,
            session_id,
            access_token,
            access_expires_at,
            refresh_token,
            family_name,
        })
    }
}
