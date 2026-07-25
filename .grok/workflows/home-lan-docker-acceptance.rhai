// Continue home-lan-sync Docker runtime + dual-path acceptance in parallel.
// Resumes from partial evidence under docs/reviews/home-lan-sync-docker-acceptance-2026-07-25/
// and closes tickets 01 + 09 with honest env boundaries.
//
// Args (object; all optional):
//   evidence_dir: string  — default docs/reviews/home-lan-sync-docker-acceptance-2026-07-25
//   base_url: string      — default http://127.0.0.1:8765
//   emu_host_url: string  — default http://10.0.2.2:8765 (emulator → host)
//   skip_device: bool     — skip emulator/APK UI path
//   skip_ticket_update: bool — do not rewrite .scratch tickets
//
// Env assumed (from prior session; agents re-check):
//   - Rootless Docker: PATH+=$HOME/bin, DOCKER_HOST=unix:///run/user/$UID/docker.sock
//   - Image lezi-sync:0.1.0 / latest may already exist
//   - Container lezi-sync on :8765 may already be healthy
//   - Emulator may be online as emulator-5554 with debug APK installed
//
// Run:
//   /home-lan-docker-acceptance
//   /workflow home-lan-docker-acceptance
//   /workflow home-lan-docker-acceptance {"skip_device":true}

let meta = #{
    name: "home-lan-docker-acceptance",
    description: "Parallel home-lan-sync Docker runtime acceptance: ticket 01 image/volume/health, ticket 09 dual-path, regression, report",
    when_to_use: "After Docker is installed and lezi-sync image work is partially done; finish 01+09 acceptance and update tickets",
    phases: [
        #{ title: "Bootstrap", detail: "Verify Docker rootless, image, container, evidence dir, ADB" },
        #{ title: "Parallel accept", detail: "01 Docker, 09 dual-path, regression tests, device UI" },
        #{ title: "Close tickets", detail: "Update ISSUES + ticket Comments with evidence" },
        #{ title: "Report", detail: "Write final acceptance report" },
    ],
};

let boot_schema = #{
    "type": "object",
    "required": ["ok", "summary", "docker_ok", "image_ok", "container_ok", "health_ok", "device_ok", "evidence_dir"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "docker_ok": #{ "type": "boolean" },
        "image_ok": #{ "type": "boolean" },
        "container_ok": #{ "type": "boolean" },
        "health_ok": #{ "type": "boolean" },
        "device_ok": #{ "type": "boolean" },
        "base_url": #{ "type": "string" },
        "evidence_dir": #{ "type": "string" },
        "notes": #{ "type": "array", "items": #{ "type": "string" } },
        "blockers": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

let track_schema = #{
    "type": "object",
    "required": ["track", "status", "summary", "checks_passed", "checks_failed", "evidence_paths"],
    "properties": #{
        "track": #{ "type": "string" },
        "status": #{ "type": "string", "enum": ["done", "partial", "blocked", "failed"] },
        "summary": #{ "type": "string" },
        "checks_passed": #{ "type": "array", "items": #{ "type": "string" } },
        "checks_failed": #{ "type": "array", "items": #{ "type": "string" } },
        "evidence_paths": #{ "type": "array", "items": #{ "type": "string" } },
        "env_blockers": #{ "type": "array", "items": #{ "type": "string" } },
        "notes": #{ "type": "string" },
    },
};

let close_schema = #{
    "type": "object",
    "required": ["ok", "summary", "ticket_01_status", "ticket_09_status", "files_updated"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "summary": #{ "type": "string" },
        "ticket_01_status": #{ "type": "string" },
        "ticket_09_status": #{ "type": "string" },
        "files_updated": #{ "type": "array", "items": #{ "type": "string" } },
        "honest_gaps": #{ "type": "array", "items": #{ "type": "string" } },
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

let evidence_dir = "docs/reviews/home-lan-sync-docker-acceptance-2026-07-25";
let base_url = "http://127.0.0.1:8765";
let emu_host_url = "http://10.0.2.2:8765";
let skip_device = false;
let skip_ticket_update = false;
if args != () {
    if args.evidence_dir != () && type_of(args.evidence_dir) == "string" { evidence_dir = args.evidence_dir; }
    if args.base_url != () && type_of(args.base_url) == "string" { base_url = args.base_url; }
    if args.emu_host_url != () && type_of(args.emu_host_url) == "string" { emu_host_url = args.emu_host_url; }
    if args.skip_device != () && args.skip_device == true { skip_device = true; }
    if args.skip_ticket_update != () && args.skip_ticket_update == true { skip_ticket_update = true; }
}

// =============================================================================
// Phase: Bootstrap
// =============================================================================

phase("Bootstrap");
log("Bootstrapping Docker / container / device for home-lan acceptance");

let boot_prompt = "";
boot_prompt += "You bootstrap the lezi home-LAN Docker acceptance environment.\n";
boot_prompt += "Repo root is the workspace. Be idempotent — reuse healthy containers when present.\n\n";
boot_prompt += "ALWAYS export before docker commands:\n";
boot_prompt += "  export PATH=\"$HOME/bin:$HOME/.local/bin:$PATH\"\n";
boot_prompt += "  export DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock\n";
boot_prompt += "Compose plugin may live at ~/.docker/cli-plugins/docker-compose.\n\n";
boot_prompt += "Steps (use shell + read tools; write evidence files):\n";
boot_prompt += "1. Ensure rootless docker is active: systemctl --user is-active docker.service; docker version\n";
boot_prompt += "   If missing, install via SKIP_IPTABLES=1 curl -fsSL https://get.docker.com/rootless | sh\n";
boot_prompt += "2. Ensure image lezi-sync:0.1.0 and lezi-sync:latest exist; if not, cd tools/lezi-sync && ./build-image.sh\n";
boot_prompt += "3. Ensure a healthy container serves " + base_url + " (prefer name lezi-sync on host port 8765).\n";
boot_prompt += "   Prefer named volume lezi-sync-data:/data OR bind-mount after chown 10001 via helper container.\n";
boot_prompt += "   Do NOT kill a healthy container unnecessarily. If port conflict, keep the healthy one.\n";
boot_prompt += "4. curl -sS " + base_url + "/health → must be 200 {\"ok\":true,\"version\":...}\n";
boot_prompt += "5. mkdir -p " + evidence_dir + " and write bootstrap-env.txt with docker version, images, ps, health JSON\n";
boot_prompt += "6. adb devices -l; if no device and skip_device is false, try lezi-emu start (or note blocker)\n";
boot_prompt += "7. Confirm prior evidence if present: live-api.json, pytest.txt, android-sync-tests.txt\n\n";
boot_prompt += "Args: evidence_dir=" + evidence_dir + " base_url=" + base_url + " skip_device=" + skip_device.to_string() + "\n";
boot_prompt += "Return docker_ok/image_ok/container_ok/health_ok/device_ok booleans. ok=true if health_ok and image_ok.\n";
boot_prompt += "Do not invent green checks — run the commands.\n";

let boot_r = agent(boot_prompt, #{
    label: "bootstrap-env",
    capability_mode: "all",
    output_schema: boot_schema,
});

if boot_r == () || !boot_r.success || boot_r.output == () {
    pause("infra", "Bootstrap agent failed. Fix Docker/rootless PATH and resume, or re-run.");
}

let boot = boot_r.output;
log("Bootstrap: " + boot.summary);
if boot.health_ok != true {
    // Do not hard-stop: parallel tracks may start/rebuild the container.
    // Fail closed later via track checks + report if health never recovers.
    log("WARNING: bootstrap health_ok=false for " + base_url + " — parallel tracks must heal or mark blocked");
}

// =============================================================================
// Phase: Parallel accept (4 tracks)
// =============================================================================

phase("Parallel accept");
log("Parallel tracks: docker-01, dual-path-09, regression, device-ui");

let jobs = [];

// Track A — Ticket 01 Docker Must
let p01 = "";
p01 += "ACCEPTANCE TRACK: ticket 01 lezi-sync Docker skeleton (Must checklist).\n";
p01 += "Read: .scratch/home-lan-sync/issues/01-lezi-sync-skeleton-docker.md\n";
p01 += "PRD: docs/prd/sync-home-lan.md §7–§8; tools/lezi-sync/README.md\n\n";
p01 += "Env:\n";
p01 += "  export PATH=\"$HOME/bin:$HOME/.local/bin:$PATH\"\n";
p01 += "  export DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock\n";
p01 += "base_url=" + base_url + " evidence_dir=" + evidence_dir + "\n";
p01 += "Bootstrap JSON: " + json_encode(boot) + "\n\n";
p01 += "Execute and RECORD evidence under " + evidence_dir + "/01-docker/:\n";
p01 += "1. Image exists: docker images | grep lezi-sync; if missing rebuild with tools/lezi-sync/build-image.sh\n";
p01 += "2. Single data root: prove lezi.db AND media/ live under ONE host volume path\n";
p01 += "   (named volume mountpoint via docker volume inspect OR bind path tools/lezi-sync/data-bind)\n";
p01 += "   Use docker run --rm -v ... alpine ls if host cannot readdir rootless uid dirs\n";
p01 += "3. curl " + base_url + "/health → HTTP 200 with version field; save health.json\n";
p01 += "4. Process non-root: docker exec <ctr> id → uid=10001(lezi)\n";
p01 += "5. Prefer also: docker compose path — if name conflict, use project -p lezi-acc2 and port 8767\n";
p01 += "   LEZI_SYNC_PORT=8767 LEZI_DATA_HOST_PATH=... docker compose -p lezi-acc2 up -d\n";
p01 += "   Document compose success or the exact conflict.\n";
p01 += "6. Write 01-checklist.md mapping each Must item to pass/fail + command output paths\n\n";
p01 += "status=done only if Must image+volume+health are proven. Non-root Dockerfile/runtime must be shown.\n";
p01 += "track=docker-01. checks_passed/failed are short strings. evidence_paths list files you wrote.\n";

jobs.push(#{
    prompt: p01,
    label: "accept:01-docker",
    capability_mode: "all",
    output_schema: track_schema,
});

// Track B — Ticket 09 dual-path (device + protocol substitute)
let p09 = "";
p09 += "ACCEPTANCE TRACK: ticket 09 dual-device foreground acceptance (as far as env allows).\n";
p09 += "Read: .scratch/home-lan-sync/issues/09-dual-device-foreground-acceptance.md\n";
p09 += "PRD: docs/prd/sync-home-lan.md §5.4\n\n";
p09 += "Env exports for docker same as other tracks. base_url=" + base_url + "\n";
p09 += "emu_host_url=" + emu_host_url + " evidence_dir=" + evidence_dir + "\n";
p09 += "Bootstrap JSON: " + json_encode(boot) + "\n\n";
p09 += "MUST run a dual-CLIENT path against the LIVE container (not only ASGI unit tests):\n";
p09 += "Client A (owner) and Client B (member) identities — can be pure HTTP scripts if only one emulator.\n";
p09 += "Cover and evidence under " + evidence_dir + "/09-dual-path/:\n";
p09 += "1. A creates family (device_id + create_request_id); B joins via invite code\n";
p09 += "2. A pushes formula record → B pull sees it\n";
p09 += "3. B pushes pee record → A pull sees it\n";
p09 += "4. Log media or record media: one side push bytes → other pull/metadata sees\n";
p09 += "5. Avatar: owner allowed; member 403\n";
p09 += "6. Document: cellular gate / no partner notifications / independent settings =\n";
p09 += "   covered by Android unit tests (cite test class names) if not device-reproducible\n";
p09 += "7. If emulator online: open account UI, set server to " + emu_host_url + " if UI allows,\n";
p09 += "   screenshot/dump under 09-dual-path/device/; never claim QR dual-phone if only 1 device\n";
p09 += "8. Reuse/extend prior live-api.json if still valid; write dual-path.json results\n\n";
p09 += "status=done only if dual-client live HTTP path fully passes AND each Must is either checked or\n";
p09 += "explicitly env-blocked with unit-test substitute cited. No fake dual-phone claims.\n";
p09 += "track=dual-path-09.\n";

jobs.push(#{
    prompt: p09,
    label: "accept:09-dual-path",
    capability_mode: "all",
    output_schema: track_schema,
});

// Track C — Regression automation
let preg = "";
preg += "ACCEPTANCE TRACK: regression automation for home-lan-sync.\n";
preg += "evidence_dir=" + evidence_dir + "\n";
preg += "Bootstrap JSON: " + json_encode(boot) + "\n\n";
preg += "Run and capture logs under " + evidence_dir + "/regression/:\n";
preg += "1. cd tools/lezi-sync && (activate .venv or create) pip install -e '.[test]' && pytest -q\n";
preg += "2. From repo root: ./gradlew :sync:test :feature:family:testDebugUnitTest --continue\n";
preg += "3. Optional: ./gradlew :app:assembleDebug if APK missing\n";
preg += "4. Write regression-summary.md with exit codes and key suite names\n\n";
preg += "status=done if pytest and gradle module tests pass. track=regression.\n";
preg += "Do not mark done on compile-only without tests actually running.\n";

jobs.push(#{
    prompt: preg,
    label: "accept:regression",
    capability_mode: "all",
    output_schema: track_schema,
});

// Track D — Device UI path (optional)
if !skip_device {
    let pdev = "";
    pdev += "ACCEPTANCE TRACK: single-emulator device UI path against live lezi-sync.\n";
    pdev += "evidence_dir=" + evidence_dir + " emu_host_url=" + emu_host_url + "\n";
    pdev += "Bootstrap JSON: " + json_encode(boot) + "\n\n";
    pdev += "1. adb devices; if none, lezi-emu start and wait boot\n";
    pdev += "2. Ensure debug APK installed: app/build/outputs/apk/debug/app-debug.apk → adb install -r\n";
    pdev += "3. am start MainActivity; capture lezi-emu shot/dump under " + evidence_dir + "/device-ui/\n";
    pdev += "4. Navigate to 账户/家庭; attempt set server base URL to " + emu_host_url + "\n";
    pdev += "5. Attempt create family or show invite UI; capture dumps/logcat for FATAL\n";
    pdev += "6. Write device-ui.md: what worked, what blocked (Wi-Fi gate on emulator, UI labels, etc.)\n";
    pdev += "7. status=partial is OK if server UI reachable but join QR not dual-phone; status=failed on crash\n";
    pdev += "track=device-ui. Prefer evidence over perfection.\n";

    jobs.push(#{
        prompt: pdev,
        label: "accept:device-ui",
        capability_mode: "all",
        output_schema: track_schema,
    });
} else {
    log("skip_device=true — device-ui track omitted");
}

let track_results = parallel(jobs);

let tracks = [];
let any_failed = false;
let i = 0;
for r in track_results {
    if r != () && r.success && r.output != () {
        tracks.push(r.output);
        log("Track " + r.output.track + ": " + r.output.status + " — " + r.output.summary);
        if r.output.status == "failed" { any_failed = true; }
    } else {
        any_failed = true;
        tracks.push(#{
            track: "index-" + i.to_string(),
            status: "failed",
            summary: "agent slot failed",
            checks_passed: [],
            checks_failed: ["agent failed or empty"],
            evidence_paths: [],
            env_blockers: [],
            notes: "",
        });
        log("Track index " + i.to_string() + " agent failed");
    }
    i += 1;
}

// =============================================================================
// Phase: Close tickets
// =============================================================================

phase("Close tickets");

let close_result = #{
    ok: true,
    summary: "skipped ticket file updates",
    ticket_01_status: "unchanged",
    ticket_09_status: "unchanged",
    files_updated: [],
    honest_gaps: [],
};

if !skip_ticket_update {
    log("Updating ticket Status from parallel acceptance evidence");

    let close_prompt = "";
    close_prompt += "Update home-lan-sync ticket tracker from acceptance evidence. Be honest.\n\n";
    close_prompt += "Files to update:\n";
    close_prompt += "- .scratch/home-lan-sync/issues/01-lezi-sync-skeleton-docker.md\n";
    close_prompt += "- .scratch/home-lan-sync/issues/09-dual-device-foreground-acceptance.md\n";
    close_prompt += "- .scratch/home-lan-sync/ISSUES.md (table Status + 验证边界)\n";
    close_prompt += "- Optionally .scratch/home-lan-sync/spec.md Status line\n\n";
    close_prompt += "Rules:\n";
    close_prompt += "- Ticket 01 → done only if image build + single volume db+media + curl health proven\n";
    close_prompt += "- Ticket 09 → done only if dual-client live path proven; if only 1 emulator,\n";
    close_prompt += "  use status partial or verification-pending with dual-HTTP evidence + unchecked dual-phone boxes\n";
    close_prompt += "- Tick Must checkboxes with [x] only for proven items; leave env-blocked unchecked\n";
    close_prompt += "- Append ## Comments with date 2026-07-25, commands, evidence paths, docker rootless note\n";
    close_prompt += "- Do not claim NAS production deploy or two physical phones without evidence\n\n";
    close_prompt += "Bootstrap: " + json_encode(boot) + "\n";
    close_prompt += "Tracks: " + json_encode(tracks) + "\n";
    close_prompt += "evidence_dir=" + evidence_dir + "\n";
    close_prompt += "Return ticket_01_status and ticket_09_status as the new Status strings written.\n";

    let close_r = agent(close_prompt, #{
        label: "close-tickets",
        capability_mode: "read-write",
        output_schema: close_schema,
    });

    if close_r != () && close_r.success && close_r.output != () {
        close_result = close_r.output;
        log("Tickets: 01=" + close_result.ticket_01_status + " 09=" + close_result.ticket_09_status);
    } else {
        close_result.ok = false;
        close_result.summary = "close-tickets agent failed";
        any_failed = true;
    }
} else {
    log("skip_ticket_update=true");
}

// =============================================================================
// Phase: Report
// =============================================================================

phase("Report");
log("Writing final home-lan Docker acceptance report");

let report_prompt = "";
report_prompt += "Write the final acceptance report for home-lan Docker parallel acceptance.\n";
report_prompt += "Path: " + evidence_dir + "/REPORT.md\n\n";
report_prompt += "Also write a short machine summary JSON at " + evidence_dir + "/summary.json\n\n";
report_prompt += "Sections:\n";
report_prompt += "1. Executive summary (what is green / what remains)\n";
report_prompt += "2. Environment (rootless Docker, image tags, ports, emulator)\n";
report_prompt += "3. Ticket 01 results + Must table\n";
report_prompt += "4. Ticket 09 results + Must table (honest dual-device vs dual-client HTTP)\n";
report_prompt += "5. Regression (pytest + gradle)\n";
report_prompt += "6. Device UI notes\n";
report_prompt += "7. Evidence file index\n";
report_prompt += "8. Remaining risks / how to re-run\n\n";
report_prompt += "Verify key evidence files exist with tools before claiming them.\n";
report_prompt += "Bootstrap: " + json_encode(boot) + "\n";
report_prompt += "Tracks: " + json_encode(tracks) + "\n";
report_prompt += "Close: " + json_encode(close_result) + "\n";
report_prompt += "ok=true if REPORT.md written and ticket 01 docker runtime is accepted or gaps listed.\n";

let report_r = agent(report_prompt, #{
    label: "final-report",
    capability_mode: "read-write",
    output_schema: report_schema,
});

let report_path = evidence_dir + "/REPORT.md";
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

let final_obj = #{
    boot: boot,
    tracks: tracks,
    close: close_result,
    report_path: report_path,
    report_ok: report_ok,
    report_summary: report_summary,
    any_failed: any_failed,
    remaining_risks: remaining,
};
let scratch_path = write_scratch_file("home-lan-acceptance-final.json", json_encode(final_obj));

log("Done. Report: " + report_path + " scratch: " + scratch_path);

complete(#{
    summary: report_summary,
    report_path: report_path,
    scratch_path: scratch_path,
    ticket_01_status: close_result.ticket_01_status,
    ticket_09_status: close_result.ticket_09_status,
    tracks: tracks,
    any_failed: any_failed,
    remaining_risks: remaining,
});
