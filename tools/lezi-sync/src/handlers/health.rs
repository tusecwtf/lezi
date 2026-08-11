use std::sync::Arc;

use axum::extract::State;
use axum::Json;
use serde_json::{json, Value};

use crate::{AppState, HEALTH_CAPABILITIES};

/// HTTP route entrypoint — `pub(crate)` so crate-root `build_apps` can bind via
/// domain path (`handlers::health::health`). See `handlers` module docs.
pub(crate) async fn health(State(state): State<Arc<AppState>>) -> Json<Value> {
    // Additive capabilities keep the body small so client health probes
    // (64 KiB bound) and redirect rejection assumptions stay valid.
    Json(json!({
        "ok": true,
        "version": state.version,
        "capabilities": HEALTH_CAPABILITIES,
    }))
}
