//! Architecture boundary contract for `offline-migrate` (audit remediation ticket 21).
//!
//! Public seam under test: **CLI usage** — subcommands match shipped help;
//! help stdout never prints literal secret values.

#[cfg(test)]
mod tests {
    use crate::offline_migrate::cli::{run, CliCommand};
    use crate::offline_migrate::cutover::COPY_BACK_RUNBOOK;

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

        // Reject real-looking secret material on CLI help stdout only.
        assert_no_literal_secret_material(&text);
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
}
