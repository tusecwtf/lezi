// Sequential multi-agent fix + accept for adversarial ISSUES, then server 0.2.3
// and Room/server DB compatibility proof.
//
// Source of truth:
//   docs/reviews/adversarial-multiagent-2026-07-25/synthesis/ISSUES.md
//
// Order (default full run):
//   Bootstrap → P1 Android batches → P1 Server → version 0.2.3 → DB compat
//   → P2 → P3 → full gates → adversarial accept → report
//
// Args (object; all optional):
//   issues_path: string   — default docs/reviews/adversarial-multiagent-2026-07-25/synthesis/ISSUES.md
//   report_dir: string    — default docs/reviews/adversarial-fix-0.2.3
//   target_version: string — default "0.2.3" (lezi-sync + App versionName bump)
//   skip_p1: bool
//   skip_p2: bool
//   skip_p3: bool
//   skip_server: bool     — skip ISS-007 and server half of media integrity
//   skip_version: bool
//   skip_db: bool
//   skip_gates: bool
//   stop_on_verify_fail: bool — default true; await_user when a batch fails accept
//
// Budget: prefer agent_budget >= 48 (full ~22 sequential agents).
//
// Run:
//   /adversarial-fix-all-023
//   /workflow adversarial-fix-all-023
//   /workflow adversarial-fix-all-023 {"skip_p3":true}
//   /workflow adversarial-fix-all-023 {"skip_p2":true,"skip_p3":true}

let meta = #{
    name: "adversarial-fix-all-023",
    description: "Sequentially fix adversarial ISSUES (P1→server→0.2.3→DB compat→P2/P3), gates, and acceptance report",
    when_to_use: "After adversarial-multiagent review; close all open ISS with code fixes, bump lezi-sync/app to 0.2.3, prove DB compatibility",
    phases: [
        #{ title: "Bootstrap", detail: "Load ISSUES, baseline versions, HEAD" },
        #{ title: "P1 Android", detail: "ISS-001/002 then 003/005 then 004/006/008 with verify" },
        #{ title: "P1 Server", detail: "ISS-007 deploy hardening + optional media integrity" },
        #{ title: "Version 0.2.3", detail: "Bump lezi-sync and App after server fixes" },
        #{ title: "DB compat", detail: "Room 1→N migrations + server SQLite existing volume" },
        #{ title: "P2 fixes", detail: "Remaining P2 ISS in ordered batches" },
        #{ title: "P3 fixes", detail: "Nits and small polish" },
        #{ title: "Full gates", detail: "gradlew test + cargo test/clippy" },
        #{ title: "Accept", detail: "Adversarial re-check closed ISS + report" },
    ],
};

// --- Schemas ---

let boot_schema = #{
    "type": "object",
    "required": ["ok", "summary", "head", "open_p1", "open_p2", "open_p3", "app_version", "server_version"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "head": #{ "type": "string" },
        "open_p1": #{ "type": "array", "items": #{ "type": "string" } },
        "open_p2": #{ "type": "array", "items": #{ "type": "string" } },
        "open_p3": #{ "type": "array", "items": #{ "type": "string" } },
        "app_version": #{ "type": "string" },
        "server_version": #{ "type": "string" },
        "notes": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let fix_schema = #{
    "type": "object",
    "required": ["ok", "batch", "status", "summary", "issues_closed", "files_changed", "tests_run"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "batch": #{ "type": "string" },
        "status": #{ "type": "string", "enum": ["done", "partial", "blocked", "skipped"] },
        "summary": #{ "type": "string" },
        "issues_closed": #{ "type": "array", "items": #{ "type": "string" } },
        "issues_open": #{ "type": "array", "items": #{ "type": "string" } },
        "files_changed": #{ "type": "array", "items": #{ "type": "string" } },
        "tests_run": #{ "type": "array", "items": #{ "type": "string" } },
        "tests_passed": #{ "type": "boolean" },
        "blockers": #{ "type": "array", "items": #{ "type": "string" } },
        "notes": #{ "type": "string" },
    },
};

let verify_schema = #{
    "type": "object",
    "required": ["ok", "batch", "passed", "summary", "evidence"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "batch": #{ "type": "string" },
        "passed": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "evidence": #{ "type": "array", "items": #{ "type": "string" } },
        "failures": #{ "type": "array", "items": #{ "type": "string" } },
        "issues_still_open": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let version_schema = #{
    "type": "object",
    "required": ["ok", "target", "summary", "files_changed", "app_version_name", "server_version"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "target": #{ "type": "string" },
        "summary": #{ "type": "string" },
        "files_changed": #{ "type": "array", "items": #{ "type": "string" } },
        "app_version_name": #{ "type": "string" },
        "app_version_code": #{ "type": "integer" },
        "server_version": #{ "type": "string" },
        "docs_updated": #{ "type": "array", "items": #{ "type": "string" } },
        "notes": #{ "type": "string" },
    },
};

let db_schema = #{
    "type": "object",
    "required": ["ok", "passed", "summary", "room_version", "migration_chain_ok", "no_destructive_fallback", "server_schema_compat"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "passed": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "room_version": #{ "type": "integer" },
        "migration_chain_ok": #{ "type": "boolean" },
        "no_destructive_fallback": #{ "type": "boolean" },
        "server_schema_compat": #{ "type": "boolean" },
        "evidence": #{ "type": "array", "items": #{ "type": "string" } },
        "failures": #{ "type": "array", "items": #{ "type": "string" } },
        "new_migrations": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let gates_schema = #{
    "type": "object",
    "required": ["ok", "passed", "summary", "gradle_ok", "cargo_test_ok", "cargo_clippy_ok"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "passed": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "gradle_ok": #{ "type": "boolean" },
        "cargo_test_ok": #{ "type": "boolean" },
        "cargo_clippy_ok": #{ "type": "boolean" },
        "commands": #{ "type": "array", "items": #{ "type": "string" } },
        "failures": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let accept_schema = #{
    "type": "object",
    "required": ["ok", "passed", "summary", "closed", "still_open", "accepted_risks"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "passed": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "closed": #{ "type": "array", "items": #{ "type": "string" } },
        "still_open": #{ "type": "array", "items": #{ "type": "string" } },
        "accepted_risks": #{ "type": "array", "items": #{ "type": "string" } },
        "regressions": #{ "type": "array", "items": #{ "type": "string" } },
        "evidence": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let report_schema = #{
    "type": "object",
    "required": ["ok", "path", "summary", "version", "db_compat_passed", "gates_passed"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "path": #{ "type": "string" },
        "summary": #{ "type": "string" },
        "version": #{ "type": "string" },
        "db_compat_passed": #{ "type": "boolean" },
        "gates_passed": #{ "type": "boolean" },
        "issues_updated_path": #{ "type": "string" },
        "remaining": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

// --- Args ---

let issues_path = "docs/reviews/adversarial-multiagent-2026-07-25/synthesis/ISSUES.md";
let report_dir = "docs/reviews/adversarial-fix-0.2.3";
let target_version = "0.2.3";
let skip_p1 = false;
let skip_p2 = false;
let skip_p3 = false;
let skip_server = false;
let skip_version = false;
let skip_db = false;
let skip_gates = false;
let stop_on_verify_fail = true;

if args != () {
    if args.issues_path != () && type_of(args.issues_path) == "string" { issues_path = args.issues_path; }
    if args.report_dir != () && type_of(args.report_dir) == "string" { report_dir = args.report_dir; }
    if args.target_version != () && type_of(args.target_version) == "string" { target_version = args.target_version; }
    if args.skip_p1 != () && args.skip_p1 == true { skip_p1 = true; }
    if args.skip_p2 != () && args.skip_p2 == true { skip_p2 = true; }
    if args.skip_p3 != () && args.skip_p3 == true { skip_p3 = true; }
    if args.skip_server != () && args.skip_server == true { skip_server = true; }
    if args.skip_version != () && args.skip_version == true { skip_version = true; }
    if args.skip_db != () && args.skip_db == true { skip_db = true; }
    if args.skip_gates != () && args.skip_gates == true { skip_gates = true; }
    if args.stop_on_verify_fail != () && args.stop_on_verify_fail == false { stop_on_verify_fail = false; }
}

// Track batch outcomes for final report (plain strings).
let batch_log = [];
let version_result = #{ ok: false, target: target_version, summary: "not run", app_version_name: "", server_version: "" };
let db_result = #{ ok: false, passed: false, summary: "not run" };
let gates_result = #{ ok: false, passed: false, summary: "not run" };
let accept_result = #{ ok: false, passed: false, summary: "not run", still_open: [] };

fn log_batch(batch_log, name, status, summary) {
    let line = name + " | " + status + " | " + summary;
    batch_log.push(line);
    log(line);
    batch_log
}

fn maybe_gate_fail(stop_on_verify_fail, batch, passed, summary) {
    // Only gate on explicit false; unit/missing (canned host) continues.
    if passed != false {
        return;
    }
    let msg = "VERIFY FAILED for batch " + batch + ": " + summary;
    log(msg);
    if stop_on_verify_fail == true {
        await_user("verification", msg + " Resume after you fix residual issues, or set stop_on_verify_fail=false.");
    }
}

// ============================================================================
// Bootstrap
// ============================================================================

phase("Bootstrap");
log("Bootstrap: read ISSUES and baseline versions");

let boot_prompt = "";
boot_prompt += "You are the bootstrap agent for lezi adversarial fix workflow.\n";
boot_prompt += "Repo root: /home/zhangtianshu/lezi\n";
boot_prompt += "MUST use tools: run_terminal_command, read_file, grep. Do not answer from memory.\n\n";
boot_prompt += "Tasks:\n";
boot_prompt += "1. git rev-parse HEAD and shortstat.\n";
boot_prompt += "2. Read the issues file at: " + issues_path + "\n";
boot_prompt += "3. List open ISS-xxx by severity (P1/P2/P3). Treat Status open or missing as open.\n";
boot_prompt += "4. Read app versionName from app/build.gradle.kts.\n";
boot_prompt += "5. Read lezi-sync version from tools/lezi-sync/Cargo.toml and docker-compose image tag defaults.\n";
boot_prompt += "6. Confirm Room DB version in LeziDatabase.kt and schemas/ directory listing.\n\n";
boot_prompt += "Return structured output only. open_p1/open_p2/open_p3 must be ISS ids like ISS-001.\n";
boot_prompt += "ok=true if ISSUES file exists and is readable.\n";

let boot = agent(boot_prompt, #{
    label: "bootstrap",
    capability_mode: "execute",
    output_schema: boot_schema,
});

if boot == () || !boot.success || boot.output == () {
    pause("infra", "Bootstrap agent failed. Check tools and issues_path.");
}
if boot.output.ok == false {
    await_user("verification", "Bootstrap ok=false: " + boot.output.summary);
}
batch_log = log_batch(batch_log, "bootstrap", "done", boot.output.summary);

// ============================================================================
// P1 Android — sequential batches with verify
// ============================================================================

phase("P1 Android");

if skip_p1 == true {
    batch_log = log_batch(batch_log, "p1-android", "skipped", "skip_p1=true");
} else {
    // --- Batch A: ISS-001 media cursor + ISS-002 open-sleep ---
    let p1a_prompt = "";
    p1a_prompt += "You are a fix agent for lezi. Repo: /home/zhangtianshu/lezi\n";
    p1a_prompt += "Read " + issues_path + " for ISS-001 and ISS-002 full text.\n";
    p1a_prompt += "USE tools (read_file, grep, search_replace, run_terminal_command). Implement real code fixes.\n\n";
    p1a_prompt += "Batch p1-media-sleep:\n";
    p1a_prompt += "ISS-001: Fix pull so missing media bytes do NOT block cursor advance.\n";
    p1a_prompt += "  Primary path: RealSyncPort downloadMissingMedia / updateCursor ordering.\n";
    p1a_prompt += "  Prefer client degrade: skip/404 soft-fail, still updateCursor; optional queue.\n";
    p1a_prompt += "  Add unit test: media GET 404 → cursor still advances.\n";
    p1a_prompt += "  Optional small server helper only if needed; prefer client-first.\n";
    p1a_prompt += "ISS-002: Prevent multi open-sleep / heal on sync applyRecord + local paths.\n";
    p1a_prompt += "  After pull or on apply, per baby keep at most one open sleep (latest); close/heal others with anomaly if needed.\n";
    p1a_prompt += "  Add/adjust CareLog or RealSyncPort tests.\n\n";
    p1a_prompt += "Run focused tests: ./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest\n";
    p1a_prompt += "Do NOT bump versions. Do NOT commit unless asked.\n";
    p1a_prompt += "Return issues_closed as ISS-001/ISS-002 when fixed.\n";

    let p1a = agent(p1a_prompt, #{
        label: "fix:p1-media-sleep",
        capability_mode: "all",
        output_schema: fix_schema,
    });
    if p1a == () || !p1a.success || p1a.output == () {
        await_user("verification", "p1-media-sleep fix agent failed. Resume after repair.");
    } else {
        if p1a.output.ok == false {
            await_user("verification", "p1-media-sleep fix ok=false: " + p1a.output.summary);
        }
        let st = if p1a.output.status != () { p1a.output.status } else { "unknown" };
        let sm = if p1a.output.summary != () { p1a.output.summary } else { "" };
        batch_log = log_batch(batch_log, "fix:p1-media-sleep", st, sm);
    }

    let v1a_prompt = "";
    v1a_prompt += "Adversarial VERIFY batch p1-media-sleep for lezi at /home/zhangtianshu/lezi\n";
    v1a_prompt += "Read ISS-001/ISS-002 in " + issues_path + " and the CURRENT code (not memory).\n";
    v1a_prompt += "Confirm with grep/read_file:\n";
    v1a_prompt += "1) Missing media bytes no longer prevent preferences.updateCursor after pull.\n";
    v1a_prompt += "2) Sync apply or heal prevents multi open-sleep hanging forever.\n";
    v1a_prompt += "3) New/updated tests exist and run: ./gradlew :sync:testDebugUnitTest :domain:testDebugUnitTest\n";
    v1a_prompt += "passed=true only with concrete path:line evidence in evidence array.\n";
    v1a_prompt += "If still broken, passed=false and list failures.\n";

    let v1a = agent(v1a_prompt, #{
        label: "verify:p1-media-sleep",
        capability_mode: "execute",
        output_schema: verify_schema,
    });
    let v1a_pass = if v1a == () || !v1a.success || v1a.output == () { false } else if v1a.output.passed == false { false } else { true };
    if v1a != () && v1a.success && v1a.output != () {
        batch_log = log_batch(batch_log, "verify:p1-media-sleep", if v1a_pass { "pass" } else { "fail" }, v1a.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "p1-media-sleep", v1a_pass, v1a.output.summary);
    } else {
        maybe_gate_fail(stop_on_verify_fail, "p1-media-sleep", false, "verify agent failed");
    }

    // --- Batch B: ISS-003 Timer + ISS-005 Composer ---
    let p1b_prompt = "";
    p1b_prompt += "You are a fix agent for lezi. Repo: /home/zhangtianshu/lezi\n";
    p1b_prompt += "Read " + issues_path + " for ISS-003 and ISS-005.\n";
    p1b_prompt += "USE tools to implement code fixes.\n\n";
    p1b_prompt += "Batch p1-timer-composer:\n";
    p1b_prompt += "ISS-003: Serialize Timer toggleLeft/toggleRight (Mutex or single queue) to prevent lost side on concurrent launch.\n";
    p1b_prompt += "  File: feature/timer/.../TimerScreen.kt. Add concurrent-safe test if feasible.\n";
    p1b_prompt += "ISS-005: RecordComposer importPhotos rethrow CancellationException; gate all post-write UI with sessionGate.deliver.\n";
    p1b_prompt += "  File: feature/log/.../RecordComposer.kt. Extend session gate tests.\n\n";
    p1b_prompt += "Run: ./gradlew :feature:timer:testDebugUnitTest :feature:log:testDebugUnitTest\n";
    p1b_prompt += "No version bump. Return closed ISS ids.\n";

    let p1b = agent(p1b_prompt, #{
        label: "fix:p1-timer-composer",
        capability_mode: "all",
        output_schema: fix_schema,
    });
    if p1b == () || !p1b.success || p1b.output == () {
        await_user("verification", "p1-timer-composer fix failed. Resume after repair.");
    } else {
        if p1b.output.ok == false {
            await_user("verification", "p1-timer-composer fix ok=false: " + p1b.output.summary);
        }
        let st = if p1b.output.status != () { p1b.output.status } else { "unknown" };
        let sm = if p1b.output.summary != () { p1b.output.summary } else { "" };
        batch_log = log_batch(batch_log, "fix:p1-timer-composer", st, sm);
    }

    let v1b_prompt = "";
    v1b_prompt += "VERIFY p1-timer-composer at /home/zhangtianshu/lezi using read_file/grep and unit tests.\n";
    v1b_prompt += "ISS-003: prove toggles cannot concurrent clobber (Mutex/channel/single-flight).\n";
    v1b_prompt += "ISS-005: prove importPhotos rethrows CancellationException; save state updates only inside deliver.\n";
    v1b_prompt += "Run: ./gradlew :feature:timer:testDebugUnitTest :feature:log:testDebugUnitTest\n";
    v1b_prompt += "passed=true only with path:line evidence.\n";

    let v1b = agent(v1b_prompt, #{
        label: "verify:p1-timer-composer",
        capability_mode: "execute",
        output_schema: verify_schema,
    });
    let v1b_pass = if v1b == () || !v1b.success || v1b.output == () { false } else if v1b.output.passed == false { false } else { true };
    if v1b != () && v1b.success && v1b.output != () {
        batch_log = log_batch(batch_log, "verify:p1-timer-composer", if v1b_pass { "pass" } else { "fail" }, v1b.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "p1-timer-composer", v1b_pass, v1b.output.summary);
    } else {
        maybe_gate_fail(stop_on_verify_fail, "p1-timer-composer", false, "verify agent failed");
    }

    // --- Batch C: ISS-004 clearAll + ISS-006 token + ISS-008 cleartext ---
    let p1c_prompt = "";
    p1c_prompt += "You are a fix agent for lezi. Repo: /home/zhangtianshu/lezi\n";
    p1c_prompt += "Read " + issues_path + " for ISS-004, ISS-006, ISS-008.\n";
    p1c_prompt += "USE tools to implement.\n\n";
    p1c_prompt += "Batch p1-wipe-security-client:\n";
    p1c_prompt += "ISS-004: clearAllLocalData must delete outbox, media_assets, custom_items, media files in one safe path;\n";
    p1c_prompt += "  align with RealSyncPort.clearLocalRecords file cleanup; add CareLog tests.\n";
    p1c_prompt += "ISS-006: Store family token with EncryptedSharedPreferences or Keystore-wrapped prefs;\n";
    p1c_prompt += "  migrate existing plaintext if present; tests where possible (Robolectric/androidX security).\n";
    p1c_prompt += "  If encryption APIs hard in unit test env, implement + document androidTest smoke.\n";
    p1c_prompt += "ISS-008: Add networkSecurityConfig to limit cleartext (private ranges / user-confirmed hosts)\n";
    p1c_prompt += "  while keeping home-LAN HTTP workable; update Manifest; UI warning if public HTTP if easy.\n";
    p1c_prompt += "  PRD allows LAN HTTP — do not break local sync.\n\n";
    p1c_prompt += "Run: ./gradlew :domain:testDebugUnitTest :sync:testDebugUnitTest :app:assembleDebug\n";
    p1c_prompt += "No version bump.\n";

    let p1c = agent(p1c_prompt, #{
        label: "fix:p1-wipe-security",
        capability_mode: "all",
        output_schema: fix_schema,
    });
    if p1c == () || !p1c.success || p1c.output == () {
        await_user("verification", "p1-wipe-security fix failed. Resume after repair.");
    } else {
        if p1c.output.ok == false {
            await_user("verification", "p1-wipe-security fix ok=false: " + p1c.output.summary);
        }
        let st = if p1c.output.status != () { p1c.output.status } else { "unknown" };
        let sm = if p1c.output.summary != () { p1c.output.summary } else { "" };
        batch_log = log_batch(batch_log, "fix:p1-wipe-security", st, sm);
    }

    let v1c_prompt = "";
    v1c_prompt += "VERIFY p1-wipe-security at /home/zhangtianshu/lezi.\n";
    v1c_prompt += "ISS-004: clearAllLocalData calls outbox/media/custom wipe — show path:line.\n";
    v1c_prompt += "ISS-006: token storage not plain Preferences only — show implementation.\n";
    v1c_prompt += "ISS-008: networkSecurityConfig or equivalent exists; cleartext not fully unrestricted without reason.\n";
    v1c_prompt += "Run unit tests for domain/sync; note assemble if needed.\n";
    v1c_prompt += "passed=true only with evidence.\n";

    let v1c = agent(v1c_prompt, #{
        label: "verify:p1-wipe-security",
        capability_mode: "execute",
        output_schema: verify_schema,
    });
    let v1c_pass = if v1c == () || !v1c.success || v1c.output == () { false } else if v1c.output.passed == false { false } else { true };
    if v1c != () && v1c.success && v1c.output != () {
        batch_log = log_batch(batch_log, "verify:p1-wipe-security", if v1c_pass { "pass" } else { "fail" }, v1c.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "p1-wipe-security", v1c_pass, v1c.output.summary);
    } else {
        maybe_gate_fail(stop_on_verify_fail, "p1-wipe-security", false, "verify agent failed");
    }
}

// ============================================================================
// P1 Server (ISS-007 + optional media integrity) — THEN version bump
// ============================================================================

phase("P1 Server");

if skip_server == true {
    batch_log = log_batch(batch_log, "p1-server", "skipped", "skip_server=true");
} else {
    let srv_prompt = "";
    srv_prompt += "You are a fix agent for lezi-sync server at /home/zhangtianshu/lezi/tools/lezi-sync\n";
    srv_prompt += "Read " + issues_path + " for ISS-007 and any server half of ISS-001 still open.\n";
    srv_prompt += "USE tools to implement.\n\n";
    srv_prompt += "Batch p1-server:\n";
    srv_prompt += "ISS-007:\n";
    srv_prompt += "  - docker-compose: default publish 127.0.0.1:8765:8765 (or document override).\n";
    srv_prompt += "  - Prefer non-root USER 10001 with optional root profile for NAS bind mounts;\n";
    srv_prompt += "    if root remains default for NAS compat, isolate as compose profile and document honestly.\n";
    srv_prompt += "  - Optional LEZI_BOOTSTRAP_SECRET for family create OR rate-limit create/join; fail closed when empty in prod docs.\n";
    srv_prompt += "  - Keep existing API wire compatible with Android clients.\n";
    srv_prompt += "Optional media integrity (if client still needs help for ISS-001):\n";
    srv_prompt += "  - Prefer not breaking wire: e.g. pull omits media without bytes, or flag; tests in tests/api.rs.\n\n";
    srv_prompt += "DB/schema: if SQLite schema changes, must be additive/backward-compatible with existing /data volumes.\n";
    srv_prompt += "Do NOT bump version to 0.2.3 yet (next phase does that).\n";
    srv_prompt += "Run: cargo test --locked && cargo clippy --all-targets --all-features -- -D warnings\n";

    let srv = agent(srv_prompt, #{
        label: "fix:p1-server",
        capability_mode: "all",
        output_schema: fix_schema,
    });
    if srv == () || !srv.success || srv.output == () {
        await_user("verification", "p1-server fix failed. Resume after repair.");
    } else {
        if srv.output.ok == false {
            await_user("verification", "p1-server fix ok=false: " + srv.output.summary);
        }
        let st = if srv.output.status != () { srv.output.status } else { "unknown" };
        let sm = if srv.output.summary != () { srv.output.summary } else { "" };
        batch_log = log_batch(batch_log, "fix:p1-server", st, sm);
    }

    let vsrv_prompt = "";
    vsrv_prompt += "VERIFY p1-server at /home/zhangtianshu/lezi/tools/lezi-sync\n";
    vsrv_prompt += "Check compose bind, Dockerfile USER/profiles, bootstrap/rate-limit if claimed.\n";
    vsrv_prompt += "Run cargo test --locked and cargo clippy -D warnings.\n";
    vsrv_prompt += "Confirm existing data volume compatibility (no drop tables without migration).\n";
    vsrv_prompt += "passed=true only with evidence.\n";

    let vsrv = agent(vsrv_prompt, #{
        label: "verify:p1-server",
        capability_mode: "execute",
        output_schema: verify_schema,
    });
    let vsrv_pass = if vsrv == () || !vsrv.success || vsrv.output == () { false } else if vsrv.output.passed == false { false } else { true };
    if vsrv != () && vsrv.success && vsrv.output != () {
        batch_log = log_batch(batch_log, "verify:p1-server", if vsrv_pass { "pass" } else { "fail" }, vsrv.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "p1-server", vsrv_pass, vsrv.output.summary);
    } else {
        maybe_gate_fail(stop_on_verify_fail, "p1-server", false, "verify agent failed");
    }
}

// ============================================================================
// Version 0.2.3 — AFTER server fixes
// ============================================================================

phase("Version 0.2.3");

if skip_version == true {
    batch_log = log_batch(batch_log, "version", "skipped", "skip_version=true");
} else {
    let ver_prompt = "";
    ver_prompt += "You are the version-bump agent for lezi. Repo: /home/zhangtianshu/lezi\n";
    ver_prompt += "Target version: " + target_version + "\n";
    ver_prompt += "Server fixes are done (or skipped). Bump versions consistently.\n";
    ver_prompt += "USE tools to edit files.\n\n";
    ver_prompt += "Required updates for lezi-sync " + target_version + ":\n";
    ver_prompt += "  - tools/lezi-sync/Cargo.toml version\n";
    ver_prompt += "  - tools/lezi-sync/build-image.sh default LEZI_SYNC_VERSION\n";
    ver_prompt += "  - tools/lezi-sync/docker-compose.yml image/build-arg defaults\n";
    ver_prompt += "  - tools/lezi-sync/README.md version references\n";
    ver_prompt += "  - cargo update lock if needed so CARGO_PKG_VERSION reflects " + target_version + "\n\n";
    ver_prompt += "App Android versionName (align product string; keep meaningful suffix if needed):\n";
    ver_prompt += "  - app/build.gradle.kts versionName → include " + target_version + " (e.g. 0.2.3 or 0.2.3-home-lan)\n";
    ver_prompt += "  - optionally bump versionCode by +1 if currently 1\n";
    ver_prompt += "  - README.md and docs/prd/tech.md version lines if they claim old 0.2.0-offline\n\n";
    ver_prompt += "Run: cd tools/lezi-sync && cargo test --locked\n";
    ver_prompt += "Return app_version_name and server_version as strings matching files.\n";

    let ver = agent(ver_prompt, #{
        label: "version-bump",
        capability_mode: "all",
        output_schema: version_schema,
    });
    if ver != () && ver.success && ver.output != () {
        version_result = ver.output;
        batch_log = log_batch(batch_log, "version-bump", if ver.output.ok == true { "done" } else { "fail" }, ver.output.summary);
        if ver.output.ok == false {
            await_user("verification", "Version bump incomplete: " + ver.output.summary);
        }
    } else {
        await_user("verification", "Version bump agent failed.");
    }

    let vver_prompt = "";
    vver_prompt += "VERIFY version " + target_version + " at /home/zhangtianshu/lezi\n";
    vver_prompt += "grep Cargo.toml, build-image.sh, docker-compose.yml, app/build.gradle.kts, health VERSION.\n";
    vver_prompt += "cargo test --locked in tools/lezi-sync.\n";
    vver_prompt += "passed=true if server package version is " + target_version + " and App versionName contains " + target_version + ".\n";

    let vver = agent(vver_prompt, #{
        label: "verify:version",
        capability_mode: "execute",
        output_schema: verify_schema,
    });
    let vver_pass = if vver == () || !vver.success || vver.output == () { false } else if vver.output.passed == false { false } else { true };
    if vver != () && vver.success && vver.output != () {
        batch_log = log_batch(batch_log, "verify:version", if vver_pass { "pass" } else { "fail" }, vver.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "version", vver_pass, vver.output.summary);
    } else {
        maybe_gate_fail(stop_on_verify_fail, "version", false, "verify agent failed");
    }
}

// ============================================================================
// DB compatibility
// ============================================================================

phase("DB compat");

if skip_db == true {
    batch_log = log_batch(batch_log, "db-compat", "skipped", "skip_db=true");
} else {
    let db_prompt = "";
    db_prompt += "You are the DB compatibility verifier+fixer for lezi. Repo: /home/zhangtianshu/lezi\n";
    db_prompt += "USE tools. Prefer verify; only add migrations if this fix wave introduced schema changes that need them.\n\n";
    db_prompt += "Android Room:\n";
    db_prompt += "1. Read LeziDatabase version and all MIGRATION_* in DatabaseModule.\n";
    db_prompt += "2. Confirm NO fallbackToDestructiveMigration.\n";
    db_prompt += "3. Confirm schemas/ has matching N.json for current version.\n";
    db_prompt += "4. Run DatabaseMigrationTest if possible: ./gradlew :core:database:connectedDebugAndroidTest\n";
    db_prompt += "   If no device/emulator: run unit tests and static check; set migration_chain_ok with honest note.\n";
    db_prompt += "5. If new Room version was introduced this wave without migration — implement MIGRATION and schema export.\n\n";
    db_prompt += "lezi-sync SQLite:\n";
    db_prompt += "6. Inspect store.rs schema init/migrations.\n";
    db_prompt += "7. Confirm upgrade from 0.2.2 data dir is additive (run relevant api tests).\n";
    db_prompt += "8. cargo test --locked covering restart/existing volume cases.\n\n";
    db_prompt += "passed=true only if both Android migration chain and server schema are backward compatible.\n";
    db_prompt += "server_schema_compat=true if old volume still works.\n";

    let db = agent(db_prompt, #{
        label: "db-compat",
        capability_mode: "all",
        output_schema: db_schema,
    });
    if db != () && db.success && db.output != () {
        db_result = db.output;
        batch_log = log_batch(batch_log, "db-compat", if db.output.passed == false { "fail" } else { "pass" }, db.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "db-compat", db.output.passed, db.output.summary);
    } else {
        maybe_gate_fail(stop_on_verify_fail, "db-compat", false, "db-compat agent failed");
    }
}

// ============================================================================
// P2
// ============================================================================

phase("P2 fixes");

if skip_p2 == true {
    batch_log = log_batch(batch_log, "p2", "skipped", "skip_p2=true");
} else {
    let p2a_prompt = "";
    p2a_prompt += "Fix agent lezi P2 batch A (data/sync). Repo: /home/zhangtianshu/lezi\n";
    p2a_prompt += "Read " + issues_path + " P2 section. Fix these if still open:\n";
    p2a_prompt += "ISS-009 LWW tie client/server align; ISS-012 FakeSyncBackend ACL gap (improve fake or tests);\n";
    p2a_prompt += "ISS-013 getCurrentBaby no implicit write; ISS-014 addBaby/customItem transactional;\n";
    p2a_prompt += "ISS-015 sleep segments cross-day dedupe; ISS-016 clearRecordsOnly calendar / merge media.\n";
    p2a_prompt += "Skip ISS-010 architecture port sink if large — note as deferred in issues_open.\n";
    p2a_prompt += "Skip ISS-011 pull pagination if large — optional simple LIMIT with client loop only if safe.\n";
    p2a_prompt += "USE tools. Run domain+sync unit tests. No version change.\n";

    let p2a = agent(p2a_prompt, #{
        label: "fix:p2-data",
        capability_mode: "all",
        output_schema: fix_schema,
    });
    if p2a != () && p2a.success && p2a.output != () {
        batch_log = log_batch(batch_log, "fix:p2-data", p2a.output.status, p2a.output.summary);
    } else {
        await_user("verification", "p2-data fix failed.");
    }

    let p2b_prompt = "";
    p2b_prompt += "Fix agent lezi P2 batch B (privacy/docs/UI). Repo: /home/zhangtianshu/lezi\n";
    p2b_prompt += "From " + issues_path + " fix if open:\n";
    p2b_prompt += "ISS-017 family error copy + hide familyId success; ISS-018 Widget privacy toggle OR document PRD;\n";
    p2b_prompt += "ISS-019 D2D extraction rules exclude DB/token; ISS-020 export PDF sandbox+cleanup;\n";
    p2b_prompt += "ISS-021 invite FLAG_SECURE optional; ISS-026/027/028 docs drift + clearAll product note;\n";
    p2b_prompt += "ISS-029 map Throwable.message to product Chinese.\n";
    p2b_prompt += "ISS-022/023 server residual if easy. USE tools. Run relevant unit tests.\n";

    let p2b = agent(p2b_prompt, #{
        label: "fix:p2-privacy-docs",
        capability_mode: "all",
        output_schema: fix_schema,
    });
    if p2b != () && p2b.success && p2b.output != () {
        batch_log = log_batch(batch_log, "fix:p2-privacy-docs", p2b.output.status, p2b.output.summary);
    } else {
        await_user("verification", "p2-privacy-docs fix failed.");
    }

    let p2c_prompt = "";
    p2c_prompt += "Fix agent lezi P2 batch C (tests). Repo: /home/zhangtianshu/lezi\n";
    p2c_prompt += "From " + issues_path + " ISS-025 and F-F items: add high-value tests for\n";
    p2c_prompt += "media 404 cursor, clearAllLocalData, Timer serialization, Composer gate, search real DAO semantics if possible.\n";
    p2c_prompt += "Do not invent green tests that assert wrong behavior.\n";
    p2c_prompt += "Run: ./gradlew test --continue\n";

    let p2c = agent(p2c_prompt, #{
        label: "fix:p2-tests",
        capability_mode: "all",
        output_schema: fix_schema,
    });
    if p2c != () && p2c.success && p2c.output != () {
        batch_log = log_batch(batch_log, "fix:p2-tests", p2c.output.status, p2c.output.summary);
    } else {
        await_user("verification", "p2-tests fix failed.");
    }

    let vp2_prompt = "";
    vp2_prompt += "VERIFY P2 batches at /home/zhangtianshu/lezi against " + issues_path + ".\n";
    vp2_prompt += "Sample-read code for each claimed closed ISS-009..029. List still_open honestly.\n";
    vp2_prompt += "Run ./gradlew test --continue and cargo test --locked if server touched.\n";
    vp2_prompt += "passed=true if no P2 regressions and critical P2 (009,013,014,015,019,020,025 cursor tests) addressed or explicitly deferred with reason in failures.\n";

    let vp2 = agent(vp2_prompt, #{
        label: "verify:p2",
        capability_mode: "execute",
        output_schema: verify_schema,
    });
    let vp2_pass = if vp2 == () || !vp2.success || vp2.output == () { false } else if vp2.output.passed == false { false } else { true };
    if vp2 != () && vp2.success && vp2.output != () {
        batch_log = log_batch(batch_log, "verify:p2", if vp2_pass { "pass" } else { "fail" }, vp2.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "p2", vp2_pass, vp2.output.summary);
    } else {
        maybe_gate_fail(stop_on_verify_fail, "p2", false, "verify agent failed");
    }
}

// ============================================================================
// P3
// ============================================================================

phase("P3 fixes");

if skip_p3 == true {
    batch_log = log_batch(batch_log, "p3", "skipped", "skip_p3=true");
} else {
    let p3_prompt = "";
    p3_prompt += "Fix agent lezi P3 nits. Repo: /home/zhangtianshu/lezi\n";
    p3_prompt += "Read " + issues_path + " P3 ISS-030..037. Fix cheap ones:\n";
    p3_prompt += "search short alias, joinWithCode dead path, docs nits, logging.\n";
    p3_prompt += "Skip intentional design items (generation restart, tombstone delay) — mark accepted.\n";
    p3_prompt += "USE tools. Run focused tests. No version change.\n";

    let p3 = agent(p3_prompt, #{
        label: "fix:p3",
        capability_mode: "all",
        output_schema: fix_schema,
    });
    if p3 != () && p3.success && p3.output != () {
        batch_log = log_batch(batch_log, "fix:p3", p3.output.status, p3.output.summary);
    } else {
        await_user("verification", "p3 fix failed.");
    }

    let vp3_prompt = "";
    vp3_prompt += "Quick VERIFY P3 at /home/zhangtianshu/lezi. List closed vs accepted. passed=true if no new breakages.\n";
    let vp3 = agent(vp3_prompt, #{
        label: "verify:p3",
        capability_mode: "execute",
        output_schema: verify_schema,
    });
    let vp3_pass = if vp3 == () || !vp3.success || vp3.output == () { false } else if vp3.output.passed == false { false } else { true };
    if vp3 != () && vp3.success && vp3.output != () {
        batch_log = log_batch(batch_log, "verify:p3", if vp3_pass { "pass" } else { "fail" }, vp3.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "p3", vp3_pass, vp3.output.summary);
    }
}

// ============================================================================
// Full gates
// ============================================================================

phase("Full gates");

if skip_gates == true {
    batch_log = log_batch(batch_log, "gates", "skipped", "skip_gates=true");
} else {
    let gates_prompt = "";
    gates_prompt += "Run full verification gates at /home/zhangtianshu/lezi. USE shell.\n";
    gates_prompt += "1. ./gradlew test --continue\n";
    gates_prompt += "2. cd tools/lezi-sync && cargo test --locked\n";
    gates_prompt += "3. cargo clippy --locked --all-targets --all-features -- -D warnings\n";
    gates_prompt += "Set gradle_ok/cargo_test_ok/cargo_clippy_ok from real exit codes.\n";
    gates_prompt += "passed=true only if all three pass.\n";
    gates_prompt += "Capture failure tails in failures array.\n";

    let gates = agent(gates_prompt, #{
        label: "full-gates",
        capability_mode: "execute",
        output_schema: gates_schema,
    });
    if gates != () && gates.success && gates.output != () {
        gates_result = gates.output;
        batch_log = log_batch(batch_log, "full-gates", if gates.output.passed == false { "fail" } else { "pass" }, gates.output.summary);
        maybe_gate_fail(stop_on_verify_fail, "full-gates", gates.output.passed, gates.output.summary);
    } else {
        maybe_gate_fail(stop_on_verify_fail, "full-gates", false, "gates agent failed");
    }
}

// ============================================================================
// Adversarial accept + report
// ============================================================================

phase("Accept");

let acc_prompt = "";
acc_prompt += "Adversarial acceptance for lezi fix wave. Repo: /home/zhangtianshu/lezi\n";
acc_prompt += "Read " + issues_path + " and CURRENT code. Do NOT trust batch claims alone.\n";
acc_prompt += "For each ISS-001..ISS-037: closed | still_open | accepted_risk with one-line evidence.\n";
acc_prompt += "Priority re-check: ISS-001 cursor, ISS-002 open sleep, ISS-003 timer, ISS-004 wipe,\n";
acc_prompt += "ISS-005 composer, ISS-006 token, ISS-007 server deploy, version " + target_version + ", DB migrations.\n";
acc_prompt += "passed=true only if all original P1 ISS are closed or explicitly accepted_risk with product reason,\n";
acc_prompt += "and no regressions vs adversarial scenarios (media 404, multi open, toggle race).\n";
acc_prompt += "USE grep/read_file/tests as needed.\n";

let acc = agent(acc_prompt, #{
    label: "adversarial-accept",
    capability_mode: "execute",
    output_schema: accept_schema,
});
if acc != () && acc.success && acc.output != () {
    accept_result = acc.output;
    batch_log = log_batch(batch_log, "accept", if acc.output.passed == false { "fail" } else { "pass" }, acc.output.summary);
    if acc.output.passed == false {
        await_user("verification", "Adversarial accept failed: " + acc.output.summary + " Resume to write report anyway or fix first.");
    }
} else {
    await_user("verification", "Accept agent failed.");
}

let rep_prompt = "";
rep_prompt += "Write final fix-wave report for lezi. Repo: /home/zhangtianshu/lezi\n";
rep_prompt += "Create directory " + report_dir + " if needed.\n";
rep_prompt += "Write " + report_dir + "/REPORT.md with: HEAD, target version " + target_version + ",\n";
rep_prompt += "batch log, gates, DB compat, closed/open ISS, remaining risks.\n";
rep_prompt += "Update " + issues_path + " Status fields for closed ISS (Status: closed with date).\n";
rep_prompt += "Also write " + report_dir + "/ISSUES-STATUS.md summary table.\n";
rep_prompt += "USE write tools. Include evidence commands that passed.\n";
rep_prompt += "Batch log lines to incorporate:\n";
let bi = 0;
while bi < batch_log.len() {
    rep_prompt += "- " + batch_log[bi] + "\n";
    bi += 1;
}
rep_prompt += "Version agent summary if any: " + version_result.summary + "\n";
rep_prompt += "DB summary: " + db_result.summary + "\n";
rep_prompt += "Gates summary: " + gates_result.summary + "\n";
rep_prompt += "Accept summary: " + accept_result.summary + "\n";
rep_prompt += "Return path to REPORT.md. ok=true if files written.\n";

let rep = agent(rep_prompt, #{
    label: "report",
    capability_mode: "all",
    output_schema: report_schema,
});

let report_path = report_dir + "/REPORT.md";
let report_ok = false;
let report_summary = "report agent failed";
if rep != () && rep.success && rep.output != () {
    report_ok = rep.output.ok == true;
    report_summary = rep.output.summary;
    if rep.output.path != () && type_of(rep.output.path) == "string" {
        report_path = rep.output.path;
    }
    batch_log = log_batch(batch_log, "report", if report_ok { "done" } else { "fail" }, report_summary);
}

// Scratch summary for UI
let scratch = "# adversarial-fix-all-023\n\n";
scratch += "target_version: " + target_version + "\n";
scratch += "report: " + report_path + "\n\n";
scratch += "## Batch log\n";
let si = 0;
while si < batch_log.len() {
    scratch += "- " + batch_log[si] + "\n";
    si += 1;
}
scratch += "\n## Gates\n" + gates_result.summary + "\n";
scratch += "\n## DB\n" + db_result.summary + "\n";
scratch += "\n## Accept\n" + accept_result.summary + "\n";
let sp = write_scratch_file("adversarial-fix-023-summary.md", scratch);

let overall_ok = report_ok;
if gates_result.passed == false && skip_gates == false { overall_ok = false; }
if db_result.passed == false && skip_db == false { overall_ok = false; }

complete(#{
    ok: overall_ok,
    summary: report_summary,
    report_path: report_path,
    scratch: sp,
    target_version: target_version,
    batch_log: batch_log,
    version: version_result,
    db: db_result,
    gates: gates_result,
    accept: accept_result,
});
