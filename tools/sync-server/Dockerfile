# lezi sync-server — NAS / local Docker image (stdlib Python HTTP)
# Build: ./build-image.sh
# Run:   docker compose up -d

FROM python:3.12-slim

LABEL org.opencontainers.image.title="lezi-sync" \
      org.opencontainers.image.description="Lezi family sync prototype server" \
      org.opencontainers.image.source="https://github.com/local/lezi"

ARG LEZI_SYNC_VERSION=0.1.0-dev

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LEZI_DATA_DIR=/data \
    LEZI_SYNC_HOST=0.0.0.0 \
    LEZI_SYNC_PORT=8765 \
    LEZI_SYNC_VERSION=${LEZI_SYNC_VERSION}

WORKDIR /app

# Non-root user for volume writes (uid matches common NAS maps)
RUN useradd --create-home --uid 1000 lezi \
    && mkdir -p /data/media \
    && chown -R lezi:lezi /data /app

COPY --chown=lezi:lezi server.py /app/server.py

USER lezi

EXPOSE 8765

# Single data root: /data/lezi.db + /data/media/
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/health', timeout=2)"

CMD ["python", "/app/server.py"]
