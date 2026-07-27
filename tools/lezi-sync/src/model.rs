use std::collections::BTreeSet;

use chrono::NaiveDate;
use serde::Deserialize;
use serde_json::{Map, Value};
use uuid::Uuid;

use crate::ApiError;

/// Device-local UI placeholder. Must never be accepted as a real family name.
pub(crate) const LOCAL_DEVICE_DISPLAY_NAME: &str = "我（本机）";

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FamilyCreateRequest {
    pub create_request_id: String,
    pub device_id: String,
    #[serde(default)]
    pub display_name: Option<String>,
    /// Shared family display name. Optional; blank/omitted stores null.
    #[serde(default)]
    pub family_name: Option<String>,
}

impl FamilyCreateRequest {
    /// Returns `(display_name, family_name)` where `family_name` is `None` when empty.
    pub fn validate(&self) -> Result<(String, Option<String>), ApiError> {
        validate_urlsafe(
            &self.create_request_id,
            32,
            128,
            "create_request_id must be 32-128 URL-safe characters",
        )?;
        validate_required_string(&self.device_id, 128, "device_id")?;
        let display_name = require_display_name(self.display_name.as_deref())?;
        let family_name = normalize_family_name(self.family_name.as_deref())?;
        Ok((display_name, family_name))
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EmptyRequest {}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct InviteRequest {}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct JoinRequest {
    pub code: String,
    pub device_id: String,
    #[serde(default)]
    pub display_name: Option<String>,
}

impl JoinRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        if !(8..=32).contains(&self.code.len())
            || !self
                .code
                .bytes()
                .all(|value| value.is_ascii_uppercase() || value.is_ascii_digit())
        {
            return Err(ApiError::unprocessable(
                "code must contain 8-32 uppercase letters or digits",
            ));
        }
        validate_required_string(&self.device_id, 128, "device_id")?;
        require_display_name(self.display_name.as_deref())
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UpdateDisplayNameRequest {
    pub display_name: String,
}

impl UpdateDisplayNameRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        require_display_name(Some(self.display_name.as_str()))
    }
}

/// Owner-only rename of the shared family name. Null/blank clears to null.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenameFamilyRequest {
    #[serde(default)]
    pub family_name: Option<String>,
}

impl RenameFamilyRequest {
    pub fn validate(&self) -> Result<Option<String>, ApiError> {
        normalize_family_name(self.family_name.as_deref())
    }
}

/// Product-required family 称呼 for create / join / self-rename.
///
/// Blank, whitespace-only, and the device-local placeholder 「我（本机）」 all
/// return 422 — never silently stored as null.
pub(crate) fn require_display_name(value: Option<&str>) -> Result<String, ApiError> {
    match normalize_display_name(value)? {
        Some(name) if name == LOCAL_DEVICE_DISPLAY_NAME => Err(ApiError::unprocessable(
            "display_name must not be the local device placeholder",
        )),
        Some(name) => Ok(name),
        None => Err(ApiError::unprocessable("display_name is required")),
    }
}

/// Normalize a human-facing member name before required-field validation.
///
/// Whitespace-only names become `None`.
/// Directional formatting and control characters are rejected because this
/// value is rendered next to a security-sensitive role.
pub(crate) fn normalize_display_name(value: Option<&str>) -> Result<Option<String>, ApiError> {
    let Some(value) = value else {
        return Ok(None);
    };
    if value
        .chars()
        .any(|character| character.is_control() || is_bidirectional_control(character))
    {
        return Err(ApiError::unprocessable(
            "display_name must not contain control or bidirectional formatting characters",
        ));
    }
    let value = value.trim();
    if value.is_empty() {
        return Ok(None);
    }
    validate_length(value, 1, 128, "display_name")?;
    Ok(Some(value.to_owned()))
}

/// Shared family name: optional; blank/omitted becomes `None` for client fallbacks.
/// Rejects control / bidirectional characters; max 64 Unicode scalars.
pub(crate) fn normalize_family_name(value: Option<&str>) -> Result<Option<String>, ApiError> {
    let Some(value) = value else {
        return Ok(None);
    };
    if value
        .chars()
        .any(|character| character.is_control() || is_bidirectional_control(character))
    {
        return Err(ApiError::unprocessable(
            "family_name must not contain control or bidirectional formatting characters",
        ));
    }
    let value = value.trim();
    if value.is_empty() {
        return Ok(None);
    }
    validate_length(value, 1, 64, "family_name")?;
    Ok(Some(value.to_owned()))
}

fn is_bidirectional_control(character: char) -> bool {
    matches!(
        character,
        '\u{061c}'
            | '\u{200e}'
            | '\u{200f}'
            | '\u{202a}'..='\u{202e}'
            | '\u{2066}'..='\u{206f}'
    )
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PushRequest {
    #[serde(default)]
    pub device_id: Option<String>,
    #[serde(default)]
    pub generation: Option<String>,
    pub entities: Vec<RawEntity>,
}

pub struct ValidatedPush {
    pub device_id: Option<String>,
    pub generation: Option<String>,
    pub entities: Vec<Entity>,
}

impl PushRequest {
    pub fn validate(self, max_media_bytes: usize) -> Result<ValidatedPush, ApiError> {
        validate_optional_nonempty_string(&self.device_id, 128, "device_id")?;
        validate_optional_nonempty_string(&self.generation, 128, "generation")?;
        if self.entities.len() > 1000 {
            return Err(ApiError::unprocessable(
                "entities must contain at most 1000 items",
            ));
        }
        let entities = self
            .entities
            .into_iter()
            .map(|entity| entity.validate(max_media_bytes))
            .collect::<Result<Vec<_>, _>>()?;
        Ok(ValidatedPush {
            device_id: self.device_id,
            generation: self.generation,
            entities,
        })
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RawEntity {
    #[serde(rename = "type")]
    pub entity_type: String,
    pub client_uuid: Uuid,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default)]
    pub payload: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Entity {
    pub entity_type: String,
    pub client_uuid: String,
    pub updated_at: i64,
    pub deleted_at: Option<i64>,
    pub payload: Map<String, Value>,
}

impl RawEntity {
    fn validate(self, max_media_bytes: usize) -> Result<Entity, ApiError> {
        self.validate_as(max_media_bytes, EntityValidationContext::OrdinaryPush)
    }

    /// Validate an entity for either ordinary push or atomic-bundle roots/media.
    pub fn validate_as(
        mut self,
        max_media_bytes: usize,
        context: EntityValidationContext,
    ) -> Result<Entity, ApiError> {
        if self.updated_at < 0 || self.deleted_at.is_some_and(|value| value < 0) {
            return Err(ApiError::unprocessable(
                "updated_at and deleted_at must be non-negative",
            ));
        }
        match (context, self.entity_type.as_str()) {
            (_, "baby") => validate_baby(&mut self.payload)?,
            (_, "record") => validate_record(&self.payload)?,
            (_, "media") => validate_media(&self.payload, max_media_bytes)?,
            (EntityValidationContext::OrdinaryPush, "custom_item") => {
                validate_custom_item(&mut self.payload)?
            }
            (EntityValidationContext::OrdinaryPush, "fulfillment_candidate") => {
                validate_fulfillment_candidate(&mut self.payload)?
            }
            (EntityValidationContext::AtomicBundleRoot, "care_plan") => {
                validate_care_plan(&mut self.payload)?
            }
            (EntityValidationContext::OrdinaryPush, "care_plan") => {
                // Care plans use atomic bundles so plan and media package semantics
                // cannot diverge.
                return Err(ApiError::unprocessable(
                    "care_plan must be published via atomic bundle",
                ));
            }
            (EntityValidationContext::OrdinaryPush, _) => return Err(ApiError::unprocessable(
                "entity type must be baby, record, media, custom_item, or fulfillment_candidate",
            )),
            (EntityValidationContext::AtomicBundleRoot, _) => {
                return Err(ApiError::unprocessable(
                    "bundle root type must be record or care_plan",
                ))
            }
            (EntityValidationContext::AtomicBundleMedia, _) => {
                return Err(ApiError::unprocessable(
                    "bundle media entities must have type media",
                ))
            }
        }
        Ok(Entity {
            entity_type: self.entity_type,
            client_uuid: self.client_uuid.to_string(),
            updated_at: self.updated_at,
            deleted_at: self.deleted_at,
            payload: self.payload,
        })
    }
}

/// Where an entity is being accepted on the wire.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EntityValidationContext {
    /// Ordinary `/v1/push` (baby | record | media | custom_item | fulfillment_candidate).
    OrdinaryPush,
    /// Root of an atomic bundle (`record` | `care_plan`).
    AtomicBundleRoot,
    /// Media row inside an atomic bundle (must be `media`).
    AtomicBundleMedia,
}

/// Max media entities per atomic bundle (product uses ≤3; protocol headroom).
pub const MAX_BUNDLE_MEDIA_ENTITIES: usize = 8;

/// Max concurrent staging bundles per family (failed/abandoned bound).
pub const MAX_OPEN_STAGING_BUNDLES_PER_FAMILY: usize = 64;

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BundleStageRequest {
    pub bundle_id: Uuid,
    pub root: RawEntity,
    #[serde(default)]
    pub media: Vec<RawEntity>,
    #[serde(default)]
    pub generation: Option<String>,
}

pub struct ValidatedBundleStage {
    pub bundle_id: String,
    pub root: Entity,
    pub media: Vec<Entity>,
    pub generation: Option<String>,
}

impl BundleStageRequest {
    pub fn validate(self, max_media_bytes: usize) -> Result<ValidatedBundleStage, ApiError> {
        validate_optional_nonempty_string(&self.generation, 128, "generation")?;
        if self.media.len() > MAX_BUNDLE_MEDIA_ENTITIES {
            return Err(ApiError::unprocessable(format!(
                "bundle media must contain at most {MAX_BUNDLE_MEDIA_ENTITIES} items"
            )));
        }
        let root = self
            .root
            .validate_as(max_media_bytes, EntityValidationContext::AtomicBundleRoot)?;
        if root.entity_type != "record" && root.entity_type != "care_plan" {
            return Err(ApiError::unprocessable(
                "bundle root type must be record or care_plan",
            ));
        }
        let mut media = Vec::with_capacity(self.media.len());
        let mut seen = BTreeSet::new();
        for raw in self.media {
            let entity =
                raw.validate_as(max_media_bytes, EntityValidationContext::AtomicBundleMedia)?;
            if entity.entity_type != "media" {
                return Err(ApiError::unprocessable(
                    "bundle media entities must have type media",
                ));
            }
            if !seen.insert(entity.client_uuid.clone()) {
                return Err(ApiError::unprocessable(
                    "bundle media client_uuid values must be unique",
                ));
            }
            media.push(entity);
        }
        // Live media must declare a positive byte_size so commit can size-check.
        for entity in &media {
            if entity.deleted_at.is_none() {
                let size = entity
                    .payload
                    .get("byte_size")
                    .and_then(Value::as_u64)
                    .unwrap_or(0);
                if size == 0 {
                    return Err(ApiError::unprocessable(
                        "live bundle media requires positive byte_size",
                    ));
                }
            }
        }
        Ok(ValidatedBundleStage {
            bundle_id: self.bundle_id.to_string(),
            root,
            media,
            generation: self.generation,
        })
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BundleCommitRequest {
    #[serde(default)]
    pub generation: Option<String>,
}

impl BundleCommitRequest {
    pub fn validate(&self) -> Result<(), ApiError> {
        validate_optional_nonempty_string(&self.generation, 128, "generation")
    }
}

fn validate_baby(payload: &mut Map<String, Value>) -> Result<(), ApiError> {
    require_keys(
        payload,
        &["nickname", "sex", "birthday", "avatar_media_uuid"],
    )?;
    allow_keys(
        payload,
        &[
            "nickname",
            "sex",
            "birthday",
            "sort_order",
            "avatar_media_uuid",
            "birth_weight_grams",
        ],
    )?;
    string(payload, "nickname", 1, 20)?;
    nullable_string(payload, "sex", 0, usize::MAX)?;
    date(payload, "birthday", false)?;
    nullable_uuid(payload, "avatar_media_uuid")?;
    optional_integer(payload, "sort_order", i64::MIN, i64::MAX)?;
    optional_integer(payload, "birth_weight_grams", 0, 100_000)?;
    payload.remove("sort_order");
    Ok(())
}

fn validate_record(payload: &Map<String, Value>) -> Result<(), ApiError> {
    require_keys(
        payload,
        &["baby_client_uuid", "type", "timestamp", "payload_json"],
    )?;
    allow_keys(
        payload,
        &[
            "baby_client_uuid",
            "type",
            "timestamp",
            "end_timestamp",
            "note",
            "payload_json",
            "schema_version",
            "created_by_membership_id",
        ],
    )?;
    uuid(payload, "baby_client_uuid")?;
    string(payload, "type", 1, 64)?;
    integer(payload, "timestamp", 0, i64::MAX)?;
    optional_integer(payload, "end_timestamp", 0, i64::MAX)?;
    optional_nullable_string(payload, "note", 0, 20_000)?;
    if !payload.get("payload_json").is_some_and(Value::is_object) {
        return Err(ApiError::unprocessable("payload_json must be an object"));
    }
    optional_integer(payload, "schema_version", 1, i64::MAX)?;
    optional_nullable_string(payload, "created_by_membership_id", 1, 64)?;
    Ok(())
}

fn validate_media(payload: &Map<String, Value>, max_media_bytes: usize) -> Result<(), ApiError> {
    require_keys(payload, &["kind"])?;
    allow_keys(
        payload,
        &[
            "kind",
            "record_client_uuid",
            "baby_client_uuid",
            "care_plan_client_uuid",
            "mime",
            "width",
            "height",
            "byte_size",
        ],
    )?;
    let kind = string_value(payload, "kind")?;
    if kind != "log" && kind != "avatar" {
        return Err(ApiError::unprocessable("media kind must be log or avatar"));
    }
    optional_nullable_uuid(payload, "record_client_uuid")?;
    optional_nullable_uuid(payload, "baby_client_uuid")?;
    optional_nullable_uuid(payload, "care_plan_client_uuid")?;
    optional_nullable_string(payload, "mime", 0, 255)?;
    let max_dimension = i64::from(i32::MAX);
    let max_byte_size = i64::try_from(max_media_bytes).unwrap_or(i64::MAX);
    optional_integer(payload, "width", 1, max_dimension)?;
    optional_integer(payload, "height", 1, max_dimension)?;
    optional_integer(payload, "byte_size", 1, max_byte_size)?;

    let record = optional_string_value(payload, "record_client_uuid")?;
    let baby = optional_string_value(payload, "baby_client_uuid")?;
    let care_plan = optional_string_value(payload, "care_plan_client_uuid")?;
    if kind == "log" && record.is_none() && care_plan.is_none() {
        return Err(ApiError::unprocessable(
            "log media requires record_client_uuid or care_plan_client_uuid",
        ));
    }
    if kind == "log" && record.is_some() && care_plan.is_some() {
        return Err(ApiError::unprocessable(
            "log media must not reference both record and care_plan",
        ));
    }
    if kind == "avatar" && baby.is_none() {
        return Err(ApiError::unprocessable(
            "avatar media requires baby_client_uuid",
        ));
    }
    if kind == "avatar" && (record.is_some() || care_plan.is_some()) {
        return Err(ApiError::unprocessable(
            "avatar media must not reference a record or care_plan",
        ));
    }
    Ok(())
}

/// Shared custom item definition (family catalog). Layout/order/slots are device-local
/// and must not appear here. Creator membership is stamped by the server on first insert.
fn validate_custom_item(payload: &mut Map<String, Value>) -> Result<(), ApiError> {
    require_keys(payload, &["name", "icon_slot"])?;
    allow_keys(payload, &["name", "icon_slot", "created_by_membership_id"])?;
    string(payload, "name", 1, 40)?;
    integer(payload, "icon_slot", 0, 7)?;
    // Client may omit or send a guess; server overwrites on insert and freezes later.
    optional_nullable_string(payload, "created_by_membership_id", 1, 64)?;
    Ok(())
}

/// Closed CarePlan wire schema for atomic-bundle roots.
/// Creator membership is stamped by the server on first insert (ACL); clients
/// may omit or send a guess that is overwritten.
fn validate_care_plan(payload: &mut Map<String, Value>) -> Result<(), ApiError> {
    require_keys(
        payload,
        &[
            "baby_client_uuid",
            "type",
            "scheduled_at",
            "scheduled_zone_id",
            "status",
            "payload_json",
        ],
    )?;
    allow_keys(
        payload,
        &[
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "scheduled_at",
            "scheduled_zone_id",
            "note",
            "payload_json",
            "schema_version",
            "status",
            "created_by_membership_id",
            "fulfilled_record_client_uuid",
            "fulfilled_at",
        ],
    )?;
    uuid(payload, "baby_client_uuid")?;
    string(payload, "type", 1, 64)?;
    optional_nullable_uuid(payload, "custom_item_client_uuid")?;
    integer(payload, "scheduled_at", 0, i64::MAX)?;
    string(payload, "scheduled_zone_id", 1, 64)?;
    optional_nullable_string(payload, "note", 0, 20_000)?;
    if !payload.get("payload_json").is_some_and(Value::is_object) {
        return Err(ApiError::unprocessable("payload_json must be an object"));
    }
    optional_integer(payload, "schema_version", 1, i64::MAX)?;
    let status = string_value(payload, "status")?;
    if status != "pending" && status != "missed" && status != "completed" && status != "skipped" {
        return Err(ApiError::unprocessable(
            "status must be pending, missed, completed, or skipped",
        ));
    }
    // Client may omit or forge; server freezes on insert and ignores later spoofs.
    optional_nullable_string(payload, "created_by_membership_id", 1, 64)?;
    optional_nullable_uuid(payload, "fulfilled_record_client_uuid")?;
    optional_integer(payload, "fulfilled_at", 0, i64::MAX)?;
    Ok(())
}

/// Fulfillment candidate for multi-member offline fulfills (ticket 23 freeze).
/// Submitter membership/role/confirmed_at are server-stamped; clients cannot
/// forge evidence. Winner selection is a later ticket — this only stores candidates.
fn validate_fulfillment_candidate(payload: &mut Map<String, Value>) -> Result<(), ApiError> {
    require_keys(payload, &["care_plan_client_uuid", "record_client_uuid"])?;
    allow_keys(
        payload,
        &[
            "care_plan_client_uuid",
            "record_client_uuid",
            "actual_timestamp",
            "submitter_membership_id",
            "submitter_role",
            "confirmed_at",
        ],
    )?;
    uuid(payload, "care_plan_client_uuid")?;
    uuid(payload, "record_client_uuid")?;
    optional_integer(payload, "actual_timestamp", 0, i64::MAX)?;
    optional_nullable_string(payload, "submitter_membership_id", 1, 64)?;
    optional_nullable_string(payload, "submitter_role", 1, 32)?;
    optional_integer(payload, "confirmed_at", 0, i64::MAX)?;
    Ok(())
}

fn require_keys(payload: &Map<String, Value>, required: &[&str]) -> Result<(), ApiError> {
    if let Some(key) = required.iter().find(|key| !payload.contains_key(**key)) {
        return Err(ApiError::unprocessable(format!("{key} is required")));
    }
    Ok(())
}

fn allow_keys(payload: &Map<String, Value>, allowed: &[&str]) -> Result<(), ApiError> {
    let allowed = allowed.iter().copied().collect::<BTreeSet<_>>();
    if let Some(key) = payload.keys().find(|key| !allowed.contains(key.as_str())) {
        return Err(ApiError::unprocessable(format!(
            "unexpected payload field: {key}"
        )));
    }
    Ok(())
}

fn string(payload: &Map<String, Value>, key: &str, min: usize, max: usize) -> Result<(), ApiError> {
    let value = string_value(payload, key)?;
    validate_length(value, min, max, key)
}

fn nullable_string(
    payload: &Map<String, Value>,
    key: &str,
    min: usize,
    max: usize,
) -> Result<(), ApiError> {
    match payload.get(key) {
        Some(Value::Null) => Ok(()),
        Some(Value::String(value)) => validate_length(value, min, max, key),
        _ => Err(ApiError::unprocessable(format!(
            "{key} must be a string or null"
        ))),
    }
}

fn optional_nullable_string(
    payload: &Map<String, Value>,
    key: &str,
    min: usize,
    max: usize,
) -> Result<(), ApiError> {
    match payload.get(key) {
        None | Some(Value::Null) => Ok(()),
        Some(Value::String(value)) => validate_length(value, min, max, key),
        _ => Err(ApiError::unprocessable(format!(
            "{key} must be a string or null"
        ))),
    }
}

fn integer(payload: &Map<String, Value>, key: &str, min: i64, max: i64) -> Result<(), ApiError> {
    let value = payload
        .get(key)
        .and_then(Value::as_i64)
        .ok_or_else(|| ApiError::unprocessable(format!("{key} must be an integer")))?;
    if !(min..=max).contains(&value) {
        return Err(ApiError::unprocessable(format!("{key} is out of range")));
    }
    Ok(())
}

fn optional_integer(
    payload: &Map<String, Value>,
    key: &str,
    min: i64,
    max: i64,
) -> Result<(), ApiError> {
    match payload.get(key) {
        None | Some(Value::Null) => Ok(()),
        Some(_) => integer(payload, key, min, max),
    }
}

fn uuid(payload: &Map<String, Value>, key: &str) -> Result<(), ApiError> {
    let value = string_value(payload, key)?;
    Uuid::parse_str(value)
        .map(|_| ())
        .map_err(|_| ApiError::unprocessable(format!("{key} must be a UUID")))
}

fn nullable_uuid(payload: &Map<String, Value>, key: &str) -> Result<(), ApiError> {
    match payload.get(key) {
        Some(Value::Null) => Ok(()),
        Some(Value::String(value)) => Uuid::parse_str(value)
            .map(|_| ())
            .map_err(|_| ApiError::unprocessable(format!("{key} must be a UUID or null"))),
        _ => Err(ApiError::unprocessable(format!(
            "{key} must be a UUID or null"
        ))),
    }
}

fn optional_nullable_uuid(payload: &Map<String, Value>, key: &str) -> Result<(), ApiError> {
    match payload.get(key) {
        None | Some(Value::Null) => Ok(()),
        Some(Value::String(value)) => Uuid::parse_str(value)
            .map(|_| ())
            .map_err(|_| ApiError::unprocessable(format!("{key} must be a UUID or null"))),
        _ => Err(ApiError::unprocessable(format!(
            "{key} must be a UUID or null"
        ))),
    }
}

fn date(payload: &Map<String, Value>, key: &str, nullable: bool) -> Result<(), ApiError> {
    match payload.get(key) {
        Some(Value::Null) if nullable => Ok(()),
        Some(Value::String(value)) => NaiveDate::parse_from_str(value, "%Y-%m-%d")
            .map(|_| ())
            .map_err(|_| ApiError::unprocessable(format!("{key} must be YYYY-MM-DD"))),
        _ => Err(ApiError::unprocessable(format!("{key} must be a date"))),
    }
}

fn string_value<'a>(payload: &'a Map<String, Value>, key: &str) -> Result<&'a str, ApiError> {
    payload
        .get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::unprocessable(format!("{key} must be a string")))
}

fn optional_string_value<'a>(
    payload: &'a Map<String, Value>,
    key: &str,
) -> Result<Option<&'a str>, ApiError> {
    match payload.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(value)) => Ok(Some(value)),
        _ => Err(ApiError::unprocessable(format!(
            "{key} must be a string or null"
        ))),
    }
}

fn validate_urlsafe(value: &str, min: usize, max: usize, message: &str) -> Result<(), ApiError> {
    if !(min..=max).contains(&value.len())
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
    {
        return Err(ApiError::unprocessable(message));
    }
    Ok(())
}

fn validate_required_string(value: &str, max: usize, field: &str) -> Result<(), ApiError> {
    validate_length(value, 1, max, field)
}

fn validate_optional_nonempty_string(
    value: &Option<String>,
    max: usize,
    field: &str,
) -> Result<(), ApiError> {
    if let Some(value) = value {
        validate_length(value, 1, max, field)?;
    }
    Ok(())
}

fn validate_length(value: &str, min: usize, max: usize, field: &str) -> Result<(), ApiError> {
    let length = value.chars().count();
    if !(min..=max).contains(&length) {
        return Err(ApiError::unprocessable(format!(
            "{field} length must be between {min} and {max}"
        )));
    }
    Ok(())
}
