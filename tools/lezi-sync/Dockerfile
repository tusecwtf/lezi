FROM python:3.12-slim

ARG LEZI_SYNC_VERSION=0.1.0

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    LEZI_SYNC_VERSION=${LEZI_SYNC_VERSION} \
    LEZI_DATA_DIR=/data \
    LEZI_HOST=0.0.0.0 \
    LEZI_PORT=8765 \
    LEZI_INVITE_TTL_HOURS=24 \
    LEZI_MAX_MEDIA_BYTES=10485760 \
    LEZI_TLS_CERTFILE= \
    LEZI_TLS_KEYFILE=

WORKDIR /app

RUN useradd --create-home --uid 10001 lezi \
    && install -d -m 0700 -o lezi -g lezi /data /data/media \
    && chown -R lezi:lezi /app /data

COPY --chown=lezi:lezi pyproject.toml /app/pyproject.toml
COPY --chown=lezi:lezi app /app/app
COPY --chown=lezi:lezi entrypoint.sh /app/entrypoint.sh
RUN python -m pip install --no-cache-dir .
RUN chmod 0755 /app/entrypoint.sh

USER lezi

EXPOSE 8765
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import os,ssl,urllib.request; s='https' if os.getenv('LEZI_TLS_CERTFILE') else 'http'; c=ssl._create_unverified_context() if s == 'https' else None; urllib.request.urlopen(f'{s}://127.0.0.1:8765/health', timeout=2, context=c)"

CMD ["/app/entrypoint.sh"]
