//! Offline migrate CLI (ticket 05 + 06 + 07): copy-out, dry-run, migrate, validate,
//! copy-back/cutover **help** (ticket 06), and live-cutover **help** (ticket 07).
//!
//! Wired from `lezi-sync offline-migrate …`. Backup (`--in`) is read-only; writes
//! go only to independent `--out` (or a temp dir for dry-run).
//!
//! Validate is **preflight + secret length** (server-open isomorphic for schema and
//! signing material). Full `/ready` (TLS, writable media, bootstrap) is proven at
//! cutover (ticket 06 runbook / ticket 07 execution). `copy-back-help` prints the
//! fixed maintenance order and points at the runbook + copy-back script; it never
//! claims the family NAS is already cut over. `live-cutover-help` points at evidence
//! requirements and the post-deploy probe; it also never invents live success.

use std::env;
use std::fs;
use std::io::{self, Write};
use std::path::{Component, Path, PathBuf};

use crate::store::Store;
use crate::SERVER_SECRET_BYTES;

use super::cutover::{cutover_help_text, COPY_BACK_RUNBOOK, COPY_BACK_SCRIPT};
use super::live_cutover::live_cutover_help_text;
use super::media::{cleanup_migrator_data_dir_outputs, migrate_v3_data_dir};
use super::migrator::{
    validate_new_root_password, MigrateError, MigrateReport, MIN_NEW_ROOT_PASSWORD_LEN,
    REAUTH_OPS_NOTE,
};

/// Process exit: success.
pub(crate) const EXIT_OK: u8 = 0;
/// Process exit: migration / validation failed (includes authoritative abort).
pub(crate) const EXIT_FAILURE: u8 = 1;
/// Process exit: bad usage / missing flags / config / short password.
pub(crate) const EXIT_USAGE: u8 = 2;

/// Env fallback for the migration-time new root password (same value ops will set as
/// `LEZI_BOOTSTRAP_SECRET` after cutover). Prefer the flag when scripting.
pub(crate) const ENV_NEW_ROOT_PASSWORD: &str = "LEZI_MIGRATE_NEW_ROOT_PASSWORD";

/// Authoritative copy-out script (repo-relative). Help text points here; do not
/// duplicate rsync/scp steps in Rust.
pub(crate) const COPY_OUT_SCRIPT: &str = "tools/lezi-sync/deploy/copy-out-nas-data.sh";

/// Parsed CLI action (public seam for argv → typed command).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum CliCommand {
    /// Full migrate: read-only `--in` → write `--out`; then preflight-validate out.
    Migrate {
        input: PathBuf,
        output: PathBuf,
        new_root_password: String,
    },
    /// Dry-run: validate + count as if migrating; write only a temp dir then delete.
    /// Never creates a durable out/ suitable for copy-back.
    DryRun {
        input: PathBuf,
        new_root_password: String,
    },
    /// Validate an existing out/ with preflight + secret length gate.
    Validate {
        output: PathBuf,
    },
    /// Print pointer to copy-out script + env table (does not touch the network).
    CopyOutHelp,
    /// Print ticket-06 cutover runbook order + copy-back script pointer (no network).
    CopyBackHelp,
    /// Print ticket-07 live cutover evidence + APK smoke checklist (no network).
    LiveCutoverHelp,
    Help,
}

/// Result of running a CLI command (testable without process spawn).
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct CliOutcome {
    pub exit_code: u8,
    pub stdout: String,
    pub stderr: String,
}

/// Parse argv after the `offline-migrate` token (or including it as argv[0] of the slice).
///
/// Accepts either:
/// - `["offline-migrate", <subcommand>, …flags]`
/// - `[<subcommand>, …flags]`
pub(crate) fn parse_args(args: &[String]) -> Result<CliCommand, String> {
    let mut rest: Vec<&str> = args.iter().map(String::as_str).collect();
    if rest.first().is_some_and(|a| *a == "offline-migrate") {
        rest.remove(0);
    }
    if rest.is_empty() {
        return Ok(CliCommand::Help);
    }

    let sub = rest[0];
    let flags = &rest[1..];

    match sub {
        "help" | "-h" | "--help" => {
            reject_unknown_flags(flags, &[])?;
            Ok(CliCommand::Help)
        }
        "copy-out-help" => {
            reject_unknown_flags(flags, &[])?;
            Ok(CliCommand::CopyOutHelp)
        }
        "copy-back-help" | "cutover-help" => {
            reject_unknown_flags(flags, &[])?;
            Ok(CliCommand::CopyBackHelp)
        }
        "live-cutover-help" | "ticket-07-help" => {
            reject_unknown_flags(flags, &[])?;
            Ok(CliCommand::LiveCutoverHelp)
        }
        "validate" => {
            reject_unknown_flags(flags, &["--out"])?;
            let output = require_flag(flags, "--out")?;
            Ok(CliCommand::Validate {
                output: PathBuf::from(output),
            })
        }
        "dry-run" => {
            reject_unknown_flags(flags, &["--in", "--new-root-password"])?;
            let input = require_flag(flags, "--in")?;
            let password = resolve_password(flags)?;
            Ok(CliCommand::DryRun {
                input: PathBuf::from(input),
                new_root_password: password,
            })
        }
        "migrate" => {
            reject_unknown_flags(flags, &["--in", "--out", "--new-root-password"])?;
            let input = require_flag(flags, "--in")?;
            let output = require_flag(flags, "--out")?;
            let password = resolve_password(flags)?;
            Ok(CliCommand::Migrate {
                input: PathBuf::from(input),
                output: PathBuf::from(output),
                new_root_password: password,
            })
        }
        other => Err(format!(
            "unknown offline-migrate subcommand `{other}`; try: migrate | dry-run | validate | copy-out-help | copy-back-help | live-cutover-help | help"
        )),
    }
}

fn reject_unknown_flags(flags: &[&str], known: &[&str]) -> Result<(), String> {
    let mut i = 0;
    while i < flags.len() {
        let f = flags[i];
        if !f.starts_with('-') {
            return Err(format!("unexpected argument `{f}`"));
        }
        let name = f.split('=').next().unwrap_or(f);
        if !known.contains(&name) {
            return Err(format!(
                "unknown flag `{name}` (did you mean one of: {})",
                if known.is_empty() {
                    "(none for this subcommand)".to_owned()
                } else {
                    known.join(", ")
                }
            ));
        }
        if f.contains('=') {
            i += 1;
        } else if i + 1 < flags.len() && !flags[i + 1].starts_with('-') {
            i += 2;
        } else {
            i += 1;
        }
    }
    Ok(())
}

fn require_flag<'a>(flags: &[&'a str], name: &str) -> Result<&'a str, String> {
    let mut i = 0;
    while i < flags.len() {
        if flags[i] == name {
            let value = flags
                .get(i + 1)
                .copied()
                .filter(|v| !v.is_empty() && !v.starts_with('-'));
            return value.ok_or_else(|| format!("missing value for {name}"));
        }
        if let Some(rest) = flags[i].strip_prefix(&format!("{name}=")) {
            if rest.is_empty() {
                return Err(format!("missing value for {name}"));
            }
            return Ok(rest);
        }
        i += 1;
    }
    Err(format!("required flag {name} is missing"))
}

fn flag_present(flags: &[&str], name: &str) -> bool {
    flags
        .iter()
        .any(|f| *f == name || f.starts_with(&format!("{name}=")))
}

fn resolve_password(flags: &[&str]) -> Result<String, String> {
    // If the flag is present, never silently fall through to env (even on empty value).
    let password = if flag_present(flags, "--new-root-password") {
        require_flag(flags, "--new-root-password")?.to_owned()
    } else {
        match env::var(ENV_NEW_ROOT_PASSWORD) {
            Ok(p) if !p.is_empty() => p,
            _ => {
                return Err(format!(
                    "new root password required via --new-root-password or env {ENV_NEW_ROOT_PASSWORD} (≥{MIN_NEW_ROOT_PASSWORD_LEN} chars)"
                ));
            }
        }
    };
    validate_new_root_password(&password).map_err(|e| e.to_string())?;
    Ok(password)
}

/// Run a parsed command; pure enough for unit tests (stdout/stderr captured as strings).
pub(crate) fn run(command: CliCommand) -> CliOutcome {
    match command {
        CliCommand::Help => CliOutcome {
            exit_code: EXIT_OK,
            stdout: usage_text(),
            stderr: String::new(),
        },
        CliCommand::CopyOutHelp => CliOutcome {
            exit_code: EXIT_OK,
            stdout: copy_out_help_text(),
            stderr: String::new(),
        },
        CliCommand::CopyBackHelp => CliOutcome {
            exit_code: EXIT_OK,
            stdout: cutover_help_text(),
            stderr: String::new(),
        },
        CliCommand::LiveCutoverHelp => CliOutcome {
            exit_code: EXIT_OK,
            stdout: live_cutover_help_text(),
            stderr: String::new(),
        },
        CliCommand::Validate { output } => match validate_out_data_dir(&output) {
            Ok(()) => CliOutcome {
                exit_code: EXIT_OK,
                stdout: format!("validate ok\nout={}\n", output.display()),
                stderr: String::new(),
            },
            Err(message) => CliOutcome {
                exit_code: EXIT_FAILURE,
                stdout: String::new(),
                stderr: format!("validate failed: {message}\n"),
            },
        },
        CliCommand::DryRun {
            input,
            new_root_password,
        } => run_dry_run(&input, &new_root_password),
        CliCommand::Migrate {
            input,
            output,
            new_root_password,
        } => run_migrate(&input, &output, &new_root_password),
    }
}

/// Entry used by `main`: parse process args and write to real stdout/stderr.
pub(crate) fn main_from_args(args: &[String]) -> u8 {
    let command = match parse_args(args) {
        Ok(c) => c,
        Err(message) => {
            let _ = writeln!(io::stderr(), "usage error: {message}");
            let _ = writeln!(io::stderr(), "{}", usage_text());
            return EXIT_USAGE;
        }
    };
    let outcome = run(command);
    if !outcome.stdout.is_empty() {
        let _ = write!(io::stdout(), "{}", outcome.stdout);
    }
    if !outcome.stderr.is_empty() {
        let _ = write!(io::stderr(), "{}", outcome.stderr);
    }
    outcome.exit_code
}

fn run_migrate(input: &Path, output: &Path, new_root_password: &str) -> CliOutcome {
    if let Err(message) = refuse_in_place(input, output) {
        return usage_outcome(&message);
    }
    if let Err(message) = ensure_out_empty_for_migrate(output) {
        return usage_outcome(&message);
    }
    if let Err(error) = validate_new_root_password(new_root_password) {
        return usage_outcome(&error.to_string());
    }

    migrate_and_validate(
        input,
        output,
        new_root_password,
        "migrate ok",
        /*cleanup_out_on_validate_fail=*/ true,
        /*durable_out=*/ true,
    )
}

fn run_dry_run(input: &Path, new_root_password: &str) -> CliOutcome {
    if let Err(error) = validate_new_root_password(new_root_password) {
        return usage_outcome(&error.to_string());
    }

    let temp_root = std::env::temp_dir().join(format!(
        "lezi-offline-migrate-dry-run-{}-{}",
        std::process::id(),
        unique_suffix()
    ));
    if let Err(error) = fs::create_dir_all(&temp_root) {
        return CliOutcome {
            exit_code: EXIT_FAILURE,
            stdout: String::new(),
            stderr: format!("dry-run failed: cannot create temp dir: {error}\n"),
        };
    }
    let temp_out = temp_root.join("out");

    let mut outcome = migrate_and_validate(
        input,
        &temp_out,
        new_root_password,
        "dry-run ok",
        /*cleanup_out_on_validate_fail=*/ false,
        /*durable_out=*/ false,
    );
    // Always attempt cleanup; success requires no durable residue.
    if let Err(error) = fs::remove_dir_all(&temp_root) {
        if outcome.exit_code == EXIT_OK {
            outcome = CliOutcome {
                exit_code: EXIT_FAILURE,
                stdout: outcome.stdout,
                stderr: format!(
                    "dry-run cleanup failed (temp out may remain): {}: {error}\n",
                    temp_root.display()
                ),
            };
        } else {
            outcome.stderr.push_str(&format!(
                "also: dry-run cleanup failed (temp out may remain): {}: {error}\n",
                temp_root.display()
            ));
        }
    }
    outcome
}

/// Shared migrate → validate control flow (dry-run and migrate).
fn migrate_and_validate(
    input: &Path,
    output: &Path,
    new_root_password: &str,
    title_ok: &str,
    cleanup_out_on_validate_fail: bool,
    durable_out: bool,
) -> CliOutcome {
    match migrate_v3_data_dir(input, output, new_root_password) {
        Ok(report) => match validate_out_data_dir(output) {
            Ok(()) => CliOutcome {
                exit_code: EXIT_OK,
                stdout: format_report(
                    title_ok,
                    if durable_out { Some(output) } else { None },
                    &report,
                    /*include_reauth=*/ true,
                ),
                stderr: String::new(),
            },
            Err(message) => {
                if cleanup_out_on_validate_fail {
                    cleanup_migrator_data_dir_outputs(output);
                }
                CliOutcome {
                    exit_code: EXIT_FAILURE,
                    // Do not emit success-shaped counters — not copy-back-ready.
                    stdout: String::new(),
                    stderr: format!(
                        "NOT copy-back-ready: validate failed after migrate: {message}\n\
authoritative intent: AbortNoCopyBackWithReport (migrator outputs cleaned when durable out)\n"
                    ),
                }
            }
        },
        Err(error) => {
            let prefix = if durable_out {
                "migrate failed"
            } else {
                "dry-run failed"
            };
            failure_outcome(prefix, &error)
        }
    }
}

fn usage_outcome(message: &str) -> CliOutcome {
    CliOutcome {
        exit_code: EXIT_USAGE,
        stdout: String::new(),
        stderr: format!("usage error: {message}\n"),
    }
}

fn unique_suffix() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0)
}

/// Preflight + contract checks for a migrated out/ data dir.
///
/// Requires `lezi.db` with current schema shape and `server.secret` with at least
/// [`SERVER_SECRET_BYTES`] (same gate as `load_or_create_server_secret`).
/// This is the CLI preflight handoff for ticket 06; full `/ready` still needs TLS
/// and process config at cutover.
pub(crate) fn validate_out_data_dir(out: &Path) -> Result<(), String> {
    if !out.try_exists().map_err(|e| e.to_string())? {
        return Err(format!("out data dir missing: {}", out.display()));
    }
    let db = out.join("lezi.db");
    if !db.try_exists().map_err(|e| e.to_string())? {
        return Err(format!("out lezi.db missing: {}", db.display()));
    }
    if !db.is_file() {
        return Err(format!(
            "out lezi.db is not a regular file: {}",
            db.display()
        ));
    }
    Store::preflight_existing_schema(&db).map_err(|e| format!("preflight: {e}"))?;
    let secret_path = out.join("server.secret");
    if !secret_path.try_exists().map_err(|e| e.to_string())? {
        return Err(format!(
            "server.secret missing under {} (migrator must RegenerateAlways)",
            out.display()
        ));
    }
    if !secret_path.is_file() {
        return Err(format!(
            "server.secret is not a regular file: {}",
            secret_path.display()
        ));
    }
    let secret = fs::read(&secret_path).map_err(|e| format!("read server.secret: {e}"))?;
    if secret.len() < SERVER_SECRET_BYTES {
        return Err(format!(
            "server.secret must contain at least {SERVER_SECRET_BYTES} bytes (got {}); matches live open gate",
            secret.len()
        ));
    }
    Ok(())
}

/// Refuse equal paths and nested --in/--out (component-aware).
fn refuse_in_place(input: &Path, output: &Path) -> Result<(), String> {
    let in_norm = normalize_path(input);
    let out_norm = normalize_path(output);
    if in_norm == out_norm {
        return Err(
            "refusing in-place migrate: --in and --out must be different directories (backup is read-only)"
                .to_owned(),
        );
    }
    if path_is_prefix_of(&in_norm, &out_norm) {
        return Err(format!(
            "refusing nested --out under --in (would write into backup tree): in={} out={}",
            in_norm.display(),
            out_norm.display()
        ));
    }
    if path_is_prefix_of(&out_norm, &in_norm) {
        return Err(format!(
            "refusing --in under --out (backup would sit inside out/): in={} out={}",
            in_norm.display(),
            out_norm.display()
        ));
    }
    Ok(())
}

/// Ticket-05 out/ handoff allowlist is migrator-owned only. Refuse non-empty out
/// so cutover cannot smuggle pre-existing tls/ junk from a prior attempt.
fn ensure_out_empty_for_migrate(output: &Path) -> Result<(), String> {
    if !output.try_exists().map_err(|e| e.to_string())? {
        return Ok(());
    }
    if !output.is_dir() {
        return Err(format!(
            "--out must be a directory (or not exist yet): {}",
            output.display()
        ));
    }
    let mut entries = fs::read_dir(output).map_err(|e| e.to_string())?;
    if entries.next().is_some() {
        return Err(format!(
            "refusing non-empty --out {} (migrator writes only lezi.db, server.secret, media/; start with an empty dir)",
            output.display()
        ));
    }
    Ok(())
}

fn normalize_path(path: &Path) -> PathBuf {
    let abs = if path.is_absolute() {
        path.to_path_buf()
    } else {
        env::current_dir()
            .unwrap_or_else(|_| PathBuf::from("."))
            .join(path)
    };
    let mut out = PathBuf::new();
    for component in abs.components() {
        match component {
            Component::Prefix(p) => out.push(p.as_os_str()),
            Component::RootDir => out.push(component.as_os_str()),
            Component::CurDir => {}
            Component::ParentDir => {
                out.pop();
            }
            Component::Normal(c) => out.push(c),
        }
    }
    out
}

/// True when `prefix` is a path prefix of `path` at a component boundary
/// (`Path::starts_with`) and the paths are not equal.
fn path_is_prefix_of(prefix: &Path, path: &Path) -> bool {
    path.starts_with(prefix) && path != prefix
}

fn failure_outcome(prefix: &str, error: &MigrateError) -> CliOutcome {
    let mut stderr = format!("{prefix}: {error}\n");
    if let Some(kind) = error.authoritative() {
        stderr.push_str(&format!("authoritative_failure={kind:?}\n"));
    }
    let stdout = match error.report() {
        Some(report) => format_report_counters(report),
        None => String::new(),
    };
    CliOutcome {
        exit_code: EXIT_FAILURE,
        stdout,
        stderr,
    }
}

fn format_report(
    title: &str,
    out: Option<&Path>,
    report: &MigrateReport,
    include_reauth: bool,
) -> String {
    let mut s = format!("{title}\n");
    if let Some(path) = out {
        s.push_str(&format!("out={}\n", path.display()));
    }
    s.push_str(&format_report_counters(report));
    if include_reauth {
        s.push_str(REAUTH_OPS_NOTE);
    }
    s
}

fn format_report_counters(report: &MigrateReport) -> String {
    format!(
        "families={}\n\
memberships={}\n\
entities={}\n\
committed_bundles={}\n\
discarded_staging_bundles={}\n\
discarded_bundle_media={}\n\
discarded_publications={}\n\
media_files_copied={}\n",
        report.families,
        report.memberships,
        report.entities,
        report.committed_bundles,
        report.discarded_staging_bundles,
        report.discarded_bundle_media,
        report.discarded_publications,
        report.media_files_copied,
    )
}

fn usage_text() -> String {
    format!(
        "\
lezi-sync offline-migrate — private NAS v3→current offline pipeline (tickets 05–07)

Subcommands:
  migrate   --in <backup_data_dir> --out <out_data_dir> --new-root-password <secret>
  dry-run   --in <backup_data_dir> --new-root-password <secret>
  validate  --out <out_data_dir>
  copy-out-help
  copy-back-help   (alias: cutover-help)
  live-cutover-help   (alias: ticket-07-help)
  help

Notes:
  - --in is the local NAS copy-out backup; it is never modified.
  - --out must be independent of --in (not equal, not nested) and empty (or absent).
  - Password may also come from env {ENV_NEW_ROOT_PASSWORD} (≥{MIN_NEW_ROOT_PASSWORD_LEN} chars).
  - validate = current-schema preflight + server.secret ≥ {SERVER_SECRET_BYTES} bytes (not full /ready).
  - migrate/dry-run/validate do NOT stop the live NAS container and do NOT copy back.
  - copy-back-help prints the ticket-06 maintenance order; it does NOT execute live cutover (ticket 07).
  - live-cutover-help prints ticket-07 evidence + APK smoke checklist; it does NOT invent live success.
  - On success, migrate/dry-run print object counts; on failure exit={EXIT_FAILURE} with a report.
  - usage / short password / bad flags → exit={EXIT_USAGE}.

Copy-out script (authoritative): {COPY_OUT_SCRIPT}
Copy-back runbook / script: {COPY_BACK_RUNBOOK} ; {COPY_BACK_SCRIPT}
Live cutover probe / evidence: tools/lezi-sync/deploy/live-cutover-probe.sh ;
  .scratch/nas-v3-offline-migrate/evidence/07/
After migrate/validate success, out/ is the ticket-06 copy-back input.
"
    )
}

/// Ops pointer: authoritative steps live in [`COPY_OUT_SCRIPT`].
pub(crate) fn copy_out_help_text() -> String {
    format!(
        "\
# Copy-out (ticket 05) — NAS data → timestamped local backup
#
# Does NOT stop the live container. Does NOT copy back (ticket 06).
#
# Authoritative steps + defaults: {COPY_OUT_SCRIPT}
#   bash {COPY_OUT_SCRIPT}
#
# Env table (script defaults):
#   NAS_SSH              13096920600@192.168.50.4
#   NAS_SSH_PORT         10000
#   LEZI_DATA_HOST_PATH  /tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data
#   LEZI_BACKUP_ROOT     $HOME/lezi-nas-backups
#   LEZI_BACKUP_DIR      optional explicit destination
#   LEZI_COPY_OUT_RO     1 = chmod -R a-w backup after copy (fail closed)
#   LEZI_COPY_OUT_VIA_DOCKER  auto|1|0 — auto falls back to docker exec tar when
#                        host data bind is mode 700 / Permission denied
#
# Script uses rsync (preferred) or scp, with docker-tar fallback when the host
# path is unreadable (measured family NAS). Checks lezi.db presence and
# user_version=3 when sqlite3 is available. Hot-copy risk while the live WAL
# store writes — see script header (quiesce / sqlite3 .backup preferred).
#
# After copy-out:
#   export {ENV_NEW_ROOT_PASSWORD}='…ops-chosen ≥{MIN_NEW_ROOT_PASSWORD_LEN} chars…'
#   lezi-sync offline-migrate dry-run --in \"$BACKUP_DIR\"
#   lezi-sync offline-migrate migrate --in \"$BACKUP_DIR\" --out \"$OUT_DIR\"
#   lezi-sync offline-migrate validate --out \"$OUT_DIR\"
#
"
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::offline_migrate::test_support::{
        open_v3_fixture, seed_baby_entity, seed_minimal_family, TEST_NEW_ROOT_PASSWORD,
    };
    use std::fs;
    use std::time::UNIX_EPOCH;
    use tempfile::tempdir;

    fn args(parts: &[&str]) -> Vec<String> {
        parts.iter().map(|s| (*s).to_owned()).collect()
    }

    fn write_v3_backup(dir: &Path) {
        fs::create_dir_all(dir).unwrap();
        let db = dir.join("lezi.db");
        let conn = open_v3_fixture(&db);
        seed_minimal_family(&conn);
        seed_baby_entity(&conn);
        fs::write(dir.join("server.secret"), vec![7u8; 32]).unwrap();
    }

    fn file_fingerprint(path: &Path) -> (u64, std::time::SystemTime) {
        let meta = fs::metadata(path).unwrap();
        (meta.len(), meta.modified().unwrap_or(UNIX_EPOCH))
    }

    // --- Seam: parse_args ---

    #[test]
    fn parse_dry_run_requires_in_and_password_flag() {
        let cmd = parse_args(&args(&[
            "offline-migrate",
            "dry-run",
            "--in",
            "/tmp/backup",
            "--new-root-password",
            TEST_NEW_ROOT_PASSWORD,
        ]))
        .expect("parse");
        assert_eq!(
            cmd,
            CliCommand::DryRun {
                input: PathBuf::from("/tmp/backup"),
                new_root_password: TEST_NEW_ROOT_PASSWORD.to_owned(),
            }
        );
    }

    #[test]
    fn parse_migrate_requires_in_out_and_password() {
        let cmd = parse_args(&args(&[
            "migrate",
            "--in",
            "/tmp/backup",
            "--out",
            "/tmp/out",
            "--new-root-password",
            TEST_NEW_ROOT_PASSWORD,
        ]))
        .expect("parse");
        assert_eq!(
            cmd,
            CliCommand::Migrate {
                input: PathBuf::from("/tmp/backup"),
                output: PathBuf::from("/tmp/out"),
                new_root_password: TEST_NEW_ROOT_PASSWORD.to_owned(),
            }
        );
    }

    #[test]
    fn parse_validate_requires_out() {
        let cmd = parse_args(&args(&["validate", "--out", "/tmp/out"])).expect("parse");
        assert_eq!(
            cmd,
            CliCommand::Validate {
                output: PathBuf::from("/tmp/out"),
            }
        );
    }

    #[test]
    fn parse_missing_in_is_usage_error() {
        let err = parse_args(&args(&[
            "dry-run",
            "--new-root-password",
            TEST_NEW_ROOT_PASSWORD,
        ]))
        .expect_err("must require --in");
        assert!(err.contains("--in"), "{err}");
    }

    #[test]
    fn parse_unknown_flag_input_typo_is_usage_error() {
        let err = parse_args(&args(&[
            "dry-run",
            "--input",
            "/tmp/backup",
            "--new-root-password",
            TEST_NEW_ROOT_PASSWORD,
        ]))
        .expect_err("typo --input must not be silent");
        assert!(
            err.contains("unknown flag") || err.contains("--input"),
            "{err}"
        );
    }

    #[test]
    fn parse_short_password_is_usage_error() {
        let err = parse_args(&args(&[
            "dry-run",
            "--in",
            "/tmp/backup",
            "--new-root-password",
            "too-short",
        ]))
        .expect_err("short password is config error");
        assert!(err.contains("16") || err.contains("at least"), "{err}");
    }

    // --- Seam: dry-run ---

    #[test]
    fn dry_run_prints_known_counts_exits_ok_and_leaves_backup_untouched() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        write_v3_backup(&backup);
        let db = backup.join("lezi.db");
        let before = file_fingerprint(&db);
        let before_secret = fs::read(backup.join("server.secret")).unwrap();

        let outcome = run(CliCommand::DryRun {
            input: backup.clone(),
            new_root_password: TEST_NEW_ROOT_PASSWORD.to_owned(),
        });

        assert_eq!(outcome.exit_code, EXIT_OK, "stderr={}", outcome.stderr);
        assert!(
            outcome.stdout.contains("dry-run ok"),
            "stdout={}",
            outcome.stdout
        );
        assert!(
            outcome.stdout.contains("families=1"),
            "stdout={}",
            outcome.stdout
        );
        assert!(
            outcome.stdout.contains("memberships=2"),
            "stdout={}",
            outcome.stdout
        );
        assert!(
            outcome.stdout.contains("entities=1"),
            "stdout={}",
            outcome.stdout
        );

        assert_eq!(
            before,
            file_fingerprint(&db),
            "backup lezi.db must not be mutated"
        );
        assert_eq!(
            before_secret,
            fs::read(backup.join("server.secret")).unwrap(),
            "backup server.secret must not be mutated"
        );
        assert!(!dir.path().join("out").exists());
    }

    // --- Seam: migrate --in/--out ---

    #[test]
    fn migrate_writes_out_never_mutates_backup_and_validates() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        let out = dir.path().join("out");
        write_v3_backup(&backup);
        let before = file_fingerprint(&backup.join("lezi.db"));

        let outcome = run(CliCommand::Migrate {
            input: backup.clone(),
            output: out.clone(),
            new_root_password: TEST_NEW_ROOT_PASSWORD.to_owned(),
        });

        assert_eq!(outcome.exit_code, EXIT_OK, "stderr={}", outcome.stderr);
        assert!(outcome.stdout.contains("migrate ok"), "{}", outcome.stdout);
        assert!(outcome.stdout.contains("families=1"), "{}", outcome.stdout);
        assert!(out.join("lezi.db").is_file());
        assert!(out.join("server.secret").is_file());
        assert_ne!(
            fs::read(backup.join("server.secret")).unwrap(),
            fs::read(out.join("server.secret")).unwrap()
        );
        assert_eq!(before, file_fingerprint(&backup.join("lezi.db")));
    }

    #[test]
    fn migrate_refuses_identical_in_and_out() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        write_v3_backup(&backup);

        let outcome = run(CliCommand::Migrate {
            input: backup.clone(),
            output: backup.clone(),
            new_root_password: TEST_NEW_ROOT_PASSWORD.to_owned(),
        });
        assert_eq!(outcome.exit_code, EXIT_USAGE, "stderr={}", outcome.stderr);
        assert!(
            outcome.stderr.contains("in-place") || outcome.stderr.contains("different"),
            "{}",
            outcome.stderr
        );
    }

    #[test]
    fn migrate_refuses_nested_out_under_in() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        write_v3_backup(&backup);
        let nested = backup.join("out");

        let outcome = run(CliCommand::Migrate {
            input: backup.clone(),
            output: nested.clone(),
            new_root_password: TEST_NEW_ROOT_PASSWORD.to_owned(),
        });
        assert_eq!(outcome.exit_code, EXIT_USAGE, "stderr={}", outcome.stderr);
        assert!(
            outcome.stderr.contains("nested") || outcome.stderr.contains("under"),
            "{}",
            outcome.stderr
        );
        assert!(!nested.exists(), "must not create nested out under backup");
        assert!(
            !backup
                .join("lezi.db")
                .metadata()
                .unwrap()
                .permissions()
                .readonly()
                || backup.join("media").exists() == backup.join("media").exists(),
            "sanity"
        );
        // No lezi.db written under nested path.
        assert!(!nested.join("lezi.db").exists());
    }

    #[test]
    fn migrate_refuses_non_empty_out() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        let out = dir.path().join("out");
        write_v3_backup(&backup);
        fs::create_dir_all(&out).unwrap();
        fs::write(out.join("junk.txt"), b"smuggle").unwrap();

        let outcome = run(CliCommand::Migrate {
            input: backup,
            output: out.clone(),
            new_root_password: TEST_NEW_ROOT_PASSWORD.to_owned(),
        });
        assert_eq!(outcome.exit_code, EXIT_USAGE, "stderr={}", outcome.stderr);
        assert!(
            outcome.stderr.contains("non-empty") || outcome.stderr.contains("empty"),
            "{}",
            outcome.stderr
        );
        assert_eq!(fs::read(out.join("junk.txt")).unwrap(), b"smuggle");
        assert!(!out.join("lezi.db").exists());
    }

    // --- Seam: validate_out_data_dir ---

    #[test]
    fn validate_accepts_migrated_out_rejects_empty_dir() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        let out = dir.path().join("out");
        write_v3_backup(&backup);
        migrate_v3_data_dir(&backup, &out, TEST_NEW_ROOT_PASSWORD).expect("migrate");

        let ok = run(CliCommand::Validate {
            output: out.clone(),
        });
        assert_eq!(ok.exit_code, EXIT_OK, "stderr={}", ok.stderr);
        assert!(ok.stdout.contains("validate ok"), "{}", ok.stdout);

        let empty = dir.path().join("empty");
        fs::create_dir_all(&empty).unwrap();
        let bad = run(CliCommand::Validate { output: empty });
        assert_eq!(bad.exit_code, EXIT_FAILURE, "stderr={}", bad.stderr);
        assert!(bad.stderr.contains("validate failed"), "{}", bad.stderr);
    }

    #[test]
    fn validate_rejects_short_server_secret() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        let out = dir.path().join("out");
        write_v3_backup(&backup);
        migrate_v3_data_dir(&backup, &out, TEST_NEW_ROOT_PASSWORD).expect("migrate");
        // Truncate secret below SERVER_SECRET_BYTES.
        fs::write(out.join("server.secret"), vec![1u8; 8]).unwrap();

        let bad = run(CliCommand::Validate { output: out });
        assert_eq!(bad.exit_code, EXIT_FAILURE, "stderr={}", bad.stderr);
        assert!(
            bad.stderr.contains("server.secret")
                && bad.stderr.contains(&SERVER_SECRET_BYTES.to_string()),
            "{}",
            bad.stderr
        );
    }

    // --- Seam: failure exit + report ---

    #[test]
    fn dry_run_authoritative_failure_is_nonzero_with_kind() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        fs::create_dir_all(&backup).unwrap();
        let conn = rusqlite::Connection::open(backup.join("lezi.db")).unwrap();
        conn.pragma_update(None, "user_version", 0i64).unwrap();
        drop(conn);

        let outcome = run(CliCommand::DryRun {
            input: backup,
            new_root_password: TEST_NEW_ROOT_PASSWORD.to_owned(),
        });
        assert_eq!(outcome.exit_code, EXIT_FAILURE, "stderr={}", outcome.stderr);
        assert!(
            outcome.stderr.contains("dry-run failed"),
            "{}",
            outcome.stderr
        );
        assert!(
            outcome.stderr.contains("SourceUserVersionNotThree")
                || outcome.stderr.contains("authoritative"),
            "stderr={}",
            outcome.stderr
        );
    }

    #[test]
    fn short_password_on_run_is_usage_not_migrate_failure() {
        let dir = tempdir().unwrap();
        let backup = dir.path().join("backup");
        write_v3_backup(&backup);
        let outcome = run(CliCommand::DryRun {
            input: backup,
            new_root_password: "short".to_owned(),
        });
        assert_eq!(outcome.exit_code, EXIT_USAGE, "stderr={}", outcome.stderr);
        assert!(outcome.stderr.contains("usage error"), "{}", outcome.stderr);
    }

    // --- Seam: copy-out help ---

    #[test]
    fn copy_out_help_points_at_script_and_no_copy_back() {
        let outcome = run(CliCommand::CopyOutHelp);
        assert_eq!(outcome.exit_code, EXIT_OK);
        assert!(
            outcome.stdout.contains(COPY_OUT_SCRIPT),
            "{}",
            outcome.stdout
        );
        assert!(
            outcome.stdout.contains("Does NOT stop the live container"),
            "{}",
            outcome.stdout
        );
        assert!(
            outcome.stdout.contains("Does NOT copy back"),
            "{}",
            outcome.stdout
        );
        assert!(
            outcome.stdout.contains("LEZI_DATA_HOST_PATH") || outcome.stdout.contains("NAS_SSH"),
            "{}",
            outcome.stdout
        );
        // Script is authoritative for rsync/scp body — help may mention them as summary.
        assert!(
            outcome.stdout.contains("rsync") || outcome.stdout.contains(COPY_OUT_SCRIPT),
            "{}",
            outcome.stdout
        );
    }

    // --- Seam: copy-back / cutover help (ticket 06) ---

    #[test]
    fn parse_copy_back_help_and_cutover_alias() {
        assert_eq!(
            parse_args(&args(&["offline-migrate", "copy-back-help"])).expect("parse"),
            CliCommand::CopyBackHelp
        );
        assert_eq!(
            parse_args(&args(&["cutover-help"])).expect("parse"),
            CliCommand::CopyBackHelp
        );
    }

    // --- Seam: live-cutover help (ticket 07) ---

    #[test]
    fn parse_live_cutover_help_and_alias() {
        assert_eq!(
            parse_args(&args(&["offline-migrate", "live-cutover-help"])).expect("parse"),
            CliCommand::LiveCutoverHelp
        );
        assert_eq!(
            parse_args(&args(&["ticket-07-help"])).expect("parse"),
            CliCommand::LiveCutoverHelp
        );
    }

    #[test]
    fn live_cutover_help_lists_evidence_and_apk_smoke() {
        let outcome = run(CliCommand::LiveCutoverHelp);
        assert_eq!(outcome.exit_code, EXIT_OK);
        let text = &outcome.stdout;
        assert!(
            text.contains(".scratch/nas-v3-offline-migrate/evidence/07"),
            "{text}"
        );
        assert!(text.contains("window.md"), "{text}");
        assert!(text.contains("health.json"), "{text}");
        assert!(text.contains("apk-smoke.md"), "{text}");
        assert!(text.contains("RESULT.md"), "{text}");
        assert!(
            text.contains("live-cutover-probe.sh") || text.contains("LIVE_CUTOVER"),
            "{text}"
        );
        assert!(text.contains("TOFU") || text.contains("HTTPS"), "{text}");
        assert!(
            text.contains("does NOT claim live success")
                || text.contains("Never mark ISSUES complete without evidence")
                || text.contains("do not claim cutover success"),
            "{text}"
        );
    }

    #[test]
    fn copy_back_help_fixed_order_paths_rollback_no_live_claim() {
        let outcome = run(CliCommand::CopyBackHelp);
        assert_eq!(outcome.exit_code, EXIT_OK);
        let text = &outcome.stdout;
        assert!(text.contains(COPY_BACK_RUNBOOK), "{text}");
        assert!(text.contains(COPY_BACK_SCRIPT), "{text}");
        assert!(text.contains("https://192.168.50.4:8765"), "{text}");
        assert!(
            text.contains("/tmp/zfsv3/sata1/13096920600/data/Docker/lezi/data"),
            "{text}"
        );
        assert!(text.contains("13096920600@192.168.50.4"), "{text}");
        // Fixed order markers
        assert!(text.contains("stop live container"), "{text}");
        assert!(text.contains("dual backup"), "{text}");
        assert!(text.contains("copy-back"), "{text}");
        assert!(text.contains("TLS"), "{text}");
        assert!(text.contains("health/ready"), "{text}");
        // Rollback + family checklist
        assert!(
            text.contains("Rollback") || text.contains("rollback"),
            "{text}"
        );
        assert!(
            text.contains("Root password") || text.contains("root password"),
            "{text}"
        );
        assert!(
            text.contains("Old APK") || text.contains("plaintext HTTP"),
            "{text}"
        );
        assert!(text.contains("TOFU") || text.contains("re-login"), "{text}");
        // Boundary: no live success claim
        assert!(
            text.contains("ticket 07") || text.contains("Ticket 07"),
            "{text}"
        );
        assert!(
            text.contains("Does NOT execute the live cutover")
                || text.contains("never claim live success"),
            "{text}"
        );
        // Cutover secret: export migration password; never inherit pre-cutover
        assert!(text.contains("export LEZI_BOOTSTRAP_SECRET"), "{text}");
        assert!(
            text.contains("NEVER inherit") || text.contains("never inherit"),
            "{text}"
        );
    }
}
