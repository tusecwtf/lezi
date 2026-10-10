//! Live cutover execution contract + evidence checklist (ticket 07).
//!
//! **Private ops only.** Ticket 06 shipped the runbook/script gates; this module
//! encodes the **evidence + APK smoke acceptance** surface for the real maintenance
//! window. CLI help never invents success — evidence under
//! [`EVIDENCE_DIR_REL`] must record measured results (or rollback).
//!
//! Public seams (crate-internal):
//! - [`live_cutover_evidence_required_files`] — minimum evidence artifacts
//! - [`live_cutover_apk_smoke_steps`] — ordered client smoke checklist
//! - [`live_cutover_help_text`] — ops surface (`offline-migrate live-cutover-help`)
//! - path constants for evidence + probe script

use super::cutover::{
    cutover_help_text, cutover_step_labels, COPY_BACK_RUNBOOK, COPY_BACK_SCRIPT,
    EXAMPLE_LAN_HTTPS_ENDPOINT,
};

/// Tracker-relative evidence directory for ticket 07 acceptance.
pub(crate) const EVIDENCE_DIR_REL: &str = ".scratch/nas-v3-offline-migrate/evidence/07";

/// Deploy-side post-cutover probe script (repo-relative).
pub(crate) const LIVE_CUTOVER_PROBE_SCRIPT: &str = "tools/lezi-sync/deploy/live-cutover-probe.sh";

/// Human runbook still authoritative for step order (ticket 06).
pub(crate) const LIVE_CUTOVER_RUNBOOK: &str = COPY_BACK_RUNBOOK;

/// Required evidence files under [`EVIDENCE_DIR_REL`] (relative basenames).
/// Acceptance fails closed if any are missing when claiming cutover success.
pub(crate) fn live_cutover_evidence_required_files() -> &'static [&'static str] {
    &[
        "window.md",
        "health.json",
        "owner-api-smoke.md",
        "apk-smoke.md",
        "RESULT.md",
    ]
}

/// Ordered APK / client smoke steps (ticket 07 acceptance).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ApkSmokeStep {
    /// Point client at measured HTTPS endpoint (not plaintext HTTP).
    TrustHttpsEndpointTofu,
    /// Owner device session with migration-time new root password.
    OwnerLoginWithNewRootPassword,
    /// Sample pre-migration authoritative records visible (and media if any).
    HistoricalAuthoritativeRecordsVisible,
    /// New care record can sync back to the family server.
    NewRecordSyncsToFamily,
}

/// Locked APK smoke order for help + evidence checklist.
pub(crate) fn live_cutover_apk_smoke_steps() -> &'static [ApkSmokeStep] {
    &[
        ApkSmokeStep::TrustHttpsEndpointTofu,
        ApkSmokeStep::OwnerLoginWithNewRootPassword,
        ApkSmokeStep::HistoricalAuthoritativeRecordsVisible,
        ApkSmokeStep::NewRecordSyncsToFamily,
    ]
}

/// Human labels matching acceptance criteria wording.
pub(crate) fn live_cutover_apk_smoke_labels() -> &'static [&'static str] {
    &[
        "1. trust HTTPS endpoint (TOFU/SPKI)",
        "2. owner login with migration new root password",
        "3. historical authoritative records visible (media sample if any)",
        "4. new record syncs to family",
    ]
}

/// Ops pointer for ticket 07: evidence, APK steps, probe.
/// Cutover order + secret rules are **not** re-authored here — they come from
/// [`cutover_help_text`] (single source of truth for ticket 06).
/// Never claims the family NAS is already cut over.
pub(crate) fn live_cutover_help_text() -> String {
    format!(
        "\
# Live cutover + APK smoke (ticket 07) — maintenance window execution
#
# This help does NOT claim live success. Record measured results under:
#   {EVIDENCE_DIR_REL}/
# Required evidence files:
#   - window.md          (time window, operator, package/image version)
#   - health.json        (measured LAN HTTPS /health + /ready + version)
#   - owner-api-smoke.md (owner login + sample pull; redact secrets + family PII)
#   - apk-smoke.md       (endpoint trust, owner login, history, new write)
#   - RESULT.md          (pass/fail; on fail include rollback outcome)
#
# Post-cutover probe (ticket 07):
#   bash {LIVE_CUTOVER_PROBE_SCRIPT}
#   Success requires **LAN HTTPS** health+ready at {EXAMPLE_LAN_HTTPS_ENDPOINT}
#   with version matching LEZI_EXPECTED_VERSION (default package version).
#   Container-internal readiness is optional corroboration via:
#     docker exec lezi-sync lezi-sync healthcheck
#   (8766 is container-local only — compose does not publish host:8766).
#   Do not SSH-curl host http://127.0.0.1:8766 — that path is unreachable on CD topology.
#   If HTTPS wrong-version and plaintext HTTP still answers on 8765 → protocol drift;
#   do not claim cutover success.
#
# APK / client smoke checklist (ticket 07):
#   {apk0}   **required from a real APK session** (not API substitute)
#   {apk1}   **required from a real APK session** (not API substitute)
#   {apk2}   UI preferred; API pull (+ media GET) may supplement
#   {apk3}   UI preferred; API bundle stage+commit may substitute when UI blocked
# Debug or release APK on LAN (emulator uses real NAS IP, not 10.0.2.2).
# Never mark ISSUES complete without apk-smoke.md steps 1–2 from a real APK.
#
# Narrative runbook / copy-back script: {LIVE_CUTOVER_RUNBOOK} ; {COPY_BACK_SCRIPT}
# Fixed cutover step labels (same as ticket 06):
#   {step0}
#   {step1}
#   {step2}
#   {step3}
#   {step4}
#
# --- Shared cutover contract (ticket 06; single source of truth for order + secrets) ---
{cutover}
",
        step0 = cutover_step_labels()[0],
        step1 = cutover_step_labels()[1],
        step2 = cutover_step_labels()[2],
        step3 = cutover_step_labels()[3],
        step4 = cutover_step_labels()[4],
        apk0 = live_cutover_apk_smoke_labels()[0],
        apk1 = live_cutover_apk_smoke_labels()[1],
        apk2 = live_cutover_apk_smoke_labels()[2],
        apk3 = live_cutover_apk_smoke_labels()[3],
        cutover = cutover_help_text(),
    )
}

#[cfg(test)]
mod tests {
    use super::super::cutover::cutover_maintenance_steps;
    use super::*;
    use crate::store::DATABASE_SCHEMA_VERSION;

    #[test]
    fn evidence_required_files_cover_acceptance() {
        let files = live_cutover_evidence_required_files();
        assert!(files.contains(&"window.md"));
        assert!(files.contains(&"health.json"));
        assert!(files.contains(&"owner-api-smoke.md"));
        assert!(files.contains(&"apk-smoke.md"));
        assert!(files.contains(&"RESULT.md"));
        assert_eq!(files.len(), 5);
    }

    #[test]
    fn apk_smoke_order_is_trust_login_history_new_write() {
        assert_eq!(
            live_cutover_apk_smoke_steps(),
            &[
                ApkSmokeStep::TrustHttpsEndpointTofu,
                ApkSmokeStep::OwnerLoginWithNewRootPassword,
                ApkSmokeStep::HistoricalAuthoritativeRecordsVisible,
                ApkSmokeStep::NewRecordSyncsToFamily,
            ]
        );
        let labels = live_cutover_apk_smoke_labels();
        assert_eq!(labels.len(), 4);
        assert!(labels[0].contains("TOFU") || labels[0].contains("HTTPS"));
        assert!(labels[1].contains("owner login") || labels[1].contains("root password"));
        assert!(labels[2].contains("historical") || labels[2].contains("authoritative"));
        assert!(labels[3].contains("new record") || labels[3].contains("sync"));
    }

    #[test]
    fn live_cutover_help_documents_evidence_runbook_and_no_auto_success() {
        let text = live_cutover_help_text();
        assert!(text.contains(EVIDENCE_DIR_REL), "{text}");
        assert!(text.contains("window.md"), "{text}");
        assert!(text.contains("health.json"), "{text}");
        assert!(text.contains("RESULT.md"), "{text}");
        assert!(text.contains(LIVE_CUTOVER_PROBE_SCRIPT), "{text}");
        assert!(
            text.contains(COPY_BACK_RUNBOOK) || text.contains(LIVE_CUTOVER_RUNBOOK),
            "{text}"
        );
        assert!(text.contains(EXAMPLE_LAN_HTTPS_ENDPOINT), "{text}");
        assert!(
            text.contains(&DATABASE_SCHEMA_VERSION.to_string()) || text.contains("user_version"),
            "{text}"
        );
        for label in cutover_step_labels() {
            assert!(text.contains(label), "missing cutover step {label}");
        }
        for label in live_cutover_apk_smoke_labels() {
            assert!(text.contains(label), "missing apk step {label}");
        }
        assert!(
            text.contains("does NOT claim live success")
                || text.contains("Never mark ISSUES complete without evidence")
                || text.contains("do not claim cutover success"),
            "{text}"
        );
        // Secret rules come from composed ticket-06 help (single source).
        assert!(
            text.contains("LEZI_FORWARD_BOOTSTRAP_SECRET"),
            "must include cutover secret forward via composed cutover help: {text}"
        );
        assert!(
            text.contains("NEVER inherit") || text.contains("never inherit"),
            "{text}"
        );
        // APK steps 1–2 not substitutable by API alone.
        assert!(
            text.contains("required from a real APK") || text.contains("not API substitute"),
            "must require APK for TOFU + owner login: {text}"
        );
        // Probe topology: LAN HTTPS required; host:8766 dead path forbidden.
        assert!(
            text.contains("LAN HTTPS") || text.contains("https://192.168.77.10:8765"),
            "{text}"
        );
        assert!(
            text.contains("docker exec") && text.contains("healthcheck"),
            "must document docker exec lezi-sync healthcheck: {text}"
        );
        assert!(
            text.contains("Do not SSH-curl host http://127.0.0.1:8766")
                || text.contains("does not publish host:8766"),
            "must warn against host:8766: {text}"
        );
        assert!(
            !text.contains("family NAS is cut over successfully")
                && !text.contains("cutover complete on live"),
            "must not auto-claim success: {text}"
        );
        assert_eq!(cutover_maintenance_steps().len(), 5);
        // Composition: ticket-06 header still present once composed.
        assert!(
            text.contains("Copy-back + TLS cutover (ticket 06)") || text.contains("ticket 06"),
            "must compose ticket-06 help: {text}"
        );
    }
}
