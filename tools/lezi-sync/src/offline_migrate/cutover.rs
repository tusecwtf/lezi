//! Copy-back + TLS cutover runbook contract (ticket 06).
//!
//! **Private ops only.** Does not execute the live maintenance window (ticket 07).
//! Encodes the fixed step order, default control-plane paths, rollback notes, and
//! family re-auth checklist so CLI help and shell scripts stay aligned.
//!
//! Public seams (crate-internal, observed without reaching into helpers):
//! - [`cutover_maintenance_steps`] — fixed ordered maintenance steps
//! - [`cutover_help_text`] — ops surface printed by `offline-migrate copy-back-help`
//! - path constants [`COPY_BACK_RUNBOOK`] / [`COPY_BACK_SCRIPT`]

use super::migrator::REAUTH_OPS_NOTE;
use crate::store::DATABASE_SCHEMA_VERSION;
use crate::SERVER_SECRET_BYTES;

/// Authoritative human runbook (repo-relative). Help points here; do not fork order.
pub(crate) const COPY_BACK_RUNBOOK: &str =
    "tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md";

/// Copy-back step script (repo-relative). Implements step 3 only; TLS start is CD.
pub(crate) const COPY_BACK_SCRIPT: &str = "tools/lezi-sync/deploy/copy-back-nas-data.sh";

/// Default SSH target (AGENTS.md / measured family control plane).
pub(crate) const DEFAULT_NAS_SSH: &str = "13096920600@192.168.50.4";
/// Default SSH port on the family NAS.
pub(crate) const DEFAULT_NAS_SSH_PORT: &str = "10000";
/// Default host bind for `/data`.
pub(crate) const DEFAULT_DATA_HOST_PATH: &str =
    "/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data";
/// Default LAN HTTPS endpoint after TLS cutover.
pub(crate) const DEFAULT_LAN_HTTPS_ENDPOINT: &str = "https://192.168.50.4:8765";
/// Container-internal HTTP readiness base (not published on host; use docker exec healthcheck).
pub(crate) const DEFAULT_LOOPBACK_HTTP_READY: &str = "http://127.0.0.1:8766";
/// Pre-TLS measured live surface (protocol drift probe).
pub(crate) const PRE_TLS_HTTP_PROBE: &str = "http://192.168.50.4:8765";

/// Fixed maintenance-window steps (ticket 06 acceptance). Order is contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CutoverMaintenanceStep {
    /// Stop the live `lezi-sync` container (data bind kept).
    StopLiveContainer,
    /// Confirm local copy-out backup **and** a NAS-side snapshot of the live data path.
    ConfirmDualBackup,
    /// Copy validated migrated `out/` onto the NAS data bind (no TLS start here).
    CopyBackUpgradedData,
    /// Start current TLS image via existing CD (`push-and-deploy` / `remote-deploy`).
    StartCurrentTlsDeploy,
    /// Probe health/ready by **actual** protocol (HTTPS 8765 / loopback 8766 / drift HTTP).
    ProbeHealthReadyByActualProtocol,
}

/// Locked step order for the cutover runbook. Must not be reordered without a new ticket.
pub(crate) fn cutover_maintenance_steps() -> &'static [CutoverMaintenanceStep] {
    &[
        CutoverMaintenanceStep::StopLiveContainer,
        CutoverMaintenanceStep::ConfirmDualBackup,
        CutoverMaintenanceStep::CopyBackUpgradedData,
        CutoverMaintenanceStep::StartCurrentTlsDeploy,
        CutoverMaintenanceStep::ProbeHealthReadyByActualProtocol,
    ]
}

/// Human labels matching acceptance criteria wording (used in help + script headers + runbook).
pub(crate) fn cutover_step_labels() -> &'static [&'static str] {
    &[
        "1. stop live container",
        "2. confirm dual backup (local copy-out + NAS-side)",
        "3. copy-back upgraded out/ to NAS data bind",
        "4. start current TLS deploy (CD)",
        "5. health/ready by actual protocol",
    ]
}

/// Ops pointer + fixed checklist. Does **not** claim live cutover success (ticket 07).
pub(crate) fn cutover_help_text() -> String {
    format!(
        "\
# Copy-back + TLS cutover (ticket 06) — maintenance-window runbook
#
# This help does NOT execute the live cutover. Ticket 07 owns real execution + APK smoke.
# Do not claim the family NAS is cut over until ticket 07 evidence exists.
#
# Authoritative narrative: {COPY_BACK_RUNBOOK}
# Copy-back step script (step 3 only): {COPY_BACK_SCRIPT}
#   bash {COPY_BACK_SCRIPT}
#
# Fixed step order (do not reorder):
#   {step0}
#   {step1}
#   {step2}
#   {step3}
#   {step4}
#
# Default control plane (override only via env; matches AGENTS.md):
#   NAS_SSH              {DEFAULT_NAS_SSH}
#   NAS_SSH_PORT         {DEFAULT_NAS_SSH_PORT}
#   LEZI_DATA_HOST_PATH  {DEFAULT_DATA_HOST_PATH}
#   LAN HTTPS endpoint   {DEFAULT_LAN_HTTPS_ENDPOINT}
#   Container ready      docker exec lezi-sync lezi-sync healthcheck ({DEFAULT_LOOPBACK_HTTP_READY}/ready inside container; not published on host)
#   Pre-TLS drift probe  {PRE_TLS_HTTP_PROBE}/health (plaintext still possible until cutover)
#   Expected out/ schema user_version={DATABASE_SCHEMA_VERSION}
#   server.secret min bytes={SERVER_SECRET_BYTES}
#   data bind uid        10001:10001 (required after copy-back)
#
# Preconditions (tickets 05+):
#   - local copy-out backup/ kept (read-only preferred)
#   - out/ from `offline-migrate migrate` then `validate` (copy-back-ready; full preflight)
#   - record migration-time new root password (= post-cutover LEZI_BOOTSTRAP_SECRET)
#   - CD package ready (linux/amd64 image + package-nas); deploy only after user confirms replace
#   - Step 0: docker inspect image id + docker save pre-cutover image tar BEFORE stop/rm
#
# Step 1 — stop live container (on NAS, data bind kept):
#   ssh -p {DEFAULT_NAS_SSH_PORT} {DEFAULT_NAS_SSH} \\
#     'docker stop lezi-sync && docker rm lezi-sync'   # data dir NOT deleted; fail if still present
#
# Step 2 — dual backup confirmation:
#   - Local: original copy-out backup/ still present and preferably a-w
#   - NAS: second snapshot of LEZI_DATA_HOST_PATH (tar/rsync aside) while container is stopped
#   Script gates: LEZI_CONFIRM_CONTAINER_STOPPED=1, LEZI_CONFIRM_DUAL_BACKUP=1,
#                 LEZI_NAS_BACKUP_PATH=<remote v3 snapshot> (live path SSH-probes both)
#
# Step 3 — copy-back upgraded data:
#   LEZI_OUT_DIR=/path/to/validated-out \\
#   LEZI_CONFIRM_CONTAINER_STOPPED=1 LEZI_CONFIRM_DUAL_BACKUP=1 \\
#   LEZI_NAS_BACKUP_PATH=/remote/v3-snapshot \\
#     bash {COPY_BACK_SCRIPT}
#   Optional: LEZI_COPY_BACK_DRY_RUN=1 (local gates + print plan; no network write)
#   Gates: sqlite3 user_version={DATABASE_SCHEMA_VERSION}, offline-migrate validate --out,
#          rsync only (scp refused), remote container absent, staging+rename swap,
#          chown/verify uid 10001. Never copy backup's old server.secret.
#   tls/ is AbsentOrCreateAtCutover — do not require tls/ inside out/; CD init-tls creates it.
#
# Step 4 — start current TLS deploy (existing CD; requires explicit operator confirm):
#   MANDATORY for this cutover (container already removed in step 1 — no live inherit):
#     export LEZI_BOOTSTRAP_SECRET='…migration-time new root password…'
#     export LEZI_FORWARD_BOOTSTRAP_SECRET=1
#     export LEZI_ALLOW_SECRET_RESEED=1
#     export LEZI_ALLOW_TLS_BOOTSTRAP=1
#     # same value as LEZI_MIGRATE_NEW_ROOT_PASSWORD / --new-root-password
#     # NEVER inherit pre-cutover container env; NEVER leave unset for cutover
#     # Unset all four after cutover so ordinary CD returns to guarded live inherit.
#   cd tools/lezi-sync && ./build-image.sh && ./deploy/push-and-deploy.sh
#   Or LEZI_SKIP_PACKAGE=1 only when dist/ embeds the intended image plus current
#   guarded helpers and valid SHA256SUMS.
#   push-and-deploy forwards LEZI_BOOTSTRAP_SECRET into remote-deploy only when
#   LEZI_FORWARD_BOOTSTRAP_SECRET=1 (opt-in; ordinary CD never injects a local secret).
#   Protocol cutover risk: live may still be HTTP :8765; current tree publishes HTTPS :8765
#   + container-internal HTTP :8766 (not on host) and creates persistent tls/ under the data bind.
#
# Step 5 — health/ready (probe actual protocol; do not assume):
#   Client-facing success = LAN HTTPS (required for APK TOFU):
#     curl --cacert <data-bind>/tls/server.crt -fsS {DEFAULT_LAN_HTTPS_ENDPOINT}/health
#     curl --cacert <data-bind>/tls/server.crt -fsS {DEFAULT_LAN_HTTPS_ENDPOINT}/ready
#     # mode-700 data bind: cert may be unreadable on host → curl -k to same URLs
#   Container-internal readiness (optional corroboration; 8766 NOT published on host):
#     ssh … 'docker exec lezi-sync lezi-sync healthcheck'   # hits {DEFAULT_LOOPBACK_HTTP_READY}/ready inside container
#   Do NOT: ssh … 'curl http://127.0.0.1:8766/…' on the NAS host (compose publishes only 8765).
#   If HTTPS fails with TLS wrong-version and HTTP {PRE_TLS_HTTP_PROBE} still answers → protocol drift
#     (live image is not the TLS stack). Report drift; do not claim cutover success.
#   Ticket 07 probe script: tools/lezi-sync/deploy/live-cutover-probe.sh
#
# Rollback (service back to pre-maintenance usable v3 HTTP):
#   0. Use Step-0 docker save tar / image-id (same tag 0.3.0 is NOT enough — ids differ)
#   1. docker stop/rm lezi-sync (keep host data path parent)
#   2. restore NAS data bind contents from the **copy-out v3 backup** (not out/)
#   3. docker load pre-cutover tar; re-start pre-cutover image / old start method that served
#      {PRE_TLS_HTTP_PROBE}
#   4. probe {PRE_TLS_HTTP_PROBE}/health and /ready until usable
#   Do not leave a half-written out/ as the live data bind after a failed open.
#
# Family notify checklist (before declaring the window done for humans):
#   - Root password rotated: owner uses the migration-time new password (LEZI_BOOTSTRAP_SECRET)
#   - Old APK / old plaintext HTTP clients are not supported after TLS + schema cutover
#   - Endpoint is {DEFAULT_LAN_HTTPS_ENDPOINT}; TOFU / SPKI trust required on trusted-HTTPS path
#   - All members re-login via current request/approve or login-grant flows (no silent restore)
#
{REAUTH_OPS_NOTE}
# Ticket boundary: ticket 06 ships the runbook + copy-back gates only.
# Ticket 07 executes the window and records evidence; never claim live success from this help alone.
"
        ,
        step0 = cutover_step_labels()[0],
        step1 = cutover_step_labels()[1],
        step2 = cutover_step_labels()[2],
        step3 = cutover_step_labels()[3],
        step4 = cutover_step_labels()[4],
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    const COPY_BACK_SCRIPT_SRC: &str = include_str!("../../deploy/copy-back-nas-data.sh");
    const COPY_BACK_RUNBOOK_SRC: &str =
        include_str!("../../deploy/copy-back-tls-cutover-runbook.md");

    #[test]
    fn cutover_step_order_is_stop_dual_backup_copy_back_tls_health() {
        assert_eq!(
            cutover_maintenance_steps(),
            &[
                CutoverMaintenanceStep::StopLiveContainer,
                CutoverMaintenanceStep::ConfirmDualBackup,
                CutoverMaintenanceStep::CopyBackUpgradedData,
                CutoverMaintenanceStep::StartCurrentTlsDeploy,
                CutoverMaintenanceStep::ProbeHealthReadyByActualProtocol,
            ]
        );
        let labels = cutover_step_labels();
        assert_eq!(labels.len(), 5);
        assert!(labels[0].contains("stop"));
        assert!(labels[1].contains("dual backup"));
        assert!(labels[2].contains("copy-back"));
        assert!(labels[3].contains("TLS"));
        assert!(labels[4].contains("health/ready"));
    }

    #[test]
    fn cutover_help_documents_defaults_rollback_and_family_checklist() {
        let text = cutover_help_text();
        // Paths / control plane
        assert!(text.contains(COPY_BACK_RUNBOOK), "{text}");
        assert!(text.contains(COPY_BACK_SCRIPT), "{text}");
        assert!(text.contains(DEFAULT_NAS_SSH), "{text}");
        assert!(text.contains(DEFAULT_NAS_SSH_PORT), "{text}");
        assert!(text.contains(DEFAULT_DATA_HOST_PATH), "{text}");
        assert!(text.contains(DEFAULT_LAN_HTTPS_ENDPOINT), "{text}");
        assert!(
            text.contains("https://192.168.50.4:8765"),
            "LAN https endpoint required: {text}"
        );
        // Fixed order present
        for label in cutover_step_labels() {
            assert!(text.contains(label), "missing step label {label} in {text}");
        }
        // Rollback points
        assert!(
            text.contains("Rollback") || text.contains("rollback"),
            "{text}"
        );
        assert!(
            text.contains("copy-out") && text.contains("v3"),
            "rollback must restore copy-out v3 backup: {text}"
        );
        assert!(
            text.contains("docker save") || text.contains("pre-cutover"),
            "rollback needs pre-cutover image artifact: {text}"
        );
        // Family checklist
        assert!(
            text.contains("Root password") || text.contains("root password"),
            "{text}"
        );
        assert!(
            text.contains("Old APK") || text.contains("old APK") || text.contains("plaintext HTTP"),
            "{text}"
        );
        assert!(
            text.contains("TOFU") || text.contains("re-login") || text.contains("re-auth"),
            "{text}"
        );
        assert!(
            text.contains(REAUTH_OPS_NOTE.trim()) || text.contains("no silent restore"),
            "{text}"
        );
        // Must not claim live success before ticket 07
        assert!(
            text.contains("ticket 07") || text.contains("Ticket 07"),
            "{text}"
        );
        assert!(
            text.contains("Does NOT execute the live cutover")
                || text.contains("does NOT execute")
                || text.contains("never claim live success"),
            "{text}"
        );
        // Expected schema for out/
        assert!(
            text.contains(&DATABASE_SCHEMA_VERSION.to_string()),
            "help should mention target user_version: {text}"
        );
    }

    #[test]
    fn cutover_help_requires_export_migration_secret_and_forbids_inherit() {
        let text = cutover_help_text();
        assert!(
            text.contains("export LEZI_BOOTSTRAP_SECRET"),
            "must show mandatory export: {text}"
        );
        assert!(
            text.contains("migration-time new root password")
                || text.contains("LEZI_MIGRATE_NEW_ROOT_PASSWORD"),
            "must tie secret to migration password: {text}"
        );
        assert!(
            text.contains("NEVER inherit") || text.contains("never inherit"),
            "must forbid pre-cutover inherit: {text}"
        );
        // Must not present live-container inherit as the cutover default.
        assert!(
            !text.contains("remote-deploy inherits LEZI_BOOTSTRAP_SECRET (must match"),
            "old mutually exclusive inherit wording must be gone: {text}"
        );
        assert!(
            text.contains("LEZI_FORWARD_BOOTSTRAP_SECRET"),
            "must document opt-in forward flag: {text}"
        );
        assert!(
            text.contains("LEZI_ALLOW_SECRET_RESEED=1"),
            "cutover must explicitly authorize replacing the pre-cutover persistent secret: {text}"
        );
        assert!(
            text.contains("forwards LEZI_BOOTSTRAP_SECRET")
                || text.contains("forwarding LEZI_BOOTSTRAP_SECRET")
                || text.contains("forwards LEZI_BOOTSTRAP_SECRET into remote-deploy"),
            "should document push-and-deploy forward path: {text}"
        );
    }

    #[test]
    fn runbook_contains_same_step_labels_as_help() {
        for label in cutover_step_labels() {
            assert!(
                COPY_BACK_RUNBOOK_SRC.contains(label),
                "runbook missing shared label {label}"
            );
        }
        // Bootstrap cutover contract in human runbook
        assert!(
            COPY_BACK_RUNBOOK_SRC.contains("export LEZI_BOOTSTRAP_SECRET"),
            "runbook must mandate export"
        );
        assert!(
            COPY_BACK_RUNBOOK_SRC.contains("LEZI_FORWARD_BOOTSTRAP_SECRET=1")
                || COPY_BACK_RUNBOOK_SRC.contains("export LEZI_FORWARD_BOOTSTRAP_SECRET"),
            "runbook must require opt-in LEZI_FORWARD_BOOTSTRAP_SECRET for cutover forward"
        );
        assert!(
            COPY_BACK_RUNBOOK_SRC.contains("LEZI_ALLOW_SECRET_RESEED=1"),
            "runbook must authorize the migration-time secret reseed"
        );
        assert!(
            COPY_BACK_RUNBOOK_SRC.contains("NEVER inherit")
                || COPY_BACK_RUNBOOK_SRC.contains("never inherit")
                || COPY_BACK_RUNBOOK_SRC.contains("inheriting the pre-cutover secret is wrong"),
            "runbook must forbid inherit"
        );
        assert!(
            COPY_BACK_RUNBOOK_SRC.contains("docker save"),
            "runbook must require pre-cutover image save"
        );
        assert!(
            COPY_BACK_RUNBOOK_SRC.contains("10001"),
            "runbook must mention container uid ownership"
        );
    }

    #[test]
    fn copy_back_script_shipped_constants_match_rust() {
        let expected_ver = format!("SHIPPED_USER_VERSION={DATABASE_SCHEMA_VERSION}");
        let expected_secret = format!("SHIPPED_MIN_SECRET_BYTES={SERVER_SECRET_BYTES}");
        assert!(
            COPY_BACK_SCRIPT_SRC.contains(&expected_ver),
            "script must ship user_version={DATABASE_SCHEMA_VERSION}: missing {expected_ver}"
        );
        assert!(
            COPY_BACK_SCRIPT_SRC.contains(&expected_secret),
            "script must ship secret bytes={SERVER_SECRET_BYTES}: missing {expected_secret}"
        );
        // Fail-closed policies encoded in script source
        assert!(
            COPY_BACK_SCRIPT_SRC.contains("scp fallback refused")
                || COPY_BACK_SCRIPT_SRC.contains("scp is required")
                || COPY_BACK_SCRIPT_SRC.contains("rsync is required"),
            "script must require rsync / refuse scp"
        );
        assert!(
            COPY_BACK_SCRIPT_SRC.contains("sqlite3 is required"),
            "script must fail closed without sqlite3"
        );
        assert!(
            COPY_BACK_SCRIPT_SRC.contains("offline-migrate validate"),
            "script must invoke full validate"
        );
        assert!(
            COPY_BACK_SCRIPT_SRC.contains("LEZI_ALLOW_USER_VERSION_OVERRIDE"),
            "script must pin version unless dual override"
        );
        assert!(
            COPY_BACK_SCRIPT_SRC.contains("docker ps -a --filter"),
            "script must positively prove the remote container is absent"
        );
        assert!(
            COPY_BACK_SCRIPT_SRC.contains("LEZI_NAS_BACKUP_PATH"),
            "script must require NAS backup path on live write"
        );
        assert!(
            COPY_BACK_SCRIPT_SRC.contains("copy-back-staging")
                || COPY_BACK_SCRIPT_SRC.contains("staging"),
            "script must stage before swap"
        );
        assert!(
            COPY_BACK_SCRIPT_SRC.contains("10001"),
            "script must chown/verify uid 10001"
        );
    }
}
