#!/bin/sh
set -eu
umask 077

set -- uvicorn app.main:create_app \
  --factory \
  --host "${LEZI_HOST:-0.0.0.0}" \
  --port "${LEZI_PORT:-8765}" \
  --workers 1

certfile="${LEZI_TLS_CERTFILE:-}"
keyfile="${LEZI_TLS_KEYFILE:-}"
if [ -n "${certfile}" ] || [ -n "${keyfile}" ]; then
  if [ -z "${certfile}" ] || [ -z "${keyfile}" ]; then
    printf '%s\n' "LEZI_TLS_CERTFILE and LEZI_TLS_KEYFILE must be set together" >&2
    exit 64
  fi
  set -- "$@" --ssl-certfile "${certfile}" --ssl-keyfile "${keyfile}"
fi

exec "$@"
