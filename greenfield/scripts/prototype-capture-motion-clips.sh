#!/usr/bin/env bash
# PROTOTYPE — dual-APK interaction / motion clip capture for Spec 02 A1–A5 review.
# Not production CI. Answers: can emulator video + frame strips support 1:1 motion parity?
#
# Usage:
#   ./greenfield/scripts/prototype-capture-motion-clips.sh [serial] [app=both|gf|legacy]
#
# Outputs (gitignored-friendly under docs; large mp4s — review then delete or absorb):
#   greenfield/docs/specs/baselines/motion-prototype/
#     gf/<scene>.mp4
#     legacy/<scene>.mp4
#     strips/<scene>-gf.jpg | <scene>-legacy.jpg   (contact sheets)
#     compare/<scene>-side-by-side.mp4             (when both exist)
#     INDEX.md
#
# Requires: adb, python3, ffmpeg; emulator booted; both packages preferred.
set -euo pipefail

SERIAL="${1:-emulator-5554}"
MODE="${2:-both}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/specs/baselines/motion-prototype"
GF=com.lezi.babylog.gf
LEGACY_CANDIDATES=(com.lezi.babylog.debug com.lezi.babylog)
XML=/tmp/lezi-motion-ui.xml
REMOTE_MP4=/sdcard/lezi-motion-clip.mp4
BITRATE=8M
W=1080
H=2400

mkdir -p "$OUT"/{gf,legacy,strips,compare}

adb() { command adb -s "$SERIAL" "$@"; }

log() { printf '%s\n' "$*"; }

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
soft = centers(rf'text="([^"]*{re.escape(n)}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
soft = [c for c in soft if len(c[2]) <= len(n) + 12]
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

# Record while a shell function body runs. Max ~time-limit seconds.
# Usage: with_record out.mp4 limit_sec -- body...
with_record() {
  local dest=$1
  local limit=${2:-8}
  shift 2
  adb shell rm -f "$REMOTE_MP4" >/dev/null 2>&1 || true
  # screenrecord blocks until time-limit or SIGINT; run in background on device
  adb shell "screenrecord --time-limit $limit --bit-rate $BITRATE --size ${W}x${H} $REMOTE_MP4" &
  local rec_pid=$!
  sleep 0.35
  # run actions (do not abort the whole script if a tap misses)
  set +e
  "$@"
  local act_rc=$?
  set -e
  # wait a beat so last frame lands, then stop recorder
  sleep 0.5
  # SIGINT screenrecord via pkill on device (cleaner end than killing adb host job only)
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
  # ~8 thumbnails across the clip for motion review without scrubbing
  ffmpeg -y -hide_banner -loglevel error -i "$mp4" \
    -vf "fps=2,scale=270:-1,tile=4x2" \
    -frames:v 1 "$jpg" 2>/dev/null || \
  ffmpeg -y -hide_banner -loglevel error -i "$mp4" \
    -vf "select='not(mod(n\\,8))',scale=270:-1,tile=4x2" \
    -frames:v 1 "$jpg" >/dev/null 2>&1 || true
  [[ -f "$jpg" ]] && log "  strip $(basename "$jpg")"
}

side_by_side() {
  local a=$1 b=$2 out=$3
  [[ -f "$a" && -f "$b" ]] || return 0
  # pad to same height, stack horizontal, re-encode short review clip
  ffmpeg -y -hide_banner -loglevel error \
    -i "$a" -i "$b" \
    -filter_complex "[0:v]scale=360:-2,setsar=1,pad=360:ih:(ow-iw)/2:(oh-ih)/2:black[l];\
[1:v]scale=360:-2,setsar=1,pad=360:ih:(ow-iw)/2:(oh-ih)/2:black[r];\
[l][r]hstack=inputs=2[v]" \
    -map "[v]" -an -c:v libx264 -pix_fmt yuv420p -crf 23 -preset veryfast \
    "$out" >/dev/null 2>&1 || true
  [[ -f "$out" ]] && log "  compare $(basename "$out")"
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

# ---------- GF flows ----------
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

# Prefer exact bottom-most match among confirm labels.
tap_confirm() {
  dump
  if python3 - "$XML" <<'PY' > /tmp/xy-motion.txt
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
labels = ("确认记录", "确认", "保存")
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
    read -r x y lab < /tmp/xy-motion.txt
    log "  tap confirm '$lab' @ $x,$y"
    adb shell input tap "$x" "$y"
    sleep 0.9
    return 0
  fi
  return 1
}

gf_seed_record() {
  gf_goto_log
  dump
  tap_n "配方奶" || return 0
  sleep 0.9
  tap_confirm || true
}

scene_gf_tab_crossfade() {
  # A2 — tab content crossfade Base
  dump
  tap_n "汇总" || tap_n "成长" || true
  sleep 0.7
  dump
  tap_n "记录" || true
  sleep 0.7
  dump
  tap_n "汇总" || true
  sleep 0.5
}

scene_gf_composer_open() {
  # A4 — composer sheet
  gf_goto_log
  dump
  tap_n "配方奶" || true
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.5
}

scene_gf_swipe() {
  # A5 — swipe reveal + commit
  dump
  local rowy
  rowy=$(python3 - "$XML" <<'PY'
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
for label in ["刚刚","分钟前","小时前"]:
    for m in re.finditer(rf'text="([^"]*{label}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        print((int(m.group(3))+int(m.group(5)))//2); raise SystemExit
print("")
PY
)
  if [[ -z "$rowy" ]]; then
    log "  ! no timeline row for swipe"
    return 0
  fi
  # half-reveal edit
  adb shell input swipe 920 "$rowy" 520 "$rowy" 380
  sleep 0.55
  adb shell input tap 540 "$rowy"
  sleep 0.35
  # full trip left → edit
  adb shell input swipe 900 "$rowy" 120 "$rowy" 320
  sleep 0.9
  adb shell input keyevent 4
  sleep 0.4
  # full trip right → delete dialog
  adb shell input swipe 160 "$rowy" 980 "$rowy" 320
  sleep 0.7
  dump
  if grep -q '确认删除' "$XML" 2>/dev/null; then
    tap_n "取消" || adb shell input keyevent 4
  fi
  sleep 0.3
}

scene_gf_more_sheet() {
  dump
  tap_n "更多" || true
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.4
}

scene_gf_timer() {
  dump
  tap_n "计 计时" || tap_n "计时" || true
  sleep 0.9
  dump
  tap_n "计时侧 左" || tap_xy 277 995
  sleep 1.0
  adb shell input keyevent 4
  sleep 0.3
  adb shell input keyevent 4
  sleep 0.3
}

scene_gf_layout() {
  # A3 — layout enter/exit Emphasized
  adb shell am start -n "$GF/com.lezi.gf.app.MainActivity" >/dev/null
  sleep 0.7
  dump
  tap_n "布局" || true
  sleep 1.0
  # short drag on first handle area (approx mid list)
  adb shell input swipe 980 900 980 1200 450
  sleep 0.5
  dump
  tap_n "完成" || adb shell input keyevent 4
  sleep 0.4
}

run_gf() {
  log "== GF motion clips =="
  gf_boot_home
  with_record "$OUT/gf/tab-crossfade.mp4" 6 scene_gf_tab_crossfade
  gf_goto_log
  with_record "$OUT/gf/composer-open.mp4" 5 scene_gf_composer_open
  gf_seed_record
  with_record "$OUT/gf/timeline-swipe.mp4" 8 scene_gf_swipe
  with_record "$OUT/gf/more-sheet.mp4" 4 scene_gf_more_sheet
  with_record "$OUT/gf/timer-open.mp4" 6 scene_gf_timer
  with_record "$OUT/gf/layout-editor.mp4" 6 scene_gf_layout
}

# ---------- Legacy flows (best-effort) ----------
legacy_boot_home() {
  local pkg=$1
  adb shell am force-stop "$GF" || true
  adb shell am force-stop "$pkg" || true
  # clear may wipe offline preference; still useful for clean welcome
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

scene_legacy_tab() {
  dump
  # bottom nav labels vary; try common ones
  tap_n "汇总" || tap_n "统计" || tap_n "成长" || true
  sleep 0.8
  dump
  tap_n "记录" || true
  sleep 0.6
}

scene_legacy_composer() {
  dump
  if grep -q '配方奶' "$XML" 2>/dev/null; then
    python3 - "$XML" <<'PY' > /tmp/xy-motion.txt
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
c=[]
for m in re.finditer(r'text="配方奶"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2=map(int,m.groups()); c.append(((x1+x2)//2,(y1+y2)//2,y2))
if not c: raise SystemExit(1)
c.sort(key=lambda t:-t[2]); print(c[0][0], c[0][1])
PY
    read -r x y < /tmp/xy-motion.txt
    adb shell input tap "$x" "$y"
    sleep 1.2
    adb shell input keyevent 4
    sleep 0.5
  fi
}

scene_legacy_swipe() {
  # try a mid-list horizontal swipe even if empty — still captures chrome motion
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
  adb shell input swipe 200 "$rowy" 900 "$rowy" 350
  sleep 0.7
  adb shell input keyevent 4
  sleep 0.3
}

run_legacy() {
  local pkg
  if ! pkg=$(resolve_legacy); then
    log "== no legacy package; skip =="
    return 0
  fi
  log "== legacy motion clips ($pkg) =="
  legacy_boot_home "$pkg"
  with_record "$OUT/legacy/tab-crossfade.mp4" 6 scene_legacy_tab
  with_record "$OUT/legacy/composer-open.mp4" 5 scene_legacy_composer
  # seed one record if dock allows
  dump
  if grep -q '配方奶' "$XML" 2>/dev/null; then
    scene_legacy_composer
    # try confirm if still open from incomplete open-close — open again and confirm
    dump
    if grep -q '配方奶' "$XML" 2>/dev/null; then
      python3 - "$XML" <<'PY' > /tmp/xy-motion.txt || true
import re, sys
from pathlib import Path
xml=Path(sys.argv[1]).read_text(errors="ignore")
c=[]
for m in re.finditer(r'text="配方奶"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2=map(int,m.groups()); c.append(((x1+x2)//2,(y1+y2)//2,y2))
if c:
    c.sort(key=lambda t:-t[2]); print(c[0][0], c[0][1])
PY
      if [[ -s /tmp/xy-motion.txt ]]; then
        read -r x y < /tmp/xy-motion.txt
        adb shell input tap "$x" "$y"
        sleep 1
        dump
        if find_xy "确认" >/tmp/xy2.txt 2>/dev/null || find_xy "保存" >/tmp/xy2.txt 2>/dev/null; then
          read -r cx cy < /tmp/xy2.txt
          adb shell input tap "$cx" "$cy"
          sleep 1
        fi
      fi
    fi
  fi
  with_record "$OUT/legacy/timeline-swipe.mp4" 7 scene_legacy_swipe
  with_record "$OUT/legacy/more-sheet.mp4" 4 scene_legacy_more
  with_record "$OUT/legacy/timer-open.mp4" 5 scene_legacy_timer
}

scene_legacy_more() {
  # dock lives on log home; exact text "更多" (avoid content-desc 更多记录 only if needed)
  dump
  tap_n "记录" || true
  sleep 0.4
  dump
  tap_n "更多" || tap_n "更多记录" || true
  sleep 1
  adb shell input keyevent 4
  sleep 0.3
}

scene_legacy_timer() {
  dump
  tap_n "记录" || true
  sleep 0.4
  dump
  # 0.3.x may put 母乳 timer path under 更多 or dock 母乳 — try 计时 then 母乳
  if ! tap_n "计时"; then
    tap_n "母乳" || true
  fi
  sleep 1.2
  adb shell input keyevent 4
  sleep 0.3
}

write_index() {
  cat > "$OUT/INDEX.md" <<EOF
# Motion prototype — dual APK interaction clips

**PROTOTYPE** (throwaway capture harness). Spec 02 A1–A5 / §2.3 motion language review.  
**When:** $(date -Iseconds)  
**Device:** \`$SERIAL\` · ${W}x${H}  
**Mode:** \`$MODE\`

## Question this answers

Can emulator \`screenrecord\` + short scripted gestures give **broader** dual-APK evidence than static PNGs — enough for 1:1 **interaction/motion** review (Tab crossfade, Composer sheet, swipe, timer, more, layout)?

## Scenes

| Scene | Token intent | GF | Legacy |
|-------|--------------|----|--------|
| tab-crossfade | A2 Base | \`gf/tab-crossfade.mp4\` | \`legacy/tab-crossfade.mp4\` |
| composer-open | A4 Base sheet | \`gf/composer-open.mp4\` | \`legacy/composer-open.mp4\` |
| timeline-swipe | A5 Fast + commit | \`gf/timeline-swipe.mp4\` | \`legacy/timeline-swipe.mp4\` |
| more-sheet | A4 | \`gf/more-sheet.mp4\` | \`legacy/more-sheet.mp4\` |
| timer-open | A4 | \`gf/timer-open.mp4\` | \`legacy/timer-open.mp4\` |
| layout-editor | A3 Emphasized | \`gf/layout-editor.mp4\` | — |

Contact sheets: \`strips/*\`. Side-by-side: \`compare/*-side-by-side.mp4\`.

## Re-run

\`\`\`bash
./greenfield/scripts/prototype-capture-motion-clips.sh emulator-5554 both
\`\`\`

## Limits (honest)

- Emulator timing ≠ physical 60fps feel; use for **language** (duration class, ease, reveal) not pixel-perfect duration audit.
- Legacy automation is best-effort (welcome / dock labels drift).
- Not a substitute for non-implementer §2.3 blind review with live dual-install.
- Large binaries: delete or move out of git after review (\`motion-prototype/\` should stay untracked or LFS if kept).

## Verdict placeholder

- [ ] Motion language GF ≈ legacy on A2/A4/A5 (pass / residual)
- [ ] Keep harness / fold into capture-uiux-baselines / delete
EOF
}

# ---------- main ----------
log "== Spec 02 PROTOTYPE motion capture on $SERIAL mode=$MODE =="
adb get-state >/dev/null

case "$MODE" in
  both) run_gf; run_legacy ;;
  gf) run_gf ;;
  legacy) run_legacy ;;
  *) log "mode must be both|gf|legacy"; exit 2 ;;
esac

log "== strips + side-by-side =="
for scene in tab-crossfade composer-open timeline-swipe more-sheet timer-open layout-editor; do
  make_strip "$OUT/gf/${scene}.mp4" "$OUT/strips/${scene}-gf.jpg"
  make_strip "$OUT/legacy/${scene}.mp4" "$OUT/strips/${scene}-legacy.jpg"
  side_by_side "$OUT/gf/${scene}.mp4" "$OUT/legacy/${scene}.mp4" "$OUT/compare/${scene}-side-by-side.mp4"
done

write_index
log "== done → $OUT =="
find "$OUT" -type f \( -name '*.mp4' -o -name '*.jpg' -o -name '*.md' \) | sort | while read -r f; do
  printf '  %8s  %s\n' "$(stat -c%s "$f" 2>/dev/null || stat -f%z "$f")" "$f"
done
