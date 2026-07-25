// Close home-lan-sync + architecture-deepening tickets with acceptance,
// await user confirmation, then generate PRD/UI test matrix and APK simulation.
//
// Args (object; all optional):
//   skip_confirm: bool     — skip await_user gate (default false)
//   skip_apk: bool         — skip emulator/APK simulation (default false)
//   skip_implement: bool   — only audit + generate tests + APK (no ticket code work)
//   only_phase: string     — "audit" | "tickets" | "tests" | "apk" | "all" (default "all")
//   agent_budget hint: prefer 96+ for full run (ticket work + matrix + APK fan-out)
//
// Run:
//   /close-tickets-prd-apk-verify
//   /workflow close-tickets-prd-apk-verify
//   /workflow close-tickets-prd-apk-verify {"skip_confirm":true}

let meta = #{
    name: "close-tickets-prd-apk-verify",
    description: "Audit implement status, close home-lan-sync and improve-codebase tickets with acceptance, then PRD/UI test matrix and APK simulation",
    when_to_use: "When home-lan-sync or architecture-deepening tickets need closure/acceptance, then full PRD scenario coverage via APK agents",
    phases: [
        #{ title: "Audit", detail: "Scan .scratch ticket Status for implement completion" },
        #{ title: "Improve codebase", detail: "Close/verify architecture-deepening-care-records tickets" },
        #{ title: "Home LAN", detail: "Close/verify home-lan-sync tickets and acceptance" },
        #{ title: "Ticket gate", detail: "Gradle tests + update ISSUES status with evidence" },
        #{ title: "Confirm", detail: "User confirms ticket closure before test generation" },
        #{ title: "PRD matrix", detail: "Generate test cases for all PRD + UI scenarios" },
        #{ title: "APK simulate", detail: "Install APK and agent-drive all scenarios on device" },
        #{ title: "Report", detail: "Write final acceptance report to docs/reviews" },
    ],
};

// --- Schemas ---

let audit_schema = #{
    "type": "object",
    "required": ["ok", "summary", "home_lan", "architecture", "other_open"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "home_lan": #{
            "type": "object",
            "required": ["done", "pending", "blocked"],
            "properties": #{
                "done": #{ "type": "array", "items": #{ "type": "string" } },
                "pending": #{ "type": "array", "items": #{ "type": "string" } },
                "blocked": #{ "type": "array", "items": #{ "type": "string" } },
            },
        },
        "architecture": #{
            "type": "object",
            "required": ["done", "pending", "blocked"],
            "properties": #{
                "done": #{ "type": "array", "items": #{ "type": "string" } },
                "pending": #{ "type": "array", "items": #{ "type": "string" } },
                "blocked": #{ "type": "array", "items": #{ "type": "string" } },
            },
        },
        "other_open": #{ "type": "array", "items": #{ "type": "string" } },
        "env_notes": #{ "type": "string" },
    },
};

let ticket_work_schema = #{
    "type": "object",
    "required": ["feature", "status", "summary", "tickets_touched", "acceptance_met", "acceptance_open"],
    "properties": #{
        "feature": #{ "type": "string" },
        "status": #{ "type": "string", "enum": ["done", "partial", "blocked"] },
        "summary": #{ "type": "string" },
        "tickets_touched": #{ "type": "array", "items": #{ "type": "string" } },
        "acceptance_met": #{ "type": "array", "items": #{ "type": "string" } },
        "acceptance_open": #{ "type": "array", "items": #{ "type": "string" } },
        "files_changed": #{ "type": "array", "items": #{ "type": "string" } },
        "test_commands": #{ "type": "array", "items": #{ "type": "string" } },
        "env_blockers": #{ "type": "array", "items": #{ "type": "string" } },
        "notes": #{ "type": "string" },
    },
};

let gate_schema = #{
    "type": "object",
    "required": ["ok", "summary", "commands_run", "failures"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "commands_run": #{ "type": "array", "items": #{ "type": "string" } },
        "failures": #{ "type": "array", "items": #{ "type": "string" } },
        "index_updates": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let matrix_schema = #{
    "type": "object",
    "required": ["ok", "path", "case_count", "domains", "summary"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "path": #{ "type": "string" },
        "case_count": #{ "type": "integer" },
        "domains": #{ "type": "array", "items": #{ "type": "string" } },
        "summary": #{ "type": "string" },
    },
};

let apk_schema = #{
    "type": "object",
    "required": ["ok", "mode", "summary", "passed", "failed", "skipped", "evidence_dir"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "mode": #{ "type": "string" },
        "summary": #{ "type": "string" },
        "passed": #{ "type": "array", "items": #{ "type": "string" } },
        "failed": #{ "type": "array", "items": #{ "type": "string" } },
        "skipped": #{ "type": "array", "items": #{ "type": "string" } },
        "logic_issues": #{ "type": "array", "items": #{ "type": "string" } },
        "evidence_dir": #{ "type": "string" },
    },
};

let report_schema = #{
    "type": "object",
    "required": ["ok", "path", "summary"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "path": #{ "type": "string" },
        "summary": #{ "type": "string" },
        "remaining_risks": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

// --- Args ---

let skip_confirm = false;
let skip_apk = false;
let skip_implement = false;
let only_phase = "all";
if args != () {
    if args.skip_confirm != () && args.skip_confirm == true { skip_confirm = true; }
    if args.skip_apk != () && args.skip_apk == true { skip_apk = true; }
    if args.skip_implement != () && args.skip_implement == true { skip_implement = true; }
    if args.only_phase != () && type_of(args.only_phase) == "string" { only_phase = args.only_phase; }
}

fn phase_enabled(only, name) {
    if only == "all" { true }
    else if only == name { true }
    else if only == "tickets" && (name == "improve" || name == "home_lan" || name == "gate") { true }
    else if only == "tests" && name == "matrix" { true }
    else { false }
}

// =============================================================================
// Phase: Audit
// =============================================================================

phase("Audit");
log("Auditing implement ticket completion under .scratch/");

let audit_prompt = "";
audit_prompt += "You are auditing implement completion for the lezi (乐记) Android baby-log app.\n";
audit_prompt += "Workspace root is the git repo. Use read-only tools only (read_file, list_dir, grep, shell read-only).\n\n";
audit_prompt += "MUST read and report Status from:\n";
audit_prompt += "1. .scratch/README.md\n";
audit_prompt += "2. .scratch/home-lan-sync/ISSUES.md and every issues/*.md Status line\n";
audit_prompt += "3. .scratch/architecture-deepening-care-records/ISSUES.md and every issues/*.md Status line\n";
audit_prompt += "4. Optionally note open tickets in .scratch/interval-duration-preview/ and delivery specs\n";
audit_prompt += "5. Check env: which docker/podman; adb devices; presence of app/build/outputs/apk/debug/app-debug.apk\n";
audit_prompt += "6. Skim docs/prd/README.md delivery checklist and docs/prd/sync-home-lan.md delivery status\n\n";
audit_prompt += "Classify each ticket as done | pending | blocked (env-blocked still counts as blocked).\n";
audit_prompt += "home_lan = home-lan-sync tickets; architecture = architecture-deepening-care-records (improve codebase).\n";
audit_prompt += "other_open = any non-done tickets outside those two epics.\n";
audit_prompt += "Do NOT invent statuses — quote only what files say. Empty arrays are valid after full scan.\n";

let audit_r = agent(audit_prompt, #{
    label: "audit-implement",
    capability_mode: "read-only",
    output_schema: audit_schema,
});

if audit_r == () || !audit_r.success || audit_r.output == () {
    pause("infra", "Audit agent failed. Resume after fixing tools, or re-run with healthy workspace.");
}

let audit = audit_r.output;
log("Audit: " + audit.summary);
if audit.env_notes != () { log("Env: " + audit.env_notes); }

if only_phase == "audit" {
    let p = write_scratch_file("audit.json", json_encode(audit));
    complete(#{ phase: "audit", path: p, audit: audit });
}

// =============================================================================
// Phase: Improve codebase (architecture-deepening)
// =============================================================================

let arch_result = #{
    feature: "architecture-deepening-care-records",
    status: "done",
    summary: "skipped",
    tickets_touched: [],
    acceptance_met: [],
    acceptance_open: [],
    files_changed: [],
    test_commands: [],
    env_blockers: [],
    notes: "",
};

if !skip_implement && phase_enabled(only_phase, "improve") {
    phase("Improve codebase");
    log("Closing/verifying architecture-deepening-care-records (improve codebase)");

    let arch_prompt = "";
    arch_prompt += "You implement and/or ACCEPTANCE-VERIFY the improve-codebase epic:\n";
    arch_prompt += ".scratch/architecture-deepening-care-records/\n\n";
    arch_prompt += "Read spec.md + ISSUES.md + every issues/*.md first.\n";
    arch_prompt += "Index claims all 13 tickets done — you MUST re-verify, not trust the index blindly:\n";
    arch_prompt += "1. For each ticket, confirm Must acceptance criteria against code + tests.\n";
    arch_prompt += "2. If any ticket is incomplete (Status not done, or criteria fail):\n";
    arch_prompt += "   - implement the missing work (read-write)\n";
    arch_prompt += "   - keep RecordComposer as single add/edit seam\n";
    arch_prompt += "   - no new parallel shallow paths; delete old paths only when cleanup ticket requires\n";
    arch_prompt += "3. Run targeted tests, e.g.:\n";
    arch_prompt += "   ./gradlew :domain:test :core:model:test :feature:log:testDebugUnitTest ";
    arch_prompt += ":feature:summary:testDebugUnitTest :feature:growth:testDebugUnitTest ";
    arch_prompt += ":feature:widget:testDebugUnitTest --continue\n";
    arch_prompt += "4. On success, update ticket Status to done, ISSUES.md table, append Comments with evidence.\n";
    arch_prompt += "5. If something fails for environment (Gradle cache) vs code, distinguish clearly.\n\n";
    arch_prompt += "Audit snapshot (JSON):\n" + json_encode(audit) + "\n\n";
    arch_prompt += "Return structured status. Do not claim done without test or code evidence.\n";

    let arch_r = agent(arch_prompt, #{
        label: "close-architecture",
        capability_mode: "all",
        output_schema: ticket_work_schema,
    });

    if arch_r != () && arch_r.success && arch_r.output != () {
        arch_result = arch_r.output;
        log("Architecture: " + arch_result.status + " — " + arch_result.summary);
    } else {
        arch_result.status = "blocked";
        arch_result.summary = "architecture agent failed";
        log("Architecture agent failed");
    }
}

// =============================================================================
// Phase: Home LAN sync tickets
// =============================================================================

let lan_result = #{
    feature: "home-lan-sync",
    status: "done",
    summary: "skipped",
    tickets_touched: [],
    acceptance_met: [],
    acceptance_open: [],
    files_changed: [],
    test_commands: [],
    env_blockers: [],
    notes: "",
};

if !skip_implement && phase_enabled(only_phase, "home_lan") {
    phase("Home LAN");
    log("Closing/verifying home-lan-sync tickets");

    let lan_prompt = "";
    lan_prompt += "You implement and/or ACCEPTANCE-VERIFY the home LAN family sync epic:\n";
    lan_prompt += ".scratch/home-lan-sync/ (authority: docs/prd/sync-home-lan.md)\n\n";
    lan_prompt += "Read ISSUES.md, spec.md, and every issues/*.md. Known open items typically:\n";
    lan_prompt += "- 01 lezi-sync Docker skeleton: verification-pending if no Docker/Podman\n";
    lan_prompt += "- 09 dual-device foreground acceptance: verification-pending without ADB dual devices + NAS\n";
    lan_prompt += "- 02–08, 10 often already done with unit/ASGI tests\n\n";
    lan_prompt += "Your job:\n";
    lan_prompt += "1. Re-verify done tickets against Must criteria and automated tests.\n";
    lan_prompt += "2. For 01: if docker/podman available, build image, compose up, curl /health, mark Must checkboxes;\n";
    lan_prompt += "   else document env blocker, keep verification-pending, do NOT fake docker success.\n";
    lan_prompt += "3. For 09: if two devices + server available, run dual-device foreground path and record evidence under docs/reviews/;\n";
    lan_prompt += "   else maximize single-device or automated substitutes (FakeSyncBackend, lezi-sync pytest),\n";
    lan_prompt += "   leave device Must unchecked, status verification-pending or partial with honest Comments.\n";
    lan_prompt += "4. Fix any code gaps found in Android :sync, feature/family, tools/lezi-sync while staying in scope.\n";
    lan_prompt += "5. Run tests such as:\n";
    lan_prompt += "   ./gradlew :sync:test :feature:family:testDebugUnitTest --continue\n";
    lan_prompt += "   cd tools/lezi-sync && python -m pytest (or project documented command)\n";
    lan_prompt += "6. Update ISSUES.md Status table and ticket Comments with commands + results.\n";
    lan_prompt += "7. Never claim dual-device or Docker acceptance without real evidence.\n\n";
    lan_prompt += "Audit snapshot (JSON):\n" + json_encode(audit) + "\n\n";
    lan_prompt += "Return structured feature result.\n";

    let lan_r = agent(lan_prompt, #{
        label: "close-home-lan",
        capability_mode: "all",
        output_schema: ticket_work_schema,
    });

    if lan_r != () && lan_r.success && lan_r.output != () {
        lan_result = lan_r.output;
        log("Home LAN: " + lan_result.status + " — " + lan_result.summary);
    } else {
        lan_result.status = "blocked";
        lan_result.summary = "home-lan agent failed";
        log("Home LAN agent failed");
    }
}

// =============================================================================
// Phase: Ticket gate (full regression + index truth)
// =============================================================================

let gate_result = #{
    ok: true,
    summary: "skipped",
    commands_run: [],
    failures: [],
    index_updates: [],
};

if !skip_implement && phase_enabled(only_phase, "gate") {
    phase("Ticket gate");
    log("Running acceptance gates and refreshing ticket indexes");

    let gate_prompt = "";
    gate_prompt += "You are the acceptance gate for sync + improve-codebase tickets.\n";
    gate_prompt += "Workspace: lezi Android monorepo.\n\n";
    gate_prompt += "1. Run a meaningful regression (prefer full unit suite if time allows):\n";
    gate_prompt += "   ./gradlew test :app:assembleDebug --continue\n";
    gate_prompt += "   If full suite is too long, at minimum:\n";
    gate_prompt += "   ./gradlew :domain:test :core:model:test :sync:test ";
    gate_prompt += ":feature:log:testDebugUnitTest :feature:family:testDebugUnitTest ";
    gate_prompt += ":feature:growth:testDebugUnitTest :app:assembleDebug --continue\n";
    gate_prompt += "2. Optionally run tools/lezi-sync tests if present.\n";
    gate_prompt += "3. Re-read .scratch/home-lan-sync/ISSUES.md and architecture ISSUES.md;\n";
    gate_prompt += "   ensure Status lines match reality after prior agents' work.\n";
    gate_prompt += "4. Fix only gate-related inconsistencies (stale Status, missing Comments).\n";
    gate_prompt += "5. ok=true only if compile+tests for these modules pass OR failures are pure env and documented.\n\n";
    gate_prompt += "Architecture result JSON:\n" + json_encode(arch_result) + "\n\n";
    gate_prompt += "Home LAN result JSON:\n" + json_encode(lan_result) + "\n";

    let gate_r = agent(gate_prompt, #{
        label: "ticket-acceptance-gate",
        capability_mode: "all",
        output_schema: gate_schema,
    });

    if gate_r != () && gate_r.success && gate_r.output != () {
        gate_result = gate_r.output;
        log("Gate: " + gate_result.summary);
    } else {
        gate_result.ok = false;
        gate_result.summary = "gate agent failed";
        gate_result.failures = ["gate agent failed"];
    }
}

if only_phase == "tickets" {
    let ticket_payload = #{
        audit: audit,
        architecture: arch_result,
        home_lan: lan_result,
        gate: gate_result,
    };
    let tp = write_scratch_file("tickets-result.json", json_encode(ticket_payload));
    complete(#{ phase: "tickets", path: tp, result: ticket_payload });
}

// =============================================================================
// Phase: User confirmation
// =============================================================================

phase("Confirm");

let confirm_msg = "";
confirm_msg += "Ticket closure/acceptance finished (or skipped).\n\n";
confirm_msg += "Audit: " + audit.summary + "\n";
confirm_msg += "Architecture: " + arch_result.status + " — " + arch_result.summary + "\n";
confirm_msg += "Home LAN: " + lan_result.status + " — " + lan_result.summary + "\n";
confirm_msg += "Gate ok=" + gate_result.ok.to_string() + " — " + gate_result.summary + "\n\n";
confirm_msg += "Resume to CONTINUE with PRD test-matrix generation and APK scenario simulation.\n";
confirm_msg += "If ticket work is incomplete, fix offline or re-run tickets phase first.\n";

if !skip_confirm {
    log("Awaiting user confirmation before PRD matrix + APK simulation");
    await_user("user", confirm_msg);
    log("User confirmed — continuing to PRD matrix");
} else {
    log("skip_confirm=true — skipping await_user");
}

// =============================================================================
// Phase: PRD + UI test matrix
// =============================================================================

let matrix_result = #{
    ok: false,
    path: "",
    case_count: 0,
    domains: [],
    summary: "not run",
};

if phase_enabled(only_phase, "matrix") || only_phase == "all" || only_phase == "apk" {
    phase("PRD matrix");
    log("Generating PRD scenario + UI acceptance test matrix");

    // Fan-out domain authors in parallel, then synthesize
    let domains = [
        #{ id: "records", title: "记录类型与 Composer", focus: "PRD README §3 record types, confirm-before-write, pee/poop icons, nursing timer, sleep soft check, formula 5ml" },
        #{ id: "day-log", title: "日视图与时间轴", focus: "PRD ui.md day bar, chips, timeline, date nav, one-hand dock, warm/journal templates" },
        #{ id: "summary-growth", title: "汇总与成长", focus: "week/month summary, CareAggregation, growth WHO percentiles, corrected age, measurements" },
        #{ id: "family-lan", title: "家庭与家网同步", focus: "sync-home-lan gates, invite QR, foreground only, outbox, media ACL, no push notifications" },
        #{ id: "settings-export", title: "设置导出搜索Widget", focus: "dark mode, reminders non-exact, calendar, search, TXT/PDF export, widget config, multi-baby" },
    ];

    let mjobs = [];
    for d in domains {
        let mp = "";
        mp += "Generate exhaustive acceptance test cases for domain: " + d.title + " (" + d.id + ").\n";
        mp += "Focus: " + d.focus + "\n\n";
        mp += "Authoritative sources (READ them with tools; do not invent product rules):\n";
        mp += "- docs/prd/README.md\n";
        mp += "- docs/prd/ui.md\n";
        mp += "- docs/prd/data-model.md\n";
        mp += "- docs/prd/tech.md\n";
        mp += "- docs/prd/sync-home-lan.md (if family/lan)\n";
        mp += "- .scratch/*/spec.md Must criteria when relevant\n";
        mp += "- docs/reviews/apk-prd-spec-alignment-2026-07-25.md for known conform/partial items\n\n";
        mp += "For EACH case output a Markdown section with:\n";
        mp += "- id: PRD-<DOMAIN>-NNN\n";
        mp += "- title\n";
        mp += "- prd_refs (section anchors)\n";
        mp += "- preconditions\n";
        mp += "- steps (numbered; UI taps when UI; API/unit when pure logic)\n";
        mp += "- expected\n";
        mp += "- layer: unit | instrumentation | adb-ui | dual-device | manual-env\n";
        mp += "- priority: P0 | P1 | P2\n\n";
        mp += "Cover happy paths, edge cases, and explicit non-goals (must NOT happen).\n";
        mp += "Write the full domain markdown to docs/reviews/prd-acceptance-matrix/domain-" + d.id + ".md\n";
        mp += "Create directories as needed. Return path + case_count estimate in free text;\n";
        mp += "schema fields: ok, path, case_count, domains=[this id], summary.\n";

        mjobs.push(#{
            prompt: mp,
            label: "matrix:" + d.id,
            capability_mode: "read-write",
            output_schema: matrix_schema,
        });
    }

    let mresults = parallel(mjobs);

    let domain_paths = [];
    let total_cases = 0;
    let domain_ids = [];
    let i = 0;
    for r in mresults {
        if r != () && r.success && r.output != () && r.output.ok == true {
            domain_paths.push(r.output.path);
            if r.output.case_count != () { total_cases += r.output.case_count; }
            if r.output.domains != () {
                for did in r.output.domains { domain_ids.push(did); }
            }
        } else {
            log("Matrix domain agent failed index=" + i.to_string());
        }
        i += 1;
    }

    let synth_prompt = "";
    synth_prompt += "Synthesize a master PRD + UI acceptance test matrix for lezi.\n";
    synth_prompt += "Read domain files under docs/reviews/prd-acceptance-matrix/ (or paths below).\n";
    synth_prompt += "Domain agent paths (JSON): " + json_encode(domain_paths) + "\n\n";
    synth_prompt += "Also re-scan docs/prd/*.md for any domain gaps and add missing P0 cases.\n";
    synth_prompt += "Write:\n";
    synth_prompt += "1. docs/reviews/prd-acceptance-matrix/README.md — index of all cases by domain\n";
    synth_prompt += "2. docs/reviews/prd-acceptance-matrix/MASTER.md — full matrix table + execution order\n";
    synth_prompt += "3. docs/reviews/prd-acceptance-matrix/adb-runbook.md — concrete adb/uiautomator steps for UI cases\n\n";
    synth_prompt += "case_count = total unique cases. domains = list of domain ids covered.\n";
    synth_prompt += "path must be MASTER.md path. ok=true if matrix is coherent and covers PRD surfaces.\n";

    let synth_r = agent(synth_prompt, #{
        label: "matrix-synthesize",
        capability_mode: "read-write",
        output_schema: matrix_schema,
    });

    if synth_r != () && synth_r.success && synth_r.output != () {
        matrix_result = synth_r.output;
        if matrix_result.case_count == 0 && total_cases > 0 {
            matrix_result.case_count = total_cases;
        }
        if matrix_result.domains == () || matrix_result.domains.len() == 0 {
            matrix_result.domains = domain_ids;
        }
        log("Matrix: " + matrix_result.case_count.to_string() + " cases → " + matrix_result.path);
    } else {
        matrix_result.ok = false;
        matrix_result.summary = "matrix synthesize failed";
        matrix_result.case_count = total_cases;
        matrix_result.domains = domain_ids;
    }
}

if only_phase == "tests" || only_phase == "matrix" {
    complete(#{
        phase: "matrix",
        matrix: matrix_result,
        audit: audit,
    });
}

// =============================================================================
// Phase: APK simulation
// =============================================================================

let apk_result = #{
    ok: false,
    mode: "skipped",
    summary: "skipped",
    passed: [],
    failed: [],
    skipped: [],
    logic_issues: [],
    evidence_dir: "",
};

if !skip_apk && (phase_enabled(only_phase, "apk") || only_phase == "all") {
    phase("APK simulate");
    log("Building APK and simulating PRD/UI scenarios on device/emulator");

    // Environment probe + build
    let build_prompt = "";
    build_prompt += "Prepare APK simulation environment for com.lezi.babylog.\n";
    build_prompt += "1. ./gradlew :app:assembleDebug\n";
    build_prompt += "2. Confirm APK at app/build/outputs/apk/debug/app-debug.apk (or package variant)\n";
    build_prompt += "3. adb devices — list online devices/emulators\n";
    build_prompt += "4. If a device is online: adb install -r the debug APK; am start MainActivity; scan logcat for FATAL\n";
    build_prompt += "5. Create evidence dir docs/reviews/prd-apk-simulation/ with launch.log\n";
    build_prompt += "6. If NO device: still build APK; mode will be jvm-and-runbook (document adb-runbook cannot execute live)\n\n";
    build_prompt += "Return:\n";
    build_prompt += "- ok=true if APK built (device optional)\n";
    build_prompt += "- mode: live-adb | jvm-and-runbook | build-failed\n";
    build_prompt += "- evidence_dir path\n";
    build_prompt += "- passed/failed/skipped for this bootstrap only\n";
    build_prompt += "- summary\n";

    let build_r = agent(build_prompt, #{
        label: "apk-build-env",
        capability_mode: "all",
        output_schema: apk_schema,
    });

    let live_mode = false;
    if build_r != () && build_r.success && build_r.output != () {
        apk_result = build_r.output;
        if apk_result.mode == "live-adb" { live_mode = true; }
        log("APK env mode=" + apk_result.mode + " ok=" + apk_result.ok.to_string());
    } else {
        apk_result.mode = "build-failed";
        apk_result.summary = "build/env agent failed";
        apk_result.failed = ["apk-build-env agent failed"];
    }

    // Scenario runners — parallel shards from matrix domains
    let scenario_ids = ["records", "day-log", "summary-growth", "family-lan", "settings-export"];
    let sjobs = [];
    for sid in scenario_ids {
        let sp = "";
        sp += "Execute PRD/UI acceptance scenarios for domain `" + sid + "` against the lezi APK.\n\n";
        sp += "Read the matrix for this domain:\n";
        sp += "- docs/reviews/prd-acceptance-matrix/domain-" + sid + ".md\n";
        sp += "- docs/reviews/prd-acceptance-matrix/MASTER.md\n";
        sp += "- docs/reviews/prd-acceptance-matrix/adb-runbook.md\n";
        sp += "- docs/prd/ui.md and docs/prd/README.md for expectations\n";
        sp += "- Prior patterns: docs/reviews/emulator-ui-ops-2026-07-24/\n\n";
        sp += "LIVE ADB MODE if device online:\n";
        sp += "1. Use adb shell input/uiautomator dump/screencap to drive flows\n";
        sp += "2. Save shots + hierarchy dumps under docs/reviews/prd-apk-simulation/" + sid + "/\n";
        sp += "3. For each case mark pass/fail/partial with evidence filenames\n";
        sp += "4. Watch logcat for FATAL EXCEPTION / AndroidRuntime crashes\n";
        sp += "5. Assert product logic: confirm-before-write, correct summaries, no data loss on edit,\n";
        sp += "   multi-baby isolation when testable, dark mode toggle, etc.\n\n";
        sp += "NO DEVICE MODE:\n";
        sp += "1. Map each P0 case to existing JVM/unit tests; run those tests with ./gradlew\n";
        sp += "2. For UI-only cases without tests, write executable adb step scripts under\n";
        sp += "   docs/reviews/prd-apk-simulation/" + sid + "/pending-steps.md and mark skipped\n";
        sp += "3. Still flag logic risks found by reading code vs PRD (as logic_issues)\n\n";
        sp += "Do NOT claim pass without evidence. Prefer fail closed on missing evidence.\n";
        sp += "Return mode, passed, failed, skipped, logic_issues, evidence_dir, ok (true if no P0 fails).\n";

        sjobs.push(#{
            prompt: sp,
            label: "apk:" + sid,
            capability_mode: "all",
            output_schema: apk_schema,
        });
    }

    let sresults = parallel(sjobs);

    let all_passed = [];
    let all_failed = [];
    let all_skipped = [];
    let all_logic = [];
    let any_fail = false;
    let j = 0;
    for r in sresults {
        if r != () && r.success && r.output != () {
            if r.output.passed != () {
                for x in r.output.passed { all_passed.push(x); }
            }
            if r.output.failed != () {
                for x in r.output.failed { all_failed.push(x); any_fail = true; }
            }
            if r.output.skipped != () {
                for x in r.output.skipped { all_skipped.push(x); }
            }
            if r.output.logic_issues != () {
                for x in r.output.logic_issues { all_logic.push(x); }
            }
            if r.output.ok == false { any_fail = true; }
        } else {
            any_fail = true;
            all_failed.push("scenario agent failed index=" + j.to_string());
        }
        j += 1;
    }

    // Merge with bootstrap result
    if apk_result.passed != () {
        for x in apk_result.passed { all_passed.push(x); }
    }
    if apk_result.failed != () {
        for x in apk_result.failed { all_failed.push(x); }
    }

    apk_result.passed = all_passed;
    apk_result.failed = all_failed;
    apk_result.skipped = all_skipped;
    apk_result.logic_issues = all_logic;
    apk_result.ok = !any_fail && apk_result.mode != "build-failed";
    if live_mode {
        apk_result.mode = "live-adb";
    }
    let sum = "";
    sum += "passed=" + all_passed.len().to_string();
    sum += " failed=" + all_failed.len().to_string();
    sum += " skipped=" + all_skipped.len().to_string();
    sum += " logic_issues=" + all_logic.len().to_string();
    apk_result.summary = sum;
    if apk_result.evidence_dir == () || apk_result.evidence_dir == "" {
        apk_result.evidence_dir = "docs/reviews/prd-apk-simulation";
    }
    log("APK sim: " + apk_result.summary);
}

// =============================================================================
// Phase: Final report
// =============================================================================

phase("Report");
log("Writing final acceptance report");

let report_prompt = "";
report_prompt += "Write the final end-to-end acceptance report for this workflow run.\n";
report_prompt += "Path: docs/reviews/close-tickets-prd-apk-verify-report.md\n\n";
report_prompt += "Include sections:\n";
report_prompt += "1. Executive summary (can we ship offline + what sync env gaps remain)\n";
report_prompt += "2. Implement audit snapshot\n";
report_prompt += "3. Architecture (improve codebase) ticket results\n";
report_prompt += "4. Home LAN sync ticket results (honest about Docker/dual-device)\n";
report_prompt += "5. Gradle/test gate\n";
report_prompt += "6. PRD test matrix location + case counts\n";
report_prompt += "7. APK simulation results + logic issues\n";
report_prompt += "8. Remaining risks and recommended next actions\n\n";
report_prompt += "Inputs (JSON):\n";
report_prompt += "audit: " + json_encode(audit) + "\n";
report_prompt += "architecture: " + json_encode(arch_result) + "\n";
report_prompt += "home_lan: " + json_encode(lan_result) + "\n";
report_prompt += "gate: " + json_encode(gate_result) + "\n";
report_prompt += "matrix: " + json_encode(matrix_result) + "\n";
report_prompt += "apk: " + json_encode(apk_result) + "\n\n";
report_prompt += "Use tools to verify key file paths exist. Be factual; no invented green checks.\n";
report_prompt += "ok=true if report written and no critical P0 logic failures remain unlisted.\n";

let report_r = agent(report_prompt, #{
    label: "final-report",
    capability_mode: "read-write",
    output_schema: report_schema,
});

let report_path = "docs/reviews/close-tickets-prd-apk-verify-report.md";
let report_ok = false;
let report_summary = "report agent failed";
let remaining = [];
if report_r != () && report_r.success && report_r.output != () {
    report_ok = report_r.output.ok;
    if report_r.output.path != () && report_r.output.path != "" {
        report_path = report_r.output.path;
    }
    report_summary = report_r.output.summary;
    if report_r.output.remaining_risks != () {
        remaining = report_r.output.remaining_risks;
    }
}

// Also stash a machine-readable scratch result
let final_obj = #{
    audit: audit,
    architecture: arch_result,
    home_lan: lan_result,
    gate: gate_result,
    matrix: matrix_result,
    apk: apk_result,
    report_path: report_path,
    report_ok: report_ok,
    report_summary: report_summary,
    remaining_risks: remaining,
};
let scratch_path = write_scratch_file("final-result.json", json_encode(final_obj));

log("Done. Report: " + report_path + " scratch: " + scratch_path);

complete(#{
    summary: report_summary,
    report_path: report_path,
    scratch_path: scratch_path,
    architecture_status: arch_result.status,
    home_lan_status: lan_result.status,
    gate_ok: gate_result.ok,
    matrix_cases: matrix_result.case_count,
    apk_ok: apk_result.ok,
    apk_mode: apk_result.mode,
    remaining_risks: remaining,
});
