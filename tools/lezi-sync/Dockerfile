FROM rust:1.95-bookworm AS builder

WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --locked --release

FROM debian:bookworm-slim

ARG LEZI_SYNC_VERSION=0.3.6

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

COPY --from=builder /build/target/release/lezi-sync /usr/local/bin/lezi-sync

USER 10001:10001

EXPOSE 8765 8767
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["lezi-sync", "healthcheck"]

ENTRYPOINT ["lezi-sync"]
