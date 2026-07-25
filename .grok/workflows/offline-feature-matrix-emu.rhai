// Offline feature inventory → exhaustive test cases (PRD + code + designsystem)
// → adversarial review → user confirm → emulator shot/dump acceptance.
//
// Scope: OFFLINE product surface only. Family LAN sync is EXCLUDED as a domain
// (account offline UI / local family stub may appear as offline cases).
//
// Args (all optional):
//   evidence_dir: string  default docs/reviews/offline-feature-matrix-emu
//   skip_confirm: bool    default false — skip await_user after review
//   skip_emu: bool        default false — stop after matrix review/report
//   max_p0_exec: int      default 40 — cap emulator-executed P0 cases per run
//   app_package: string   default com.lezi.babylog.debug
//   only_phase: string    "all" | "matrix" | "review" | "emu" (default all)
//
// Run:
//   /offline-feature-matrix-emu
//   /workflow offline-feature-matrix-emu {"skip_confirm":true}
//   /workflow offline-feature-matrix-emu {"only_phase":"matrix"}

let meta = #{
    name: "offline-feature-matrix-emu",
    description: "Decompose all offline features into test cases including unmentioned components, review, then emulator screenshot acceptance",
    when_to_use: "When you need a full offline PRD+code test matrix and device screenshot acceptance without relying on sync",
    phases: [
        #{ title: "Inventory", detail: "Parallel scan PRD, features, designsystem, domain" },
        #{ title: "Matrix", detail: "Synthesize MASTER offline test case catalog" },
        #{ title: "Review", detail: "Adversarial completeness review of the matrix" },
        #{ title: "Confirm", detail: "User confirms matrix before emulator runs" },
        #{ title: "Emulator prep", detail: "APK install, clean or seed offline state" },
        #{ title: "Execute", detail: "Run P0 cases with adb shot/dump evidence" },
        #{ title: "Report", detail: "Pass/fail matrix + screenshots index" },
    ],
};

let inventory_schema = #{
    "type": "object",
    "required": ["ok", "domain", "path", "case_hints", "summary"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "domain": #{ "type": "string" },
        "path": #{ "type": "string" },
        "case_hints": #{ "type": "integer" },
        "summary": #{ "type": "string" },
        "surfaces": #{ "type": "array", "items": #{ "type": "string" } },
        "components": #{ "type": "array", "items": #{ "type": "string" } },
        "gaps": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let matrix_schema = #{
    "type": "object",
    "required": ["ok", "path", "case_count", "p0_count", "domains", "summary"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "path": #{ "type": "string" },
        "case_count": #{ "type": "integer" },
        "p0_count": #{ "type": "integer" },
        "domains": #{ "type": "array", "items": #{ "type": "string" } },
        "summary": #{ "type": "string" },
        "runbook_path": #{ "type": "string" },
    },
};

let review_schema = #{
    "type": "object",
    "required": ["ok", "approved", "summary", "missing_areas", "weak_cases"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "approved": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "missing_areas": #{ "type": "array", "items": #{ "type": "string" } },
        "weak_cases": #{ "type": "array", "items": #{ "type": "string" } },
        "suggested_adds": #{ "type": "array", "items": #{ "type": "string" } },
        "path": #{ "type": "string" },
    },
};

let prep_schema = #{
    "type": "object",
    "required": ["ok", "status", "summary", "serial", "evidence_paths"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "status": #{ "type": "string" },
        "summary": #{ "type": "string" },
        "serial": #{ "type": "string" },
        "evidence_paths": #{ "type": "array", "items": #{ "type": "string" } },
        "notes": #{ "type": "string" },
    },
};

let exec_schema = #{
    "type": "object",
    "required": ["ok", "shard", "summary", "passed", "failed", "skipped", "evidence_dir"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "shard": #{ "type": "string" },
        "summary": #{ "type": "string" },
        "passed": #{ "type": "array", "items": #{ "type": "string" } },
        "failed": #{ "type": "array", "items": #{ "type": "string" } },
        "skipped": #{ "type": "array", "items": #{ "type": "string" } },
        "evidence_dir": #{ "type": "string" },
        "logic_issues": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let report_schema = #{
    "type": "object",
    "required": ["ok", "path", "summary"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "path": #{ "type": "string" },
        "summary": #{ "type": "string" },
        "case_count": #{ "type": "integer" },
        "executed": #{ "type": "integer" },
        "pass_count": #{ "type": "integer" },
        "fail_count": #{ "type": "integer" },
        "remaining_risks": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

// --- Args ---

let evidence_dir = "docs/reviews/offline-feature-matrix-emu";
let skip_confirm = false;
let skip_emu = false;
let max_p0_exec = 40;
let app_pkg = "com.lezi.babylog.debug";
let only_phase = "all";

if args != () {
    if args.evidence_dir != () && type_of(args.evidence_dir) == "string" { evidence_dir = args.evidence_dir; }
    if args.skip_confirm != () && args.skip_confirm == true { skip_confirm = true; }
    if args.skip_emu != () && args.skip_emu == true { skip_emu = true; }
    if args.max_p0_exec != () { max_p0_exec = args.max_p0_exec; }
    if args.app_package != () && type_of(args.app_package) == "string" { app_pkg = args.app_package; }
    if args.only_phase != () && type_of(args.only_phase) == "string" { only_phase = args.only_phase; }
}

fn want(only, name) {
    if only == "all" { true }
    else if only == name { true }
    else if only == "matrix" && (name == "inventory" || name == "matrix") { true }
    else if only == "review" && (name == "inventory" || name == "matrix" || name == "review") { true }
    else if only == "emu" && (name == "prep" || name == "exec" || name == "report") { true }
    else { false }
}

// =============================================================================
// Phase: Inventory (parallel domain scanners)
// =============================================================================

phase("Inventory");
log("Scanning offline surfaces from PRD + code + designsystem");

// Fixed domain list — exhaustive offline product surfaces (no LAN family protocol)
let domains = [
    #{ id: "records-composer", title: "记录类型与 Composer", focus: "All RecordType keys, confirm-before-write, drafts, edit/delete, More panel, custom items" },
    #{ id: "log-day", title: "日视图与时间轴", focus: "Timeline, day chips, timebar, date nav, dock, warm/journal, one-hand" },
    #{ id: "timer-sleep", title: "计时与睡眠", focus: "Nursing timer FGS, restore, sleep down/wake, anomaly !, intervals" },
    #{ id: "summary-growth", title: "汇总与成长", focus: "Week/month summary CareAggregation, WHO growth, measurements, corrected age" },
    #{ id: "settings-export", title: "设置搜索导出Widget", focus: "Dark mode, templates, formula step, next feed, calendar, search, TXT/PDF, widget config" },
    #{ id: "designsystem", title: "设计系统组件", focus: "ClockDial, TimelineComponents, RecordRow, glyphs, Theme, PageComponents — UI atoms not named in PRD" },
    #{ id: "domain-offline", title: "领域离线规则", focus: "CareLog isolation, aggregation windows, payload v2, RecordTime DST, no-network paths" },
];

let inv_jobs = [];
for d in domains {
    let ip = "";
    ip += "OFFLINE inventory only (exclude home-LAN RealSync protocol as a product domain).\n";
    ip += "Domain: " + d.title + " (" + d.id + ")\n";
    ip += "Focus: " + d.focus + "\n\n";
    ip += "MUST use tools to read sources — do not invent from memory:\n";
    ip += "1. docs/prd/README.md, ui.md, data-model.md, tech.md (offline parts)\n";
    ip += "2. feature/* screens and ViewModels for this domain\n";
    ip += "3. designsystem/* if domain is designsystem or UI-related\n";
    ip += "4. domain/* CareLog CareAggregation GrowthMeasurements RecordTime paths\n";
    ip += "5. Existing tests under feature/*/src/test and domain/src/test for case hints\n";
    ip += "6. docs/reviews/apk-prd-spec-alignment-2026-07-25.md for known Partial/Missing\n\n";
    ip += "Write Markdown to " + evidence_dir + "/inventory/domain-" + d.id + ".md with:\n";
    ip += "- surfaces (screens/routes)\n";
    ip += "- components (Compose/designsystem atoms)\n";
    ip += "- user-visible behaviors (happy + edge + explicit non-goals)\n";
    ip += "- suggested test case titles (at least 8; more if surface is large)\n";
    ip += "- gaps where code exists but PRD is silent (mark Undocumented-in-PRD)\n\n";
    ip += "domain field must be " + d.id + ". case_hints = number of suggested cases. ok=true if file written after reading code.\n";

    inv_jobs.push(#{
        prompt: ip,
        label: "inv:" + d.id,
        capability_mode: "read-write",
        output_schema: inventory_schema,
    });
}

let inv_results = [];
if want(only_phase, "inventory") {
    inv_results = parallel(inv_jobs);
} else {
    log("skip inventory phase by only_phase");
}

let inv_paths = [];
let inv_ok_count = 0;
let ii = 0;
for r in inv_results {
    if r != () && r.success && r.output != () && r.output.ok == true {
        inv_ok_count += 1;
        if r.output.path != () { inv_paths.push(r.output.path); }
        log("Inventory " + r.output.domain + ": hints=" + r.output.case_hints.to_string());
    } else {
        log("Inventory slot failed index=" + ii.to_string());
    }
    ii += 1;
}

// =============================================================================
// Phase: Matrix synthesis
// =============================================================================

let matrix = #{
    ok: false,
    path: evidence_dir + "/MASTER.md",
    case_count: 0,
    p0_count: 0,
    domains: [],
    summary: "not run",
    runbook_path: "",
};

if want(only_phase, "matrix") {
    phase("Matrix");
    log("Synthesizing offline MASTER test matrix");

    let mp = "";
    mp += "Synthesize the MASTER offline test case catalog for 乐记.\n";
    mp += "evidence_dir=" + evidence_dir + "\n";
    mp += "Inventory paths: " + json_encode(inv_paths) + "\n";
    mp += "Also re-scan docs/prd/*.md and feature/ modules for anything inventory missed.\n\n";
    mp += "OUTPUT FILES (create dirs as needed):\n";
    mp += "1. " + evidence_dir + "/MASTER.md — full matrix table + per-case sections\n";
    mp += "2. " + evidence_dir + "/README.md — index by domain priority\n";
    mp += "3. " + evidence_dir + "/adb-runbook.md — executable adb/uiautomator steps for P0/P1 UI cases\n";
    mp += "4. " + evidence_dir + "/by-domain/*.md — one file per domain if large\n\n";
    mp += "EACH case MUST have:\n";
    mp += "- id: OFF-DOMAIN-NNN (stable)\n";
    mp += "- title, prd_refs or code_refs, preconditions, steps, expected\n";
    mp += "- layer: unit | instrumentation | adb-ui | manual\n";
    mp += "- priority: P0 | P1 | P2\n";
    mp += "- offline: true (all cases offline)\n";
    mp += "- component: designsystem or feature component name when applicable\n\n";
    mp += "Coverage requirements (fail open only with explicit gap list):\n";
    mp += "- All record types from PRD §3 that exist in code\n";
    mp += "- Confirm-before-write for every add path including Widget\n";
    mp += "- Multi-baby isolation, theme color, dark mode, warm/journal templates\n";
    mp += "- ClockDial / time picker / calendar jump\n";
    mp += "- Summary aggregation edges (midnight sleep, in-progress sleep)\n";
    mp += "- Growth measurements + percentiles\n";
    mp += "- Search edit, export, settings, calendar reminders, custom items\n";
    mp += "- designsystem components that have no PRD name but ship in UI\n";
    mp += "- Explicit non-goals as negative cases (e.g. no IAP, no exact alarm)\n";
    mp += "EXCLUDE dual-device LAN sync protocol cases (mark N/A offline).\n\n";
    mp += "p0_count = count of P0 cases. case_count = total. path = MASTER.md path.\n";
    mp += "runbook_path = adb-runbook.md. ok=true if matrix is coherent and multi-domain.\n";

    let mr = agent(mp, #{
        label: "matrix-synthesize",
        capability_mode: "read-write",
        output_schema: matrix_schema,
    });

    if mr != () && mr.success && mr.output != () {
        matrix = mr.output;
        log("Matrix: cases=" + matrix.case_count.to_string() + " p0=" + matrix.p0_count.to_string());
    } else {
        matrix.summary = "matrix agent failed";
        log("Matrix agent failed");
    }
}

if only_phase == "matrix" {
    complete(#{ phase: "matrix", matrix: matrix, inventory_ok: inv_ok_count });
}

// =============================================================================
// Phase: Adversarial review
// =============================================================================

let review = #{
    ok: false,
    approved: false,
    summary: "not run",
    missing_areas: [],
    weak_cases: [],
    suggested_adds: [],
    path: "",
};

if want(only_phase, "review") {
    phase("Review");
    log("Adversarial review of offline test matrix completeness");

    // Two independent reviewers in parallel
    let rev_jobs = [];
    let r1 = "";
    r1 += "You are a skeptical QA lead. Review the offline test matrix for GAPS.\n";
    r1 += "Read " + evidence_dir + "/MASTER.md and inventory/*.md and adb-runbook.md.\n";
    r1 += "Cross-check against docs/prd/README.md §2–§4 and feature/ module list:\n";
    r1 += "onboarding log timer summary growth search export settings widget family(offline) designsystem domain.\n";
    r1 += "approved=true ONLY if every major offline surface has at least one P0 or justified P1,\n";
    r1 += "and designsystem atoms are not completely missing.\n";
    r1 += "List missing_areas and weak_cases concretely. Write review notes to " + evidence_dir + "/REVIEW-qa.md\n";
    r1 += "path=that file. Use tools; do not approve without reading MASTER.md.\n";

    rev_jobs.push(#{
        prompt: r1,
        label: "review:qa",
        capability_mode: "read-write",
        output_schema: review_schema,
    });

    let r2 = "";
    r2 += "You are a product/PRD skeptic. Review the offline matrix for PRD fidelity and silent code features.\n";
    r2 += "Read " + evidence_dir + "/MASTER.md, docs/prd/ui.md, and grep feature/*/ for Dialog|Screen|Route that lack cases.\n";
    r2 += "Flag undocumented-in-PRD but shipped UI without cases. Suggest adds.\n";
    r2 += "Write " + evidence_dir + "/REVIEW-prd.md. approved=true only if PRD critical paths covered.\n";

    rev_jobs.push(#{
        prompt: r2,
        label: "review:prd",
        capability_mode: "read-write",
        output_schema: review_schema,
    });

    let rev_results = parallel(rev_jobs);

    // Merge: approved only if BOTH approve; union missing
    let approved_all = true;
    let missing = [];
    let weak = [];
    let adds = [];
    let rev_i = 0;
    for r in rev_results {
        if r != () && r.success && r.output != () {
            if r.output.approved != true { approved_all = false; }
            if r.output.missing_areas != () {
                for m in r.output.missing_areas { missing.push(m); }
            }
            if r.output.weak_cases != () {
                for w in r.output.weak_cases { weak.push(w); }
            }
            if r.output.suggested_adds != () {
                for a in r.output.suggested_adds { adds.push(a); }
            }
        } else {
            approved_all = false;
            missing.push("reviewer slot failed index=" + rev_i.to_string());
        }
        rev_i += 1;
    }

    // Optional patch agent if not approved
    if !approved_all && adds.len() > 0 {
        log("Matrix not dual-approved — applying suggested adds");
        let patch_p = "";
        patch_p += "Update the offline MASTER matrix to address review gaps.\n";
        patch_p += "MASTER: " + matrix.path + "\n";
        patch_p += "missing_areas: " + json_encode(missing) + "\n";
        patch_p += "suggested_adds: " + json_encode(adds) + "\n";
        patch_p += "weak_cases: " + json_encode(weak) + "\n";
        patch_p += "Add P0/P1 cases as needed; update README and adb-runbook for new P0 UI cases.\n";
        patch_p += "Return updated case_count/p0_count/path/summary. ok=true if files updated.\n";

        let pr = agent(patch_p, #{
            label: "matrix-patch",
            capability_mode: "read-write",
            output_schema: matrix_schema,
        });
        if pr != () && pr.success && pr.output != () && pr.output.ok == true {
            matrix = pr.output;
            log("Matrix patched: cases=" + matrix.case_count.to_string());
        }
    }

    review.ok = true;
    review.approved = approved_all;
    review.missing_areas = missing;
    review.weak_cases = weak;
    review.suggested_adds = adds;
    review.summary = "approved=" + approved_all.to_string() + " missing=" + missing.len().to_string();
    review.path = evidence_dir + "/REVIEW-qa.md";
    log("Review: " + review.summary);
}

if only_phase == "review" {
    complete(#{ phase: "review", matrix: matrix, review: review });
}

// =============================================================================
// Phase: User confirm
// =============================================================================

phase("Confirm");
let confirm_msg = "";
confirm_msg += "Offline test matrix ready for emulator acceptance.\n";
confirm_msg += "MASTER: " + matrix.path + " cases=" + matrix.case_count.to_string() + " p0=" + matrix.p0_count.to_string() + "\n";
confirm_msg += "Review: " + review.summary + "\n";
confirm_msg += "max_p0_exec=" + max_p0_exec.to_string() + " (emulator will execute up to this many P0 cases)\n";
confirm_msg += "Resume to run emulator screenshot acceptance. Or cancel if matrix needs more edits.\n";

if !skip_confirm && want(only_phase, "prep") {
    await_user("user", confirm_msg);
    log("User confirmed — continuing to emulator");
} else {
    log("skip_confirm or phase gate — continue");
}

if skip_emu {
    let p = write_scratch_file("offline-matrix-only.json", json_encode(#{
        matrix: matrix,
        review: review,
    }));
    complete(#{ phase: "matrix-only", path: p, matrix: matrix, review: review });
}

// =============================================================================
// Phase: Emulator prep
// =============================================================================

let prep = #{
    ok: false,
    status: "failed",
    summary: "not run",
    serial: "",
    evidence_paths: [],
    notes: "",
};

if want(only_phase, "prep") || only_phase == "all" || only_phase == "emu" {
    phase("Emulator prep");
    log("Preparing emulator for offline acceptance");

    let pp = "";
    pp += "Prepare a SINGLE emulator for OFFLINE screenshot acceptance.\n";
    pp += "app_package=" + app_pkg + " evidence_dir=" + evidence_dir + "/00-prep\n";
    pp += "Do NOT require lezi-sync / Docker. Airplane mode optional but preferred for honesty:\n";
    pp += "  adb shell cmd connectivity airplane-mode enable  (if available) OR note network still on but no sync server needed\n\n";
    pp += "Steps:\n";
    pp += "1. lezi-emu status / start if needed\n";
    pp += "2. ./gradlew :app:assembleDebug if APK missing\n";
    pp += "3. adb install -r app/build/outputs/apk/debug/app-debug.apk\n";
    pp += "4. pm clear " + app_pkg + " for clean onboarding unless matrix requires seeded data — prefer clear\n";
    pp += "5. Start MainActivity; capture launch shot/dump\n";
    pp += "6. Complete minimal onboarding (baby nickname) so Log home is usable\n";
    pp += "7. Write prep-notes.md with serial\n";
    pp += "ok=true if app launches without FATAL. serial = adb serial.\n";

    let pr = agent(pp, #{
        label: "emu-prep",
        capability_mode: "all",
        output_schema: prep_schema,
    });
    if pr != () && pr.success && pr.output != () {
        prep = pr.output;
    }
    log("Prep: " + prep.status + " — " + prep.summary);
}

// =============================================================================
// Phase: Execute P0 shards in parallel
// =============================================================================

let exec_results_summary = [];
let all_passed = [];
let all_failed = [];
let all_skipped = [];
let all_logic = [];

if want(only_phase, "exec") || only_phase == "all" || only_phase == "emu" {
    phase("Execute");
    log("Executing offline P0 cases on emulator (max " + max_p0_exec.to_string() + ")");

    // Fixed shards map to offline domains — each agent picks its P0 cases from MASTER
    let shards = [
        "records-composer",
        "log-day",
        "timer-sleep",
        "summary-growth",
        "settings-export",
        "designsystem-misc",
    ];

    let ejobs = [];
    for s in shards {
        let ep = "";
        ep += "Execute OFFLINE P0 adb-ui test cases for shard `" + s + "` on the emulator.\n";
        ep += "app_package=" + app_pkg + " max total P0 across all shards ≈ " + max_p0_exec.to_string() + "\n";
        ep += "This shard should run roughly max_p0_exec/6 cases (at least 3 if available), prefer unique P0 from MASTER.\n";
        ep += "MASTER: " + matrix.path + "\n";
        ep += "Runbook: " + evidence_dir + "/adb-runbook.md\n";
        ep += "Prep: " + json_encode(prep) + "\n";
        ep += "evidence_dir=" + evidence_dir + "/exec/" + s + "\n\n";
        ep += "Rules:\n";
        ep += "1. Read MASTER + runbook; select cases for this shard only\n";
        ep += "2. Drive UI with adb input / uiautomator; lezi-emu shot/dump per case id\n";
        ep += "3. Assert expected offline behavior (confirm-before-write, list updates, no crash)\n";
        ep += "4. Mark pass only with shot+dump evidence filenames\n";
        ep += "5. On failure capture logcat FATAL/AndroidRuntime\n";
        ep += "6. Skip cases that need dual-device sync or hardware camera with reason\n";
        ep += "7. Write shard-results.md with table case_id | result | evidence\n";
        ep += "8. Do not claim pass without screenshots\n\n";
        ep += "shard=" + s + ". ok=true if no failed P0 (skips ok).\n";

        ejobs.push(#{
            prompt: ep,
            label: "exec:" + s,
            capability_mode: "all",
            output_schema: exec_schema,
        });
    }

    let eres = parallel(ejobs);
    let ei = 0;
    for r in eres {
        if r != () && r.success && r.output != () {
            exec_results_summary.push(r.output);
            log("Exec " + r.output.shard + ": " + r.output.summary);
            if r.output.passed != () {
                for x in r.output.passed { all_passed.push(x); }
            }
            if r.output.failed != () {
                for x in r.output.failed { all_failed.push(x); }
            }
            if r.output.skipped != () {
                for x in r.output.skipped { all_skipped.push(x); }
            }
            if r.output.logic_issues != () {
                for x in r.output.logic_issues { all_logic.push(x); }
            }
        } else {
            all_failed.push("exec agent failed index=" + ei.to_string());
            log("Exec agent failed index=" + ei.to_string());
        }
        ei += 1;
    }
}

// =============================================================================
// Phase: Report
// =============================================================================

phase("Report");
log("Writing offline matrix + emulator acceptance report");

let rp = "";
rp += "Write the final OFFLINE feature matrix + emulator acceptance report.\n";
rp += "Path: " + evidence_dir + "/REPORT.md\n";
rp += "Also: " + evidence_dir + "/RESULTS.md (case_id → pass/fail/skip + shot links)\n";
rp += "And: " + evidence_dir + "/summary.json\n\n";
rp += "Include:\n";
rp += "1. Executive summary\n";
rp += "2. Matrix stats (total/P0, domains)\n";
rp += "3. Review outcome\n";
rp += "4. Emulator execution results with evidence index\n";
rp += "5. Logic issues found\n";
rp += "6. Remaining gaps (unexecuted P1/P2, components still thin)\n";
rp += "7. How to re-run /workflow offline-feature-matrix-emu\n\n";
rp += "matrix: " + json_encode(matrix) + "\n";
rp += "review: " + json_encode(review) + "\n";
rp += "prep: " + json_encode(prep) + "\n";
rp += "passed: " + json_encode(all_passed) + "\n";
rp += "failed: " + json_encode(all_failed) + "\n";
rp += "skipped: " + json_encode(all_skipped) + "\n";
rp += "logic_issues: " + json_encode(all_logic) + "\n";
rp += "exec shards: " + json_encode(exec_results_summary) + "\n";
rp += "Verify key paths exist. ok=true if REPORT written.\n";
rp += "pass_count/fail_count/executed from arrays. case_count from matrix.\n";

let rr = agent(rp, #{
    label: "final-report",
    capability_mode: "read-write",
    output_schema: report_schema,
});

let report_path = evidence_dir + "/REPORT.md";
let report_ok = false;
let report_summary = "report agent failed";
let remaining = [];
let pass_count = all_passed.len();
let fail_count = all_failed.len();
let executed = pass_count + fail_count;
if rr != () && rr.success && rr.output != () {
    report_ok = rr.output.ok;
    if rr.output.path != () && rr.output.path != "" { report_path = rr.output.path; }
    report_summary = rr.output.summary;
    if rr.output.remaining_risks != () { remaining = rr.output.remaining_risks; }
    if rr.output.pass_count != () { pass_count = rr.output.pass_count; }
    if rr.output.fail_count != () { fail_count = rr.output.fail_count; }
    if rr.output.executed != () { executed = rr.output.executed; }
}

let final_obj = #{
    matrix: matrix,
    review: review,
    prep: prep,
    report_path: report_path,
    report_ok: report_ok,
    report_summary: report_summary,
    pass_count: pass_count,
    fail_count: fail_count,
    skipped_count: all_skipped.len(),
    executed: executed,
    remaining_risks: remaining,
};
let scratch_path = write_scratch_file("offline-feature-matrix-final.json", json_encode(final_obj));

log("Done. " + report_summary);

complete(#{
    summary: report_summary,
    report_path: report_path,
    scratch_path: scratch_path,
    matrix_cases: matrix.case_count,
    p0_count: matrix.p0_count,
    review_approved: review.approved,
    pass_count: pass_count,
    fail_count: fail_count,
    executed: executed,
    remaining_risks: remaining,
});
