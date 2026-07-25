// Ticket 09 device-side family + invite path, optional dual-emulator QR/join,
// and live backend (lezi-sync) verification after a clean family rebuild.
//
// Modes (args.mode):
//   auto          — prefer dual-emu if feasible; else single-device create+invite + backend (default)
//   device-family — wipe server family, one emulator: create family + invite code + backend checks
//   dual-emu      — require two emulators (clone AVD / second port); A create + invite/QR, B join,
//                   then bidirectional foreground-style sync + backend verify
//
// Args (all optional):
//   mode: string
//   evidence_dir: string  default docs/reviews/device-family-dual-emu-09
//   base_url: string      default http://127.0.0.1:8765
//   emu_host_url: string  default http://10.0.2.2:8765
//   app_package: string   default com.lezi.babylog.debug
//   wipe_volume: bool     default true — recreate empty lezi-sync-data so create-family is not 409
//   skip_ticket_update: bool default false
//   allow_single_fallback: bool default true when mode=auto/dual-emu
//
// Env:
//   export PATH="$HOME/bin:$HOME/.local/bin:$PATH"
//   export DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock
//   lezi-emu for primary AVD; second emu via `emulator -avd lezi_api35 -port 5556 -read-only ...`
//     or a cloned AVD if read-only multi-instance fails.
//
// Run:
//   /device-family-dual-emu-09
//   /workflow device-family-dual-emu-09 {"mode":"device-family"}
//   /workflow device-family-dual-emu-09 {"mode":"dual-emu"}

let meta = #{
    name: "device-family-dual-emu-09",
    description: "Wipe family volume, device create+invite, optional dual-emulator join, and lezi-sync backend verification for ticket 09",
    when_to_use: "When ticket 09 is partial and you need clean device-side create-family + invite, or dual-emulator join + backend verify",
    phases: [
        #{ title: "Reset family", detail: "Healthy lezi-sync with empty data volume (no 409)" },
        #{ title: "Devices", detail: "Primary emulator + optional second emulator" },
        #{ title: "Device family", detail: "Owner: onboarding, set baseUrl, create family, invite" },
        #{ title: "Join path", detail: "Member join via invite code and/or QR if dual-emu" },
        #{ title: "Backend verify", detail: "Live HTTP + optional UI formula/pee cross-visibility" },
        #{ title: "Close report", detail: "Update ticket 09 + REPORT.md" },
    ],
};

let phase_schema = #{
    "type": "object",
    "required": ["ok", "status", "summary", "evidence_paths"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "status": #{ "type": "string", "enum": ["done", "partial", "blocked", "failed"] },
        "summary": #{ "type": "string" },
        "evidence_paths": #{ "type": "array", "items": #{ "type": "string" } },
        "checks_passed": #{ "type": "array", "items": #{ "type": "string" } },
        "checks_failed": #{ "type": "array", "items": #{ "type": "string" } },
        "env_blockers": #{ "type": "array", "items": #{ "type": "string" } },
        "artifacts": #{ "type": "object" },
        "notes": #{ "type": "string" },
    },
};

let report_schema = #{
    "type": "object",
    "required": ["ok", "path", "summary", "ticket_09_status"],
    "properties": #{
        "ok": #{ "type": "boolean" },
        "path": #{ "type": "string" },
        "summary": #{ "type": "string" },
        "ticket_09_status": #{ "type": "string" },
        "remaining_risks": #{ "type": "array", "items": #{ "type": "string" } },
        "files_updated": #{ "type": "array", "items": #{ "type": "string" } },
    },
};

// --- Args ---

let mode = "auto";
let evidence_dir = "docs/reviews/device-family-dual-emu-09";
let base_url = "http://127.0.0.1:8765";
let emu_host_url = "http://10.0.2.2:8765";
let app_pkg = "com.lezi.babylog.debug";
let wipe_volume = true;
let skip_ticket_update = false;
let allow_single_fallback = true;

if args != () {
    if args.mode != () && type_of(args.mode) == "string" { mode = args.mode; }
    if args.evidence_dir != () && type_of(args.evidence_dir) == "string" { evidence_dir = args.evidence_dir; }
    if args.base_url != () && type_of(args.base_url) == "string" { base_url = args.base_url; }
    if args.emu_host_url != () && type_of(args.emu_host_url) == "string" { emu_host_url = args.emu_host_url; }
    if args.app_package != () && type_of(args.app_package) == "string" { app_pkg = args.app_package; }
    if args.wipe_volume != () && args.wipe_volume == false { wipe_volume = false; }
    if args.skip_ticket_update != () && args.skip_ticket_update == true { skip_ticket_update = true; }
    if args.allow_single_fallback != () && args.allow_single_fallback == false { allow_single_fallback = false; }
}

let want_dual = (mode == "dual-emu") || (mode == "auto");
let want_device_family = (mode == "device-family") || (mode == "auto") || (mode == "dual-emu");

log("mode=" + mode + " evidence_dir=" + evidence_dir + " wipe_volume=" + wipe_volume.to_string());

// =============================================================================
// Phase: Reset family (empty volume + healthy container)
// =============================================================================

phase("Reset family");

let reset_prompt = "";
reset_prompt += "RESET lezi-sync to empty family state so device create-family is not HTTP 409.\n\n";
reset_prompt += "ALWAYS:\n";
reset_prompt += "  export PATH=\"$HOME/bin:$HOME/.local/bin:$PATH\"\n";
reset_prompt += "  export DOCKER_HOST=unix:///run/user/$(id -u)/docker.sock\n\n";
reset_prompt += "evidence_dir=" + evidence_dir + " base_url=" + base_url + " wipe_volume=" + wipe_volume.to_string() + "\n";
reset_prompt += "mkdir -p " + evidence_dir + "/00-reset\n\n";
reset_prompt += "Steps:\n";
reset_prompt += "1. Ensure rootless docker active (systemctl --user start docker if needed).\n";
reset_prompt += "2. Ensure image lezi-sync:0.1.0 or lezi-sync:latest exists; else cd tools/lezi-sync && ./build-image.sh\n";
reset_prompt += "3. If wipe_volume=true:\n";
reset_prompt += "   - docker rm -f lezi-sync 2>/dev/null || true\n";
reset_prompt += "   - docker volume rm lezi-sync-data 2>/dev/null || true\n";
reset_prompt += "   - docker volume create lezi-sync-data\n";
reset_prompt += "   - docker run -d --name lezi-sync -p 8765:8765 -e LEZI_DATA_DIR=/data \\\n";
reset_prompt += "       -v lezi-sync-data:/data lezi-sync:0.1.0 || lezi-sync:latest\n";
reset_prompt += "   If volume rm fails because another container uses it, stop lezi-sync-bind too if needed for wipe,\n";
reset_prompt += "   or create volume lezi-sync-data-fresh and bind that to a NEW container name lezi-sync on 8765.\n";
reset_prompt += "4. If wipe_volume=false: keep healthy container; still verify /health.\n";
reset_prompt += "5. Wait until curl -sS " + base_url + "/health → 200 {ok:true,version:...}\n";
reset_prompt += "6. Prove empty family: POST /v1/family/create with new create_request_id must return 201 (not 409).\n";
reset_prompt += "   Then optionally leave that family or delete via owner /v1/family/delete so device starts clean —\n";
reset_prompt += "   prefer delete so device is first creator. Save create/delete JSON with tokens REDACTED.\n";
reset_prompt += "7. Write 00-reset/reset-notes.md + health.json + volume-inspect.txt\n\n";
reset_prompt += "ok=true only if health 200 and create-family is possible (201). status=done|failed.\n";
reset_prompt += "artifacts may include volume_name, container_id.\n";

let reset_r = agent(reset_prompt, #{
    label: "reset-family-volume",
    capability_mode: "all",
    output_schema: phase_schema,
});

let reset = #{
    ok: false,
    status: "failed",
    summary: "reset agent failed",
    evidence_paths: [],
    checks_passed: [],
    checks_failed: ["reset agent failed"],
    env_blockers: [],
    notes: "",
};
if reset_r != () && reset_r.success && reset_r.output != () {
    reset = reset_r.output;
}
log("Reset: " + reset.status + " — " + reset.summary);
if reset.ok != true {
    log("WARNING: reset not fully ok — later phases must heal or mark blocked");
}

// =============================================================================
// Phase: Devices (primary + optional second emulator)
// =============================================================================

phase("Devices");

let dev_prompt = "";
dev_prompt += "PREPARE Android emulators for lezi device acceptance.\n";
dev_prompt += "app_package=" + app_pkg + " evidence_dir=" + evidence_dir + " want_dual=" + want_dual.to_string() + "\n";
dev_prompt += "allow_single_fallback=" + allow_single_fallback.to_string() + " mode=" + mode + "\n";
dev_prompt += "APK: app/build/outputs/apk/debug/app-debug.apk — assembleDebug if missing.\n\n";
dev_prompt += "mkdir -p " + evidence_dir + "/01-devices\n\n";
dev_prompt += "Primary device (serial A):\n";
dev_prompt += "1. lezi-emu status; if no device, lezi-emu start (or --swiftshader if GPU fails)\n";
dev_prompt += "2. adb devices -l → record primary serial (usually emulator-5554)\n";
dev_prompt += "3. adb -s <A> install -r debug APK\n";
dev_prompt += "4. adb -s <A> shell pm clear " + app_pkg + "   # clean app state for create-family\n";
dev_prompt += "5. adb -s <A> shell am start -n " + app_pkg + "/com.lezi.babylog.MainActivity\n\n";
dev_prompt += "Second device (serial B) if want_dual:\n";
dev_prompt += "6. Try start second emulator instance WITHOUT killing A:\n";
dev_prompt += "   - Preferred: emulator -avd lezi_api35 -port 5556 -read-only -no-window -no-audio \\\n";
dev_prompt += "       -no-boot-anim -gpu swiftshader_indirect -no-snapshot-save &\n";
dev_prompt += "   - Or clone AVD: avdmanager copy/create lezi_api35_b if read-only fails\n";
dev_prompt += "   - Wait boot_completed on new serial (often emulator-5556)\n";
dev_prompt += "7. adb -s <B> install -r APK; pm clear; start MainActivity\n";
dev_prompt += "8. If second emu cannot start and allow_single_fallback=true: document blocker, continue single-device.\n";
dev_prompt += "   If mode=dual-emu and allow_single_fallback=false: status=blocked.\n";
dev_prompt += "9. Write 01-devices/devices.txt (serials), install logs, dual_ok bool in notes\n\n";
dev_prompt += "artifacts SHOULD include serial_a, serial_b (empty if none), dual_ready (bool as string in notes).\n";
dev_prompt += "ok=true if at least primary device has app installed and launched without install failure.\n";
dev_prompt += "status=done if dual ready when wanted OR single ready when dual not required/fallback ok.\n";

let dev_r = agent(dev_prompt, #{
    label: "prepare-devices",
    capability_mode: "all",
    output_schema: phase_schema,
});

let devices = #{
    ok: false,
    status: "failed",
    summary: "devices agent failed",
    evidence_paths: [],
    checks_passed: [],
    checks_failed: ["devices agent failed"],
    env_blockers: [],
    notes: "",
};
if dev_r != () && dev_r.success && dev_r.output != () {
    devices = dev_r.output;
}
log("Devices: " + devices.status + " — " + devices.summary);

// =============================================================================
// Phase: Device family — owner create + invite (and parallel backend smoke)
// =============================================================================

phase("Device family");

// Parallel: owner UI create/invite + backend health/create capability recheck
let fam_jobs = [];

let owner_p = "";
owner_p += "DEVICE OWNER path: complete create-family + invite on primary emulator AFTER clean volume.\n";
owner_p += "app_package=" + app_pkg + " emu_host_url=" + emu_host_url + " base_url=" + base_url + "\n";
owner_p += "evidence_dir=" + evidence_dir + "/02-owner\n";
owner_p += "Prior reset: " + json_encode(reset) + "\n";
owner_p += "Prior devices: " + json_encode(devices) + "\n\n";
owner_p += "Use adb + lezi-emu shot/dump (pass -s serial). Read docs/prd/sync-home-lan.md and FamilyScreen for labels.\n";
owner_p += "UI is 简体中文. Typical path: 账户 tab → 服务器地址 set to " + emu_host_url + " → 保存\n";
owner_p += "→ 新建家庭 / 创建家庭 → expect success (NOT 409)\n";
owner_p += "→ generate 邀请码 or show QR if available; capture invite code text from dump if possible\n\n";
owner_p += "Also complete onboarding if first launch (baby nickname etc.) before account actions.\n";
owner_p += "Save shots/dumps under evidence_dir; write owner-result.md with:\n";
owner_p += "  create_ok, invite_code (or QR path), server_url_saved, errors\n";
owner_p += "If create fails, capture logcat for lezi/sync and HTTP status.\n";
owner_p += "Redact tokens in any saved JSON.\n";
owner_p += "ok=true only if family created on device against live server AND invite code or QR is available for B.\n";
owner_p += "status=done|partial|failed. artifacts.invite_code if known.\n";

fam_jobs.push(#{
    prompt: owner_p,
    label: "owner-create-invite",
    capability_mode: "all",
    output_schema: phase_schema,
});

let backend_smoke_p = "";
backend_smoke_p += "BACKEND smoke while owner works: verify empty-ish live server at " + base_url + ".\n";
backend_smoke_p += "evidence_dir=" + evidence_dir + "/02-backend-smoke\n";
backend_smoke_p += "1. GET /health\n";
backend_smoke_p += "2. Script: create family via HTTP, invite, join second device_id, push formula, pull — REDACT tokens\n";
backend_smoke_p += "3. This proves backend independently of UI; do NOT leave extra families if owner UI needs exclusive empty server —\n";
backend_smoke_p += "   prefer only /health + list nothing if wipe already proven. If you create, delete with owner family/delete after.\n";
backend_smoke_p += "ok=true if health ok and server accepts create/invite/join/push/pull (or health+delete clean).\n";

fam_jobs.push(#{
    prompt: backend_smoke_p,
    label: "backend-smoke",
    capability_mode: "all",
    output_schema: phase_schema,
});

let fam_results = parallel(fam_jobs);

let owner = #{
    ok: false,
    status: "failed",
    summary: "owner agent missing",
    evidence_paths: [],
    checks_passed: [],
    checks_failed: [],
    env_blockers: [],
    notes: "",
};
let backend_smoke = #{
    ok: false,
    status: "failed",
    summary: "backend smoke missing",
    evidence_paths: [],
    checks_passed: [],
    checks_failed: [],
    env_blockers: [],
    notes: "",
};
if fam_results.len() > 0 && fam_results[0] != () && fam_results[0].success && fam_results[0].output != () {
    owner = fam_results[0].output;
}
if fam_results.len() > 1 && fam_results[1] != () && fam_results[1].success && fam_results[1].output != () {
    backend_smoke = fam_results[1].output;
}
log("Owner: " + owner.status + " — " + owner.summary);
log("Backend smoke: " + backend_smoke.status + " — " + backend_smoke.summary);

// =============================================================================
// Phase: Join path (member on second emu or invite-code on same-device protocol assist)
// =============================================================================

phase("Join path");

let join_prompt = "";
join_prompt += "MEMBER join path for ticket 09.\n";
join_prompt += "mode=" + mode + " want_dual=" + want_dual.to_string() + "\n";
join_prompt += "app_package=" + app_pkg + " emu_host_url=" + emu_host_url + "\n";
join_prompt += "evidence_dir=" + evidence_dir + "/03-join\n";
join_prompt += "Owner result JSON: " + json_encode(owner) + "\n";
join_prompt += "Devices JSON: " + json_encode(devices) + "\n\n";
join_prompt += "Strategy:\n";
join_prompt += "A) If two emulators ready (dual):\n";
join_prompt += "   - On B: onboarding, set server " + emu_host_url + ", join via 输入邀请码 from owner invite_code\n";
join_prompt += "   - Also try QR path if owner shows QR and B has scanner UI — capture both attempts\n";
join_prompt += "   - Prefer invite-code success even if QR automation fails (document QR as manual/partial)\n";
join_prompt += "B) If only one emulator:\n";
join_prompt += "   - Complete invite-code join using a SECOND app user is hard on one device;\n";
join_prompt += "   - Do HTTP member join with invite code extracted from owner UI/dump (backend join),\n";
join_prompt += "     then on the same device verify family state if UI shows members; mark dual-phone QR unchecked\n";
join_prompt += "   - If owner invite_code unknown, read dumps under 02-owner to extract code text\n";
join_prompt += "C) Save join-result.md, shots/dumps, redacted join JSON\n\n";
join_prompt += "ok=true if a member role is established on the same family (device B or HTTP member) after owner create.\n";
join_prompt += "status=done for dual device join; partial for HTTP-member-only; failed if no join.\n";

let join_r = agent(join_prompt, #{
    label: "member-join",
    capability_mode: "all",
    output_schema: phase_schema,
});

let join = #{
    ok: false,
    status: "failed",
    summary: "join agent failed",
    evidence_paths: [],
    checks_passed: [],
    checks_failed: ["join agent failed"],
    env_blockers: [],
    notes: "",
};
if join_r != () && join_r.success && join_r.output != () {
    join = join_r.output;
}
log("Join: " + join.status + " — " + join.summary);

// =============================================================================
// Phase: Backend verify + optional dual UI formula/pee
// =============================================================================

phase("Backend verify");

let ver_jobs = [];

let live_p = "";
live_p += "LIVE BACKEND verification for the family created in this run (not a random leftover family if avoidable).\n";
live_p += "base_url=" + base_url + " evidence_dir=" + evidence_dir + "/04-backend\n";
live_p += "Owner/join artifacts: " + json_encode(owner) + " " + json_encode(join) + "\n\n";
live_p += "If you have owner+member tokens (from HTTP assist or saved redacted flows), re-auth may be impossible if redacted.\n";
live_p += "Preferred: use invite/create flow again ONLY if device family already exists — instead:\n";
live_p += "1. From device dumps/notes extract family_id if present\n";
live_p += "2. Run end-to-end HTTP create/invite/join/push/pull/media/avatar ACL on the LIVE container\n";
live_p += "   as protocol regression (label as protocol-verify) AND separately note whether it is the same family as device\n";
live_p += "3. Checks: health, formula push A→B, pee push B→A, log media, avatar member 403, no-token 401\n";
live_p += "4. Write backend-verify.json with tokens REDACTED and checklist.md\n";
live_p += "ok=true if all protocol checks pass on live container.\n";

ver_jobs.push(#{
    prompt: live_p,
    label: "backend-live-verify",
    capability_mode: "all",
    output_schema: phase_schema,
});

let ui_p = "";
ui_p += "FOREGROUND UI cross-visibility if dual emulators (or best-effort single device).\n";
ui_p += "app_package=" + app_pkg + " evidence_dir=" + evidence_dir + "/04-ui-cross\n";
ui_p += "Devices: " + json_encode(devices) + " Join: " + json_encode(join) + "\n\n";
ui_p += "If dual serials:\n";
ui_p += "1. On A (owner): add formula record via Composer, confirm write; optional pull-to-refresh\n";
ui_p += "2. On B: resume app / pull-to-refresh / re-enter 记录 tab; verify formula visible\n";
ui_p += "3. On B: add pee; on A refresh; verify pee visible\n";
ui_p += "4. Capture shots/dumps before/after on both\n";
ui_p += "If single device only: mark UI cross as skipped/blocked; do not fake dual visibility\n";
ui_p += "Also scan logcat both devices for FATAL\n";
ui_p += "ok=true only if dual UI cross visibility proven; partial if only one side recorded.\n";

ver_jobs.push(#{
    prompt: ui_p,
    label: "ui-cross-visibility",
    capability_mode: "all",
    output_schema: phase_schema,
});

let ver_results = parallel(ver_jobs);

let backend_v = #{
    ok: false,
    status: "failed",
    summary: "backend verify missing",
    evidence_paths: [],
    checks_passed: [],
    checks_failed: [],
    env_blockers: [],
    notes: "",
};
let ui_cross = #{
    ok: false,
    status: "failed",
    summary: "ui cross missing",
    evidence_paths: [],
    checks_passed: [],
    checks_failed: [],
    env_blockers: [],
    notes: "",
};
if ver_results.len() > 0 && ver_results[0] != () && ver_results[0].success && ver_results[0].output != () {
    backend_v = ver_results[0].output;
}
if ver_results.len() > 1 && ver_results[1] != () && ver_results[1].success && ver_results[1].output != () {
    ui_cross = ver_results[1].output;
}
log("Backend verify: " + backend_v.status + " — " + backend_v.summary);
log("UI cross: " + ui_cross.status + " — " + ui_cross.summary);

// =============================================================================
// Phase: Close report + ticket 09
// =============================================================================

phase("Close report");

let report_prompt = "";
report_prompt += "Write final report and update ticket 09 honestly.\n\n";
report_prompt += "Write:\n";
report_prompt += "1. " + evidence_dir + "/REPORT.md — full narrative + Must table from ticket 09\n";
report_prompt += "2. " + evidence_dir + "/summary.json — machine summary, REDACT secrets\n";
if !skip_ticket_update {
    report_prompt += "3. Update .scratch/home-lan-sync/issues/09-dual-device-foreground-acceptance.md Status + Must checkboxes + Comments\n";
    report_prompt += "4. Update .scratch/home-lan-sync/ISSUES.md row for 09 and 验证边界\n";
} else {
    report_prompt += "3. skip_ticket_update=true — do not edit .scratch tickets\n";
}
report_prompt += "\nStatus rules for ticket 09:\n";
report_prompt += "- done: dual-device (2 emulators or phones) create+join (invite or QR) AND formula/pee cross-visible on devices OR equivalent proven with device evidence\n";
report_prompt += "- partial: clean device create+invite ok + backend live dual-client ok but not dual-phone QR / not dual UI\n";
report_prompt += "- verification-pending: still blocked on env\n";
report_prompt += "- Never claim dual-phone QR without two devices and evidence shots\n\n";
report_prompt += "Phase results JSON:\n";
report_prompt += "reset: " + json_encode(reset) + "\n";
report_prompt += "devices: " + json_encode(devices) + "\n";
report_prompt += "owner: " + json_encode(owner) + "\n";
report_prompt += "backend_smoke: " + json_encode(backend_smoke) + "\n";
report_prompt += "join: " + json_encode(join) + "\n";
report_prompt += "backend_v: " + json_encode(backend_v) + "\n";
report_prompt += "ui_cross: " + json_encode(ui_cross) + "\n";
report_prompt += "mode=" + mode + " evidence_dir=" + evidence_dir + "\n";
report_prompt += "Verify evidence files exist before listing them. ticket_09_status = Status string written.\n";

let report_r = agent(report_prompt, #{
    label: "close-report-09",
    capability_mode: "all",
    output_schema: report_schema,
});

let report_path = evidence_dir + "/REPORT.md";
let report_ok = false;
let report_summary = "report agent failed";
let ticket_09_status = "unchanged";
let remaining = [];
let files_updated = [];
if report_r != () && report_r.success && report_r.output != () {
    report_ok = report_r.output.ok;
    if report_r.output.path != () && report_r.output.path != "" {
        report_path = report_r.output.path;
    }
    report_summary = report_r.output.summary;
    if report_r.output.ticket_09_status != () {
        ticket_09_status = report_r.output.ticket_09_status;
    }
    if report_r.output.remaining_risks != () {
        remaining = report_r.output.remaining_risks;
    }
    if report_r.output.files_updated != () {
        files_updated = report_r.output.files_updated;
    }
}

let final_obj = #{
    mode: mode,
    reset: reset,
    devices: devices,
    owner: owner,
    backend_smoke: backend_smoke,
    join: join,
    backend_v: backend_v,
    ui_cross: ui_cross,
    report_path: report_path,
    report_ok: report_ok,
    report_summary: report_summary,
    ticket_09_status: ticket_09_status,
    remaining_risks: remaining,
    files_updated: files_updated,
};
let scratch_path = write_scratch_file("device-family-dual-emu-09-final.json", json_encode(final_obj));

log("Done. ticket_09=" + ticket_09_status + " report=" + report_path);

complete(#{
    summary: report_summary,
    report_path: report_path,
    scratch_path: scratch_path,
    ticket_09_status: ticket_09_status,
    mode: mode,
    owner_ok: owner.ok,
    join_ok: join.ok,
    backend_ok: backend_v.ok,
    dual_ui_ok: ui_cross.ok,
    remaining_risks: remaining,
});
