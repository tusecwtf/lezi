//! Lezi family sync server — HTTP + SQLite, single data root.
//!
//! Wire-compatible with the former Python prototype and Android `HttpSyncBackend`:
//!   GET  /health
//!   POST /v1/push
//!   GET  /v1/pull?family_id=&cursor=
//!   POST /v1/invite
//!   POST /v1/join

mod db;
mod handlers;
mod models;

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use axum::routing::{get, post};
use axum::Router;
use tower_http::cors::{Any, CorsLayer};
use tower_http::trace::TraceLayer;
use tracing_subscriber::EnvFilter;

use crate::db::Store;
use crate::handlers::{health, invite, join, pull, push, AppState};

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let data_dir = PathBuf::from(env_or(
        "LEZI_DATA_DIR",
        &std::env::current_dir()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|_| ".".into()),
    ));
    let db_path = std::env::var("LEZI_SYNC_DB")
        .map(PathBuf::from)
        .unwrap_or_else(|_| data_dir.join("lezi.db"));
    let media_dir = data_dir.join("media");
    let host = env_or("LEZI_SYNC_HOST", "0.0.0.0");
    let port: u16 = env_or("LEZI_SYNC_PORT", "8765")
        .parse()
        .expect("LEZI_SYNC_PORT must be a u16");
    let version = env_or("LEZI_SYNC_VERSION", env!("CARGO_PKG_VERSION"));

    std::fs::create_dir_all(&data_dir).expect("create data dir");
    std::fs::create_dir_all(&media_dir).expect("create media dir");

    let store = Store::open(&db_path).expect("open sqlite");
    let state = Arc::new(AppState {
        store,
        data_dir: data_dir.clone(),
        version,
    });

    let app = Router::new()
        .route("/health", get(health))
        .route("/v1/push", post(push))
        .route("/v1/pull", get(pull))
        .route("/v1/invite", post(invite))
        .route("/v1/join", post(join))
        .layer(
            CorsLayer::new()
                .allow_origin(Any)
                .allow_methods(Any)
                .allow_headers(Any),
        )
        .layer(TraceLayer::new_for_http())
        .with_state(state);

    let addr: SocketAddr = format!("{host}:{port}")
        .parse()
        .expect("invalid LEZI_SYNC_HOST/PORT");
    tracing::info!(
        %addr,
        data_dir = %data_dir.display(),
        db = %db_path.display(),
        media = %media_dir.display(),
        "lezi sync server listening"
    );

    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .expect("bind listen address");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .expect("server error");
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install signal handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    tracing::info!("shutdown signal received");
}
