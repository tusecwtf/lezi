# syntax=docker/dockerfile:1.7

FROM rust:1.95-bookworm AS builder

WORKDIR /build

# Mainland-China-friendly default. Cargo.lock checksums still apply.
# Override: --build-arg CARGO_INDEX_URL=sparse+https://index.crates.io/
# or CARGO_INDEX_URL=crates-io to use crates.io with no source replacement.
ARG CARGO_INDEX_URL=sparse+https://rsproxy.cn/index/
ARG PROJECT_CARGO_CONFIG=docker/project-cargo-config.toml
ARG VENDOR_SRC=docker/empty-vendor
ARG LEZI_CARGO_OFFLINE=0

ENV CARGO_NET_RETRY=10 \
    CARGO_HTTP_TIMEOUT=120 \
    CARGO_HTTP_MULTIPLEXING=false \
    CARGO_REGISTRIES_CRATES_IO_PROTOCOL=sparse \
    LEZI_CARGO_OFFLINE=${LEZI_CARGO_OFFLINE}

RUN set -eu; \
    if [ "${CARGO_INDEX_URL}" = "crates-io" ] || [ "${CARGO_INDEX_URL}" = "off" ] || [ -z "${CARGO_INDEX_URL}" ]; then \
      printf '%s\n' '[net]' 'retry = 10' 'git-fetch-with-cli = true' \
        >/usr/local/cargo/config.toml; \
    else \
      printf '%s\n' \
        '[source.crates-io]' \
        'replace-with = "mirror"' \
        '[source.mirror]' \
        "registry = \"${CARGO_INDEX_URL}\"" \
        '[net]' \
        'retry = 10' \
        'git-fetch-with-cli = true' \
        >/usr/local/cargo/config.toml; \
    fi

COPY ${PROJECT_CARGO_CONFIG} /build/.cargo/config.toml
COPY ${VENDOR_SRC}/ /build/vendor/
COPY docker/fetch-and-build.sh /tmp/fetch-and-build.sh
COPY Cargo.toml Cargo.lock ./
ARG LEZI_SYNC_VERSION
COPY docker/validate-version.sh /tmp/validate-version.sh
RUN sh /tmp/validate-version.sh Cargo.toml "${LEZI_SYNC_VERSION}"
COPY src ./src

# Cache mounts keep crates/objects after a timed-out RUN so the next attempt
# does not start from an empty registry. Vendored builds skip the network.
RUN --mount=type=cache,target=/usr/local/cargo/registry,sharing=locked \
    --mount=type=cache,target=/usr/local/cargo/git,sharing=locked \
    --mount=type=cache,target=/build/target,sharing=locked \
    /bin/sh /tmp/fetch-and-build.sh

FROM debian:bookworm-slim

ARG LEZI_SYNC_VERSION

LABEL org.opencontainers.image.title="lezi-sync" \
      org.opencontainers.image.description="Rust home-LAN sync server for Lezi" \
      org.opencontainers.image.version="${LEZI_SYNC_VERSION}"

ENV LEZI_SYNC_VERSION=${LEZI_SYNC_VERSION} \
    LEZI_DATA_DIR=/data \
    LEZI_HOST=0.0.0.0 \
    LEZI_PORT=8765 \
    LEZI_INTERNAL_PORT=8766 \
    LEZI_TLS_CERTFILE=/data/tls/server.crt \
    LEZI_TLS_KEYFILE=/data/tls/server.key \
    LEZI_MAX_MEDIA_BYTES=10485760

# Default non-root runtime uid/gid. NAS bind mounts that cannot chown to 10001
# should use compose profile `nas-root` (user 0:0) instead of baking root into
# the default image path.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates openssl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 lezi \
    && useradd --system --uid 10001 --gid 10001 --home-dir /data --shell /usr/sbin/nologin lezi \
    && install -d -o lezi -g lezi -m 0700 /data /data/media

COPY --from=builder /build/lezi-sync /usr/local/bin/lezi-sync

USER 10001:10001

EXPOSE 8765 8767
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["lezi-sync", "healthcheck"]

ENTRYPOINT ["lezi-sync"]
