#!/usr/bin/env bash
# NAS-side publication helper for the explicitly authorized 11/12 -> 13 cutover.
# Snapshots remain in the invocation-owned staging directory until the operator
# retires the whole maintenance record. No automatic stale-state recovery.
set -euo pipefail
action="${1:-}"
data="${2:-}"
image="${3:-}"
stage="${4:-}"
case "$action" in prepare|publish|verify|rollback|release) ;; *) exit 64 ;; esac
for path in "$data" "$stage"; do
  [[ "$path" == /* && "$path" =~ ^/[A-Za-z0-9._/-]+$ && "$path" != / \
    && "$path" != *'//'* && "$path" != */./* && "$path" != */../* \
    && "$path" != */. && "$path" != */.. ]] || exit 64
done
[[ -n "$image" && -d "$stage" && ! -L "$stage" ]] || exit 64
owner="$(basename -- "$stage")"
[[ "$owner" =~ ^lezi-sync-0\.4\.0-nas\.incoming-[0-9a-f]{64}$ ]] || exit 64
# Only the outer cutover owner may publish or restore. This nonce is not a
# credential; it ties every helper call to the real data-bind publication lease.
token="${LEZI_UPDATE_PUBLICATION_TOKEN:-}"
[[ "$token" =~ ^[0-9a-f]{64}$ ]] || { echo 'error: publication owner lease token is required' >&2; exit 1; }
[[ -x "$stage/credential-deploy-lock.sh" && ! -L "$stage/credential-deploy-lock.sh" ]] || exit 1
"$stage/credential-deploy-lock.sh" validate "$(dirname -- "$data")/config/.lezi-sync-credential-deploy.lock.app-update" "$token"

docker run --rm --user 0 \
  -v "$data:/data" -v "$stage:/transaction" \
  --entrypoint /bin/sh "$image" -ec '
    umask 077
      # A lost Docker/SSH response does not prove its writer has stopped.
      # Serialize each helper operation; a killed helper leaves a fail-closed
      # lock rather than allowing rollback to race a still-running publication.
      operation_lock=/data/.lezi-app-update-helper-lock
      if ! mkdir -m 700 "$operation_lock"; then
        echo "error: app-update helper active or interrupted; preserve evidence and refuse concurrent recovery" >&2
        exit 1
      fi
      unlock_operation() {
        status=$?
        trap - EXIT
        if ! rmdir "$operation_lock"; then status=1; fi
        exit "$status"
      }
      trap unlock_operation EXIT
      trap "exit 129" HUP
      trap "exit 130" INT
      trap "exit 143" TERM
    action=$1
    owner=$2
    marker=/data/.lezi-schema-app-update-transaction
    snapshot=/transaction/rollback-app-update
    same_bytes() {
      test -f "$1" && test ! -L "$1"
      test -f "$2" && test ! -L "$2"
      left=$(sha256sum <"$1")
      right=$(sha256sum <"$2")
      test "$left" = "$right"
    }
    owned() {
      test -d "$marker" && test ! -L "$marker"
      test -f "$marker/owner" && test ! -L "$marker/owner"
      test "$(cat "$marker/owner")" = "$owner"
    }
    create_marker() {
      mkdir -m 700 "$marker"
      printf "%s\n" "$owner" >"$marker/owner"
      sync -f /data
    }
    release_marker() {
      if test -e "$marker" || test -L "$marker"; then
        owned
        rm -f "$marker/new.apk" "$marker/new.json"
        rm "$marker/owner"
        rmdir "$marker"
        sync -f /data
      fi
    }
    install_pair() {
      source=$1
      owned
      cp "$source/app-release.apk" "$marker/new.apk"
      cp "$source/app-update.json" "$marker/new.json"
      chown 10001:10001 "$marker/new.apk" "$marker/new.json"
      chmod 644 "$marker/new.apk" "$marker/new.json"
      same_bytes "$source/app-release.apk" "$marker/new.apk"
      same_bytes "$source/app-update.json" "$marker/new.json"
      sync -f "$marker/new.apk"
      sync -f "$marker/new.json"
      mv -f "$marker/new.apk" /data/app-release.apk
      mv -f "$marker/new.json" /data/app-update.json
      sync -f /data
      same_bytes "$source/app-release.apk" /data/app-release.apk
      same_bytes "$source/app-update.json" /data/app-update.json
    }
    case "$action" in
      prepare)
        for path in "$marker" /data/.lezi-app-update-transaction /data/app-release.apk.lezi-staging /data/app-update.json.lezi-staging /data/app-release.apk.lezi-rollback /data/app-update.json.lezi-rollback "$snapshot"; do
          if test -e "$path" || test -L "$path"; then
            echo "error: interrupted schema app-update publication; preserve evidence and refuse retry" >&2
            exit 1
          fi
        done
        test ! -e /transaction/app-update-snapshot-ready
        test ! -e /transaction/app-update-mutation-started
        for path in /data/app-release.apk /data/app-update.json; do
          test -f "$path" && test ! -L "$path"
        done
        create_marker
        mkdir -m 700 "$snapshot"
        cp /data/app-release.apk "$snapshot/app-release.apk"
        cp /data/app-update.json "$snapshot/app-update.json"
        same_bytes /data/app-release.apk "$snapshot/app-release.apk"
        same_bytes /data/app-update.json "$snapshot/app-update.json"
        sync -f "$snapshot/app-release.apk"
        sync -f "$snapshot/app-update.json"
        touch /transaction/app-update-snapshot-ready
        sync -f /transaction
        ;;
      publish)
        owned
        test -f /transaction/app-update-snapshot-ready
        touch /transaction/app-update-mutation-started
        sync -f /transaction
        install_pair /transaction/app-update
        ;;
      verify)
        owned
        same_bytes /transaction/app-update/app-release.apk /data/app-release.apk
        same_bytes /transaction/app-update/app-update.json /data/app-update.json
        ;;
      rollback)
        # No complete snapshot means the prepare phase must not have mutated
        # either live filename. An ambiguous marker fails closed instead.
        if test -f /transaction/app-update-snapshot-ready; then
          if test ! -e "$marker" && test ! -L "$marker"; then create_marker; fi
          install_pair "$snapshot"
        else
          test ! -e /transaction/app-update-mutation-started
        fi
        release_marker
        printf "%s\n" complete >/transaction/app-update-restore-complete
        sync -f /transaction
        ;;
      release)
        # Snapshot files deliberately survive even a lost release response.
        # A caller can still restore them under the held publication lease.
        release_marker
        ;;
    esac
  ' lezi-schema-update-pair "$action" "$owner"
