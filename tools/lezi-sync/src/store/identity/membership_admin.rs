//! Membership rename, device-less add, hard-delete, and family deletion.

use rusqlite::{params, OptionalExtension, TransactionBehavior};
use uuid::Uuid;

use super::super::bundles::anonymize_membership_bundle_references;
use super::super::{PendingMemberRenameRequest, Store, StoreError};
use super::anonymize::anonymize_membership_entity_references;

impl Store {
    pub fn create_member_rename_request(
        &self,
        family_id: &str,
        membership_id: &str,
        requested_display_name: &str,
        requested_display_name_key: &str,
        now: i64,
        ttl_seconds: i64,
    ) -> Result<PendingMemberRenameRequest, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute(
        "UPDATE member_rename_requests SET status = 'expired', decided_at = ?1 WHERE family_id = ?2 AND status = 'pending' AND expires_at <= ?1",
        params![now, family_id],
    )?;
        let current_display_name = transaction
        .query_row(
            "SELECT display_name FROM memberships WHERE family_id = ?1 AND membership_id = ?2 AND role = 'member' AND left_at IS NULL",
            params![family_id, membership_id],
            |row| row.get::<_, String>(0),
        )
        .optional()?
        .ok_or(StoreError::MembershipNotFound)?;
        let conflict = transaction
        .query_row(
            "SELECT 1 FROM memberships WHERE family_id = ?1 AND membership_id != ?2 AND display_name_key = ?3 AND left_at IS NULL LIMIT 1",
            params![family_id, membership_id, requested_display_name_key],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        if conflict {
            return Err(StoreError::DisplayNameConflict);
        }
        transaction.execute(
        "UPDATE member_rename_requests SET status = 'cancelled', decided_at = ?1 WHERE membership_id = ?2 AND status = 'pending'",
        params![now, membership_id],
    )?;
        let request_id = Uuid::new_v4().to_string();
        let expires_at = now + ttl_seconds;
        transaction.execute(
            "
        INSERT INTO member_rename_requests(
            request_id, family_id, membership_id, requested_display_name,
            requested_display_name_key, status, created_at, expires_at
        ) VALUES (?1, ?2, ?3, ?4, ?5, 'pending', ?6, ?7)
        ",
            params![
                request_id,
                family_id,
                membership_id,
                requested_display_name,
                requested_display_name_key,
                now,
                expires_at,
            ],
        )?;
        transaction.commit()?;
        Ok(PendingMemberRenameRequest {
            request_id,
            membership_id: membership_id.to_owned(),
            current_display_name,
            requested_display_name: requested_display_name.to_owned(),
            created_at: now,
            expires_at,
        })
    }

    pub fn pending_member_rename_requests(
        &self,
        family_id: &str,
        now: i64,
    ) -> Result<Vec<PendingMemberRenameRequest>, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        transaction.execute(
        "UPDATE member_rename_requests SET status = 'expired', decided_at = ?1 WHERE family_id = ?2 AND status = 'pending' AND expires_at <= ?1",
        params![now, family_id],
    )?;
        let requests = {
            let mut statement = transaction.prepare(
                "
            SELECT requests.request_id, requests.membership_id,
                   memberships.display_name, requests.requested_display_name,
                   requests.created_at, requests.expires_at
            FROM member_rename_requests AS requests
            JOIN memberships
              ON memberships.membership_id = requests.membership_id
             AND memberships.family_id = requests.family_id
            WHERE requests.family_id = ?1
              AND requests.status = 'pending'
              AND requests.expires_at > ?2
              AND memberships.left_at IS NULL
            ORDER BY requests.created_at, requests.request_id COLLATE BINARY
            ",
            )?;
            let rows = statement
                .query_map(params![family_id, now], |row| {
                    Ok(PendingMemberRenameRequest {
                        request_id: row.get(0)?,
                        membership_id: row.get(1)?,
                        current_display_name: row.get(2)?,
                        requested_display_name: row.get(3)?,
                        created_at: row.get(4)?,
                        expires_at: row.get(5)?,
                    })
                })?
                .collect::<Result<Vec<_>, _>>()?;
            rows
        };
        transaction.commit()?;
        Ok(requests)
    }

    pub fn approve_member_rename_request(
        &self,
        family_id: &str,
        request_id: &str,
        now: i64,
    ) -> Result<String, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let request = transaction
            .query_row(
                "
            SELECT requests.status, requests.expires_at,
                   requests.membership_id, requests.requested_display_name,
                   requests.requested_display_name_key
            FROM member_rename_requests AS requests
            WHERE requests.family_id = ?1 AND requests.request_id = ?2
            ",
                params![family_id, request_id],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, i64>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                    ))
                },
            )
            .optional()?
            .ok_or(StoreError::RenameRequestNotFound)?;
        let (status, expires_at, membership_id, display_name, display_name_key) = request;
        if status == "expired" || (status == "pending" && expires_at <= now) {
            transaction.execute(
            "UPDATE member_rename_requests SET status = 'expired', decided_at = ?1 WHERE request_id = ?2 AND status = 'pending'",
            params![now, request_id],
        )?;
            transaction.commit()?;
            return Err(StoreError::RenameRequestExpired);
        }
        if status != "pending" {
            return Err(StoreError::RenameRequestStateConflict);
        }
        let target_exists = transaction
        .query_row(
            "SELECT 1 FROM memberships WHERE family_id = ?1 AND membership_id = ?2 AND role = 'member' AND left_at IS NULL",
            params![family_id, membership_id],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        if !target_exists {
            return Err(StoreError::MembershipNotFound);
        }
        let conflict = transaction
        .query_row(
            "SELECT 1 FROM memberships WHERE family_id = ?1 AND membership_id != ?2 AND display_name_key = ?3 AND left_at IS NULL LIMIT 1",
            params![family_id, membership_id, display_name_key],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        if conflict {
            return Err(StoreError::DisplayNameConflict);
        }
        transaction.execute(
        "UPDATE memberships SET display_name = ?1, display_name_key = ?2 WHERE membership_id = ?3",
        params![display_name, display_name_key, membership_id],
    )?;
        transaction.execute(
        "UPDATE member_rename_requests SET status = 'approved', decided_at = ?1 WHERE request_id = ?2",
        params![now, request_id],
    )?;
        transaction.commit()?;
        Ok(display_name)
    }

    pub fn reject_member_rename_request(
        &self,
        family_id: &str,
        request_id: &str,
        now: i64,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let request = transaction
        .query_row(
            "SELECT status, expires_at FROM member_rename_requests WHERE family_id = ?1 AND request_id = ?2",
            params![family_id, request_id],
            |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
        )
        .optional()?
        .ok_or(StoreError::RenameRequestNotFound)?;
        if request.0 == "expired" || (request.0 == "pending" && request.1 <= now) {
            transaction.execute(
            "UPDATE member_rename_requests SET status = 'expired', decided_at = ?1 WHERE request_id = ?2 AND status = 'pending'",
            params![now, request_id],
        )?;
            transaction.commit()?;
            return Err(StoreError::RenameRequestExpired);
        }
        if request.0 != "pending" {
            return Err(StoreError::RenameRequestStateConflict);
        }
        transaction.execute(
        "UPDATE member_rename_requests SET status = 'rejected', decided_at = ?1 WHERE request_id = ?2",
        params![now, request_id],
    )?;
        transaction.commit()?;
        Ok(())
    }

    pub fn cancel_own_member_rename_request(
        &self,
        family_id: &str,
        membership_id: &str,
        now: i64,
    ) -> Result<(), StoreError> {
        let connection = self.connect()?;
        let changed = connection.execute(
        "UPDATE member_rename_requests SET status = 'cancelled', decided_at = ?1 WHERE family_id = ?2 AND membership_id = ?3 AND status = 'pending'",
        params![now, family_id, membership_id],
    )?;
        if changed == 0 {
            return Err(StoreError::RenameRequestNotFound);
        }
        Ok(())
    }

    pub fn add_device_less_member(
        &self,
        family_id: &str,
        display_name: &str,
        display_name_key: &str,
    ) -> Result<String, StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let conflict = transaction
        .query_row(
            "SELECT 1 FROM memberships WHERE family_id = ?1 AND display_name_key = ?2 AND left_at IS NULL LIMIT 1",
            params![family_id, display_name_key],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        if conflict {
            return Err(StoreError::DisplayNameConflict);
        }
        let membership_id = Uuid::new_v4().to_string();
        transaction.execute(
        "INSERT INTO memberships(membership_id, family_id, role, display_name, display_name_key) VALUES (?1, ?2, 'member', ?3, ?4)",
        params![membership_id, family_id, display_name, display_name_key],
    )?;
        transaction.commit()?;
        Ok(membership_id)
    }

    pub fn rename_active_membership(
        &self,
        family_id: &str,
        membership_id: &str,
        display_name: &str,
        display_name_key: &str,
        now: i64,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let exists = transaction
        .query_row(
            "SELECT 1 FROM memberships WHERE family_id = ?1 AND membership_id = ?2 AND left_at IS NULL",
            params![family_id, membership_id],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        if !exists {
            return Err(StoreError::MembershipNotFound);
        }
        let conflict = transaction
        .query_row(
            "SELECT 1 FROM memberships WHERE family_id = ?1 AND membership_id != ?2 AND display_name_key = ?3 AND left_at IS NULL LIMIT 1",
            params![family_id, membership_id, display_name_key],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        if conflict {
            return Err(StoreError::DisplayNameConflict);
        }
        transaction.execute(
        "UPDATE memberships SET display_name = ?1, display_name_key = ?2 WHERE membership_id = ?3",
        params![display_name, display_name_key, membership_id],
    )?;
        transaction.execute(
        "UPDATE member_rename_requests SET status = 'cancelled', decided_at = ?1 WHERE membership_id = ?2 AND status = 'pending'",
        params![now, membership_id],
    )?;
        transaction.commit()?;
        Ok(())
    }

    pub fn rename_active_device(
        &self,
        family_id: &str,
        actor_membership_id: &str,
        actor_is_owner: bool,
        device_id: &str,
        device_name: &str,
        device_name_key: &str,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let target_membership_id = transaction
            .query_row(
                "
            SELECT devices.membership_id
            FROM devices
            JOIN memberships ON memberships.membership_id = devices.membership_id
            WHERE memberships.family_id = ?1
              AND memberships.left_at IS NULL
              AND devices.device_id = ?2
              AND devices.status = 'active'
            ",
                params![family_id, device_id],
                |row| row.get::<_, String>(0),
            )
            .optional()?
            .ok_or(StoreError::DeviceNotFound)?;
        if !actor_is_owner && target_membership_id != actor_membership_id {
            return Err(StoreError::DeviceNotFound);
        }
        let conflict = transaction
        .query_row(
            "SELECT 1 FROM devices WHERE membership_id = ?1 AND device_id != ?2 AND device_name_key = ?3 AND status = 'active' LIMIT 1",
            params![target_membership_id, device_id, device_name_key],
            |_| Ok(()),
        )
        .optional()?
        .is_some();
        if conflict {
            return Err(StoreError::DeviceNameConflict);
        }
        transaction.execute(
            "UPDATE devices SET device_name = ?1, device_name_key = ?2 WHERE device_id = ?3",
            params![device_name, device_name_key, device_id],
        )?;
        transaction.commit()?;
        Ok(())
    }

    /// Hard-deletes one ordinary membership while retaining shared family facts.
    /// Exact credential hashes are copied to a non-identifying terminal-denial set
    /// before the identity tree is cascaded away, so an offline device can still
    /// learn the stable protocol reason without a recoverable member tombstone.
    pub fn hard_delete_membership(
        &self,
        family_id: &str,
        membership_id: &str,
        now: i64,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let role = transaction
            .query_row(
                "
            SELECT role
            FROM memberships
            WHERE family_id = ?1 AND membership_id = ?2 AND left_at IS NULL
            ",
                params![family_id, membership_id],
                |row| row.get::<_, String>(0),
            )
            .optional()?;
        if role.as_deref() != Some("member") {
            return Err(StoreError::MembershipNotFound);
        }

        transaction.execute(
            "
        INSERT OR REPLACE INTO terminal_credential_denials(token_hash, token_kind, reason)
        SELECT device_sessions.access_token_hash, 'access', 'membership_deleted'
        FROM device_sessions
        JOIN devices ON devices.device_id = device_sessions.device_id
        WHERE devices.membership_id = ?1
        ",
            params![membership_id],
        )?;
        transaction.execute(
            "
        INSERT OR REPLACE INTO terminal_credential_denials(token_hash, token_kind, reason)
        SELECT device_sessions.refresh_token_hash, 'refresh', 'membership_deleted'
        FROM device_sessions
        JOIN devices ON devices.device_id = device_sessions.device_id
        WHERE devices.membership_id = ?1
        ",
            params![membership_id],
        )?;
        transaction.execute(
            "
        INSERT OR REPLACE INTO terminal_credential_denials(token_hash, token_kind, reason)
        SELECT refresh_token_history.token_hash, 'refresh', 'membership_deleted'
        FROM refresh_token_history
        JOIN device_sessions
          ON device_sessions.session_id = refresh_token_history.session_id
        JOIN devices ON devices.device_id = device_sessions.device_id
        WHERE devices.membership_id = ?1
        ",
            params![membership_id],
        )?;

        anonymize_membership_entity_references(&transaction, family_id, membership_id, now)?;
        anonymize_membership_bundle_references(&transaction, family_id, membership_id)?;

        // ON DELETE SET NULL would retain request display/device names. Remove
        // requests that had already been bound to this identity first instead.
        transaction.execute(
            "DELETE FROM member_login_requests WHERE membership_id = ?1",
            params![membership_id],
        )?;
        let deleted = transaction.execute(
            "
        DELETE FROM memberships
        WHERE family_id = ?1 AND membership_id = ?2 AND role = 'member'
        ",
            params![family_id, membership_id],
        )?;
        if deleted != 1 {
            return Err(StoreError::MembershipNotFound);
        }
        transaction.commit()?;
        self.invalidate_auth_cache();
        Ok(())
    }

    pub fn delete_family(
        &self,
        family_id: &str,
        confirmed_family_name: &str,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let stored_family_name = transaction
            .query_row(
                "SELECT name FROM families WHERE id = ?1",
                params![family_id],
                |row| row.get::<_, Option<String>>(0),
            )
            .optional()?
            .flatten()
            .ok_or(StoreError::FamilyNotConfigured)?;
        if stored_family_name != confirmed_family_name {
            return Err(StoreError::FamilyNameMismatch);
        }

        for (token_kind, column) in [
            ("access", "access_token_hash"),
            ("refresh", "refresh_token_hash"),
        ] {
            transaction.execute(
                &format!(
                    "
                INSERT OR REPLACE INTO terminal_credential_denials(token_hash, token_kind, reason)
                SELECT device_sessions.{column}, ?2, 'family_deleted'
                FROM device_sessions
                JOIN devices ON devices.device_id = device_sessions.device_id
                JOIN memberships ON memberships.membership_id = devices.membership_id
                WHERE memberships.family_id = ?1
                "
                ),
                params![family_id, token_kind],
            )?;
        }
        transaction.execute(
            "
        INSERT OR REPLACE INTO terminal_credential_denials(token_hash, token_kind, reason)
        SELECT refresh_token_history.token_hash, 'refresh', 'family_deleted'
        FROM refresh_token_history
        JOIN device_sessions
          ON device_sessions.session_id = refresh_token_history.session_id
        JOIN devices ON devices.device_id = device_sessions.device_id
        JOIN memberships ON memberships.membership_id = devices.membership_id
        WHERE memberships.family_id = ?1
        ",
            params![family_id],
        )?;
        let deleted =
            transaction.execute("DELETE FROM families WHERE id = ?1", params![family_id])?;
        if deleted != 1 {
            return Err(StoreError::FamilyNotConfigured);
        }
        transaction.commit()?;
        self.invalidate_auth_cache();
        Ok(())
    }
}
