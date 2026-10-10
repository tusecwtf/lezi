#!/usr/bin/env bash
# Disposable, synthetic loopback identity. Never reads or writes a NAS identity.
set -euo pipefail
umask 077
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
output="$repo_root/sync/build/generated/androidTransportAssets"
mkdir -p -- "$output"
staging=$(mktemp -d "$output/.tls.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
openssl req -x509 -newkey rsa:2048 -sha256 -days 2 -nodes \
    -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
    -keyout "$staging/server.key" -out "$staging/server.crt" 2>"$staging/openssl.log"
# Explicit PKCS12 algorithms supported on the app's API 26 minimum. This public
# password protects only a disposable test key; it is not an account credential.
openssl pkcs12 -export -inkey "$staging/server.key" -in "$staging/server.crt" \
    -name lezi-loopback -passout pass:lezi-loopback-fixture \
    -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1 \
    -out "$staging/transport-loopback.p12"
openssl x509 -in "$staging/server.crt" -noout -checkhost localhost >/dev/null
openssl x509 -in "$staging/server.crt" -noout -checkip 127.0.0.1 >/dev/null
mv -- "$staging/transport-loopback.p12" "$output/transport-loopback.p12"
printf '%s\n' 'Prepared a two-day loopback-only test identity in the ignored sync build directory.'
