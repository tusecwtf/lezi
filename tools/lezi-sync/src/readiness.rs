use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;
use std::sync::Arc;

use axum::extract::State;
use axum::http::StatusCode;
use axum::Json;
use serde_json::{json, Value};
use uuid::Uuid;

use crate::{sync_directory, AppState};

const READINESS_CACHE_SECONDS: i64 = 5;

#[derive(Clone, Copy)]
pub(super) struct CachedReadiness {
    checked_at: i64,
    healthy: bool,
}

pub(super) async fn readiness(State(state): State<Arc<AppState>>) -> (StatusCode, Json<Value>) {
    let now = state.now();
    let mut cache = state.readiness_cache.lock().await;
    let healthy = if let Some(cached) = *cache {
        if now >= cached.checked_at
            && now.saturating_sub(cached.checked_at) < READINESS_CACHE_SECONDS
        {
            cached.healthy
        } else {
            refresh(&state, now, &mut cache).await
        }
    } else {
        refresh(&state, now, &mut cache).await
    };
    response(&state.version, healthy)
}

async fn refresh(state: &AppState, now: i64, cache: &mut Option<CachedReadiness>) -> bool {
    let store = state.store.clone();
    let data_root = state.data_root.clone();
    let media_root = state.media_root.clone();
    let result = tokio::task::spawn_blocking(move || {
        store
            .health_check()
            .map_err(|error| error.to_string())
            .and_then(|()| probe_directory_writable(&data_root).map_err(|error| error.to_string()))
            .and_then(|()| probe_directory_writable(&media_root).map_err(|error| error.to_string()))
    })
    .await;
    let healthy = match result {
        Ok(Ok(())) => true,
        Ok(Err(error)) => {
            tracing::error!(%error, "readiness check failed");
            false
        }
        Err(error) => {
            tracing::error!(%error, "readiness worker failed");
            false
        }
    };
    *cache = Some(CachedReadiness {
        checked_at: now,
        healthy,
    });
    healthy
}

fn response(version: &str, healthy: bool) -> (StatusCode, Json<Value>) {
    if healthy {
        (
            StatusCode::OK,
            Json(json!({"ok": true, "version": version})),
        )
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(json!({
                "ok": false,
                "status": "degraded",
                "version": version,
            })),
        )
    }
}

fn probe_directory_writable(directory: &Path) -> std::io::Result<()> {
    let path = directory.join(format!(
        ".lezi-health-{}-{}",
        std::process::id(),
        Uuid::new_v4()
    ));
    let result = (|| {
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)?;
        file.write_all(b"ok")?;
        file.sync_all()
    })();
    let cleanup = if path.exists() {
        fs::remove_file(path)
    } else {
        Ok(())
    };
    result.and(cleanup).and_then(|()| sync_directory(directory))
}
