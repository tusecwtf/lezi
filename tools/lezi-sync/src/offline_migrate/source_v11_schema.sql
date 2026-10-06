    CREATE TABLE families (
        id TEXT PRIMARY KEY,
        created_at INTEGER NOT NULL,
        create_request_hash TEXT,
        name TEXT,
        owner_root_fingerprint TEXT
    );
    CREATE UNIQUE INDEX families_create_request
        ON families(create_request_hash);

    CREATE TABLE memberships (
        membership_id TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        role TEXT NOT NULL CHECK(role IN ('owner', 'member')),
        display_name TEXT NOT NULL,
        display_name_key TEXT NOT NULL,
        left_at INTEGER
    );
    CREATE INDEX memberships_family ON memberships(family_id);
    CREATE UNIQUE INDEX memberships_one_owner
        ON memberships(family_id) WHERE role = 'owner' AND left_at IS NULL;
    CREATE UNIQUE INDEX memberships_active_display_name
        ON memberships(family_id, display_name_key) WHERE left_at IS NULL;

    CREATE TABLE devices (
        device_id TEXT PRIMARY KEY,
        membership_id TEXT NOT NULL
            REFERENCES memberships(membership_id) ON DELETE CASCADE,
        device_name TEXT NOT NULL,
        device_name_key TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('active', 'revoked')),
        created_at INTEGER NOT NULL,
        last_used_at INTEGER NOT NULL
    );
    CREATE INDEX devices_membership ON devices(membership_id);
    CREATE UNIQUE INDEX devices_active_name
        ON devices(membership_id, device_name_key) WHERE status = 'active';

    CREATE TABLE device_sessions (
        session_id TEXT PRIMARY KEY,
        device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
        access_token_hash TEXT NOT NULL UNIQUE,
        access_expires_at INTEGER NOT NULL,
        refresh_token_hash TEXT NOT NULL UNIQUE,
        refresh_generation INTEGER NOT NULL DEFAULT 0,
        revoked_at INTEGER,
        revoked_reason TEXT
    );
    CREATE INDEX device_sessions_device ON device_sessions(device_id);

    CREATE TABLE refresh_token_history (
        token_hash TEXT PRIMARY KEY,
        session_id TEXT NOT NULL
            REFERENCES device_sessions(session_id) ON DELETE CASCADE,
        used_at INTEGER NOT NULL
    );
    CREATE INDEX refresh_token_history_session
        ON refresh_token_history(session_id);

    CREATE TABLE terminal_credential_denials (
        token_hash TEXT NOT NULL,
        token_kind TEXT NOT NULL CHECK(token_kind IN ('access', 'refresh')),
        reason TEXT NOT NULL CHECK(reason IN ('membership_deleted', 'family_deleted')),
        PRIMARY KEY (token_hash, token_kind)
    );

    CREATE TABLE owner_login_requests (
        request_hash TEXT PRIMARY KEY,
        device_id TEXT NOT NULL UNIQUE REFERENCES devices(device_id) ON DELETE CASCADE,
        device_name TEXT NOT NULL,
        takeover INTEGER NOT NULL CHECK(takeover IN (0, 1))
    );

    CREATE TABLE member_login_requests (
        request_id TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        pending_secret_hash TEXT NOT NULL UNIQUE,
        display_name TEXT NOT NULL,
        display_name_key TEXT NOT NULL,
        device_name TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN (
            'pending', 'approved', 'rejected', 'cancelled', 'expired', 'claimed'
        )),
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        decided_at INTEGER,
        claimed_at INTEGER,
        approval_kind TEXT CHECK(approval_kind IN ('new', 'existing')),
        membership_id TEXT REFERENCES memberships(membership_id) ON DELETE SET NULL,
        device_id TEXT REFERENCES devices(device_id) ON DELETE SET NULL
    );
    CREATE INDEX member_login_requests_family_status
        ON member_login_requests(family_id, status, expires_at);

    CREATE TABLE member_login_grants (
        grant_hash TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        membership_id TEXT NOT NULL REFERENCES memberships(membership_id) ON DELETE CASCADE,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        used_at INTEGER,
        claimed_device_id TEXT REFERENCES devices(device_id) ON DELETE SET NULL
    );
    CREATE INDEX member_login_grants_family_expiry
        ON member_login_grants(family_id, expires_at);

    CREATE TABLE member_rename_requests (
        request_id TEXT PRIMARY KEY,
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        membership_id TEXT NOT NULL
            REFERENCES memberships(membership_id) ON DELETE CASCADE,
        requested_display_name TEXT NOT NULL,
        requested_display_name_key TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN (
            'pending', 'approved', 'rejected', 'cancelled', 'expired'
        )),
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        decided_at INTEGER
    );
    CREATE INDEX member_rename_requests_family_status
        ON member_rename_requests(family_id, status, expires_at);
    CREATE UNIQUE INDEX member_rename_requests_one_pending
        ON member_rename_requests(membership_id) WHERE status = 'pending';

    CREATE TABLE family_meta (
        family_id TEXT PRIMARY KEY REFERENCES families(id) ON DELETE CASCADE,
        rev INTEGER NOT NULL DEFAULT 0
    );

    CREATE TABLE entities (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        entity_type TEXT NOT NULL CHECK(entity_type IN ('baby', 'record', 'media', 'care_plan', 'custom_item', 'fulfillment_candidate')),
        client_uuid TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        deleted_at INTEGER,
        payload_json TEXT NOT NULL,
        rev INTEGER NOT NULL,
        PRIMARY KEY (family_id, entity_type, client_uuid)
    );
    CREATE INDEX entities_family_rev ON entities(family_id, rev);

    CREATE TABLE sync_bundles (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        bundle_id TEXT NOT NULL,
        staged_membership_id TEXT NOT NULL,
        status TEXT NOT NULL CHECK(status IN ('staging', 'committed')),
        root_type TEXT NOT NULL,
        root_client_uuid TEXT NOT NULL,
        root_updated_at INTEGER NOT NULL,
        root_deleted_at INTEGER,
        root_payload_json TEXT NOT NULL,
        media_entities_json TEXT NOT NULL,
        content_hash TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        committed_at INTEGER,
        committed_cursor INTEGER,
        committed_applied INTEGER,
        PRIMARY KEY (family_id, bundle_id)
    );
    CREATE INDEX sync_bundles_family_status
        ON sync_bundles(family_id, status);

    CREATE TABLE sync_bundle_media (
        family_id TEXT NOT NULL,
        bundle_id TEXT NOT NULL,
        media_uuid TEXT NOT NULL,
        declared_byte_size INTEGER,
        staged_byte_size INTEGER,
        staged_sha256 TEXT,
        staged_at INTEGER,
        PRIMARY KEY (family_id, bundle_id, media_uuid),
        FOREIGN KEY (family_id, bundle_id)
            REFERENCES sync_bundles(family_id, bundle_id) ON DELETE CASCADE
    );

    CREATE TABLE media_publications (
        family_id TEXT NOT NULL REFERENCES families(id) ON DELETE CASCADE,
        media_uuid TEXT NOT NULL,
        source TEXT NOT NULL
            CHECK(source IN ('ordinary', 'bundle_pending', 'bundle')),
        bundle_id TEXT,
        PRIMARY KEY (family_id, media_uuid)
    );
