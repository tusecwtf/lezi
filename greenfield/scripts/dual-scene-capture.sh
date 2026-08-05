#!/usr/bin/env bash
# Dual-install scene capture harness for Spec 02 multi-scene review.
# Evolves prototype-capture-motion-clips.sh — per scene-id, seed profiles, fail-closed legacy.
#
# Usage:
#   ./greenfield/scripts/dual-scene-capture.sh [serial] [scene-id|all] [app=both|gf|legacy]
#
# Examples:
#   ./greenfield/scripts/dual-scene-capture.sh emulator-5554 composer-formula both
#   ./greenfield/scripts/dual-scene-capture.sh emulator-5554 all both
#
# Outputs:
#   greenfield/docs/specs/baselines/scenes/<scene-id>/{gf,legacy,compare}.*
#   greenfield/docs/specs/baselines/scenes/INDEX.md
#
# Seed profiles: empty | seeded-formula | multi-row (see .scratch/spec-02-dual-scene-review/matrix.md)
# Large mp4: review-local — do not force-add to git.
set -euo pipefail

SERIAL="${1:-emulator-5554}"
SCENE_ARG="${2:-all}"
MODE="${3:-both}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_ROOT="$ROOT/docs/specs/baselines/scenes"
GF=com.lezi.babylog.gf
LEGACY_CANDIDATES=(com.lezi.babylog.debug com.lezi.babylog)
XML=/tmp/lezi-dual-ui.xml
REMOTE_MP4=/sdcard/lezi-dual-scene.mp4
BITRATE=8M
W=1080
H=2400

# scene-id → seed profile
declare -A SEED_OF=(
  [log-home-empty-ia]=empty
  [log-home-seeded-row]=seeded-formula
  [tab-crossfade]=empty
  [day-axis-nav]=empty
  [composer-formula]=empty
  [composer-sleep]=empty
  [composer-diaper]=empty
  [composer-breast-vs-timer]=empty
  [swipe-half-reveal]=multi-row
  [swipe-full-edit]=multi-row
  [swipe-full-delete]=multi-row
  [more-sheet-four-col]=empty
  [timer-enter-idle]=empty
  [timer-run-complete]=empty
  [layout-editor-drag]=empty
  [summary-week]=data-ish
  [growth-curve]=data-ish
  [template-warm-journal]=empty
)

ALL_SCENES=(
  log-home-empty-ia
  log-home-seeded-row
  tab-crossfade
  day-axis-nav
  composer-formula
  composer-sleep
  composer-diaper
  composer-breast-vs-timer
  swipe-half-reveal
  swipe-full-edit
  swipe-full-delete
  more-sheet-four-col
  timer-enter-idle
  timer-run-complete
  layout-editor-drag
)

adb() { command adb -s "$SERIAL" "$@"; }
log() { printf '%s\n' "$*"; }

mkdir -p "$OUT_ROOT"

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb pull /sdcard/ui.xml "$XML" >/dev/null 2>&1 || true
}

find_xy() {
  local needle=$1
  python3 - "$needle" "$XML" <<'PY'
import re, sys
from pathlib import Path
n, path = sys.argv[1], sys.argv[2]
xml = Path(path).read_text(errors="ignore")
def centers(pattern, group_text=1):
    out = []
    for m in re.finditer(pattern, xml):
        t = m.group(group_text)
        nums = [int(x) for x in m.groups()[-4:]]
        x1, y1, x2, y2 = nums
        out.append(((x1 + x2) // 2, (y1 + y2) // 2, t, y2))
    return out
exact = centers(rf'text="{re.escape(n)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
exact += centers(rf'content-desc="{re.escape(n)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
if exact:
    exact.sort(key=lambda c: c[3])
    x, y, _, _ = exact[-1]
    print(x, y)
    sys.exit(0)
# Soft match: only if needle is not a short substring of a longer label that would false-hit
# (e.g. "布局" must not match "配方奶，长按编辑布局" — require short soft or exact desc).
soft = centers(rf'text="([^"]*{re.escape(n)}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
soft = [c for c in soft if len(c[2]) <= len(n) + 8]
if soft:
    soft.sort(key=lambda c: c[3])
    x, y, _, _ = soft[-1]
    print(x, y)
    sys.exit(0)
sys.exit(2)
PY
}

tap_n() {
  dump
  local xy
  if ! xy=$(find_xy "$1"); then
    log "  ! miss '$1'"
    return 1
  fi
  local x y
  read -r x y <<<"$xy"
  log "  tap '$1' @ $x,$y"
  adb shell input tap "$x" "$y"
  sleep 0.45
}

tap_xy() {
  adb shell input tap "$1" "$2"
  sleep 0.35
}

tap_confirm() {
  dump
  if python3 - "$XML" <<'PY' > /tmp/xy-dual.txt
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
labels = ("确认记录", "确认睡下", "确认醒来", "确认", "保存")
c=[]
for lab in labels:
    for m in re.finditer(rf'(?:text|content-desc)="{re.escape(lab)}"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1,y1,x2,y2=map(int,m.groups()); c.append(((x1+x2)//2,(y1+y2)//2,y2,lab))
if not c:
    raise SystemExit(1)
c.sort(key=lambda t:t[2])
print(c[-1][0], c[-1][1], c[-1][3])
PY
  then
    read -r x y lab < /tmp/xy-dual.txt
    log "  tap confirm '$lab' @ $x,$y"
    adb shell input tap "$x" "$y"
    sleep 0.9
    return 0
  fi
  log "  ! miss confirm"
  return 1
}

# Prefer exact bottom-most 配方奶 (dock), not more-sheet duplicates.
tap_formula_dock() {
  dump
  if python3 - "$XML" <<'PY' > /tmp/xy-dual.txt
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
c=[]
for m in re.finditer(r'text="配方奶"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2=map(int,m.groups()); c.append(((x1+x2)//2,(y1+y2)//2,y2))
# content-desc may be "配方奶，长按编辑布局"
for m in re.finditer(r'content-desc="配方奶[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2=map(int,m.groups()); c.append(((x1+x2)//2,(y1+y2)//2,y2))
if not c:
    raise SystemExit(1)
c.sort(key=lambda t:t[2])
print(c[-1][0], c[-1][1])
PY
  then
    read -r x y < /tmp/xy-dual.txt
    log "  tap 配方奶 dock @ $x,$y"
    adb shell input tap "$x" "$y"
    sleep 0.9
    return 0
  fi
  return 1
}

with_record() {
  local dest=$1
  local limit=${2:-8}
  shift 2
  adb shell rm -f "$REMOTE_MP4" >/dev/null 2>&1 || true
  adb shell "screenrecord --time-limit $limit --bit-rate $BITRATE --size ${W}x${H} $REMOTE_MP4" &
  local rec_pid=$!
  sleep 0.35
  set +e
  "$@"
  local act_rc=$?
  set -e
  sleep 0.5
  adb shell pkill -INT screenrecord >/dev/null 2>&1 || true
  wait "$rec_pid" 2>/dev/null || true
  sleep 0.4
  if ! adb pull "$REMOTE_MP4" "$dest" >/dev/null 2>&1; then
    log "  ! pull failed for $dest (action rc=$act_rc)"
    return 0
  fi
  local bytes
  bytes=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest")
  log "  ✓ $(basename "$dest") (${bytes} B, action rc=$act_rc)"
}

make_strip() {
  local mp4=$1
  local jpg=$2
  [[ -f "$mp4" ]] || return 0
  ffmpeg -y -hide_banner -loglevel error -i "$mp4" \
    -vf "fps=2,scale=270:-1,tile=4x2" \
    -frames:v 1 "$jpg" 2>/dev/null || true
  [[ -f "$jpg" ]] && log "  strip $(basename "$jpg")"
}

side_by_side() {
  local a=$1 b=$2 out=$3
  [[ -f "$a" && -f "$b" ]] || return 0
  ffmpeg -y -hide_banner -loglevel error \
    -i "$a" -i "$b" \
    -filter_complex "[0:v]scale=360:-2,setsar=1,pad=360:ih:(ow-iw)/2:(oh-ih)/2:black[l];\
[1:v]scale=360:-2,setsar=1,pad=360:ih:(ow-iw)/2:(oh-ih)/2:black[r];\
[l][r]hstack=inputs=2[v]" \
    -map "[v]" -an -c:v libx264 -pix_fmt yuv420p -crf 23 -preset veryfast \
    "$out" >/dev/null 2>&1 || true
  [[ -f "$out" ]] && log "  compare $(basename "$out")"
}

shot() {
  local dest=$1
  adb exec-out screencap -p > "$dest" 2>/dev/null || true
  [[ -f "$dest" ]] && log "  shot $(basename "$dest") ($(stat -c%s "$dest" 2>/dev/null || echo 0) B)"
}

resolve_legacy() {
  local p
  for p in "${LEGACY_CANDIDATES[@]}"; do
    if adb shell pm path "$p" >/dev/null 2>&1; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

require_packages() {
  if ! adb shell pm path "$GF" >/dev/null 2>&1; then
    log "FAIL: GF package $GF not installed"
    exit 3
  fi
  if [[ "$MODE" == both || "$MODE" == legacy ]]; then
    if ! resolve_legacy >/dev/null; then
      log "FAIL: no legacy package among ${LEGACY_CANDIDATES[*]} (fail-closed)"
      exit 3
    fi
  fi
}

gf_boot_home() {
  adb shell am force-stop "$GF" || true
  adb shell pm clear "$GF" >/dev/null 2>&1 || true
  adb shell am start -W -n "$GF/com.lezi.gf.app.MainActivity" >/dev/null
  sleep 1.4
  dump
  if grep -q '开始记录' "$XML" 2>/dev/null; then
    tap_n "宝宝昵称" || tap_xy 540 1227
    sleep 0.15
    adb shell input text "Doudou"
    sleep 0.25
    dump
    tap_n "开始记录"
    sleep 1.0
  fi
}

gf_goto_log() {
  dump
  tap_n "记录" || true
  sleep 0.5
}

gf_seed_formula_n() {
  local n=${1:-1}
  local i
  for ((i=0; i<n; i++)); do
    gf_goto_log
    tap_formula_dock || { log "  ! seed formula open failed"; return 1; }
    sleep 0.6
    tap_confirm || { log "  ! seed confirm failed"; return 1; }
    sleep 0.5
  done
}

legacy_boot_home() {
  local pkg=$1
  adb shell am force-stop "$GF" || true
  adb shell am force-stop "$pkg" || true
  adb shell pm clear "$pkg" >/dev/null 2>&1 || true
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 2.2
  dump
  if grep -q '离线模式' "$XML" 2>/dev/null; then
    tap_n "离线模式" || tap_xy 540 834
    sleep 2
    dump
  fi
  if grep -q '开始记录' "$XML" 2>/dev/null; then
    tap_n "开始记录" || true
    sleep 2
    dump
  fi
}

legacy_goto_log() {
  dump
  tap_n "记录" || true
  sleep 0.4
}

legacy_seed_formula_n() {
  local n=${1:-1}
  local i
  for ((i=0; i<n; i++)); do
    legacy_goto_log
    tap_formula_dock || tap_n "配方奶" || return 1
    sleep 0.8
    tap_confirm || true
    sleep 0.6
  done
}

# ---------- scene bodies (GF) ----------
scene_gf_log_home_empty() {
  gf_goto_log
  sleep 0.8
}

scene_gf_log_home_seeded() {
  gf_goto_log
  sleep 0.8
}

scene_gf_tab() {
  dump
  tap_n "汇总" || true
  sleep 0.7
  dump
  tap_n "记录" || true
  sleep 0.6
  dump
  tap_n "汇总" || true
  sleep 0.5
}

scene_gf_day_axis() {
  gf_goto_log
  dump
  # swipe three-day axis area
  adb shell input swipe 800 700 280 700 350
  sleep 0.6
  adb shell input swipe 280 700 800 700 350
  sleep 0.5
}

scene_gf_composer_formula() {
  gf_goto_log
  tap_formula_dock || true
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.4
}

scene_gf_composer_sleep() {
  gf_goto_log
  dump
  tap_n "睡眠" || true
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.4
}

scene_gf_composer_diaper() {
  gf_goto_log
  dump
  tap_n "尿" || true
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.4
}

scene_gf_breast_timer_entry() {
  gf_goto_log
  dump
  tap_n "喂奶" || true
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.4
  dump
  tap_n "计时" || true
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.3
}

scene_gf_swipe_half() {
  dump
  local rowy
  rowy=$(python3 - "$XML" <<'PY'
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
for label in ["刚刚","分钟前","小时前","配方","ml"]:
    for m in re.finditer(rf'text="([^"]*{label}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        print((int(m.group(3))+int(m.group(5)))//2); raise SystemExit
print("")
PY
)
  if [[ -z "$rowy" ]]; then log "  ! no row"; return 0; fi
  adb shell input swipe 920 "$rowy" 520 "$rowy" 380
  sleep 0.7
  adb shell input tap 540 "$rowy"
  sleep 0.3
}

scene_gf_swipe_edit() {
  dump
  local rowy
  rowy=$(python3 - "$XML" <<'PY'
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
for label in ["刚刚","分钟前","小时前","配方","ml"]:
    for m in re.finditer(rf'text="([^"]*{label}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        print((int(m.group(3))+int(m.group(5)))//2); raise SystemExit
print("")
PY
)
  if [[ -z "$rowy" ]]; then log "  ! no row"; return 0; fi
  adb shell input swipe 900 "$rowy" 120 "$rowy" 320
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.3
}

scene_gf_swipe_delete() {
  dump
  local rowy
  rowy=$(python3 - "$XML" <<'PY'
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
for label in ["刚刚","分钟前","小时前","配方","ml"]:
    for m in re.finditer(rf'text="([^"]*{label}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        print((int(m.group(3))+int(m.group(5)))//2); raise SystemExit
print("")
PY
)
  if [[ -z "$rowy" ]]; then log "  ! no row"; return 0; fi
  adb shell input swipe 160 "$rowy" 980 "$rowy" 320
  sleep 0.8
  dump
  if grep -qE '确认删除|删除' "$XML" 2>/dev/null; then
    tap_n "取消" || adb shell input keyevent 4
  fi
  sleep 0.3
}

scene_gf_more() {
  gf_goto_log
  dump
  tap_n "更多" || true
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.3
}

scene_gf_timer_idle() {
  gf_goto_log
  dump
  tap_n "计时" || true
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.3
}

scene_gf_timer_run() {
  gf_goto_log
  dump
  tap_n "计时" || true
  sleep 0.8
  dump
  tap_n "左" || tap_xy 277 995
  sleep 1.2
  dump
  tap_n "完成" || true
  sleep 0.8
  adb shell input keyevent 4
  sleep 0.3
  adb shell input keyevent 4
  sleep 0.3
}

scene_gf_layout() {
  gf_goto_log
  dump
  # exact "布局" only (soft match length-capped)
  tap_n "布局" || true
  sleep 1.0
  adb shell input swipe 980 900 980 1200 450
  sleep 0.5
  dump
  tap_n "完成" || adb shell input keyevent 4
  sleep 0.3
}

# ---------- scene bodies (legacy best-effort) ----------
scene_legacy_log_home() {
  legacy_goto_log
  sleep 0.8
}

scene_legacy_tab() {
  dump
  tap_n "汇总" || tap_n "统计" || true
  sleep 0.7
  dump
  tap_n "记录" || true
  sleep 0.5
}

scene_legacy_day_axis() {
  legacy_goto_log
  adb shell input swipe 800 280 280 280 350
  sleep 0.5
  adb shell input swipe 280 280 800 280 350
  sleep 0.4
}

scene_legacy_composer_formula() {
  legacy_goto_log
  tap_formula_dock || tap_n "配方奶" || true
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.4
}

scene_legacy_composer_sleep() {
  legacy_goto_log
  tap_n "睡眠" || true
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.3
}

scene_legacy_composer_diaper() {
  legacy_goto_log
  tap_n "尿尿" || tap_n "尿" || true
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.3
}

scene_legacy_breast_timer() {
  legacy_goto_log
  tap_n "母乳" || true
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.3
  dump
  tap_n "计时" || true
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.3
}

scene_legacy_swipe() {
  dump
  local rowy=1100
  rowy=$(python3 - "$XML" <<'PY' || true
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
for label in ["刚刚","分钟前","小时前","配方","母乳","睡眠"]:
    for m in re.finditer(rf'text="([^"]*{label}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        print((int(m.group(3))+int(m.group(5)))//2); raise SystemExit
print(1100)
PY
)
  adb shell input swipe 900 "$rowy" 200 "$rowy" 350
  sleep 0.7
  adb shell input keyevent 4
  sleep 0.3
}

scene_legacy_more() {
  legacy_goto_log
  dump
  tap_n "更多" || true
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.3
}

scene_legacy_timer() {
  legacy_goto_log
  dump
  if ! tap_n "计时"; then
    tap_n "母乳" || true
  fi
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.3
}

scene_legacy_layout() {
  # 0.3.x layout may live under menu — best-effort
  dump
  tap_n "菜单" || true
  sleep 0.5
  dump
  tap_n "布局" || true
  sleep 0.8
  adb shell input keyevent 4
  sleep 0.3
}

prepare_seed_gf() {
  local profile=$1
  case "$profile" in
    empty) ;;
    seeded-formula) gf_seed_formula_n 1 ;;
    multi-row) gf_seed_formula_n 2 ;;
    data-ish) gf_seed_formula_n 3 ;;
  esac
}

prepare_seed_legacy() {
  local profile=$1
  case "$profile" in
    empty) ;;
    seeded-formula) legacy_seed_formula_n 1 || true ;;
    multi-row) legacy_seed_formula_n 2 || true ;;
    data-ish) legacy_seed_formula_n 3 || true ;;
  esac
}

run_scene_gf() {
  local sid=$1
  local seed=${SEED_OF[$sid]:-empty}
  local dir="$OUT_ROOT/$sid"
  mkdir -p "$dir"
  log "== GF scene=$sid seed=$seed =="
  gf_boot_home
  prepare_seed_gf "$seed"
  case "$sid" in
    log-home-empty-ia)
      with_record "$dir/gf.mp4" 4 scene_gf_log_home_empty
      scene_gf_log_home_empty; shot "$dir/gf.png"
      ;;
    log-home-seeded-row)
      with_record "$dir/gf.mp4" 4 scene_gf_log_home_seeded
      scene_gf_log_home_seeded; shot "$dir/gf.png"
      ;;
    tab-crossfade)
      with_record "$dir/gf.mp4" 6 scene_gf_tab
      shot "$dir/gf.png"
      ;;
    day-axis-nav)
      with_record "$dir/gf.mp4" 5 scene_gf_day_axis
      shot "$dir/gf.png"
      ;;
    composer-formula)
      with_record "$dir/gf.mp4" 5 scene_gf_composer_formula
      gf_goto_log; tap_formula_dock || true; sleep 0.8; shot "$dir/gf.png"
      adb shell input keyevent 4
      ;;
    composer-sleep)
      with_record "$dir/gf.mp4" 5 scene_gf_composer_sleep
      gf_goto_log; tap_n "睡眠" || true; sleep 0.8; shot "$dir/gf.png"
      adb shell input keyevent 4
      ;;
    composer-diaper)
      with_record "$dir/gf.mp4" 5 scene_gf_composer_diaper
      gf_goto_log; tap_n "尿" || true; sleep 0.8; shot "$dir/gf.png"
      adb shell input keyevent 4
      ;;
    composer-breast-vs-timer)
      with_record "$dir/gf.mp4" 7 scene_gf_breast_timer_entry
      shot "$dir/gf.png"
      ;;
    swipe-half-reveal)
      with_record "$dir/gf.mp4" 5 scene_gf_swipe_half
      shot "$dir/gf.png"
      ;;
    swipe-full-edit)
      with_record "$dir/gf.mp4" 6 scene_gf_swipe_edit
      shot "$dir/gf.png"
      ;;
    swipe-full-delete)
      with_record "$dir/gf.mp4" 6 scene_gf_swipe_delete
      shot "$dir/gf.png"
      ;;
    more-sheet-four-col)
      with_record "$dir/gf.mp4" 4 scene_gf_more
      gf_goto_log; tap_n "更多" || true; sleep 0.8; shot "$dir/gf.png"
      adb shell input keyevent 4
      ;;
    timer-enter-idle)
      with_record "$dir/gf.mp4" 5 scene_gf_timer_idle
      gf_goto_log; tap_n "计时" || true; sleep 0.8; shot "$dir/gf.png"
      adb shell input keyevent 4
      ;;
    timer-run-complete)
      with_record "$dir/gf.mp4" 8 scene_gf_timer_run
      shot "$dir/gf.png"
      ;;
    layout-editor-drag)
      with_record "$dir/gf.mp4" 6 scene_gf_layout
      shot "$dir/gf.png"
      ;;
    *) log "  unknown scene $sid"; return 1 ;;
  esac
  make_strip "$dir/gf.mp4" "$dir/gf-strip.jpg"
}

run_scene_legacy() {
  local sid=$1
  local pkg
  pkg=$(resolve_legacy)
  local seed=${SEED_OF[$sid]:-empty}
  local dir="$OUT_ROOT/$sid"
  mkdir -p "$dir"
  log "== legacy scene=$sid seed=$seed pkg=$pkg =="
  legacy_boot_home "$pkg"
  prepare_seed_legacy "$seed"
  case "$sid" in
    log-home-empty-ia|log-home-seeded-row)
      with_record "$dir/legacy.mp4" 4 scene_legacy_log_home
      scene_legacy_log_home; shot "$dir/legacy.png"
      ;;
    tab-crossfade)
      with_record "$dir/legacy.mp4" 6 scene_legacy_tab
      shot "$dir/legacy.png"
      ;;
    day-axis-nav)
      with_record "$dir/legacy.mp4" 5 scene_legacy_day_axis
      shot "$dir/legacy.png"
      ;;
    composer-formula)
      with_record "$dir/legacy.mp4" 5 scene_legacy_composer_formula
      legacy_goto_log; tap_formula_dock || tap_n "配方奶" || true; sleep 0.8; shot "$dir/legacy.png"
      adb shell input keyevent 4
      ;;
    composer-sleep)
      with_record "$dir/legacy.mp4" 5 scene_legacy_composer_sleep
      shot "$dir/legacy.png"
      ;;
    composer-diaper)
      with_record "$dir/legacy.mp4" 5 scene_legacy_composer_diaper
      shot "$dir/legacy.png"
      ;;
    composer-breast-vs-timer)
      with_record "$dir/legacy.mp4" 7 scene_legacy_breast_timer
      shot "$dir/legacy.png"
      ;;
    swipe-half-reveal|swipe-full-edit|swipe-full-delete)
      with_record "$dir/legacy.mp4" 6 scene_legacy_swipe
      shot "$dir/legacy.png"
      ;;
    more-sheet-four-col)
      with_record "$dir/legacy.mp4" 4 scene_legacy_more
      legacy_goto_log; tap_n "更多" || true; sleep 0.8; shot "$dir/legacy.png"
      adb shell input keyevent 4
      ;;
    timer-enter-idle|timer-run-complete)
      with_record "$dir/legacy.mp4" 6 scene_legacy_timer
      shot "$dir/legacy.png"
      ;;
    layout-editor-drag)
      with_record "$dir/legacy.mp4" 5 scene_legacy_layout
      shot "$dir/legacy.png"
      ;;
    *) log "  unknown scene $sid"; return 1 ;;
  esac
  make_strip "$dir/legacy.mp4" "$dir/legacy-strip.jpg"
  side_by_side "$dir/gf.mp4" "$dir/legacy.mp4" "$dir/compare.mp4"
}

write_index() {
  cat > "$OUT_ROOT/INDEX.md" <<EOF
# Dual-scene capture index

**When:** $(date -Iseconds)  
**Device:** \`$SERIAL\` · ${W}x${H}  
**Mode:** \`$MODE\` · scene arg: \`$SCENE_ARG\`

## Command

\`\`\`bash
./greenfield/scripts/dual-scene-capture.sh emulator-5554 <scene-id|all> both
\`\`\`

## Layout

\`scenes/<scene-id>/{gf,legacy,compare}.{mp4,png}\` · strips \`*-strip.jpg\`

## Matrix / rubric

- \`.scratch/spec-02-dual-scene-review/matrix.md\`
- \`.scratch/spec-02-dual-scene-review/rubric.md\`

## Policy

Large videos are **review-local** — do not force-add to git. Missing legacy package **fails closed**.
EOF
}

# ---------- main ----------
log "== dual-scene-capture serial=$SERIAL scene=$SCENE_ARG mode=$MODE =="
adb get-state >/dev/null
require_packages

SCENES=()
if [[ "$SCENE_ARG" == all ]]; then
  SCENES=("${ALL_SCENES[@]}")
else
  SCENES=("$SCENE_ARG")
fi

for sid in "${SCENES[@]}"; do
  if [[ -z "${SEED_OF[$sid]+x}" ]]; then
    log "FAIL: unknown scene-id '$sid'"
    log "Known: ${!SEED_OF[*]}"
    exit 2
  fi
  case "$MODE" in
    both) run_scene_gf "$sid"; run_scene_legacy "$sid" ;;
    gf) run_scene_gf "$sid" ;;
    legacy) run_scene_legacy "$sid" ;;
    *) log "mode must be both|gf|legacy"; exit 2 ;;
  esac
done

write_index
log "== done → $OUT_ROOT =="
find "$OUT_ROOT" -type f \( -name '*.mp4' -o -name '*.png' -o -name '*.jpg' -o -name '*.md' \) 2>/dev/null | sort | while read -r f; do
  printf '  %8s  %s\n' "$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f")" "$f"
done
