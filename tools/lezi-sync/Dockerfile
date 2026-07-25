FROM rust:1.95-bookworm AS builder

WORKDIR /build
COPY Cargo.toml Cargo.lock ./
COPY src ./src
RUN cargo build --locked --release

FROM debian:bookworm-slim

ARG LEZI_SYNC_VERSION=0.2.2

LABEL org.opencontainers.image.title="lezi-sync" \
      org.opencontainers.image.description="Rust home-LAN sync server for Lezi" \
      org.opencontainers.image.version="${LEZI_SYNC_VERSION}"

ENV LEZI_SYNC_VERSION=${LEZI_SYNC_VERSION} \
    LEZI_DATA_DIR=/data \
    LEZI_HOST=0.0.0.0 \
    LEZI_PORT=8765 \
    LEZI_INVITE_TTL_HOURS=24 \
    LEZI_MAX_MEDIA_BYTES=10485760

RUN install -d -m 0700 /data /data/media

COPY --from=builder /build/target/release/lezi-sync /usr/local/bin/lezi-sync

USER 0:0

EXPOSE 8765
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD ["lezi-sync", "healthcheck"]

ENTRYPOINT ["lezi-sync"]
