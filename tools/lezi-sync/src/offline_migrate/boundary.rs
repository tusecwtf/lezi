//! Architecture boundary contract for `offline-migrate` (audit remediation ticket 21).
//!
//! ## Public seams under test (docs + CLI surface, no private helpers)
//!
//! 1. **ADR-0013** — sole allowed exception to NAS fresh-current: explicit CLI, stop
//!    service, fixed v3 source → current, independent temp out/, validate then switch.
//! 2. **ADR-0008** — startup remains exact-current / fail-closed; points at ADR-0013.
//! 3. **CLI usage** — subcommands match shipped help; no secret printing.
//! 4. **Ops docs** — lezi-sync README, DEPLOY.md, root README use the same terms and
//!    link the authoritative cutover runbook; ordinary CD does not run offline-migrate.
//! 5. **Ticket 14** — departed membership hard-delete disposition is documented.
//!
//! Design notes (self-confirmed): seams are the human/ops surfaces operators and
//! future agents read. Tests lock required phrases and cross-links via `include_str!`
//! so docs cannot drift into “startup migration” or “rolling compatibility” wording.

#![allow(dead_code)]

/// Repo-relative authoritative cutover runbook (shared with cutover module).
pub(crate) const AUTHORITATIVE_CUTOVER_RUNBOOK: &str =
    "tools/lezi-sync/deploy/copy-back-tls-cutover-runbook.md";

/// ADR that defines the offline-migrate architecture boundary.
pub(crate) const ADR_OFFLINE_MIGRATE_BOUNDARY: &str =
    "docs/adr/0013-offline-migrate-is-maintenance-window-cutover.md";

#[cfg(test)]
mod tests {
    use super::*;
    use crate::offline_migrate::cli::{run, CliCommand};
    use crate::store::DATABASE_SCHEMA_VERSION;

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

    #[test]
    fn adr_0013_states_maintenance_window_exception_not_startup_migration() {
        assert!(
            ADR_0013.contains("维护窗") || ADR_0013.contains("maintenance-window"),
            "must name maintenance window"
        );
        assert!(
            ADR_0013.contains("offline-migrate"),
            "must name the CLI surface"
        );
        assert!(
            ADR_0013.contains("不是") && ADR_0013.contains("startup"),
            "must deny server startup migration: {ADR_0013}"
        );
        assert!(
            ADR_0013.contains("fail closed")
                || ADR_0013.contains("fail-closed")
                || ADR_0013.contains("fail closed"),
            "must keep fail-closed language"
        );
        assert!(
            ADR_0013.contains("fresh-current") || ADR_0013.contains("fresh-current"),
            "must keep fresh-current language"
        );
        // Sole exception pipeline keywords
        for needle in [
            "显式 CLI",
            "停服",
            "临时目标",
            "验证后切换",
            "user_version=3",
            "DATABASE_SCHEMA_VERSION",
        ] {
            assert!(
                ADR_0013.contains(needle),
                "ADR-0013 missing pipeline keyword {needle}"
            );
        }
        assert!(
            ADR_0013.contains("不得")
                && (ADR_0013.contains("自动迁移") || ADR_0013.contains("自动升级")),
            "must forbid auto-migrate on startup"
        );
        assert!(
            ADR_0013.contains("部分原地改写") || ADR_0013.contains("原地改写"),
            "must forbid partial in-place rewrite"
        );
        assert!(
            ADR_0013.contains("普通 CD") && ADR_0013.contains("不得"),
            "ordinary CD must not run offline-migrate"
        );
        assert!(
            ADR_0013.contains("滚动") && ADR_0013.contains("兼容"),
            "must deny rolling schema compatibility framing"
        );
    }

    #[test]
    fn adr_0013_covers_secret_bind_backup_validate_and_departed_membership() {
        assert!(
            ADR_0013.contains("LEZI_BOOTSTRAP_SECRET")
                && ADR_0013.contains("LEZI_MIGRATE_NEW_ROOT_PASSWORD"),
            "secret contract"
        );
        assert!(
            ADR_0013.contains("不得") && ADR_0013.contains("打印") && ADR_0013.contains("secret"),
            "must forbid printing secrets"
        );
        assert!(
            ADR_0013.contains("data bind") || ADR_0013.contains("Data bind"),
            "data bind"
        );
        assert!(
            ADR_0013.contains("10001"),
            "container uid on data bind"
        );
        assert!(
            ADR_0013.contains("回滚") || ADR_0013.contains("rollback") || ADR_0013.contains("Rollback"),
            "backup/rollback"
        );
        assert!(
            ADR_0013.contains("validate") && ADR_0013.contains("server.secret"),
            "target validation"
        );
        assert!(
            ADR_0013.contains("departed") || ADR_0013.contains("Departed"),
            "departed membership"
        );
        assert!(
            ADR_0013.contains("hard-delete") || ADR_0013.contains("hard delete"),
            "hard-delete disposition"
        );
        assert!(
            ADR_0013.contains("anonymized_membership_refs")
                || ADR_0013.contains("匿名事实")
                || ADR_0013.contains("置 null"),
            "anonymized fact refs"
        );
        assert!(
            ADR_0013.contains(AUTHORITATIVE_CUTOVER_RUNBOOK)
                || ADR_0013.contains("copy-back-tls-cutover-runbook.md"),
            "must link authoritative runbook"
        );
    }

    #[test]
    fn adr_0008_keeps_exact_current_startup_and_points_at_0013() {
        // Startup contract: exact current, not a free-floating historical "v3" as the
        // only accepted live schema (v3 is offline-migrate *source*).
        assert!(
            ADR_0008.contains("DATABASE_SCHEMA_VERSION")
                || ADR_0008.contains("精确 current")
                || ADR_0008.contains("精确 current schema")
                || ADR_0008.contains("current schema"),
            "startup must accept current schema, not only legacy v3 wording: check ADR-0008"
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
            ADR_0008.contains("0013")
                || ADR_0008.contains("offline-migrate")
                || ADR_0008.contains("维护窗"),
            "ADR-0008 must acknowledge offline-migrate exception via ADR-0013"
        );
        assert!(
            ADR_0008.contains("不得") && (ADR_0008.contains("自动迁移") || ADR_0008.contains("原位迁移")),
            "startup must not auto-migrate"
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
            assert!(text.contains(sub), "CLI help missing subcommand {sub}: {text}");
        }
        assert!(
            text.contains("LEZI_MIGRATE_NEW_ROOT_PASSWORD"),
            "password env documented"
        );
        assert!(
            text.contains(AUTHORITATIVE_CUTOVER_RUNBOOK)
                || text.contains("copy-back-tls-cutover-runbook.md"),
            "help must point at runbook"
        );
        // No embedded literal secret material / bootstrap value dumps.
        assert!(
            !text.contains("LEZI_BOOTSTRAP_SECRET=")
                || text.contains("LEZI_BOOTSTRAP_SECRET") && !text.contains("LEZI_BOOTSTRAP_SECRET=\"sk_"),
            "must not print secret values"
        );
        assert!(
            !text.contains("sk_live") && !text.contains("BEGIN RSA"),
            "must not print secret material"
        );
        // ADR documents the same subcommands
        for sub in ["migrate", "dry-run", "validate", "copy-out-help", "copy-back-help"] {
            assert!(
                ADR_0013.contains(sub),
                "ADR-0013 must list CLI subcommand {sub}"
            );
        }
        let _ = DATABASE_SCHEMA_VERSION; // target schema is current, not a free number in help alone
    }

    #[test]
    fn readme_deploy_prd_share_boundary_terms_and_runbook_links() {
        // lezi-sync README
        assert!(
            LEZI_SYNC_README.contains("offline-migrate"),
            "lezi-sync README must document offline-migrate"
        );
        assert!(
            LEZI_SYNC_README.contains("维护窗")
                || LEZI_SYNC_README.contains("非") && LEZI_SYNC_README.contains("服务启动"),
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
            LEZI_SYNC_README.contains("departed")
                || LEZI_SYNC_README.contains("Departed")
                || LEZI_SYNC_README.contains("left_at"),
            "ticket 14 disposition in README"
        );
        assert!(
            LEZI_SYNC_README.contains("普通 CD")
                || LEZI_SYNC_README.contains("不执行")
                || LEZI_SYNC_README.contains("不得执行"),
            "ordinary CD does not run offline-migrate"
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
            DEPLOY_MD.contains("普通") && (DEPLOY_MD.contains("不执行") || DEPLOY_MD.contains("不得")),
            "ordinary CD must not execute offline-migrate"
        );

        // Root README pointer
        assert!(
            ROOT_README.contains("offline-migrate") || ROOT_README.contains("离线") && ROOT_README.contains("迁移"),
            "root README should point at offline-migrate boundary"
        );
        assert!(
            ROOT_README.contains("0013")
                || ROOT_README.contains("copy-back-tls-cutover-runbook")
                || ROOT_README.contains("offline-migrate"),
            "root README must link ADR or runbook"
        );

        // PRD surfaces
        assert!(
            TECH_MD.contains("offline-migrate") || TECH_MD.contains("ADR-0013"),
            "tech.md must mention the boundary"
        );
        assert!(
            PRD_README.contains("fresh-current")
                && (PRD_README.contains("offline-migrate")
                    || PRD_README.contains("ADR-0013")
                    || PRD_README.contains("维护窗")),
            "PRD README must keep fresh-current and acknowledge offline cutover boundary"
        );
    }
}
