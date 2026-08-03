//! HTTP media/bundle routes and media-root process lifecycle.
//!
//! Route entrypoints are `pub(crate)` so crate-root `build_apps` can bind them via
//! domain paths (`handlers::media::commit_bundle`, …). Non-route helpers:
//! - [`media_entity_is_pullable`] — pull filter seam used by [`super::sync`]
//! - [`collect_orphan_family_media`] / [`retry_committed_pending_bundle_media_cleanup`]
//!   — startup cleanup on the media root, invoked from `build_apps` (not HTTP)

use std::collections::BTreeSet;
use std::fs::{self, OpenOptions};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

use axum::body::Body;
use axum::extract::rejection::JsonRejection;
use axum::extract::{Path as AxumPath, Request, State};
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE};
use axum::http::{HeaderMap, HeaderValue};
use axum::response::{IntoResponse, Response};
use axum::Json;
use futures_util::StreamExt;
use serde_json::Value;
use sha2::{Digest, Sha256};
use uuid::Uuid;

use crate::model::{BundleCommitRequest, BundleStageRequest};
use crate::store::{CommittedPendingBundleMedia, Store, StoreError};
use crate::{
    authenticate, json_body, require_supported_client, run_blocking, secure_directory, secure_file,
    sync_directory, write_private_file, ApiError, AppState, MAX_ENTITY_FUTURE_SKEW_MILLIS,
    OPEN_STAGING_BUNDLE_TTL_SECONDS,
};

/// HTTP route entrypoint — domain-path assembly from crate root.
pub(crate) async fn retired_ordinary_media_upload() -> Result<Json<Value>, ApiError> {
    Err(ApiError::unprocessable(
        "ordinary media upload is retired; upload media through an atomic bundle",
    ))
}

pub(crate) async fn get_media(
    State(state): State<Arc<AppState>>,
    AxumPath(client_uuid): AxumPath<Uuid>,
    headers: HeaderMap,
) -> Result<Response, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let store = state.store.clone();
    let family_id = principal.family_id.clone();
    let media_id = client_uuid.to_string();
    let metadata = run_blocking(move || {
        let metadata = store
            .media_metadata(&family_id, &media_id)?
            .ok_or_else(|| ApiError::not_found("Media metadata not found"))?;
        if !store.is_media_published(&family_id, &media_id)? {
            return Err(ApiError::not_found("Media bytes are not published"));
        }
        Ok(metadata)
    })
    .await?;
    drop(_guard);
    let path = state.media_path(&principal.family_id, client_uuid)?;
    let bytes = run_blocking(move || {
        if !media_file_is_ready(&path, metadata.byte_size, &client_uuid.to_string()) {
            return Err(ApiError::not_found("Media bytes incomplete or invalid"));
        }
        Ok(fs::read(path)?)
    })
    .await?;
    let mut response = Response::new(Body::from(bytes));
    response.headers_mut().insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/octet-stream"),
    );
    Ok(response)
}

pub(crate) async fn stage_bundle(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    body: Result<Json<BundleStageRequest>, JsonRejection>,
) -> Result<impl IntoResponse, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let request = json_body(body)?.validate(state.max_media_bytes)?;
    if request.generation != state.generation {
        return Err(ApiError::conflict_value(
            state
                .recovery_detail(&principal.family_id, "generation_changed")
                .await?,
        ));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let blocking_state = state.clone();
    let status = run_blocking(move || {
        let expired = blocking_state.store.expire_open_staging_bundles(
            &principal.family_id,
            blocking_state
                .now()
                .saturating_sub(OPEN_STAGING_BUNDLE_TTL_SECONDS),
        )?;
        for bundle_id in expired {
            let Ok(bundle_id) = Uuid::parse_str(&bundle_id) else {
                tracing::error!(%bundle_id, "stored expired bundle UUID is invalid");
                continue;
            };
            let path = blocking_state.bundle_stage_dir(&principal.family_id, &bundle_id)?;
            if let Err(error) = fs::remove_dir_all(&path) {
                if error.kind() != std::io::ErrorKind::NotFound {
                    tracing::warn!(
                        path = %path.display(),
                        %error,
                        "failed to remove expired staging bytes"
                    );
                }
            }
        }
        blocking_state
            .store
            .stage_bundle(
                &principal,
                &request.bundle_id,
                request.root,
                request.media,
                blocking_state.now(),
            )
            .map_err(map_stage_bundle_store_error)
    })
    .await?;
    Ok(Json(status))
}

pub(crate) async fn get_bundle(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(bundle_id): AxumPath<Uuid>,
) -> Result<Json<crate::store::BundleStageStatus>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let store = state.store.clone();
    let family_id = principal.family_id;
    let bundle_id = bundle_id.to_string();
    run_blocking(move || Ok(store.bundle_status(&family_id, &bundle_id)?))
        .await?
        .map(Json)
        .ok_or_else(|| ApiError::not_found("Bundle not found"))
}

pub(crate) async fn put_bundle_media(
    State(state): State<Arc<AppState>>,
    AxumPath((bundle_id, client_uuid)): AxumPath<(Uuid, Uuid)>,
    request: Request,
) -> Result<Json<crate::store::BundleStageStatus>, ApiError> {
    let principal = authenticate(&state, request.headers()).await?;
    require_supported_client(&state, request.headers()).await?;
    if let Some(length) = request.headers().get(CONTENT_LENGTH) {
        let length = length
            .to_str()
            .ok()
            .and_then(|value| value.parse::<usize>().ok())
            .ok_or_else(|| ApiError::bad_request("Invalid Content-Length"))?;
        if length > state.max_media_bytes {
            return Err(ApiError::payload_too_large("Media is too large"));
        }
    }

    let max_media_bytes = state.max_media_bytes;
    let content = tokio::time::timeout(Duration::from_secs(120), async move {
        let mut content = Vec::new();
        let mut stream = request.into_body().into_data_stream();
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|error| ApiError::bad_request(error.to_string()))?;
            if content.len() + chunk.len() > max_media_bytes {
                return Err(ApiError::payload_too_large("Media is too large"));
            }
            content.extend_from_slice(&chunk);
        }
        Ok::<_, ApiError>(content)
    })
    .await
    .map_err(|_| ApiError::request_timeout("Media upload body timed out"))??;
    if content.is_empty() {
        return Err(ApiError::unprocessable("Media body must not be empty"));
    }

    // Never hold the per-family serialization lock while awaiting an
    // untrusted request body. Once bounded bytes are complete, re-read the
    // current bundle manifest under the lock before touching disk or SQLite.
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let store = state.store.clone();
    let family_id = principal.family_id.clone();
    let bundle_key = bundle_id.to_string();
    let bundle = run_blocking(move || Ok(store.load_bundle(&family_id, &bundle_key)?))
        .await?
        .ok_or_else(|| ApiError::not_found("Bundle not found"))?;
    if bundle.status != "staging" {
        return Err(ApiError::conflict(
            "committed bundle does not accept staged media",
        ));
    }
    if bundle.staged_membership_id != principal.membership_id {
        return Err(ApiError::conflict(
            "bundle belongs to another family membership",
        ));
    }
    let media_entity = bundle
        .media
        .iter()
        .find(|entity| entity.client_uuid == client_uuid.to_string())
        .ok_or_else(|| ApiError::unprocessable("media is not listed in the bundle manifest"))?;
    if media_entity.deleted_at.is_some() {
        return Err(ApiError::unprocessable(
            "tombstone media does not accept bytes",
        ));
    }
    if media_entity
        .payload
        .get("kind")
        .and_then(|value| value.as_str())
        .is_some_and(|kind| kind == "avatar")
        && principal.role != "owner"
    {
        return Err(ApiError::forbidden("Only owner may change avatar"));
    }
    if let Some(declared) = media_entity
        .payload
        .get("byte_size")
        .and_then(|value| value.as_u64())
        .and_then(|size| usize::try_from(size).ok())
    {
        if declared != content.len() {
            return Err(ApiError::unprocessable(
                "Media body size does not match declared byte_size",
            ));
        }
    }

    let blocking_state = state.clone();
    let status = run_blocking(move || {
        let path =
            blocking_state.bundle_media_path(&principal.family_id, &bundle_id, &client_uuid)?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
            secure_directory(parent)?;
        }
        write_private_file(&path, &content)?;
        let staged_sha256 = hex::encode(Sha256::digest(&content));

        match blocking_state.store.mark_bundle_media_staged(
            &principal,
            &bundle_id.to_string(),
            &client_uuid.to_string(),
            content.len(),
            &staged_sha256,
            blocking_state.now(),
        ) {
            Ok(value) => Ok(value),
            Err(StoreError::BundleMediaNotInManifest) => Err(ApiError::unprocessable(
                "media is not listed in the bundle manifest",
            )),
            Err(StoreError::BundleMediaIncomplete) => Err(ApiError::unprocessable(
                "Media body size does not match declared byte_size",
            )),
            Err(StoreError::BundleMediaUploadClosed) => Err(ApiError::conflict(
                "committed bundle does not accept staged media",
            )),
            Err(StoreError::BundleMembershipMismatch) => Err(ApiError::conflict(
                "bundle belongs to another family membership",
            )),
            Err(StoreError::BundleNotFound) => Err(ApiError::not_found("Bundle not found")),
            Err(error) => Err(error.into()),
        }
    })
    .await?;
    Ok(Json(status))
}

pub(crate) async fn commit_bundle(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    AxumPath(bundle_id): AxumPath<Uuid>,
    body: Result<Json<BundleCommitRequest>, JsonRejection>,
) -> Result<Json<crate::store::BundleCommitResult>, ApiError> {
    let principal = authenticate(&state, &headers).await?;
    require_supported_client(&state, &headers).await?;
    let request = json_body(body)?;
    request.validate()?;
    if request.generation != state.generation {
        return Err(ApiError::conflict_value(
            state
                .recovery_detail(&principal.family_id, "generation_changed")
                .await?,
        ));
    }
    let family_lock = state.family_lock(&principal.family_id).await;
    let _guard = family_lock.lock().await;
    let state = state.clone();
    let result = run_blocking(move || {
        let bundle = state
            .store
            .load_bundle(&principal.family_id, &bundle_id.to_string())?
            .ok_or_else(|| ApiError::not_found("Bundle not found"))?;

        // Preflight authenticated ownership before inspecting or changing any
        // final-path media. Store::commit_bundle repeats this check as the
        // transactional authority; this early guard prevents rejected principals
        // from leaving claimable filesystem state.
        if bundle.staged_membership_id != principal.membership_id {
            return Err(ApiError::conflict(
                "bundle belongs to another family membership",
            ));
        }

        let mut media_ready = std::collections::BTreeMap::new();
        let mut staged_publications = Vec::new();
        for media_uuid in &bundle.required_media {
            let media_id = Uuid::parse_str(media_uuid)
                .map_err(|_| ApiError::internal("stored media uuid is invalid"))?;
            let integrity = bundle
                .media_integrity
                .get(media_uuid)
                .ok_or_else(|| ApiError::internal("stored bundle media integrity is missing"))?;
            if bundle.status == "committed" {
                let final_path = state.media_path(&principal.family_id, media_id)?;
                let digest = integrity
                    .staged_sha256
                    .as_deref()
                    .and_then(|expected_sha256| {
                        media_file_integrity_sha256(
                            &final_path,
                            integrity.declared_byte_size,
                            Some(expected_sha256),
                            media_uuid,
                        )
                    });
                if digest.is_some() {
                    sync_published_media_file(&final_path)?;
                }
                media_ready.insert(media_uuid.clone(), digest.is_some());
                continue;
            }
            let staged_path =
                state.bundle_media_path(&principal.family_id, &bundle_id, &media_id)?;
            let digest = integrity
                .staged_sha256
                .as_deref()
                .and_then(|expected_sha256| {
                    media_file_integrity_sha256(
                        &staged_path,
                        integrity.declared_byte_size,
                        Some(expected_sha256),
                        media_uuid,
                    )
                });
            if digest.is_some() {
                staged_publications.push((media_uuid.clone(), media_id, staged_path));
            }
            media_ready.insert(media_uuid.clone(), digest.is_some());
        }

        // Validate the complete staging manifest before the first final-path
        // change. Incomplete/corrupt later entries must not leave earlier files
        // pre-published. The Store still returns the canonical 422 below.
        if bundle.status == "staging" && media_ready.values().all(|ready| *ready) {
            for (media_uuid, media_id, staged_path) in staged_publications {
                let final_path = state.media_path(&principal.family_id, media_id)?;
                prepare_published_media_file(&staged_path, &final_path)?;
                match state.store.mark_bundle_media_prepared(
                    &principal,
                    &bundle_id.to_string(),
                    &media_uuid,
                ) {
                    Ok(()) => {}
                    Err(StoreError::BundleMembershipMismatch) => {
                        return Err(ApiError::conflict(
                            "bundle belongs to another family membership",
                        ))
                    }
                    Err(StoreError::BundleNotFound) => {
                        return Err(ApiError::not_found("Bundle not found"))
                    }
                    Err(error) => return Err(error.into()),
                }
            }
        }

        let max_updated_at = state
            .now()
            .saturating_mul(1_000)
            .saturating_add(MAX_ENTITY_FUTURE_SKEW_MILLIS);
        let (result, _package) = match state.store.commit_bundle(
            &principal,
            &bundle_id.to_string(),
            &media_ready,
            max_updated_at,
            state.now(),
        ) {
            Ok(value) => value,
            Err(error) => {
                return Err(map_commit_bundle_store_error(error));
            }
        };
        cleanup_committed_pending_bundle_media_for_bundle(
            &state.store,
            &state.media_root,
            &principal.family_id,
            &bundle_id.to_string(),
        )?;
        // Best-effort staging cleanup; failed/abandoned dirs are bounded by open-bundle limits.
        let _ = fs::remove_dir_all(state.bundle_stage_dir(&principal.family_id, &bundle_id)?);

        Ok(result)
    })
    .await?;
    Ok(Json(result))
}

/// Cross-module pull filter seam (used by [`super::sync::pull_entities`]).
pub(crate) fn media_entity_is_pullable(
    state: &AppState,
    family_id: &str,
    entity: &crate::store::PulledEntity,
    published_media: &BTreeSet<String>,
) -> bool {
    if entity.entity_type != "media" || entity.deleted_at.is_some() {
        return true;
    }
    let is_published = published_media.contains(&entity.client_uuid);
    let Ok(client_uuid) = Uuid::parse_str(&entity.client_uuid) else {
        return false;
    };
    let declared_size = match entity.payload.get("byte_size") {
        None => None,
        Some(value) => match value.as_u64().and_then(|size| usize::try_from(size).ok()) {
            Some(size) => Some(size),
            None => {
                tracing::error!(
                    client_uuid = %entity.client_uuid,
                    "stored media byte_size is invalid; omitting media from pull"
                );
                return false;
            }
        },
    };
    match state.media_path(family_id, client_uuid) {
        Ok(path) => media_file_is_ready(&path, declared_size, &entity.client_uuid) && is_published,
        Err(_) => false,
    }
}

fn media_file_is_ready(path: &Path, declared_size: Option<usize>, client_uuid: &str) -> bool {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return false,
        Err(error) => {
            tracing::error!(
                %client_uuid,
                path = %path.display(),
                %error,
                "cannot inspect media bytes; omitting media"
            );
            return false;
        }
    };
    let actual_size = metadata.len();
    let expected_matches = declared_size.is_none_or(|expected| {
        u64::try_from(expected).is_ok_and(|expected| expected == actual_size)
    });
    if metadata.file_type().is_file() && actual_size > 0 && expected_matches {
        return true;
    }

    tracing::warn!(
        %client_uuid,
        path = %path.display(),
        actual_size,
        ?declared_size,
        "media bytes are invalid; omitting and repairing incomplete state"
    );
    if metadata.file_type().is_file() || metadata.file_type().is_symlink() {
        match fs::remove_file(path) {
            Ok(()) => {
                if let Some(parent) = path.parent() {
                    if let Err(error) = sync_directory(parent) {
                        tracing::error!(
                            %client_uuid,
                            path = %parent.display(),
                            %error,
                            "failed to sync media directory after corrupt-file cleanup"
                        );
                    }
                }
            }
            Err(error) => {
                tracing::error!(
                    %client_uuid,
                    path = %path.display(),
                    %error,
                    "failed to remove invalid media bytes"
                );
            }
        }
    }
    false
}

fn media_file_integrity_sha256(
    path: &Path,
    declared_size: Option<usize>,
    expected_sha256: Option<&str>,
    client_uuid: &str,
) -> Option<String> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return None,
        Err(error) => {
            tracing::error!(
                %client_uuid,
                path = %path.display(),
                %error,
                "cannot inspect atomic media integrity"
            );
            return None;
        }
    };
    if !metadata.file_type().is_file() || metadata.len() == 0 {
        return None;
    }
    if declared_size.is_some_and(|expected| u64::try_from(expected) != Ok(metadata.len())) {
        return None;
    }
    match fs::read(path) {
        Ok(bytes) => {
            let actual = hex::encode(Sha256::digest(bytes));
            if expected_sha256.is_none_or(|expected| expected == actual) {
                Some(actual)
            } else {
                None
            }
        }
        Err(error) => {
            tracing::error!(
                %client_uuid,
                path = %path.display(),
                %error,
                "cannot hash atomic media"
            );
            None
        }
    }
}

/// Startup media-root lifecycle (not an HTTP route). Called from `build_apps`.
pub(crate) fn collect_orphan_family_media(
    store: &Store,
    media_root: &Path,
    restore_family_ids: &BTreeSet<String>,
) -> Result<(), ApiError> {
    let family_ids = store.family_ids()?;
    let mut removed_any = false;
    for entry in fs::read_dir(media_root)? {
        let entry = entry?;
        if !entry.file_type()?.is_dir() {
            continue;
        }
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        let Ok(family_id) = Uuid::parse_str(&name) else {
            continue;
        };
        if family_ids.contains(&family_id.to_string())
            || restore_family_ids.contains(&family_id.to_string())
        {
            continue;
        }
        match fs::remove_dir_all(entry.path()) {
            Ok(()) => {
                removed_any = true;
                tracing::info!(
                    family_id = %family_id,
                    "removed orphan family media left by an interrupted deletion"
                );
            }
            Err(error) => {
                tracing::error!(
                    family_id = %family_id,
                    path = %entry.path().display(),
                    %error,
                    "failed to remove orphan family media; startup will retry"
                );
            }
        }
    }
    if removed_any {
        sync_directory(media_root)?;
    }
    Ok(())
}

fn cleanup_committed_pending_bundle_media_for_bundle(
    store: &Store,
    media_root: &Path,
    family_id: &str,
    bundle_id: &str,
) -> Result<(), ApiError> {
    let pending = store.committed_pending_bundle_media_for_bundle(family_id, bundle_id)?;
    for entry in pending {
        cleanup_committed_pending_bundle_media(store, media_root, &entry)?;
    }
    Ok(())
}

fn cleanup_committed_pending_bundle_media(
    store: &Store,
    media_root: &Path,
    pending: &CommittedPendingBundleMedia,
) -> Result<(), ApiError> {
    let family_id = Uuid::parse_str(&pending.family_id)
        .map_err(|_| ApiError::internal("stored pending media family uuid is invalid"))?;
    let media_id = Uuid::parse_str(&pending.media_uuid)
        .map_err(|_| ApiError::internal("stored pending media uuid is invalid"))?;
    let published = media_root
        .join(family_id.to_string())
        .join(media_id.to_string());
    cleanup_committed_pending_media_with_ops(
        &published,
        |path| fs::remove_file(path),
        sync_directory,
        || {
            store.finalize_committed_pending_bundle_media(pending)?;
            Ok(())
        },
    )
}

fn cleanup_committed_pending_media_with_ops(
    published: &Path,
    remove: impl FnOnce(&Path) -> std::io::Result<()>,
    sync: impl Fn(&Path) -> std::io::Result<()>,
    finalize: impl FnOnce() -> Result<(), ApiError>,
) -> Result<(), ApiError> {
    match remove(published) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    // NotFound can mean an earlier attempt removed the file but crashed before
    // its directory fsync. Re-sync both directory levels on every retry before
    // deleting the durable SQLite cleanup evidence.
    sync_published_media_directories(published, &sync)?;
    finalize()
}

/// Startup media-root lifecycle (not an HTTP route). Called from `build_apps`.
pub(crate) fn retry_committed_pending_bundle_media_cleanup(
    store: &Store,
    media_root: &Path,
) -> Result<(), ApiError> {
    for pending in store.committed_pending_bundle_media()? {
        if let Err(error) = cleanup_committed_pending_bundle_media(store, media_root, &pending) {
            tracing::error!(
                family_id = %pending.family_id,
                bundle_id = %pending.bundle_id,
                media_uuid = %pending.media_uuid,
                ?error,
                "failed to finish committed bundle media cleanup; startup will retry"
            );
        }
    }
    Ok(())
}

fn prepare_published_media_file(staged: &Path, published: &Path) -> Result<(), ApiError> {
    prepare_published_media_file_with_link(staged, published, |source, destination| {
        fs::hard_link(source, destination)
    })
}

fn prepare_published_media_file_with_link(
    staged: &Path,
    published: &Path,
    link: impl FnOnce(&Path, &Path) -> std::io::Result<()>,
) -> Result<(), ApiError> {
    prepare_published_media_file_with_link_and_sync(staged, published, link, |directory| {
        sync_directory(directory)
    })
}

fn prepare_published_media_file_with_link_and_sync(
    staged: &Path,
    published: &Path,
    link: impl FnOnce(&Path, &Path) -> std::io::Result<()>,
    sync_parent: impl Fn(&Path) -> std::io::Result<()>,
) -> Result<(), ApiError> {
    let parent = published
        .parent()
        .ok_or_else(|| ApiError::internal("media path has no parent directory"))?;
    fs::create_dir_all(parent)?;
    secure_directory(parent)?;

    match link(staged, published) {
        Ok(()) => {
            secure_file(published)?;
            fs::File::open(published)?.sync_all()?;
            sync_published_media_directories(published, &sync_parent)?;
            Ok(())
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            verify_existing_published_media(staged, published, &sync_parent)
        }
        Err(error) if hard_link_fallback_allowed(&error) => {
            match atomic_copy_media_noreplace(staged, published) {
                Ok(()) => {
                    sync_published_media_directories(published, &sync_parent)?;
                    Ok(())
                }
                Err(copy_error) if copy_error.kind() == std::io::ErrorKind::AlreadyExists => {
                    verify_existing_published_media(staged, published, &sync_parent)
                }
                Err(copy_error) => Err(copy_error.into()),
            }
        }
        Err(error) => Err(error.into()),
    }
}

fn verify_existing_published_media(
    staged: &Path,
    published: &Path,
    sync_parent: &impl Fn(&Path) -> std::io::Result<()>,
) -> Result<(), ApiError> {
    let metadata = fs::symlink_metadata(published)?;
    if !metadata.file_type().is_file() || fs::read(staged)? != fs::read(published)? {
        return Err(ApiError::conflict(
            "published media bytes conflict with staged bundle",
        ));
    }
    secure_file(published)?;
    fs::File::open(published)?.sync_all()?;
    sync_published_media_directories(published, sync_parent)?;
    Ok(())
}

fn sync_published_media_file(published: &Path) -> Result<(), ApiError> {
    secure_file(published)?;
    fs::File::open(published)?.sync_all()?;
    sync_published_media_directories(published, &|directory| sync_directory(directory))?;
    Ok(())
}

fn sync_published_media_directories(
    published: &Path,
    sync: &impl Fn(&Path) -> std::io::Result<()>,
) -> Result<(), ApiError> {
    let family_directory = published
        .parent()
        .ok_or_else(|| ApiError::internal("media path has no family directory"))?;
    let media_root = family_directory
        .parent()
        .ok_or_else(|| ApiError::internal("media path has no media root"))?;
    sync(family_directory)?;
    sync(media_root)?;
    Ok(())
}

fn hard_link_fallback_allowed(error: &std::io::Error) -> bool {
    if error.kind() == std::io::ErrorKind::Unsupported {
        return true;
    }
    #[cfg(unix)]
    {
        matches!(
            error.raw_os_error(),
            Some(code)
                if code == libc::EXDEV
                    || code == libc::EPERM
                    || code == libc::EACCES
                    || code == libc::EOPNOTSUPP
        )
    }
    #[cfg(not(unix))]
    false
}

fn atomic_copy_media_noreplace(staged: &Path, published: &Path) -> std::io::Result<()> {
    let temporary = staged.with_extension(format!("{}.publish.tmp", Uuid::new_v4()));
    let result = (|| -> std::io::Result<()> {
        let mut source = fs::File::open(staged)?;
        let mut destination = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        std::io::copy(&mut source, &mut destination)?;
        destination.sync_all()?;
        secure_file(&temporary)?;
        rename_noreplace(&temporary, published)?;
        secure_file(published)?;
        fs::File::open(published)?.sync_all()?;
        let published_parent = published.parent().ok_or_else(|| {
            std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "media path has no parent directory",
            )
        })?;
        sync_directory(published_parent)?;
        if let Some(staging_parent) = temporary.parent() {
            if staging_parent != published_parent {
                sync_directory(staging_parent)?;
            }
        }
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

#[cfg(target_os = "linux")]
fn rename_noreplace(source: &Path, destination: &Path) -> std::io::Result<()> {
    use std::ffi::CString;
    use std::os::unix::ffi::OsStrExt;

    let source_c = CString::new(source.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    let destination_c = CString::new(destination.as_os_str().as_bytes())
        .map_err(|_| std::io::Error::from(std::io::ErrorKind::InvalidInput))?;
    // SAFETY: both C strings are NUL-terminated and remain alive for the call.
    let result = unsafe {
        libc::renameat2(
            libc::AT_FDCWD,
            source_c.as_ptr(),
            libc::AT_FDCWD,
            destination_c.as_ptr(),
            libc::RENAME_NOREPLACE,
        )
    };
    if result == 0 {
        return Ok(());
    }
    let error = std::io::Error::last_os_error();
    if matches!(
        error.raw_os_error(),
        Some(code) if code == libc::ENOSYS || code == libc::EINVAL || code == libc::EOPNOTSUPP
    ) {
        return rename_noreplace_single_process(source, destination);
    }
    Err(error)
}

#[cfg(target_os = "linux")]
fn rename_noreplace_single_process(source: &Path, destination: &Path) -> std::io::Result<()> {
    if destination.try_exists()? {
        return Err(std::io::Error::from(std::io::ErrorKind::AlreadyExists));
    }
    // The supported deployment is a single server process, so after the
    // exclusive family lock this fallback has no competing publisher.
    fs::rename(source, destination)
}

#[cfg(not(target_os = "linux"))]
fn rename_noreplace(source: &Path, destination: &Path) -> std::io::Result<()> {
    if destination.try_exists()? {
        return Err(std::io::Error::from(std::io::ErrorKind::AlreadyExists));
    }
    fs::rename(source, destination)
}

/// Shared StoreError → ApiError mapping for immutable/tombstone/ACL policy
/// failures so stage and commit cannot drift on detail text or status.
fn map_bundle_policy_store_error(error: StoreError) -> Result<StoreError, ApiError> {
    match error {
        StoreError::ForbiddenBaby => {
            Err(ApiError::forbidden("Only owner may manage baby profiles"))
        }
        StoreError::ForbiddenAvatar => Err(ApiError::forbidden("Only owner may change avatar")),
        StoreError::ForbiddenCustomItem => Err(ApiError::forbidden(
            "Only the creator or family owner may change this custom item",
        )),
        StoreError::ForbiddenCarePlan => Err(ApiError::forbidden(
            "Only the creator or family owner may change this care plan",
        )),
        StoreError::ForbiddenRecord => Err(ApiError::forbidden(
            "Only the record creator or family owner may change this record",
        )),
        StoreError::ForbiddenAnonymousFact => Err(ApiError::forbidden(
            "Only the family owner may change an anonymous shared fact",
        )),
        StoreError::CarePlanTombstoneResurrection => Err(ApiError::conflict(
            "Deleted care plan cannot be resurrected",
        )),
        StoreError::ImmutableCarePlanFulfillmentBinding => Err(ApiError::conflict(
            "Completed care plan fulfillment binding is immutable",
        )),
        StoreError::ImmutableFulfillmentCandidateEvidence => Err(ApiError::conflict(
            "Fulfillment candidate evidence is immutable",
        )),
        StoreError::FulfillmentCandidateTombstoneResurrection => Err(ApiError::conflict(
            "Deleted fulfillment candidate cannot be resurrected",
        )),
        StoreError::CustomItemTombstoneResurrection => Err(ApiError::conflict(
            "Deleted custom item cannot be resurrected",
        )),
        StoreError::ImmutableMediaAssociation => Err(ApiError::conflict(
            "Media kind and association are immutable",
        )),
        // Schema shape (status↔pair), not a missing cross-entity ref → 422.
        StoreError::InvalidCarePlanFulfillmentPair(message) => {
            Err(ApiError::unprocessable(message))
        }
        StoreError::UnresolvedReference(message) => Err(ApiError::conflict(message)),
        StoreError::PullEntityTooLarge => Err(ApiError::unprocessable(
            "entity payload is too large for bounded sync pull",
        )),
        other => Ok(other),
    }
}

fn map_stage_bundle_store_error(error: StoreError) -> ApiError {
    match map_bundle_policy_store_error(error) {
        Err(api) => api,
        Ok(StoreError::BundleContentConflict) => {
            ApiError::conflict("bundle_id already committed with different content")
        }
        Ok(StoreError::BundleMembershipMismatch) => {
            ApiError::conflict("bundle belongs to another family membership")
        }
        Ok(StoreError::BundleStagingLimit) => {
            ApiError::unprocessable("too many open staging bundles; commit or wait for cleanup")
        }
        Ok(other) => other.into(),
    }
}

fn map_commit_bundle_store_error(error: StoreError) -> ApiError {
    match map_bundle_policy_store_error(error) {
        Err(api) => api,
        Ok(StoreError::BundleMediaIncomplete) => {
            ApiError::unprocessable("bundle media bytes are incomplete")
        }
        Ok(StoreError::BundleRootNotNewer) => {
            ApiError::conflict("bundle root is not newer than the published version")
        }
        Ok(StoreError::BundleMembershipMismatch) => {
            ApiError::conflict("bundle belongs to another family membership")
        }
        Ok(StoreError::TimestampOutOfRange) => {
            ApiError::unprocessable("updated_at is outside the accepted server time window")
        }
        Ok(StoreError::BundleNotFound) => ApiError::not_found("Bundle not found"),
        Ok(other) => other.into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::write_private_file;
    use axum::http::StatusCode;

    #[cfg(unix)]
    #[test]
    fn atomic_media_publish_falls_back_when_hard_links_are_unsupported() {
        let directory = tempfile::tempdir().unwrap();
        let staged = directory.path().join("staged");
        let published = directory.path().join("published");
        write_private_file(&staged, b"image-bytes").unwrap();

        prepare_published_media_file_with_link(&staged, &published, |_from, _to| {
            Err(std::io::Error::from_raw_os_error(libc::EOPNOTSUPP))
        })
        .unwrap();

        assert_eq!(fs::read(&published).unwrap(), b"image-bytes");
        write_private_file(&staged, b"different-bytes").unwrap();
        let conflict = prepare_published_media_file_with_link(&staged, &published, |_from, _to| {
            Err(std::io::Error::from_raw_os_error(libc::EOPNOTSUPP))
        })
        .unwrap_err();
        assert_eq!(conflict.status, StatusCode::CONFLICT);
        assert_eq!(fs::read(&published).unwrap(), b"image-bytes");
        assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 2);
    }

    #[test]
    fn atomic_media_publication_syncs_family_and_media_root_directories() {
        let directory = tempfile::tempdir().unwrap();
        let staged = directory.path().join("staged");
        let media_root = directory.path().join("media");
        let family_directory = media_root.join("11111111-2222-4333-8444-555555555555");
        fs::create_dir_all(&family_directory).unwrap();
        let published = family_directory.join("published");
        write_private_file(&staged, b"same-bytes").unwrap();
        let synced = std::cell::RefCell::new(std::collections::BTreeSet::new());

        prepare_published_media_file_with_link_and_sync(
            &staged,
            &published,
            |from, to| fs::hard_link(from, to),
            |path| {
                synced.borrow_mut().insert(path.to_path_buf());
                Ok(())
            },
        )
        .unwrap();

        assert_eq!(fs::read(&published).unwrap(), b"same-bytes");
        assert_eq!(
            synced.into_inner(),
            std::collections::BTreeSet::from([family_directory, media_root])
        );
    }

    #[test]
    fn existing_atomic_media_retry_syncs_family_and_media_root_directories() {
        let directory = tempfile::tempdir().unwrap();
        let staged = directory.path().join("staged");
        let media_root = directory.path().join("media");
        let family_directory = media_root.join("11111111-2222-4333-8444-555555555555");
        fs::create_dir_all(&family_directory).unwrap();
        let published = family_directory.join("published");
        write_private_file(&staged, b"same-bytes").unwrap();
        write_private_file(&published, b"same-bytes").unwrap();
        let synced = std::cell::RefCell::new(std::collections::BTreeSet::new());

        prepare_published_media_file_with_link_and_sync(
            &staged,
            &published,
            |_from, _to| Err(std::io::Error::from(std::io::ErrorKind::AlreadyExists)),
            |path| {
                synced.borrow_mut().insert(path.to_path_buf());
                Ok(())
            },
        )
        .unwrap();

        assert_eq!(
            synced.into_inner(),
            std::collections::BTreeSet::from([family_directory, media_root])
        );
    }

    #[test]
    fn pending_media_cleanup_retries_directory_sync_before_finalizing_evidence() {
        let directory = tempfile::tempdir().unwrap();
        let media_root = directory.path().join("media");
        let family_directory = media_root.join("11111111-2222-4333-8444-555555555555");
        fs::create_dir_all(&family_directory).unwrap();
        let published = family_directory.join("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        write_private_file(&published, b"discarded").unwrap();
        let fail_sync = std::cell::Cell::new(true);
        let finalized = std::cell::Cell::new(0);

        let attempt = || {
            cleanup_committed_pending_media_with_ops(
                &published,
                |path| fs::remove_file(path),
                |_directory| {
                    if fail_sync.get() {
                        Err(std::io::Error::other("injected directory sync failure"))
                    } else {
                        Ok(())
                    }
                },
                || {
                    finalized.set(finalized.get() + 1);
                    Ok(())
                },
            )
        };

        assert!(attempt().is_err());
        assert!(!published.exists());
        assert_eq!(finalized.get(), 0);
        // The second remove observes NotFound, but another fsync failure must
        // still retain the durable publication/manifest evidence.
        assert!(attempt().is_err());
        assert_eq!(finalized.get(), 0);

        fail_sync.set(false);
        attempt().unwrap();
        assert_eq!(finalized.get(), 1);
    }
}
