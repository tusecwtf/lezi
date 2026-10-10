#!/usr/bin/env bash
# Fault injection against the real publication fragment. Only synthetic files
# and fake Docker/curl commands are used; no container or NAS is contacted.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
work=$(mktemp -d "${TMPDIR:-/tmp}/lezi-app-update-transaction.XXXXXX")
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/bin" "$work/package/app-update"
printf 'new-apk\n' >"$work/package/app-update/app-release.apk"
printf '{"version_code":35,"min_supported_version_code":35}\n' >"$work/package/app-update/app-update.json"
printf 'old-apk\n' >"$work/old.apk"
printf '{"version_code":34,"min_supported_version_code":21}\n' >"$work/old.json"
python3 - "$SCRIPT_DIR/remote-deploy.sh" "$work/fragment.sh" <<'PY'
import sys
text = open(sys.argv[1]).read()
start = text.index('# A running old server is the only endpoint')
end = text.index('echo "==> stop/remove existing container', start)
open(sys.argv[2], 'w').write('#!/usr/bin/env bash\nset -euo pipefail\n' + text[start:end] + '\ntouch "$TEST_CASE/container-stop"\napp_update_helper finish\n')
PY
cat >"$work/bin/docker" <<'PY'
#!/usr/bin/env python3
import os, signal, subprocess, sys
from pathlib import Path
args = sys.argv[1:]
assert args[:3] == ['run', '--rm', '--user']
assert args[3] == '10001:10001'
binds = {}
for i, arg in enumerate(args):
    if arg == '-v':
        host, container, *mode = args[i+1].split(':')
        binds[container] = host
index = args.index('-ec')
body = args[index+1]
for container, host in binds.items():
    body = body.replace(container, host)
operation = args[index+3]
fault = os.environ.get('FAULT', '')
root = Path(os.environ['TEST_CASE'])
with (root/'operations').open('a') as out:
    out.write(operation+'\n')
if fault == 'helper-before' and operation == 'publish': sys.exit(73)
if fault == 'final-check' and operation == 'verify': sys.exit(73)
if fault == 'commit-before' and operation == 'commit': sys.exit(73)
env = dict(os.environ, TEST_OPERATION=operation)
result = subprocess.run(['/bin/sh', '-ec', body, *args[index+2:]], env=env)
if result.returncode: sys.exit(result.returncode)
if fault == 'commit-after' and operation == 'commit': sys.exit(73)
if operation == 'publish':
    if fault == 'helper-after': sys.exit(73)
    if fault in ('HUP', 'INT', 'TERM', 'KILL'):
        os.kill(os.getppid(), getattr(signal, 'SIG'+fault))
sys.exit(0)
PY
cat >"$work/bin/fault-command" <<'SH2'
#!/usr/bin/env bash
set -euo pipefail
command="${0##*/}"
last="${!#}"
point=""
if [[ "$TEST_OPERATION" == publish ]]; then
  case "$command:$last" in
    cp:*/new.apk) point=copy-apk ;;
    cp:*/new.json) point=copy-meta ;;
    mv:*/app-release.apk) point=rename-apk ;;
    mv:*/app-update.json) point=rename-meta ;;
  esac
fi
if [[ "$TEST_OPERATION" == rollback && "$FAULT" == rollback-failure && "$command" == cp ]]; then
  exit 74
fi
if [[ -n "$point" && "$FAULT" == "$point-before" ]]; then exit 73; fi
"/usr/bin/$command" "$@"
if [[ -n "$point" && "$FAULT" == "$point-after" ]]; then exit 73; fi
SH2
for cmd in cp mv; do cp "$work/bin/fault-command" "$work/bin/$cmd"; done
cat >"$work/bin/curl" <<'SH2'
#!/usr/bin/env bash
set -euo pipefail
[[ "$FAULT" != curl && "$FAULT" != rollback-failure ]] || exit 22
if [[ "$FAULT" == wrong-download ]]; then printf 'wrong bytes\n'; else cat "$data_path/app-release.apk"; fi
SH2
chmod +x "$work/bin/"*
export PATH="$work/bin:$PATH" DIR="$work/package" image='lezi-sync:synthetic' container_running=1
cases=0
run_case() {
  export FAULT="$1" TEST_CASE="$work/$1" data_path="$work/$1/data"
  mkdir -p "$data_path"
  /usr/bin/cp "$work/old.apk" "$data_path/app-release.apk"
  /usr/bin/cp "$work/old.json" "$data_path/app-update.json"
  [[ "$FAULT" != stuck-helper ]] || mkdir "$data_path/.lezi-app-update-helper-lock"
  local status=0
  bash "$work/fragment.sh" >"$TEST_CASE/run.log" 2>&1 || status=$?
  if [[ "$FAULT" == success ]]; then
    [[ "$status" == 0 && -f "$TEST_CASE/container-stop" ]]
    cmp "$DIR/app-update/app-release.apk" "$data_path/app-release.apk"
    cmp "$DIR/app-update/app-update.json" "$data_path/app-update.json"
    [[ ! -e "$data_path/.lezi-app-update-transaction" ]]
  else
    [[ "$status" != 0 && ! -e "$TEST_CASE/container-stop" ]]
    if [[ "$FAULT" == KILL || "$FAULT" == rollback-failure ]]; then
      [[ -f "$data_path/.lezi-app-update-transaction/old.apk" ]]
      cmp "$work/old.apk" "$data_path/.lezi-app-update-transaction/old.apk"
      cmp "$work/old.json" "$data_path/.lezi-app-update-transaction/old.json"
      local before after
      before=$(find "$data_path" -type f -exec sha256sum {} \; | sort)
      if FAULT=success bash "$work/fragment.sh" >"$TEST_CASE/retry.log" 2>&1; then
        echo 'Interrupted publication was reused' >&2; exit 1
      fi
      after=$(find "$data_path" -type f -exec sha256sum {} \; | sort)
      [[ "$before" == "$after" && ! -e "$TEST_CASE/container-stop" ]]
      grep -q 'interrupted app-update publication' "$TEST_CASE/retry.log"
      if [[ "$FAULT" == rollback-failure ]]; then grep -q 'rollback failed' "$TEST_CASE/run.log"; fi
    else
      cmp "$work/old.apk" "$data_path/app-release.apk"
      cmp "$work/old.json" "$data_path/app-update.json"
      [[ ! -e "$data_path/.lezi-app-update-transaction" ]]
      if [[ "$FAULT" == stuck-helper ]]; then
        [[ -d "$data_path/.lezi-app-update-helper-lock" ]]
        grep -q 'helper active or interrupted' "$TEST_CASE/run.log"
      fi
    fi
  fi
  printf 'PASS app-update %s\n' "$FAULT"
  cases=$((cases+1))
}
for fault in success stuck-helper copy-apk-before copy-apk-after copy-meta-before copy-meta-after rename-apk-before rename-apk-after rename-meta-before rename-meta-after helper-before helper-after final-check commit-before commit-after curl wrong-download HUP INT TERM KILL rollback-failure; do
  run_case "$fault"
done
printf 'app-update transaction: %s synthetic cases passed\n' "$cases"
