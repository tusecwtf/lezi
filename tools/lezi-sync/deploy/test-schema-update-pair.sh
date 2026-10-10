#!/usr/bin/env bash
# Actual maintenance remote body + shared filesystem helper, with synthetic
# mounts and fake Docker/curl only. No SSH, container or real NAS operation.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
work=$(mktemp -d "${TMPDIR:-/tmp}/lezi-schema-update-test.XXXXXX")
cleanup() {
  local status=$?
  if [[ "$status" != 0 ]]; then find "$work" -name run.log -exec tail -n 30 {} \; >&2; fi
  rm -rf -- "$work"
  exit "$status"
}
trap cleanup EXIT
mkdir "$work/bin"
python3 - "$HERE/schema-cutover-steps.sh" "$work/remote.sh" <<'PY'
import sys
text=open(sys.argv[1]).read().split('app_update_prepublish() {',1)[1]
body=text.split("<<'REMOTE'\n",1)[1].split('\nREMOTE',1)[0]
open(sys.argv[2],'w').write('#!/usr/bin/env bash\nexport TEST_REMOTE_PID=$$\n'+body+'\ntouch "$TEST_CASE/can-stop"\n')
PY
cat >"$work/bin/docker" <<'PY'
#!/usr/bin/env python3
import os,signal,subprocess,sys
from pathlib import Path
args=sys.argv[1:]
if args[0]=='inspect':
    print('true' if args[-1]=='{{.State.Running}}' else 'lezi-sync:synthetic')
    sys.exit(0)
assert args[:4]==['run','--rm','--user','0']
binds={}
for i,arg in enumerate(args):
    if arg=='-v':
        host,container,*mode=args[i+1].split(':'); binds[container]=host
index=args.index('-ec'); body=args[index+1]; action=args[index+3]
for container,host in binds.items(): body=body.replace(container,host)
fault=os.environ['FAULT']; root=Path(os.environ['TEST_CASE'])
with (root/'operations').open('a') as out: out.write(action+'\n')
if fault=='helper-before' and action=='publish': sys.exit(73)
if fault=='final-check' and action=='verify': sys.exit(73)
if fault=='release-before' and action=='release': sys.exit(73)
result=subprocess.run(['/bin/sh','-ec',body,*args[index+2:]], env=dict(os.environ,TEST_OPERATION=action))
if result.returncode: sys.exit(result.returncode)
if fault=='helper-after' and action=='publish': sys.exit(73)
if fault=='release-after' and action=='release': sys.exit(73)
if action=='publish' and fault in ('HUP','INT','TERM','KILL'):
    os.kill(int(os.environ['TEST_REMOTE_PID']),getattr(signal,'SIG'+fault))
PY
cat >"$work/bin/fault-command" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
cmd="${0##*/}"
last="${!#}"
point=""
if [[ "$TEST_OPERATION" == publish ]]; then
  case "$cmd:$last" in
    cp:*/new.apk) point=copy-apk ;;
    cp:*/new.json) point=copy-meta ;;
    mv:*/app-release.apk) point=rename-apk ;;
    mv:*/app-update.json) point=rename-meta ;;
  esac
fi
if [[ "$TEST_OPERATION" == prepare ]]; then
  case "$cmd:$last" in
    cp:*/rollback-app-update/app-release.apk) point=snapshot-apk ;;
    cp:*/rollback-app-update/app-update.json) point=snapshot-meta ;;
  esac
fi
if [[ "$TEST_OPERATION" == rollback && "$FAULT" == rollback-failure && "$cmd" == cp ]]; then exit 74; fi
if [[ -n "$point" && "$FAULT" == "$point-before" ]]; then exit 73; fi
"/usr/bin/$cmd" "$@"
if [[ -n "$point" && "$FAULT" == "$point-after" ]]; then exit 73; fi
FAKE
for command in cp mv; do cp "$work/bin/fault-command" "$work/bin/$command"; done
cat >"$work/bin/chown" <<'FAKE'
#!/usr/bin/env bash
[[ "$1" == 10001:10001 ]]
FAKE
cat >"$work/bin/curl" <<'FAKE'
#!/usr/bin/env bash
[[ "$FAULT" != curl && "$FAULT" != rollback-failure ]] || exit 22
if [[ "$FAULT" == wrong-download ]]; then printf 'wrong\n'; else cat "$TEST_CASE/data/app-release.apk"; fi
FAKE
chmod +x "$work/bin/"*
export PATH="$work/bin:$PATH"
export LEZI_UPDATE_PUBLICATION_TOKEN="$(printf 'b%.0s' {1..64})"
count=0
for fault in success snapshot-apk-before snapshot-apk-after snapshot-meta-before snapshot-meta-after copy-apk-before copy-apk-after copy-meta-before copy-meta-after rename-apk-before rename-apk-after rename-meta-before rename-meta-after helper-before helper-after final-check release-before release-after curl wrong-download HUP INT TERM KILL rollback-failure; do
  export FAULT="$fault" TEST_CASE="$work/$fault"
  data="$TEST_CASE/data"
  stage="$TEST_CASE/lezi-sync-0.4.0-nas.incoming-$(printf 'a%.0s' {1..64})"
  mkdir -p "$data" "$stage/app-update"
  printf 'old apk\n' >"$TEST_CASE/old.apk"
  printf '{"floor":6}\n' >"$TEST_CASE/old.json"
  /usr/bin/cp "$TEST_CASE/old.apk" "$data/app-release.apk"
  /usr/bin/cp "$TEST_CASE/old.json" "$data/app-update.json"
  /usr/bin/cp "$HERE/schema-update-pair.sh" "$stage/schema-update-pair.sh"
  /usr/bin/cp "$HERE/credential-deploy-lock.sh" "$stage/credential-deploy-lock.sh"
  "$HERE/credential-deploy-lock.sh" acquire "$TEST_CASE/config/.lezi-sync-credential-deploy.lock.app-update" "$LEZI_UPDATE_PUBLICATION_TOKEN"
  printf 'new apk\n' >"$stage/app-update/app-release.apk"
  printf '{"floor":21}\n' >"$stage/app-update/app-update.json"
  sha="$(sha256sum "$stage/app-update/app-release.apk" | cut -d ' ' -f1)"
  status=0
  bash "$work/remote.sh" fixture "$stage" "$sha" "$data" "$LEZI_UPDATE_PUBLICATION_TOKEN" >"$TEST_CASE/run.log" 2>&1 || status=$?
  if [[ "$fault" == success ]]; then
    [[ "$status" == 0 && -f "$TEST_CASE/can-stop" ]]
    cmp "$stage/app-update/app-release.apk" "$data/app-release.apk"
    cmp "$stage/app-update/app-update.json" "$data/app-update.json"
    [[ ! -e "$data/.lezi-schema-app-update-transaction" ]]
    # The actual pre-open rollback helper also works after publication released
    # its marker, including an idempotent second recovery from the same snapshot.
    "$stage/schema-update-pair.sh" rollback "$data" lezi-sync:synthetic "$stage"
    "$stage/schema-update-pair.sh" rollback "$data" lezi-sync:synthetic "$stage"
    cmp "$TEST_CASE/old.apk" "$data/app-release.apk"
    cmp "$TEST_CASE/old.json" "$data/app-update.json"
  else
    [[ "$status" != 0 && ! -e "$TEST_CASE/can-stop" ]]
    if [[ "$fault" == KILL || "$fault" == rollback-failure ]]; then
      cmp "$TEST_CASE/old.apk" "$stage/rollback-app-update/app-release.apk"
      cmp "$TEST_CASE/old.json" "$stage/rollback-app-update/app-update.json"
      [[ -d "$data/.lezi-schema-app-update-transaction" ]]
      before=$(find "$data" "$stage" -type f -exec sha256sum {} \; | sort)
      if FAULT=success bash "$work/remote.sh" fixture "$stage" "$sha" "$data" "$LEZI_UPDATE_PUBLICATION_TOKEN" >"$TEST_CASE/retry.log" 2>&1; then exit 1; fi
      after=$(find "$data" "$stage" -type f -exec sha256sum {} \; | sort)
      [[ "$before" == "$after" && ! -e "$TEST_CASE/can-stop" ]]
      grep -q 'interrupted schema app-update publication' "$TEST_CASE/retry.log"
      [[ ! -f "$stage/app-update-restore-complete" ]]
    else
      cmp "$TEST_CASE/old.apk" "$data/app-release.apk"
      cmp "$TEST_CASE/old.json" "$data/app-update.json"
      if [[ "$fault" == snapshot-* ]]; then
        # Simulate the real outer orchestrator after an incomplete preparation.
        "$stage/schema-update-pair.sh" rollback "$data" lezi-sync:synthetic "$stage"
      fi
      [[ ! -e "$data/.lezi-schema-app-update-transaction" ]]
      [[ -f "$stage/app-update-restore-complete" ]]
    fi
  fi
  printf 'PASS schema app-update %s\n' "$fault"
  count=$((count+1))
done
# A lost transport can leave a writer running. A second helper must refuse
# without touching either final file or any snapshot, even for the same owner.
mkdir "$data/.lezi-app-update-helper-lock"
before=$(find "$data" "$stage" -type f -exec sha256sum {} \; | sort)
if FAULT=success "$stage/schema-update-pair.sh" rollback "$data" lezi-sync:synthetic "$stage" >"$TEST_CASE/busy-helper.log" 2>&1; then exit 1; fi
after=$(find "$data" "$stage" -type f -exec sha256sum {} \; | sort)
[[ "$before" == "$after" && -d "$data/.lezi-app-update-helper-lock" ]]
grep -q 'helper active or interrupted' "$TEST_CASE/busy-helper.log"
# Lease authorization is independently checked before any Docker helper runs.
before=$(wc -l <"$TEST_CASE/operations")
for token in '' "$(printf 'c%.0s' {1..64})"; do
  if LEZI_UPDATE_PUBLICATION_TOKEN="$token" "$stage/schema-update-pair.sh" verify "$data" lezi-sync:synthetic "$stage" >"$TEST_CASE/lease-rejected.log" 2>&1; then exit 1; fi
  [[ "$(wc -l <"$TEST_CASE/operations")" == "$before" ]]
done
# Exercise the real developer-side phase: a lost SSH result must already have a
# durable intent marker, even before any remote-marker query can return.
export TEST_CASE="$work/lost-ssh" LEZI_SCHEMA_CUTOVER_STATE_DIR="$work/lost-ssh/state"
mkdir -p "$LEZI_SCHEMA_CUTOVER_STATE_DIR" "$TEST_CASE/package/app-update" "$TEST_CASE/transport"
printf 'old fixture APK\n' >"$TEST_CASE/package/app-update/app-release.apk"
printf '0123456789abcdef0123456789abcdef\n' >"$LEZI_SCHEMA_CUTOVER_STATE_DIR/operation-id"
printf '%s\n' "$LEZI_UPDATE_PUBLICATION_TOKEN" >"$LEZI_SCHEMA_CUTOVER_STATE_DIR/update-lease-token"
cat >"$TEST_CASE/transport/ssh" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == *'fixture@example.invalid'* ]]
if [[ "$*" == *'bash -s --'* ]]; then
  [[ "$(cat "$LEZI_SCHEMA_CUTOVER_STATE_DIR/app-update-mutation-started")" == uncertain ]]
  cat >/dev/null
  touch "$TEST_CASE/intent-before-ssh"
  exit 42
fi
FAKE
printf '#!/bin/sh\nexit 0\n' >"$TEST_CASE/transport/scp"
chmod +x "$TEST_CASE/transport/"*
if PATH="$TEST_CASE/transport:$PATH" NAS_SSH=fixture@example.invalid NAS_SSH_PORT=10000 LEZI_LAN_HOST=192.168.77.10 LEZI_DATA_HOST_PATH="$TEST_CASE/data" LEZI_NAS_PACKAGE_DIR="$TEST_CASE/package" \
  bash "$HERE/schema-cutover-steps.sh" app_update_prepublish >"$TEST_CASE/run.log" 2>&1; then exit 1; fi
[[ -f "$TEST_CASE/intent-before-ssh" ]]
[[ "$(cat "$LEZI_SCHEMA_CUTOVER_STATE_DIR/app-update-mutation-started")" == uncertain ]]
printf 'schema app-update: %s synthetic fault cases, idempotent pre-open rollback, busy-helper refusal, two lease rejections and durable lost-SSH intent passed\n' "$count"
