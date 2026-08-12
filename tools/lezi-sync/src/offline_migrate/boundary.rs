//! Architecture boundary contract for `offline-migrate` (audit remediation ticket 21).
//!
//! ## Public seams under test (docs + CLI surface, no private helpers)
//!
//! 1. **ADR-0013** — sole allowed exception to NAS fresh-current: two-phase ops
//!    (offline prep on backup, then maintenance-window cutover); architecture
//!    invariants (explicit CLI, independent `--out`, validate before switch,
//!    process opens current only). Fixed order applies **only** to cutover steps.
//! 2. **ADR-0008** — startup remains exact-current / fail-closed; points at ADR-0013.
//! 3. **CLI usage** — subcommands match shipped help; no secret printing.
//! 4. **Ops docs** — lezi-sync README, DEPLOY.md, root README use the same terms and
//!    link the authoritative cutover runbook; ordinary CD does not run offline-migrate.
//! 5. **Ticket 14** — departed membership hard-delete disposition is documented.
//!
//! Design notes (self-confirmed): seams are the human/ops surfaces operators and
//! future agents read. Tests lock required phrases and cross-links via `include_str!`
//! so docs cannot drift into “startup migration”, “stop-then-migrate”, or “rolling
//! compatibility” wording.

#[cfg(test)]
mod tests {
    use crate::offline_migrate::cli::{run, CliCommand};
    use crate::offline_migrate::cutover::COPY_BACK_RUNBOOK;

    const ADR_0013: &str =
        include_str!("../../../../docs/adr/0013-offline-migrate-is-maintenance-window-cutover.md");
    const ADR_0008: &str =
        include_str!("../../../../docs/adr/0008-support-only-fresh-current-product-contracts.md");
    const ADR_INDEX: &str = include_str!("../../../../docs/adr/README.md");
    const LEZI_SYNC_README: &str = include_str!("../../README.md");
    const DEPLOY_MD: &str = include_str!("../../deploy/DEPLOY.md");
    const ROOT_README: &str = include_str!("../../../../README.md");
    const TECH_MD: &str = include_str!("../../../../docs/prd/tech.md");
    const PRD_README: &str = include_str!("../../../../docs/prd/README.md");
    const RUNBOOK: &str = include_str!("../../deploy/copy-back-tls-cutover-runbook.md");

    #[test]
    fn adr_0013_states_maintenance_window_exception_not_startup_migration() {
        assert!(
            ADR_0013.contains("维护窗") && ADR_0013.contains("H29"),
            "must keep maintenance window behind H29"
        );
        assert!(
            ADR_0013.contains("offline-migrate"),
            "must name the CLI surface"
        );
        assert!(
            ADR_0013.contains("不是")
                && ADR_0013.contains("startup")
                && ADR_0013.contains("自动迁移"),
            "must deny server startup migration with distinctive phrasing"
        );
        assert!(
            ADR_0013.contains("fail closed") || ADR_0013.contains("fail-closed"),
            "must keep fail-closed language"
        );
        assert!(ADR_0013.contains("schema 13"), "must name current schema");
        // Architecture invariants (not a single ordered stop-then-migrate list)
        for needle in [
            "源只读",
            "user_version=11",
            "`12`",
            "原子 rename",
            "server.secret",
            "TLS",
            "普通 CD",
        ] {
            assert!(
                ADR_0013.contains(needle),
                "ADR-0013 missing architecture invariant keyword {needle}"
            );
        }
        assert!(
            ADR_0013.contains("不得")
                && (ADR_0013.contains("自动迁移") || ADR_0013.contains("自动升级")),
            "must forbid auto-migrate on startup"
        );
        assert!(
            ADR_0013.contains("ALTER"),
            "must forbid source in-place rewrite"
        );
        // Require both "not startup" framing and ordinary-CD prohibition.
        assert!(
            ADR_0013.contains("不是") && ADR_0013.contains("startup"),
            "must state offline-migrate is not startup migration"
        );
        assert!(
            ADR_0013.contains("普通 CD")
                && ADR_0013.contains("不得")
                && ADR_0013.contains("offline-migrate"),
            "ordinary CD must not run offline-migrate"
        );
        assert!(
            ADR_0013.contains("ordinary CD"),
            "must deny ordinary deployment migration"
        );
    }

    #[test]
    fn adr_0013_prep_before_window_cutover_after_validate() {
        assert!(
            ADR_0013.contains("dry-run")
                && ADR_0013.contains("migrate")
                && ADR_0013.contains("validate"),
            "ADR-0013 must describe local preparation commands"
        );
        assert!(
            ADR_0013.contains("H28")
                && ADR_0013.contains("H29")
                && ADR_0013.contains("只完成")
                && ADR_0013.contains("copy-back"),
            "ADR-0013 must separate local migration from cutover orchestration"
        );
        // migrate/dry-run/validate do not stop the live container (preconditions).
        assert!(
            ADR_0013.contains("migrate")
                && ADR_0013.contains("dry-run")
                && ADR_0013.contains("validate")
                && ADR_0013.contains("不")
                && ADR_0013.contains("stop")
                && ADR_0013.contains("stop/rm"),
            "local commands must not stop the live service"
        );
        // Runbook: preconditions before maintenance window; fixed order starts at stop.
        assert!(
            RUNBOOK.contains("Preconditions (before the maintenance window)")
                && RUNBOOK.contains("Fixed step order (do not reorder)")
                && RUNBOOK.contains("1. stop live container"),
            "authoritative runbook must keep prep preconditions and cutover fixed order starting at stop"
        );
    }

    #[test]
    fn adr_0013_covers_identity_media_backup_and_validation() {
        assert!(
            ADR_0013.contains("LEZI_MIGRATE_NEW_ROOT_PASSWORD") && ADR_0013.contains("不读取"),
            "legacy password must not rotate identity"
        );
        assert!(
            ADR_0013.contains("不得") && ADR_0013.contains("轮换身份"),
            "must forbid identity rotation"
        );
        assert!(
            ADR_0013.contains("validate") && ADR_0013.contains("server.secret"),
            "target validation"
        );
        assert!(
            ADR_0013.contains("row count")
                && ADR_0013.contains("integrity")
                && ADR_0013.contains("media"),
            "target validation must cover rows, database, and media"
        );
    }

    #[test]
    fn adr_0008_keeps_exact_current_startup_and_points_at_0013() {
        assert!(
            ADR_0008.contains("DATABASE_SCHEMA_VERSION")
                && ADR_0008.contains("精确 current schema"),
            "startup must accept exact current schema"
        );
        assert!(
            !ADR_0008.contains("user_version=3")
                || ADR_0008.contains("ADR-0013")
                || ADR_0008.contains("0013"),
            "if ADR-0008 still mentions user_version=3 it must frame v3 as non-startup or point at 0013"
        );
        assert!(
            ADR_0008.contains("fail closed") || ADR_0008.contains("fail-closed"),
            "fail closed"
        );
        assert!(
            ADR_0008.contains("0013") && ADR_0008.contains("offline-migrate"),
            "ADR-0008 must acknowledge offline-migrate exception via ADR-0013"
        );
        assert!(
            ADR_0008.contains("不得")
                && (ADR_0008.contains("自动迁移") || ADR_0008.contains("原位迁移")),
            "startup must not auto-migrate"
        );
        // Exception phrasing: offline CLI on backup + authorized cutover (not stop-then-migrate).
        assert!(
            ADR_0008.contains("独立备份")
                && ADR_0008.contains("已授权维护窗")
                && ADR_0008.contains("copy-back"),
            "ADR-0008 exception must phrase offline CLI on backup + authorized cutover switch"
        );
        assert!(
            !ADR_0008.contains("显式 `lezi-sync offline-migrate` CLI、停服、固定源"),
            "must not re-seed the stop-then-migrate enumeration"
        );
        // Frontmatter: exception does not overturn fail-closed startup.
        assert!(
            ADR_0008.contains("does not overturn")
                || ADR_0008.contains("不")
                    && ADR_0008.contains("推翻")
                    && ADR_0008.contains("fail-closed"),
            "status/body must clarify ADR-0013 does not overturn fail-closed startup"
        );
    }

    #[test]
    fn adr_index_lists_0013() {
        assert!(
            ADR_INDEX.contains("0013-offline-migrate-is-maintenance-window-cutover.md"),
            "index must link ADR-0013 file"
        );
        assert!(
            ADR_INDEX.contains("offline-migrate") || ADR_INDEX.contains("维护窗"),
            "index title should name the decision"
        );
    }

    #[test]
    fn cli_help_matches_documented_subcommands_and_never_prints_secret_values() {
        let help = run(CliCommand::Help);
        assert_eq!(help.exit_code, 0);
        let text = help.stdout;
        for sub in [
            "migrate",
            "dry-run",
            "validate",
            "copy-out-help",
            "copy-back-help",
            "live-cutover-help",
            "help",
        ] {
            assert!(
                text.contains(sub),
                "CLI help missing subcommand {sub}: {text}"
            );
        }
        assert!(
            text.contains("schema 11/12→13"),
            "current source/target documented"
        );
        assert!(
            text.contains("server.secret") && text.contains("TLS"),
            "identity preservation documented"
        );
        assert!(
            text.contains(COPY_BACK_RUNBOOK) || text.contains("copy-back-tls-cutover-runbook.md"),
            "help must point at runbook"
        );

        // Reject real-looking secret material (not env names or placeholder exports).
        // Vacuous "must not print secret" checks that only ban impossible needles are not enough.
        for corpus in [text.as_str(), ADR_0013, LEZI_SYNC_README] {
            assert_no_literal_secret_material(corpus);
        }

        for sub in [
            "migrate",
            "dry-run",
            "validate",
            "copy-out-help",
            "copy-back-help",
        ] {
            assert!(
                ADR_0013.contains(sub),
                "ADR-0013 must list CLI subcommand {sub}"
            );
        }
    }

    /// Fail if help/docs embed assignment values that look like real secrets.
    /// Placeholder forms (`…`, `...`, `<…>`, `openssl rand`) are allowed.
    fn assert_no_literal_secret_material(corpus: &str) {
        for needle in ["sk_live", "BEGIN RSA", "BEGIN PRIVATE KEY", "BEGIN OPENSSH"] {
            assert!(
                !corpus.contains(needle),
                "must not print secret material containing {needle:?}"
            );
        }
        // Assignment of a high-entropy-looking value to bootstrap/migrate password env.
        // Allows: =..., ='…', ="<placeholder>", =$(openssl …)
        for line in corpus.lines() {
            for key in ["LEZI_BOOTSTRAP_SECRET=", "LEZI_MIGRATE_NEW_ROOT_PASSWORD="] {
                let Some(idx) = line.find(key) else {
                    continue;
                };
                let value = line[idx + key.len()..].trim();
                let placeholder = value.is_empty()
                    || value.starts_with("…")
                    || value.starts_with("...")
                    || value.starts_with('<')
                    || value.contains("…")
                    || value.contains("ops-chosen")
                    || value.contains("placeholder")
                    || value.contains("openssl rand")
                    || value.contains("migration-time")
                    || value.contains("deployment bootstrap")
                    || value.starts_with("$(")
                    || value.starts_with("${")
                    || value.starts_with("'…")
                    || value.starts_with("\"…")
                    || value.starts_with("\"<")
                    || value.starts_with("'<");
                assert!(
                    placeholder,
                    "assignment-like secret literal not allowed (use placeholder): {line}"
                );
            }
        }
    }

    #[test]
    fn readme_deploy_prd_share_boundary_terms_and_runbook_links() {
        // lezi-sync README — phase split + fresh-current
        assert!(
            LEZI_SYNC_README.contains("offline-migrate"),
            "lezi-sync README must document offline-migrate"
        );
        assert!(
            LEZI_SYNC_README.contains("fresh-current"),
            "data-dir contract must use fresh-current (not only fresh-only)"
        );
        assert!(
            LEZI_SYNC_README.contains("11/12→13")
                && LEZI_SYNC_README.contains("H29")
                && LEZI_SYNC_README.contains("不 stop/copy-back/CD"),
            "README must separate H28 local prep from H29 cutover"
        );
        assert!(
            LEZI_SYNC_README.contains("不是")
                && LEZI_SYNC_README.contains("startup")
                && LEZI_SYNC_README.contains("自动迁移"),
            "must state not startup migration"
        );
        assert!(
            LEZI_SYNC_README.contains("ADR-0013")
                || LEZI_SYNC_README.contains("0013-offline-migrate"),
            "must link ADR-0013"
        );
        assert!(
            LEZI_SYNC_README.contains("copy-back-tls-cutover-runbook.md"),
            "must link authoritative runbook"
        );
        assert!(
            LEZI_SYNC_README.contains("server.secret") && LEZI_SYNC_README.contains("TLS pair"),
            "README must state identity preservation"
        );
        assert!(
            LEZI_SYNC_README.contains("普通 CD")
                && (LEZI_SYNC_README.contains("不执行") || LEZI_SYNC_README.contains("不得执行")),
            "ordinary CD does not run offline-migrate"
        );
        assert!(
            !LEZI_SYNC_README.contains(
                "显式 CLI → 停服 → 固定源 v3→current → 独立临时 `--out` → `validate` 后切换"
            ),
            "must not keep stop-before-migrate arrow chain"
        );

        // DEPLOY.md — ordinary CD boundary
        assert!(
            DEPLOY_MD.contains("offline-migrate"),
            "DEPLOY must mention offline-migrate boundary"
        );
        assert!(
            DEPLOY_MD.contains("ADR-0013") || DEPLOY_MD.contains("0013-offline-migrate"),
            "DEPLOY must link ADR-0013"
        );
        assert!(
            DEPLOY_MD.contains("copy-back-tls-cutover-runbook.md"),
            "DEPLOY must link cutover runbook"
        );
        assert!(
            DEPLOY_MD.contains("普通")
                && (DEPLOY_MD.contains("不执行") || DEPLOY_MD.contains("不得")),
            "ordinary CD must not execute offline-migrate"
        );

        // Root README pointer
        assert!(
            ROOT_README.contains("offline-migrate"),
            "root README should point at offline-migrate boundary"
        );
        assert!(
            ROOT_README.contains("0013") || ROOT_README.contains("copy-back-tls-cutover-runbook"),
            "root README must link ADR or runbook"
        );

        // PRD surfaces
        assert!(
            TECH_MD.contains("offline-migrate") && TECH_MD.contains("ADR-0013"),
            "tech.md must mention the boundary"
        );
        assert!(
            TECH_MD.contains("维护窗前") || TECH_MD.contains("两阶段"),
            "tech.md must not imply stop-then-migrate as a single ordered list"
        );
        assert!(
            !TECH_MD.contains("显式 CLI、停服、固定源→current"),
            "tech.md must not re-broadcast stop-then-migrate enumeration"
        );
        assert!(
            PRD_README.contains("fresh-current")
                && (PRD_README.contains("offline-migrate") || PRD_README.contains("ADR-0013")),
            "PRD README must keep fresh-current and acknowledge offline cutover boundary"
        );
        assert!(
            PRD_README.contains("copy-back-tls-cutover-runbook.md"),
            "PRD index must surface the authoritative cutover runbook"
        );
    }
}
