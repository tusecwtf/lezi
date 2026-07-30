# lezi-sync NAS package (zdocker / Docker Compose v2 friendly).
# Rendered by package-nas.sh — no YAML anchors, no build:.
# LEZI_BOOTSTRAP_SECRET comes from deploy-time .env (inherited from live container).
#
# image: lezi-sync:__LEZI_SYNC_VERSION__
# data:  __LEZI_DATA_HOST_PATH__

services:
  lezi-sync:
    image: lezi-sync:__LEZI_SYNC_VERSION__
    container_name: lezi-sync
    user: "10001:10001"
    restart: unless-stopped
    stop_grace_period: 30s
    init: true
    environment:
      LEZI_DATA_DIR: /data
      LEZI_HOST: "0.0.0.0"
      LEZI_PORT: "8765"
      LEZI_INVITE_TTL_HOURS: "24"
      LEZI_MAX_MEDIA_BYTES: "10485760"
      LEZI_CREATE_RATE_LIMIT: "20"
      LEZI_JOIN_RATE_LIMIT: "60"
      LEZI_RATE_LIMIT_WINDOW_SECONDS: "60"
      LEZI_ALLOW_PERMISSION_HARDENING_SKIP: "0"
      LEZI_BOOTSTRAP_SECRET: ${LEZI_BOOTSTRAP_SECRET}
    volumes:
      - __LEZI_DATA_HOST_PATH__:/data
    ports:
      - "0.0.0.0:8765:8765"
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
    healthcheck:
      test: ["CMD", "lezi-sync", "healthcheck"]
      interval: 30s
      timeout: 3s
      retries: 3
      start_period: 5s
