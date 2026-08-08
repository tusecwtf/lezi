use std::sync::Arc;

use axum::extract::State;
use axum::Json;
use serde_json::{json, Value};

use crate::{
    AppState, CAPABILITY_ATOMIC_BUNDLE, CAPABILITY_AUTHORITATIVE_RECONCILE,
    CAPABILITY_CAUSAL_VERSIONS, CAPABILITY_DISASTER_RESTORE, CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
    CAPABILITY_VALIDATED_DEFERRED_FULFILLMENT, CAPABILITY_WAKE_OBSERVATION,
};

/// HTTP route entrypoint — `pub(crate)` so crate-root `build_apps` can bind via
/// domain path (`handlers::health::health`). See `handlers` module docs.
pub(crate) async fn health(State(state): State<Arc<AppState>>) -> Json<Value> {
    // Additive capabilities keep the body small so client health probes
    // (64 KiB bound) and redirect rejection assumptions stay valid.
    Json(json!({
        "ok": true,
        "version": state.version,
        "capabilities": [
            CAPABILITY_ATOMIC_BUNDLE,
            CAPABILITY_RECORD_MEMBERSHIP_AUTHOR,
            CAPABILITY_DISASTER_RESTORE,
            CAPABILITY_AUTHORITATIVE_RECONCILE,
            CAPABILITY_VALIDATED_DEFERRED_FULFILLMENT,
            CAPABILITY_CAUSAL_VERSIONS,
            CAPABILITY_WAKE_OBSERVATION,
            // CAPABILITY_SOURCE_RELATIONS withheld until declare/resolve HTTP exists.
        ],
    }))
}
