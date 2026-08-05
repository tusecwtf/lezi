#!/usr/bin/env bash
# Spec 02 — automate dual-install screenshots on a running emulator.
# Usage:
#   ./greenfield/scripts/capture-uiux-baselines.sh [serial]
# Requires: adb, python3, assembled GF debug APK, emulator booted.
set -euo pipefail

SERIAL="${1:-emulator-5554}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/docs/specs/baselines"
APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
GF=com.lezi.babylog.gf
LEGACY_CANDIDATES=(com.lezi.babylog.debug com.lezi.babylog)
mkdir -p "$OUT"

adb() { command adb -s "$SERIAL" "$@"; }

shot() {
  local name=$1
  adb shell screencap -p /sdcard/shot.png
  adb pull /sdcard/shot.png "$OUT/$name" >/dev/null
  echo "  ✓ $name ($(stat -c%s "$OUT/$name" 2>/dev/null || stat -f%z "$OUT/$name") B)"
}

dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/lezi-ui-baseline.xml >/dev/null 2>&1
}

# find center of exact text, else content-desc exact, else text contains
find_xy() {
  local needle=$1
  python3 - "$needle" <<'PY'
import re, sys
from pathlib import Path
n = sys.argv[1]
xml = Path("/tmp/lezi-ui-baseline.xml").read_text(errors="ignore")
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
  xy=$(find_xy "$1") || { echo "  ! miss '$1'"; return 1; }
  local x y
  read -r x y <<<"$xy"
  echo "  tap '$1' @ $x,$y"
  adb shell input tap "$x" "$y"
  sleep 0.55
}

echo "== Spec 02 baseline capture on $SERIAL =="
adb get-state >/dev/null

if [[ -f "$APK" ]]; then
  echo "-- install GF debug"
  adb install -r "$APK" >/dev/null
else
  echo "-- skip install (no APK at $APK); use already-installed GF"
fi

# ----- GF -----
echo "-- GF onboarding / log home"
adb shell am force-stop "$GF" || true
adb shell pm clear "$GF" >/dev/null || true
adb shell am start -W -n "$GF/com.lezi.gf.app.MainActivity" >/dev/null
sleep 1.5
dump
if grep -q '开始记录' /tmp/lezi-ui-baseline.xml; then
  tap_n "宝宝昵称" || adb shell input tap 540 1227
  sleep 0.2
  adb shell input text "Doudou"
  sleep 0.3
  dump
  tap_n "开始记录"
  sleep 1.2
fi
dump
shot log-home-warm-gf.png

echo "-- composer formula"
tap_n "配方奶"
sleep 1.1
shot composer-formula-gf.png
# confirm
if find_xy "确认" >/tmp/xy.txt 2>/dev/null; then
  # bottom-most 确认
  python3 - <<'PY' > /tmp/xy.txt
import re
from pathlib import Path
xml=Path("/tmp/lezi-ui-baseline.xml").read_text(errors="ignore")
c=[]
for m in re.finditer(r'text="确认"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2=map(int,m.groups()); c.append(((x1+x2)//2,(y1+y2)//2,y2))
c.sort(key=lambda t:t[2]); print(c[-1][0], c[-1][1])
PY
  read -r x y < /tmp/xy.txt
  adb shell input tap "$x" "$y"
  sleep 1
fi
shot log-home-with-record-gf.png

echo "-- swipe edit/delete"
dump
ROWY=$(python3 - <<'PY'
import re
from pathlib import Path
xml=Path("/tmp/lezi-ui-baseline.xml").read_text(errors="ignore")
for label in ["刚刚","分钟前","小时前"]:
    for m in re.finditer(rf'text="([^"]*{label}[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        print((int(m.group(3))+int(m.group(5)))//2); raise SystemExit
print("")
PY
)
if [[ -n "$ROWY" ]]; then
  # half-reveal left (edit green)
  adb shell input swipe 900 "$ROWY" 550 "$ROWY" 350
  sleep 0.6
  shot timeline-swipe-edit-gf.png
  adb shell input tap 540 "$ROWY"
  sleep 0.4
  dump
  if grep -qE '记录确认面板|圆盘调时|编辑 ·' /tmp/lezi-ui-baseline.xml; then
    adb shell input keyevent 4; sleep 0.5
  fi
  # full right → delete confirm
  adb shell input swipe 150 "$ROWY" 980 "$ROWY" 350
  sleep 0.7
  shot timeline-swipe-delete-gf.png
  dump
  if grep -q '确认删除' /tmp/lezi-ui-baseline.xml; then
    tap_n "取消" || adb shell input keyevent 4
    sleep 0.4
  fi
fi

echo "-- more sheet"
dump
tap_n "更多"
sleep 1
shot more-sheet-gf.png
adb shell input keyevent 4; sleep 0.4

echo "-- timer"
dump
tap_n "计 计时" || tap_n "计时"
sleep 1
shot timer-running-gf.png
dump
tap_n "计时侧 左" || adb shell input tap 277 995
sleep 1.2
shot timer-running-active-gf.png
dump
tap_n "完成 → 确认写入" || tap_n "完成"
sleep 1
shot timer-confirm-gf.png
adb shell input keyevent 4; sleep 0.3
adb shell input keyevent 4; sleep 0.3

echo "-- layout editor"
adb shell am start -n "$GF/com.lezi.gf.app.MainActivity" >/dev/null
sleep 0.8
dump
tap_n "布局"
sleep 1
shot layout-editor-gf.png
tap_n "完成" || true

# ----- Legacy (optional) -----
LEGACY=""
for p in "${LEGACY_CANDIDATES[@]}"; do
  if adb shell pm path "$p" >/dev/null 2>&1; then LEGACY=$p; break; fi
done
if [[ -n "$LEGACY" ]]; then
  echo "-- legacy $LEGACY"
  adb shell am force-stop "$GF" || true
  adb shell am force-stop "$LEGACY" || true
  adb shell monkey -p "$LEGACY" -c android.intent.category.LAUNCHER 1 >/dev/null
  sleep 2
  dump
  if grep -q '离线模式' /tmp/lezi-ui-baseline.xml; then
    tap_n "离线模式" || adb shell input tap 540 834
    sleep 2
    dump
  fi
  if grep -q '开始记录' /tmp/lezi-ui-baseline.xml; then
    tap_n "开始记录" || true
    sleep 2
    dump
  fi
  shot log-home-warm-legacy.png
  if grep -q '配方奶' /tmp/lezi-ui-baseline.xml; then
    # bottom-most dock label
    python3 - <<'PY' > /tmp/xy.txt
import re
from pathlib import Path
xml=Path("/tmp/lezi-ui-baseline.xml").read_text(errors="ignore")
c=[]
for m in re.finditer(r'text="配方奶"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    x1,y1,x2,y2=map(int,m.groups()); c.append(((x1+x2)//2,(y1+y2)//2,y2))
c.sort(key=lambda t:-t[2]); print(c[0][0], c[0][1])
PY
    read -r x y < /tmp/xy.txt
    adb shell input tap "$x" "$y"
    sleep 1.2
    shot composer-formula-legacy.png
  fi
else
  echo "-- no legacy package installed; skip dual compare"
fi

echo "== done → $OUT =="
ls -la "$OUT"/*.png | awk '{printf "  %8s  %s\n",$5,$NF}'
