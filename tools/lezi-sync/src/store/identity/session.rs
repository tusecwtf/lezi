//! Create-family, owner login, sessions, and device listings.

use rusqlite::{params, OptionalExtension, TransactionBehavior};
use uuid::Uuid;

use crate::model::normalized_device_name_key;

use super::super::{
    ActiveDevice, ActiveMembership, CreateFamilyInput, CreatedDeviceSession, Principal, Store,
    StoreError,
};
use super::{active_device_name_conflicts, ACCESS_TOKEN_TTL_SECONDS};

impl Store {
    pub fn create_family<F>(
        &self,
        input: CreateFamilyInput<'_>,
        derive_tokens: F,
    ) -> Result<CreatedDeviceSession, StoreError>
    where
        F: Fn(&str, &str, &str) -> (String, String),
    {
        let CreateFamilyInput {
            now,
            create_request_id,
            display_name,
            display_name_key,
            family_name,
            device_name,
            owner_root_fingerprint,
        } = input;
        let create_request_hash = crate::hash_secret(create_request_id);
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let retries = {
            let mut statement = transaction.prepare(
                "
            SELECT families.id, families.name, memberships.display_name,
                   memberships.membership_id, devices.device_id,
                   devices.device_name, device_sessions.session_id,
                   device_sessions.access_expires_at,
                   device_sessions.access_token_hash,
                   device_sessions.refresh_token_hash
            FROM families
            JOIN memberships
              ON memberships.family_id = families.id
             AND memberships.role = 'owner'
             AND memberships.left_at IS NULL
            JOIN devices ON devices.membership_id = memberships.membership_id
              AND devices.status = 'active'
            JOIN device_sessions ON device_sessions.device_id = devices.device_id
              AND device_sessions.revoked_at IS NULL
            WHERE families.create_request_hash = ?1
            ",
            )?;
            let rows = statement
                .query_map(params![create_request_hash], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                        row.get::<_, String>(5)?,
                        row.get::<_, String>(6)?,
                        row.get::<_, i64>(7)?,
                        row.get::<_, String>(8)?,
                        row.get::<_, String>(9)?,
                    ))
                })?
                .collect::<Result<Vec<_>, _>>()?;
            rows
        };
        if !retries.is_empty() {
            for (
                family_id,
                stored_family_name,
                stored_name,
                membership_id,
                device_id,
                stored_device_name,
                session_id,
                access_expires_at,
                stored_access_hash,
                stored_refresh_hash,
            ) in retries
            {
                if stored_name != display_name
                    || stored_family_name.as_deref() != Some(family_name)
                    || stored_device_name != device_name
                {
                    continue;
                }
                let (access_token, refresh_token) =
                    derive_tokens(&create_request_hash, &family_id, &device_id);
                if crate::hash_secret(&access_token) == stored_access_hash
                    && crate::hash_secret(&refresh_token) == stored_refresh_hash
                {
                    return Ok(CreatedDeviceSession {
                        family_id,
                        membership_id,
                        device_id,
                        session_id,
                        access_token,
                        access_expires_at,
                        refresh_token,
                        family_name: stored_family_name,
                    });
                }
            }
            return Err(StoreError::FamilyAlreadyExists);
        }

        if transaction
            .query_row("SELECT 1 FROM families LIMIT 1", [], |_| Ok(()))
            .optional()?
            .is_some()
        {
            return Err(StoreError::FamilyAlreadyExists);
        }

        let family_id = Uuid::new_v4().to_string();
        let membership_id = Uuid::new_v4().to_string();
        let device_id = Uuid::new_v4().to_string();
        let session_id = Uuid::new_v4().to_string();
        let access_expires_at = now + ACCESS_TOKEN_TTL_SECONDS;
        let (access_token, refresh_token) =
            derive_tokens(&create_request_hash, &family_id, &device_id);
        transaction.execute(
            "
        INSERT INTO families(
            id, created_at, create_request_hash, name, owner_root_fingerprint
        ) VALUES (?1, ?2, ?3, ?4, ?5)
        ",
            params![
                family_id,
                now,
                create_request_hash,
                family_name,
                owner_root_fingerprint,
            ],
        )?;
        transaction.execute(
            "INSERT INTO family_meta(family_id, rev) VALUES (?1, 0)",
            params![family_id],
        )?;
        transaction.execute(
            "
        INSERT INTO memberships(
            membership_id, family_id, role, display_name, display_name_key
        ) VALUES (?1, ?2, 'owner', ?3, ?4)
        ",
            params![membership_id, family_id, display_name, display_name_key],
        )?;
        transaction.execute(
            "
        INSERT INTO devices(
            device_id, membership_id, device_name, device_name_key,
            status, created_at, last_used_at
        ) VALUES (?1, ?2, ?3, ?4, 'active', ?5, ?5)
        ",
            params![
                device_id,
                membership_id,
                device_name,
                normalized_device_name_key(device_name),
                now,
            ],
        )?;
        transaction.execute(
            "
        INSERT INTO device_sessions(
            session_id, device_id, access_token_hash, access_expires_at, refresh_token_hash
        ) VALUES (?1, ?2, ?3, ?4, ?5)
        ",
            params![
                session_id,
                device_id,
                crate::hash_secret(&access_token),
                access_expires_at,
                crate::hash_secret(&refresh_token),
            ],
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
            family_name: Some(family_name.to_owned()),
        })
    }

    /// Reconciles the deployment-owned root password at startup. A changed
    /// keyed fingerprint revokes only Owner devices; member sessions survive.
    pub fn reconcile_owner_root_fingerprint(
        &self,
        now: i64,
        current_fingerprint: Option<&str>,
    ) -> Result<(), StoreError> {
        let Some(current_fingerprint) = current_fingerprint else {
            return Ok(());
        };
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let family = transaction
            .query_row(
                "SELECT id, owner_root_fingerprint FROM families LIMIT 1",
                [],
                |row| Ok((row.get::<_, String>(0)?, row.get::<_, Option<String>>(1)?)),
            )
            .optional()?;
        let Some((family_id, stored_fingerprint)) = family else {
            return Ok(());
        };
        if stored_fingerprint.as_deref() == Some(current_fingerprint) {
            return Ok(());
        }
        transaction.execute(
            "
        UPDATE device_sessions
        SET revoked_at = COALESCE(revoked_at, ?1),
            revoked_reason = COALESCE(revoked_reason, 'root_password_rotated')
        WHERE device_id IN (
            SELECT devices.device_id
            FROM devices
            JOIN memberships ON memberships.membership_id = devices.membership_id
            WHERE memberships.family_id = ?2 AND memberships.role = 'owner'
        )
        ",
            params![now, family_id],
        )?;
        transaction.execute(
            "
        UPDATE devices
        SET status = 'revoked', last_used_at = ?1
        WHERE membership_id IN (
            SELECT membership_id FROM memberships
            WHERE family_id = ?2 AND role = 'owner'
        )
        ",
            params![now, family_id],
        )?;
        transaction.execute(
            "UPDATE families SET owner_root_fingerprint = ?1 WHERE id = ?2",
            params![current_fingerprint, family_id],
        )?;
        transaction.commit()?;
        Ok(())
    }

    /// Adds a Device to the unique Owner membership, or atomically takes over
    /// by revoking all older Owner devices before issuing the new session.
    pub fn owner_login<F>(
        &self,
        now: i64,
        login_request_id: &str,
        device_name: &str,
        takeover: bool,
        derive_tokens: F,
    ) -> Result<CreatedDeviceSession, StoreError>
    where
        F: Fn(&str, &str, &str) -> (String, String),
    {
        let request_hash = crate::hash_secret(login_request_id);
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let retry = transaction
            .query_row(
                "
            SELECT families.id, families.name, memberships.membership_id,
                   devices.device_id, devices.device_name, devices.status,
                   device_sessions.session_id, device_sessions.access_expires_at,
                   device_sessions.access_token_hash, device_sessions.refresh_token_hash,
                   device_sessions.revoked_at, owner_login_requests.takeover
            FROM owner_login_requests
            JOIN devices ON devices.device_id = owner_login_requests.device_id
            JOIN memberships ON memberships.membership_id = devices.membership_id
            JOIN families ON families.id = memberships.family_id
            JOIN device_sessions ON device_sessions.device_id = devices.device_id
            WHERE owner_login_requests.request_hash = ?1
            LIMIT 1
            ",
                params![request_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                        row.get::<_, String>(5)?,
                        row.get::<_, String>(6)?,
                        row.get::<_, i64>(7)?,
                        row.get::<_, String>(8)?,
                        row.get::<_, String>(9)?,
                        row.get::<_, Option<i64>>(10)?,
                        row.get::<_, bool>(11)?,
                    ))
                },
            )
            .optional()?;
        if let Some((
            family_id,
            family_name,
            membership_id,
            device_id,
            stored_device_name,
            device_status,
            session_id,
            access_expires_at,
            stored_access_hash,
            stored_refresh_hash,
            revoked_at,
            stored_takeover,
        )) = retry
        {
            let (access_token, refresh_token) =
                derive_tokens(&request_hash, &family_id, &device_id);
            if stored_device_name != device_name
                || stored_takeover != takeover
                || device_status != "active"
                || revoked_at.is_some()
                || crate::hash_secret(&access_token) != stored_access_hash
                || crate::hash_secret(&refresh_token) != stored_refresh_hash
            {
                return Err(StoreError::OwnerLoginRequestConflict);
            }
            return Ok(CreatedDeviceSession {
                family_id,
                membership_id,
                device_id,
                session_id,
                access_token,
                access_expires_at,
                refresh_token,
                family_name,
            });
        }

        let (family_id, family_name, membership_id) = transaction
            .query_row(
                "
            SELECT families.id, families.name, memberships.membership_id
            FROM families
            JOIN memberships ON memberships.family_id = families.id
            WHERE memberships.role = 'owner' AND memberships.left_at IS NULL
            LIMIT 1
            ",
                [],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, String>(2)?,
                    ))
                },
            )
            .optional()?
            .ok_or(StoreError::FamilyNotConfigured)?;
        if takeover {
            transaction.execute(
                "
            UPDATE device_sessions
            SET revoked_at = COALESCE(revoked_at, ?1),
                revoked_reason = COALESCE(revoked_reason, 'owner_takeover')
            WHERE device_id IN (
                SELECT device_id FROM devices WHERE membership_id = ?2
            )
            ",
                params![now, membership_id],
            )?;
            transaction.execute(
                "
            UPDATE devices SET status = 'revoked', last_used_at = ?1
            WHERE membership_id = ?2
            ",
                params![now, membership_id],
            )?;
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
        let (access_token, refresh_token) = derive_tokens(&request_hash, &family_id, &device_id);
        transaction.execute(
            "
        INSERT INTO devices(
            device_id, membership_id, device_name, device_name_key,
            status, created_at, last_used_at
        ) VALUES (?1, ?2, ?3, ?4, 'active', ?5, ?5)
        ",
            params![
                device_id,
                membership_id,
                device_name,
                normalized_device_name_key(device_name),
                now,
            ],
        )?;
        transaction.execute(
            "
        INSERT INTO device_sessions(
            session_id, device_id, access_token_hash, access_expires_at, refresh_token_hash
        ) VALUES (?1, ?2, ?3, ?4, ?5)
        ",
            params![
                session_id,
                device_id,
                crate::hash_secret(&access_token),
                access_expires_at,
                crate::hash_secret(&refresh_token),
            ],
        )?;
        transaction.execute(
            "
        INSERT INTO owner_login_requests(request_hash, device_id, device_name, takeover)
        VALUES (?1, ?2, ?3, ?4)
        ",
            params![request_hash, device_id, device_name, takeover],
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

    /// Sets the current shared family name. Caller validates non-empty and owner role.
    pub fn rename_family(&self, family_id: &str, family_name: &str) -> Result<(), StoreError> {
        let connection = self.connect()?;
        connection.execute(
            "UPDATE families SET name = ?1 WHERE id = ?2",
            params![family_name, family_id],
        )?;
        Ok(())
    }

    pub fn authenticate(&self, token: &str, now: i64) -> Result<Option<Principal>, StoreError> {
        let connection = self.connect()?;
        let token_hash = crate::hash_secret(token);
        let row = connection
            .query_row(
                "
            SELECT memberships.family_id, memberships.role, memberships.membership_id,
                   devices.device_id, device_sessions.session_id
            FROM device_sessions
            JOIN devices ON devices.device_id = device_sessions.device_id
            JOIN memberships ON memberships.membership_id = devices.membership_id
            WHERE device_sessions.access_token_hash = ?1
              AND device_sessions.access_expires_at > ?2
              AND device_sessions.revoked_at IS NULL
              AND devices.status = 'active'
              AND memberships.left_at IS NULL
            ",
                params![token_hash, now],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                    ))
                },
            )
            .optional()?;
        let Some((family_id, role, membership_id, device_id, _session_id)) = row else {
            return Ok(None);
        };
        connection.execute(
            "UPDATE devices SET last_used_at = ?1 WHERE device_id = ?2",
            params![now, device_id],
        )?;
        Ok(Some(Principal {
            family_id,
            role,
            membership_id,
            device_id,
        }))
    }

    /// Returns only the terminal reason bound to this exact access-token hash.
    /// Callers expose only a stable, non-identifying protocol code.
    pub fn revoked_access_reason(&self, token: &str) -> Result<Option<String>, StoreError> {
        let connection = self.connect()?;
        let token_hash = crate::hash_secret(token);
        let session_reason = connection
            .query_row(
                "
            SELECT revoked_reason
            FROM device_sessions
            WHERE access_token_hash = ?1 AND revoked_at IS NOT NULL
            ",
                params![token_hash],
                |row| row.get(0),
            )
            .optional()?;
        if session_reason.is_some() {
            return Ok(session_reason);
        }
        connection
            .query_row(
                "
            SELECT reason
            FROM terminal_credential_denials
            WHERE token_hash = ?1 AND token_kind = 'access'
            ",
                params![token_hash],
                |row| row.get(0),
            )
            .optional()
            .map_err(StoreError::from)
    }

    /// Rotates one device session in an IMMEDIATE transaction. A durable
    /// request id may replay only the exact currently derived handoff (whether
    /// the caller retained the old or already saved the new refresh token).
    /// Every other token in rotation history revokes only that Device.
    pub fn refresh_session<F>(
        &self,
        now: i64,
        presented_refresh_token: &str,
        refresh_request_id: Option<&str>,
        derive_tokens: F,
    ) -> Result<CreatedDeviceSession, StoreError>
    where
        F: Fn(&str, &str) -> (String, String),
    {
        let presented_hash = crate::hash_secret(presented_refresh_token);
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let current = transaction
            .query_row(
                "
            SELECT families.id, families.name, memberships.membership_id,
                   memberships.role, devices.device_id, device_sessions.session_id,
                   device_sessions.access_expires_at,
                   device_sessions.access_token_hash,
                   device_sessions.refresh_token_hash
            FROM device_sessions
            JOIN devices ON devices.device_id = device_sessions.device_id
            JOIN memberships ON memberships.membership_id = devices.membership_id
            JOIN families ON families.id = memberships.family_id
            WHERE device_sessions.refresh_token_hash = ?1
              AND device_sessions.revoked_at IS NULL
              AND devices.status = 'active'
              AND memberships.left_at IS NULL
            ",
                params![presented_hash],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, Option<String>>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, String>(3)?,
                        row.get::<_, String>(4)?,
                        row.get::<_, String>(5)?,
                        row.get::<_, i64>(6)?,
                        row.get::<_, String>(7)?,
                        row.get::<_, String>(8)?,
                    ))
                },
            )
            .optional()?;
        if let Some((
            family_id,
            family_name,
            membership_id,
            _role,
            device_id,
            session_id,
            current_access_expires_at,
            current_access_hash,
            current_refresh_hash,
        )) = current
        {
            let (new_access_token, new_refresh_token) = derive_tokens(&family_id, &device_id);
            let new_access_hash = crate::hash_secret(&new_access_token);
            let new_refresh_hash = crate::hash_secret(&new_refresh_token);

            // The client may have durably replaced its refresh token and then
            // crashed before clearing the equally durable rotation id. In that
            // state it presents the current token with the same id. Exact
            // derived-hash equality proves this is the already completed
            // handoff, so return it without a second history row, generation
            // increment, expiry extension, or replay revocation.
            if refresh_request_id.is_some()
                && presented_hash == new_refresh_hash
                && current_access_hash == new_access_hash
                && current_refresh_hash == new_refresh_hash
            {
                transaction.commit()?;
                return Ok(CreatedDeviceSession {
                    family_id,
                    membership_id,
                    device_id,
                    session_id,
                    access_token: new_access_token,
                    access_expires_at: current_access_expires_at,
                    refresh_token: new_refresh_token,
                    family_name,
                });
            }

            let access_expires_at = now + ACCESS_TOKEN_TTL_SECONDS;
            transaction.execute(
                "
            INSERT INTO refresh_token_history(token_hash, session_id, used_at)
            VALUES (?1, ?2, ?3)
            ",
                params![presented_hash, session_id, now],
            )?;
            transaction.execute(
                "
            UPDATE device_sessions
            SET access_token_hash = ?1,
                access_expires_at = ?2,
                refresh_token_hash = ?3,
                refresh_generation = refresh_generation + 1
            WHERE session_id = ?4
            ",
                params![
                    new_access_hash,
                    access_expires_at,
                    new_refresh_hash,
                    session_id,
                ],
            )?;
            transaction.execute(
                "UPDATE devices SET last_used_at = ?1 WHERE device_id = ?2",
                params![now, device_id],
            )?;
            transaction.commit()?;
            return Ok(CreatedDeviceSession {
                family_id,
                membership_id,
                device_id,
                session_id,
                access_token: new_access_token,
                access_expires_at,
                refresh_token: new_refresh_token,
                family_name,
            });
        }

        let revoked_reason = transaction
            .query_row(
                "
            SELECT revoked_reason
            FROM device_sessions
            WHERE refresh_token_hash = ?1 AND revoked_at IS NOT NULL
            ",
                params![presented_hash],
                |row| row.get::<_, Option<String>>(0),
            )
            .optional()?
            .flatten();
        if revoked_reason.as_deref() == Some("device_removed") {
            return Err(StoreError::DeviceRemoved);
        }

        let terminal_reason = transaction
            .query_row(
                "
            SELECT reason
            FROM terminal_credential_denials
            WHERE token_hash = ?1 AND token_kind = 'refresh'
            ",
                params![presented_hash],
                |row| row.get::<_, String>(0),
            )
            .optional()?;
        if terminal_reason.as_deref() == Some("membership_deleted") {
            return Err(StoreError::MembershipDeleted);
        }
        if terminal_reason.as_deref() == Some("family_deleted") {
            return Err(StoreError::FamilyDeleted);
        }

        // A durable client rotation id makes one crash-window retry
        // reconstructible without storing another secret or widening replay
        // grace. It is idempotent only while its deterministically derived
        // hashes are still the exact active session lineage.
        if refresh_request_id.is_some() {
            let replay = transaction
                .query_row(
                    "
                    SELECT families.id, families.name, memberships.membership_id,
                           devices.device_id, device_sessions.session_id,
                           device_sessions.access_expires_at,
                           device_sessions.access_token_hash,
                           device_sessions.refresh_token_hash
                    FROM refresh_token_history
                    JOIN device_sessions
                      ON device_sessions.session_id = refresh_token_history.session_id
                    JOIN devices ON devices.device_id = device_sessions.device_id
                    JOIN memberships ON memberships.membership_id = devices.membership_id
                    JOIN families ON families.id = memberships.family_id
                    WHERE refresh_token_history.token_hash = ?1
                      AND device_sessions.revoked_at IS NULL
                      AND devices.status = 'active'
                      AND memberships.left_at IS NULL
                    ",
                    params![presented_hash],
                    |row| {
                        Ok((
                            row.get::<_, String>(0)?,
                            row.get::<_, Option<String>>(1)?,
                            row.get::<_, String>(2)?,
                            row.get::<_, String>(3)?,
                            row.get::<_, String>(4)?,
                            row.get::<_, i64>(5)?,
                            row.get::<_, String>(6)?,
                            row.get::<_, String>(7)?,
                        ))
                    },
                )
                .optional()?;
            if let Some((
                family_id,
                family_name,
                membership_id,
                device_id,
                session_id,
                access_expires_at,
                current_access_hash,
                current_refresh_hash,
            )) = replay
            {
                let (access_token, refresh_token) = derive_tokens(&family_id, &device_id);
                if crate::hash_secret(&access_token) == current_access_hash
                    && crate::hash_secret(&refresh_token) == current_refresh_hash
                {
                    return Ok(CreatedDeviceSession {
                        family_id,
                        membership_id,
                        device_id,
                        session_id,
                        access_token,
                        access_expires_at,
                        refresh_token,
                        family_name,
                    });
                }
            }
        }

        let replayed_device = transaction
            .query_row(
                "
            SELECT device_sessions.device_id
            FROM refresh_token_history
            JOIN device_sessions
              ON device_sessions.session_id = refresh_token_history.session_id
            WHERE refresh_token_history.token_hash = ?1
            ",
                params![presented_hash],
                |row| row.get::<_, String>(0),
            )
            .optional()?;
        let Some(device_id) = replayed_device else {
            return Err(StoreError::InvalidRefreshToken);
        };
        transaction.execute(
            "UPDATE devices SET status = 'revoked', last_used_at = ?1 WHERE device_id = ?2",
            params![now, device_id],
        )?;
        transaction.execute(
            "
        UPDATE device_sessions
        SET revoked_at = COALESCE(revoked_at, ?1),
            revoked_reason = COALESCE(revoked_reason, 'refresh_replay')
        WHERE device_id = ?2
        ",
            params![now, device_id],
        )?;
        transaction.commit()?;
        Err(StoreError::RefreshTokenReplay)
    }

    /// Revokes one device and every session lineage attached to it without
    /// changing its membership or sibling devices. Repeating the same request
    /// is successful while the opaque device still belongs to this family.
    pub fn revoke_family_device(
        &self,
        family_id: &str,
        device_id: &str,
        now: i64,
    ) -> Result<(), StoreError> {
        let mut connection = self.connect()?;
        let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let belongs_to_family = transaction
            .query_row(
                "
            SELECT 1
            FROM devices
            JOIN memberships ON memberships.membership_id = devices.membership_id
            WHERE devices.device_id = ?1 AND memberships.family_id = ?2
            ",
                params![device_id, family_id],
                |_| Ok(()),
            )
            .optional()?
            .is_some();
        if !belongs_to_family {
            return Err(StoreError::DeviceNotFound);
        }
        transaction.execute(
            "
        UPDATE devices
        SET status = 'revoked', last_used_at = ?1
        WHERE device_id = ?2
        ",
            params![now, device_id],
        )?;
        transaction.execute(
            "
        UPDATE device_sessions
        SET revoked_at = COALESCE(revoked_at, ?1),
            revoked_reason = COALESCE(revoked_reason, 'device_removed')
        WHERE device_id = ?2
        ",
            params![now, device_id],
        )?;
        transaction.commit()?;
        Ok(())
    }

    pub fn active_memberships(&self, family_id: &str) -> Result<Vec<ActiveMembership>, StoreError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "
        SELECT role, display_name, membership_id
        FROM memberships
        WHERE family_id = ?1 AND left_at IS NULL
        ORDER BY
            CASE role WHEN 'owner' THEN 0 ELSE 1 END,
            membership_id COLLATE BINARY
        ",
        )?;
        let rows = statement
            .query_map(params![family_id], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows
            .into_iter()
            .map(|(role, display_name, membership_id)| ActiveMembership {
                role,
                display_name,
                membership_id,
            })
            .collect())
    }

    /// Returns only active devices the authenticated viewer is allowed to inspect.
    ///
    /// Owner sees the whole family; an ordinary member is constrained in SQL to
    /// their own membership so another member's device metadata never crosses
    /// the store boundary.
    pub fn visible_active_devices(
        &self,
        family_id: &str,
        viewer_membership_id: &str,
        viewer_is_owner: bool,
    ) -> Result<Vec<ActiveDevice>, StoreError> {
        let connection = self.connect()?;
        let mut statement = connection.prepare(
            "
        SELECT devices.device_id, devices.membership_id,
               devices.device_name, devices.last_used_at
        FROM devices
        JOIN memberships ON memberships.membership_id = devices.membership_id
        WHERE memberships.family_id = ?1
          AND memberships.left_at IS NULL
          AND devices.status = 'active'
          AND (?3 = 1 OR devices.membership_id = ?2)
        ORDER BY devices.membership_id COLLATE BINARY,
                 devices.created_at,
                 devices.device_id COLLATE BINARY
        ",
        )?;
        let rows = statement
            .query_map(
                params![family_id, viewer_membership_id, viewer_is_owner],
                |row| {
                    Ok(ActiveDevice {
                        device_id: row.get(0)?,
                        membership_id: row.get(1)?,
                        device_name: row.get(2)?,
                        last_used_at: row.get(3)?,
                    })
                },
            )?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }
}
