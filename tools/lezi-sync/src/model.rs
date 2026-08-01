use std::collections::BTreeSet;

use chrono::NaiveDate;
use serde::Deserialize;
use serde_json::{Map, Number, Value};
use unicode_normalization::UnicodeNormalization;
use uuid::Uuid;

use crate::ApiError;

/// Device-local UI placeholder. Must never be accepted as a real family name.
pub(crate) const LOCAL_DEVICE_DISPLAY_NAME: &str = "我（本机）";

/// Internal CarePlan `note` prefix for family-shared next-feed intent (v1).
/// Not a user-facing note format. Production value is hardcoded; the versioned
/// build/test contract is `config/next-feed-plan-marker.v1.json` (Kotlin + Rust).
pub(crate) const NEXT_FEED_PLAN_MARKER: &str = "[[lezi:next-feed:v1]]";

/// True when [note] carries the next-feed protocol marker as a prefix.
pub(crate) fn is_next_feed_plan_note(note: Option<&str>) -> bool {
    note.is_some_and(|value| value.starts_with(NEXT_FEED_PLAN_MARKER))
}

/// Visible remainder after stripping the next-feed marker (Kotlin-aligned).
/// Only meaningful when [is_next_feed_plan_note] is true.
#[cfg(test)]
fn visible_next_feed_note(note: &str) -> Option<&str> {
    note.strip_prefix(NEXT_FEED_PLAN_MARKER)
        .map(str::trim_start)
        .filter(|rest| !rest.is_empty())
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FamilyCreateRequest {
    pub create_request_id: String,
    pub display_name: String,
    pub device_name: String,
    pub family_name: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RefreshSessionRequest {
    pub refresh_token: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct OwnerLoginRequest {
    pub login_request_id: String,
    pub device_name: String,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MemberLoginRequest {
    pub display_name: String,
    pub device_name: String,
}

impl MemberLoginRequest {
    pub fn validate(&self) -> Result<(String, String, String), ApiError> {
        let display_name = require_display_name(Some(&self.display_name))?;
        let display_name_key = normalized_display_name_key(&display_name);
        let device_name = require_device_name(&self.device_name)?;
        Ok((display_name, display_name_key, device_name))
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PendingSecretRequest {
    pub pending_secret: String,
}

impl PendingSecretRequest {
    pub fn validate(&self) -> Result<&str, ApiError> {
        validate_url_safe_secret(&self.pending_secret, "pending_secret")
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BindExistingMemberRequest {
    pub membership_id: String,
}

impl BindExistingMemberRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        Uuid::parse_str(self.membership_id.trim())
            .map(|value| value.to_string())
            .map_err(|_| ApiError::unprocessable("membership_id must be a UUID"))
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CreateMemberLoginGrantRequest {
    pub membership_id: String,
}

impl CreateMemberLoginGrantRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        Uuid::parse_str(self.membership_id.trim())
            .map(|value| value.to_string())
            .map_err(|_| ApiError::unprocessable("membership_id must be a UUID"))
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ClaimMemberLoginGrantRequest {
    pub grant: String,
    pub device_name: String,
}

impl ClaimMemberLoginGrantRequest {
    pub fn validate(&self) -> Result<(&str, String), ApiError> {
        Ok((
            validate_url_safe_secret(&self.grant, "grant")?,
            require_device_name(&self.device_name)?,
        ))
    }
}

impl OwnerLoginRequest {
    pub fn validate(&self) -> Result<(String, String), ApiError> {
        validate_urlsafe(
            &self.login_request_id,
            32,
            128,
            "login_request_id must be 32-128 URL-safe characters",
        )?;
        Ok((
            self.login_request_id.clone(),
            require_device_name(&self.device_name)?,
        ))
    }
}

impl RefreshSessionRequest {
    pub fn validate(&self) -> Result<&str, ApiError> {
        let token = self.refresh_token.trim();
        if token.is_empty() || token.len() > 512 {
            return Err(ApiError::unprocessable("refresh_token is required"));
        }
        Ok(token)
    }
}

impl FamilyCreateRequest {
    /// Returns the canonical Owner membership name, family name and device name.
    pub fn validate(&self) -> Result<(String, String, String), ApiError> {
        validate_urlsafe(
            &self.create_request_id,
            32,
            128,
            "create_request_id must be 32-128 URL-safe characters",
        )?;
        let display_name = require_display_name(Some(&self.display_name))?;
        let family_name = normalize_family_name(Some(&self.family_name))?
            .ok_or_else(|| ApiError::unprocessable("family_name is required"))?;
        let device_name = require_device_name(&self.device_name)?;
        Ok((display_name, family_name, device_name))
    }
}

pub(crate) fn require_device_name(value: &str) -> Result<String, ApiError> {
    if value
        .chars()
        .any(|character| character.is_control() || is_bidirectional_control(character))
    {
        return Err(ApiError::unprocessable(
            "device_name must not contain control or bidirectional formatting characters",
        ));
    }
    let value = value
        .nfkc()
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    validate_required_string(&value, 128, "device_name")?;
    Ok(value)
}

pub(crate) fn normalized_device_name_key(value: &str) -> String {
    value.nfkc().flat_map(char::to_lowercase).collect()
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct EmptyRequest {}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DeleteFamilyRequest {
    pub family_name: String,
}

impl DeleteFamilyRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        normalize_family_name(Some(&self.family_name))?
            .ok_or_else(|| ApiError::unprocessable("family_name is required"))
    }
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UpdateDisplayNameRequest {
    pub display_name: String,
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UpdateDeviceNameRequest {
    pub device_name: String,
}

impl UpdateDeviceNameRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        require_device_name(&self.device_name)
    }
}

impl UpdateDisplayNameRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        require_display_name(Some(self.display_name.as_str()))
    }
}

/// Owner-only rename of the shared family name. Current wire requires a name.
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RenameFamilyRequest {
    pub family_name: String,
}

impl RenameFamilyRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        normalize_family_name(Some(&self.family_name))?
            .ok_or_else(|| ApiError::unprocessable("family_name must be a non-empty family name"))
    }
}

/// Owner-only removal of another active membership (not self, not owner).
#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct RemoveMemberRequest {
    pub membership_id: String,
}

impl RemoveMemberRequest {
    pub fn validate(&self) -> Result<String, ApiError> {
        let id = self.membership_id.trim();
        if id.is_empty() || id.len() > 64 {
            return Err(ApiError::unprocessable(
                "membership_id must be a non-empty membership identifier",
            ));
        }
        // Server mints UUID strings; reject control characters / whitespace-only noise.
        if id.chars().any(|c| c.is_control() || c.is_whitespace()) {
            return Err(ApiError::unprocessable("membership_id is invalid"));
        }
        Ok(id.to_owned())
    }
}

/// Product-required family 称呼 for create / member request / self-rename.
///
/// Blank, whitespace-only, and the device-local placeholder 「我（本机）」 all
/// return 422 — never silently stored as null.
pub(crate) fn require_display_name(value: Option<&str>) -> Result<String, ApiError> {
    match normalize_display_name(value)? {
        Some(name)
            if normalized_display_name_key(&name)
                == normalized_display_name_key(LOCAL_DEVICE_DISPLAY_NAME) =>
        {
            Err(ApiError::unprocessable(
                "display_name must not be the local device placeholder",
            ))
        }
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
    let value = value
        .nfkc()
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if value.is_empty() {
        return Ok(None);
    }
    validate_length(&value, 1, 128, "display_name")?;
    Ok(Some(value))
}

pub(crate) fn normalized_display_name_key(value: &str) -> String {
    value.nfkc().flat_map(char::to_lowercase).collect()
}

fn validate_url_safe_secret<'a>(value: &'a str, field: &str) -> Result<&'a str, ApiError> {
    if !(32..=128).contains(&value.len())
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'_')
    {
        return Err(ApiError::unprocessable(format!(
            "{field} must be 32-128 URL-safe characters"
        )));
    }
    Ok(value)
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
    /// Validate an entity for an atomic-bundle root or media manifest entry.
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
            (EntityValidationContext::AtomicBundleRoot, "baby") => {
                validate_baby(&mut self.payload)?
            }
            (EntityValidationContext::AtomicBundleMedia, "media") => validate_media(
                &mut self.payload,
                max_media_bytes,
                self.deleted_at.is_some(),
            )?,
            (EntityValidationContext::AtomicBundleRoot, "custom_item") => {
                validate_custom_item(&mut self.payload)?
            }
            (EntityValidationContext::AtomicBundleRoot, "fulfillment_candidate") => {
                validate_fulfillment_candidate(&mut self.payload)?
            }
            (EntityValidationContext::AtomicBundleRoot, "record") => {
                validate_record(&mut self.payload)?
            }
            (EntityValidationContext::AtomicBundleRoot, "care_plan") => {
                validate_care_plan(&mut self.payload)?
            }
            (EntityValidationContext::AtomicBundleRoot, _) => {
                return Err(ApiError::unprocessable(
                    "bundle root type must be record, care_plan, baby, custom_item, or fulfillment_candidate",
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
    /// Root of an atomic bundle (every current publishable entity except `media`).
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
    pub generation: String,
}

pub struct ValidatedBundleStage {
    pub bundle_id: String,
    pub root: Entity,
    pub media: Vec<Entity>,
    pub generation: String,
}

impl BundleStageRequest {
    pub fn validate(self, max_media_bytes: usize) -> Result<ValidatedBundleStage, ApiError> {
        validate_required_string(&self.generation, 128, "generation")?;
        if self.media.len() > MAX_BUNDLE_MEDIA_ENTITIES {
            return Err(ApiError::unprocessable(format!(
                "bundle media must contain at most {MAX_BUNDLE_MEDIA_ENTITIES} items"
            )));
        }
        let root = self
            .root
            .validate_as(max_media_bytes, EntityValidationContext::AtomicBundleRoot)?;
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
        validate_bundle_media_for_root(&root, &media)?;
        Ok(ValidatedBundleStage {
            bundle_id: self.bundle_id.to_string(),
            root,
            media,
            generation: self.generation,
        })
    }
}

/// Shared by wire bundle validation and offline v3→current migrator.
pub(crate) fn validate_bundle_media_for_root(
    root: &Entity,
    media: &[Entity],
) -> Result<(), ApiError> {
    if matches!(
        root.entity_type.as_str(),
        "custom_item" | "fulfillment_candidate"
    ) && !media.is_empty()
    {
        return Err(ApiError::unprocessable(format!(
            "{} bundle root must not contain media",
            root.entity_type
        )));
    }

    let root_baby = root.payload.get("baby_client_uuid").and_then(Value::as_str);
    for entity in media {
        let kind = entity.payload.get("kind").and_then(Value::as_str);
        let record = entity
            .payload
            .get("record_client_uuid")
            .and_then(Value::as_str);
        let baby = entity
            .payload
            .get("baby_client_uuid")
            .and_then(Value::as_str);
        let care_plan = entity
            .payload
            .get("care_plan_client_uuid")
            .and_then(Value::as_str);
        let matches_root = match root.entity_type.as_str() {
            "record" => {
                kind == Some("log")
                    && record == Some(root.client_uuid.as_str())
                    && care_plan.is_none()
                    && baby.is_none_or(|value| Some(value) == root_baby)
            }
            "care_plan" => {
                kind == Some("log")
                    && care_plan == Some(root.client_uuid.as_str())
                    && record.is_none()
                    && baby.is_none_or(|value| Some(value) == root_baby)
            }
            "baby" => {
                kind == Some("avatar")
                    && baby == Some(root.client_uuid.as_str())
                    && record.is_none()
                    && care_plan.is_none()
            }
            "custom_item" | "fulfillment_candidate" => false,
            _ => return Err(ApiError::unprocessable("unsupported bundle root type")),
        };
        if !matches_root {
            return Err(ApiError::unprocessable(format!(
                "bundle media kind and association must match {} root",
                root.entity_type
            )));
        }
    }
    Ok(())
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct BundleCommitRequest {
    pub generation: String,
}

impl BundleCommitRequest {
    pub fn validate(&self) -> Result<(), ApiError> {
        validate_required_string(&self.generation, 128, "generation")
    }
}

fn validate_baby(payload: &mut Map<String, Value>) -> Result<(), ApiError> {
    payload
        .entry("birth_weight_grams".to_owned())
        .or_insert(Value::Null);
    require_keys(
        payload,
        &[
            "nickname",
            "sex",
            "birthday",
            "avatar_media_uuid",
            "birth_weight_grams",
        ],
    )?;
    allow_keys(
        payload,
        &[
            "nickname",
            "sex",
            "birthday",
            "avatar_media_uuid",
            "birth_weight_grams",
        ],
    )?;
    trimmed_nonblank_string(payload, "nickname", 20)?;
    nullable_string(payload, "sex", 0, usize::MAX)?;
    if let Some(sex) = payload.get("sex").and_then(Value::as_str) {
        if sex != "female" && sex != "male" {
            return Err(ApiError::unprocessable("sex must be female, male, or null"));
        }
    }
    date(payload, "birthday", false)?;
    nullable_uuid(payload, "avatar_media_uuid")?;
    optional_integer(payload, "birth_weight_grams", 0, 100_000)?;
    Ok(())
}

const CURRENT_RECORD_TYPES: &[&str] = &[
    "nursing",
    "formula",
    "pumped_feed",
    "pump_express",
    "pee",
    "poop",
    "both_diaper",
    "sleep",
    "temperature",
    "diary",
    "bath",
    "walk",
    "cough",
    "rash",
    "vomit",
    "injury",
    "medicine",
    "hospital",
    "height",
    "weight",
    "baby_food",
    "snack",
    "drink",
    "head",
    "chest",
    "foot_size",
    "vaccine",
    "custom",
];

fn current_record_type(payload: &Map<String, Value>) -> Result<&str, ApiError> {
    let record_type = string_value(payload, "type")?;
    if !CURRENT_RECORD_TYPES.contains(&record_type) {
        return Err(ApiError::unprocessable(
            "type must be a current record type",
        ));
    }
    Ok(record_type)
}

fn validate_record(payload: &mut Map<String, Value>) -> Result<(), ApiError> {
    require_keys(
        payload,
        &[
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "end_timestamp",
            "note",
            "payload_json",
            "schema_version",
        ],
    )?;
    allow_keys(
        payload,
        &[
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "end_timestamp",
            "note",
            "payload_json",
            "schema_version",
            "created_by_membership_id",
        ],
    )?;
    uuid(payload, "baby_client_uuid")?;
    let record_type = current_record_type(payload)?.to_owned();
    optional_nullable_uuid(payload, "custom_item_client_uuid")?;
    integer(payload, "timestamp", 0, i64::MAX)?;
    optional_integer(payload, "end_timestamp", 0, i64::MAX)?;
    let timestamp = payload["timestamp"].as_i64().expect("validated timestamp");
    if payload
        .get("end_timestamp")
        .and_then(Value::as_i64)
        .is_some_and(|end| end < timestamp)
    {
        return Err(ApiError::unprocessable(
            "end_timestamp must not be before timestamp",
        ));
    }
    optional_nullable_string(payload, "note", 0, 20_000)?;
    validate_current_payload_json(&record_type, payload.get_mut("payload_json"), false)?;
    integer(payload, "schema_version", 2, 2)?;
    optional_nullable_string(payload, "created_by_membership_id", 1, 64)?;
    Ok(())
}

fn validate_media(
    payload: &mut Map<String, Value>,
    max_media_bytes: usize,
    is_tombstone: bool,
) -> Result<(), ApiError> {
    for key in [
        "record_client_uuid",
        "baby_client_uuid",
        "care_plan_client_uuid",
        "mime",
        "width",
        "height",
    ] {
        payload.entry(key.to_owned()).or_insert(Value::Null);
    }
    if is_tombstone {
        payload
            .entry("byte_size".to_owned())
            .or_insert_with(|| Value::Number(0.into()));
    }
    require_keys(
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
    integer(
        payload,
        "byte_size",
        if is_tombstone { 0 } else { 1 },
        max_byte_size,
    )?;

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
    trimmed_nonblank_string(payload, "name", 40)?;
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
            "custom_item_client_uuid",
            "scheduled_at",
            "scheduled_zone_id",
            "note",
            "status",
            "payload_json",
            "schema_version",
            "created_by_membership_id",
            "fulfilled_record_client_uuid",
            "fulfilled_at",
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
    let record_type = current_record_type(payload)?.to_owned();
    optional_nullable_uuid(payload, "custom_item_client_uuid")?;
    integer(payload, "scheduled_at", 0, i64::MAX)?;
    string(payload, "scheduled_zone_id", 1, 64)?;
    let scheduled_zone_id = string_value(payload, "scheduled_zone_id")?;
    if !is_android_zone_id(scheduled_zone_id) {
        return Err(ApiError::unprocessable(
            "scheduled_zone_id must be a current Android ZoneId",
        ));
    }
    optional_nullable_string(payload, "note", 0, 20_000)?;
    let allow_intent_only_feed =
        is_next_feed_plan_note(payload.get("note").and_then(Value::as_str));
    validate_current_payload_json(
        &record_type,
        payload.get_mut("payload_json"),
        allow_intent_only_feed,
    )?;
    integer(payload, "schema_version", 2, 2)?;
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
    // Bidirectional invariant: completed ⇔ full pair; both fields both-null or
    // both-set. Rejects complete-then-bind and non-completed pair carriage.
    validate_care_plan_fulfillment_pair(payload, status)?;
    Ok(())
}

/// Wire invariant failure for CarePlan `status` ↔ fulfillment pair.
/// Shared by model validation and store push defense-in-depth so detail text
/// and both-or-neither edge handling cannot drift.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum CarePlanFulfillmentPairIssue {
    Partial,
    CompletedMissing,
    NonCompletedCarries,
}

impl CarePlanFulfillmentPairIssue {
    pub(crate) const fn detail(self) -> &'static str {
        match self {
            Self::Partial => {
                "fulfilled_record_client_uuid and fulfilled_at must both be set or both null"
            }
            Self::CompletedMissing => {
                "completed care plan requires fulfilled_record_client_uuid and fulfilled_at"
            }
            Self::NonCompletedCarries => {
                "only completed care plans may carry fulfilled_record_client_uuid and fulfilled_at"
            }
        }
    }
}

/// Pure status↔pair both-or-neither invariant.
///
/// Callers decide field presence after seam-specific type/empty handling
/// (`model` rejects empty UUID via parse; `store` treats empty string as absent).
pub(crate) fn care_plan_fulfillment_pair_issue(
    status: &str,
    has_record: bool,
    has_at: bool,
) -> Option<CarePlanFulfillmentPairIssue> {
    if has_record != has_at {
        return Some(CarePlanFulfillmentPairIssue::Partial);
    }
    if status == "completed" {
        if !has_record {
            return Some(CarePlanFulfillmentPairIssue::CompletedMissing);
        }
    } else if has_record {
        return Some(CarePlanFulfillmentPairIssue::NonCompletedCarries);
    }
    None
}

/// CarePlan wire: `fulfilled_record_client_uuid` + `fulfilled_at` must be both
/// null or both non-null. `status=completed` requires the full pair; any other
/// status must carry neither field (fail closed).
fn validate_care_plan_fulfillment_pair(
    payload: &Map<String, Value>,
    status: &str,
) -> Result<(), ApiError> {
    // Presence only — UUID/integer type already checked by optional_* above.
    let has_record = matches!(
        payload.get("fulfilled_record_client_uuid"),
        Some(Value::String(_))
    );
    let has_at = payload
        .get("fulfilled_at")
        .and_then(Value::as_i64)
        .is_some();
    if let Some(issue) = care_plan_fulfillment_pair_issue(status, has_record, has_at) {
        return Err(ApiError::unprocessable(issue.detail()));
    }
    Ok(())
}

fn validate_current_payload_json(
    record_type: &str,
    payload_json: Option<&mut Value>,
    allow_intent_only_feed: bool,
) -> Result<(), ApiError> {
    let payload = payload_json
        .and_then(Value::as_object_mut)
        .ok_or_else(|| ApiError::unprocessable("payload_json must be an object"))?;
    match record_type {
        "nursing" => {
            require_keys(payload, &["left_min", "right_min", "order", "record_mode"])?;
            allow_keys(
                payload,
                &["left_min", "right_min", "order", "amount_ml", "record_mode"],
            )?;
            let left = nested_integer(payload, "left_min", 0, i64::from(i32::MAX))?;
            let right = nested_integer(payload, "right_min", 0, i64::from(i32::MAX))?;
            if left.saturating_add(right) <= 0 && !allow_intent_only_feed {
                return Err(ApiError::unprocessable(
                    "payload_json nursing duration must be positive",
                ));
            }
            nested_string_in(payload, "order", &["L", "R", "LR", "RL"])?;
            nested_optional_integer(
                payload,
                "amount_ml",
                i64::from(i32::MIN),
                i64::from(i32::MAX),
            )?;
            nested_string_in(payload, "record_mode", &["start", "end"])?;
        }
        "formula" => {
            require_keys(payload, &["amount_ml"])?;
            allow_keys(payload, &["amount_ml", "prepared_ml", "duration_min"])?;
            nested_integer(
                payload,
                "amount_ml",
                if allow_intent_only_feed { 0 } else { 1 },
                999,
            )?;
            nested_optional_integer(payload, "prepared_ml", 0, 999)?;
            nested_optional_integer(payload, "duration_min", 0, 1_440)?;
        }
        "pumped_feed" | "pump_express" => {
            require_keys(payload, &["amount_ml"])?;
            allow_keys(payload, &["amount_ml"])?;
            nested_integer(
                payload,
                "amount_ml",
                if allow_intent_only_feed && record_type == "pumped_feed" {
                    0
                } else {
                    1
                },
                999,
            )?;
        }
        "pee" => {
            allow_keys(payload, &["pee_amount"])?;
            nested_optional_integer(payload, "pee_amount", 1, 3)?;
            payload
                .entry("pee_amount".to_owned())
                .or_insert_with(|| Value::Number(2.into()));
        }
        "poop" => {
            allow_keys(
                payload,
                &["stool_amount", "stool_consistency", "stool_color"],
            )?;
            nested_optional_integer(payload, "stool_amount", 1, 4)?;
            nested_optional_integer(payload, "stool_consistency", 1, 4)?;
            nested_optional_integer(payload, "stool_color", 0, 7)?;
            canonicalize_stool_defaults(payload);
        }
        "both_diaper" => {
            allow_keys(
                payload,
                &[
                    "pee_amount",
                    "stool_amount",
                    "stool_consistency",
                    "stool_color",
                ],
            )?;
            nested_optional_integer(payload, "pee_amount", 1, 3)?;
            nested_optional_integer(payload, "stool_amount", 1, 4)?;
            nested_optional_integer(payload, "stool_consistency", 1, 4)?;
            nested_optional_integer(payload, "stool_color", 0, 7)?;
            payload
                .entry("pee_amount".to_owned())
                .or_insert_with(|| Value::Number(2.into()));
            canonicalize_stool_defaults(payload);
        }
        "sleep" => {
            require_keys(payload, &["anomaly_flag"])?;
            allow_keys(payload, &["is_nap", "anomaly_flag"])?;
            nested_optional_boolean(payload, "is_nap")?;
            nested_boolean(payload, "anomaly_flag")?;
            payload
                .entry("is_nap".to_owned())
                .or_insert(Value::Bool(false));
        }
        "temperature" => {
            require_keys(payload, &["celsius"])?;
            allow_keys(payload, &["celsius"])?;
            let celsius = nested_number(payload, "celsius", 34.0, 43.0)?;
            payload.insert(
                "celsius".to_owned(),
                Value::Number(Number::from_f64(celsius).expect("finite temperature")),
            );
        }
        "diary" => {
            require_keys(payload, &["body"])?;
            allow_keys(payload, &["body"])?;
            nested_nonblank_string(payload, "body")?;
        }
        "bath" | "walk" => allow_keys(payload, &[])?,
        "cough" | "rash" | "vomit" | "injury" => {
            require_keys(payload, &["severity"])?;
            allow_keys(payload, &["severity", "description"])?;
            nested_integer(payload, "severity", 1, 3)?;
            nested_optional_string(payload, "description")?;
        }
        "medicine" => {
            require_keys(payload, &["name"])?;
            allow_keys(payload, &["name", "dose"])?;
            nested_nonblank_string(payload, "name")?;
            nested_optional_string(payload, "dose")?;
        }
        "hospital" => {
            require_keys(payload, &["reason"])?;
            allow_keys(payload, &["reason", "advice"])?;
            nested_nonblank_string(payload, "reason")?;
            nested_optional_string(payload, "advice")?;
        }
        "height" | "weight" | "head" | "chest" | "foot_size" => {
            require_keys(payload, &["value", "unit"])?;
            allow_keys(payload, &["value", "unit"])?;
            let value = nested_number(payload, "value", f64::MIN_POSITIVE, f64::MAX)?;
            let unit = nested_string(payload, "unit")?;
            let display_value = if record_type == "weight" && unit == "g" {
                value / 1_000.0
            } else {
                value
            };
            let maximum = if record_type == "weight" {
                100.0
            } else {
                250.0
            };
            if display_value > maximum {
                return Err(ApiError::unprocessable(
                    "payload_json measurement value is out of range",
                ));
            }
            payload.insert(
                "value".to_owned(),
                if value.fract() == 0.0 {
                    Value::Number((value as i64).into())
                } else {
                    Value::Number(Number::from_f64(value).expect("finite measurement"))
                },
            );
        }
        "baby_food" | "snack" | "drink" => {
            require_keys(payload, &["content"])?;
            allow_keys(payload, &["content", "amount"])?;
            nested_nonblank_string(payload, "content")?;
            nested_optional_string(payload, "amount")?;
        }
        "vaccine" => {
            require_keys(payload, &["name"])?;
            allow_keys(payload, &["name", "batch"])?;
            nested_nonblank_string(payload, "name")?;
            nested_optional_string(payload, "batch")?;
        }
        "custom" => {
            require_keys(payload, &["title"])?;
            allow_keys(payload, &["title", "detail", "icon_slot"])?;
            nested_nonblank_string(payload, "title")?;
            nested_optional_string(payload, "detail")?;
            nested_optional_integer(
                payload,
                "icon_slot",
                i64::from(i32::MIN),
                i64::from(i32::MAX),
            )?;
        }
        _ => {
            return Err(ApiError::unprocessable(
                "type must be a current record type",
            ));
        }
    }
    Ok(())
}

fn nested_integer(
    payload: &Map<String, Value>,
    key: &str,
    min: i64,
    max: i64,
) -> Result<i64, ApiError> {
    let value = payload
        .get(key)
        .and_then(Value::as_i64)
        .ok_or_else(|| ApiError::unprocessable(format!("payload_json.{key} must be an integer")))?;
    if !(min..=max).contains(&value) {
        return Err(ApiError::unprocessable(format!(
            "payload_json.{key} is out of range"
        )));
    }
    Ok(value)
}

fn nested_optional_integer(
    payload: &Map<String, Value>,
    key: &str,
    min: i64,
    max: i64,
) -> Result<(), ApiError> {
    if payload.contains_key(key) {
        nested_integer(payload, key, min, max)?;
    }
    Ok(())
}

fn nested_number(
    payload: &Map<String, Value>,
    key: &str,
    min: f64,
    max: f64,
) -> Result<f64, ApiError> {
    let value = payload
        .get(key)
        .and_then(Value::as_f64)
        .filter(|value| value.is_finite())
        .ok_or_else(|| ApiError::unprocessable(format!("payload_json.{key} must be a number")))?;
    if !(min..=max).contains(&value) {
        return Err(ApiError::unprocessable(format!(
            "payload_json.{key} is out of range"
        )));
    }
    Ok(value)
}

fn nested_string<'a>(payload: &'a Map<String, Value>, key: &str) -> Result<&'a str, ApiError> {
    payload
        .get(key)
        .and_then(Value::as_str)
        .ok_or_else(|| ApiError::unprocessable(format!("payload_json.{key} must be a string")))
}

fn nested_nonblank_string(payload: &Map<String, Value>, key: &str) -> Result<(), ApiError> {
    if nested_string(payload, key)?.trim().is_empty() {
        return Err(ApiError::unprocessable(format!(
            "payload_json.{key} must not be blank"
        )));
    }
    Ok(())
}

fn nested_optional_string(payload: &Map<String, Value>, key: &str) -> Result<(), ApiError> {
    if payload.contains_key(key) {
        nested_string(payload, key)?;
    }
    Ok(())
}

fn nested_string_in(
    payload: &Map<String, Value>,
    key: &str,
    allowed: &[&str],
) -> Result<(), ApiError> {
    let value = nested_string(payload, key)?;
    if !allowed.contains(&value) {
        return Err(ApiError::unprocessable(format!(
            "payload_json.{key} has an unsupported value"
        )));
    }
    Ok(())
}

fn nested_boolean(payload: &Map<String, Value>, key: &str) -> Result<(), ApiError> {
    if !payload.get(key).is_some_and(Value::is_boolean) {
        return Err(ApiError::unprocessable(format!(
            "payload_json.{key} must be a boolean"
        )));
    }
    Ok(())
}

fn nested_optional_boolean(payload: &Map<String, Value>, key: &str) -> Result<(), ApiError> {
    if payload.contains_key(key) {
        nested_boolean(payload, key)?;
    }
    Ok(())
}

fn canonicalize_stool_defaults(payload: &mut Map<String, Value>) {
    for (key, value) in [
        ("stool_amount", 3),
        ("stool_consistency", 3),
        ("stool_color", 0),
    ] {
        payload
            .entry(key.to_owned())
            .or_insert_with(|| Value::Number(value.into()));
    }
}

/// Fulfillment candidate for multi-member offline fulfills.
/// Submitter membership/role/confirmed_at are server-stamped on first accept;
/// plan/record/actual_timestamp freeze with those stamps as immutable evidence.
/// Clients cannot forge or splice-rewrite. Winner selection is client-side.
fn validate_fulfillment_candidate(payload: &mut Map<String, Value>) -> Result<(), ApiError> {
    payload
        .entry("actual_timestamp".to_owned())
        .or_insert(Value::Null);
    require_keys(
        payload,
        &[
            "care_plan_client_uuid",
            "record_client_uuid",
            "actual_timestamp",
        ],
    )?;
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

fn trimmed_nonblank_string(
    payload: &Map<String, Value>,
    key: &str,
    max: usize,
) -> Result<(), ApiError> {
    string(payload, key, 1, max)?;
    let value = string_value(payload, key)?;
    if value.trim().is_empty() {
        return Err(ApiError::unprocessable(format!("{key} must not be blank")));
    }
    Ok(())
}

fn is_android_zone_id(value: &str) -> bool {
    value.parse::<chrono_tz::Tz>().is_ok()
        || value == "Z"
        || ["UTC", "GMT", "UT"]
            .iter()
            .find_map(|prefix| value.strip_prefix(prefix))
            .is_some_and(is_valid_zone_offset)
        || is_valid_zone_offset(value)
}

fn is_valid_zone_offset(value: &str) -> bool {
    let Some(signless) = value.strip_prefix('+').or_else(|| value.strip_prefix('-')) else {
        return false;
    };
    let digits = signless.replace(':', "");
    if digits.is_empty() || !digits.bytes().all(|byte| byte.is_ascii_digit()) {
        return false;
    }
    let (hours, minutes, seconds) = match digits.len() {
        1 | 2 => (&digits[..], "0", "0"),
        3 | 4 => (
            &digits[..digits.len() - 2],
            &digits[digits.len() - 2..],
            "0",
        ),
        5 | 6 => (
            &digits[..digits.len() - 4],
            &digits[digits.len() - 4..digits.len() - 2],
            &digits[digits.len() - 2..],
        ),
        _ => return false,
    };
    let Ok(hours) = hours.parse::<u8>() else {
        return false;
    };
    let Ok(minutes) = minutes.parse::<u8>() else {
        return false;
    };
    let Ok(seconds) = seconds.parse::<u8>() else {
        return false;
    };
    hours <= 18 && minutes <= 59 && seconds <= 59 && (hours < 18 || (minutes == 0 && seconds == 0))
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

fn validate_length(value: &str, min: usize, max: usize, field: &str) -> Result<(), ApiError> {
    let length = value.chars().count();
    if !(min..=max).contains(&length) {
        return Err(ApiError::unprocessable(format!(
            "{field} length must be between {min} and {max}"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeSet;

    use serde_json::{json, Value};
    use uuid::Uuid;

    use super::{
        is_next_feed_plan_note, visible_next_feed_note, EntityValidationContext, RawEntity,
        NEXT_FEED_PLAN_MARKER,
    };

    fn entity(entity_type: &str, payload: Value) -> RawEntity {
        RawEntity {
            entity_type: entity_type.to_owned(),
            client_uuid: Uuid::new_v4(),
            updated_at: 1,
            deleted_at: None,
            payload: payload.as_object().unwrap().clone(),
        }
    }

    fn record(payload: Value) -> RawEntity {
        RawEntity {
            entity_type: "record".to_owned(),
            client_uuid: Uuid::new_v4(),
            updated_at: 1,
            deleted_at: None,
            payload: payload.as_object().unwrap().clone(),
        }
    }

    fn record_payload() -> Value {
        json!({
            "baby_client_uuid": Uuid::new_v4(),
            "type": "formula",
            "custom_item_client_uuid": null,
            "timestamp": 1,
            "end_timestamp": null,
            "note": null,
            "payload_json": {"amount_ml": 120},
            "schema_version": 2,
        })
    }

    fn care_plan(payload: Value) -> RawEntity {
        RawEntity {
            entity_type: "care_plan".to_owned(),
            client_uuid: Uuid::new_v4(),
            updated_at: 1,
            deleted_at: None,
            payload: payload.as_object().unwrap().clone(),
        }
    }

    fn care_plan_payload() -> Value {
        json!({
            "baby_client_uuid": Uuid::new_v4(),
            "type": "formula",
            "custom_item_client_uuid": null,
            "scheduled_at": 1,
            "scheduled_zone_id": "Asia/Shanghai",
            "note": null,
            "payload_json": {"amount_ml": 120},
            "schema_version": 2,
            "status": "pending",
            "created_by_membership_id": null,
            "fulfilled_record_client_uuid": null,
            "fulfilled_at": null,
        })
    }

    fn valid_nested_payload(record_type: &str) -> Value {
        match record_type {
            "nursing" => json!({
                "left_min": 10,
                "right_min": 0,
                "order": "L",
                "record_mode": "end",
            }),
            "formula" => json!({"amount_ml": 120, "prepared_ml": 150, "duration_min": 15}),
            "pumped_feed" | "pump_express" => json!({"amount_ml": 90}),
            "pee" => json!({"pee_amount": 2}),
            "poop" => json!({
                "stool_amount": 3,
                "stool_consistency": 3,
                "stool_color": 0,
            }),
            "both_diaper" => json!({
                "pee_amount": 2,
                "stool_amount": 3,
                "stool_consistency": 3,
                "stool_color": 0,
            }),
            "sleep" => json!({"is_nap": true, "anomaly_flag": false}),
            "temperature" => json!({"celsius": 36.7}),
            "diary" => json!({"body": "今天很好"}),
            "bath" | "walk" => json!({}),
            "cough" | "rash" | "vomit" | "injury" => {
                json!({"severity": 2, "description": "轻微"})
            }
            "medicine" => json!({"name": "维生素 D", "dose": "一滴"}),
            "hospital" => json!({"reason": "复诊", "advice": "观察"}),
            "height" | "head" | "chest" | "foot_size" => {
                json!({"value": 65.5, "unit": "cm"})
            }
            "weight" => json!({"value": 6500, "unit": "g"}),
            "baby_food" | "snack" | "drink" => {
                json!({"content": "香蕉", "amount": "半根"})
            }
            "vaccine" => json!({"name": "乙肝", "batch": "A001"}),
            "custom" => json!({"title": "抚触", "detail": "睡前", "icon_slot": 2}),
            _ => panic!("missing test payload for {record_type}"),
        }
    }

    #[test]
    fn record_requires_schema_version_two() {
        record(record_payload())
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();

        for invalid in [Value::Null, json!(1), json!(3), json!("2")] {
            let mut payload = record_payload();
            payload["schema_version"] = invalid;
            assert!(record(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }

        let mut missing = record_payload();
        missing.as_object_mut().unwrap().remove("schema_version");
        assert!(record(missing)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .is_err());
    }

    #[test]
    fn record_is_accepted_as_an_atomic_bundle_root() {
        record(record_payload())
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();
    }

    #[test]
    fn care_plan_requires_schema_version_two() {
        care_plan(care_plan_payload())
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();

        for invalid in [Value::Null, json!(1), json!(3), json!("2")] {
            let mut payload = care_plan_payload();
            payload["schema_version"] = invalid;
            assert!(care_plan(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }

        let mut missing = care_plan_payload();
        missing.as_object_mut().unwrap().remove("schema_version");
        assert!(care_plan(missing)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .is_err());
    }

    #[test]
    fn record_requires_current_nullable_keys() {
        record(record_payload())
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();

        for key in ["custom_item_client_uuid", "end_timestamp", "note"] {
            let mut payload = record_payload();
            payload.as_object_mut().unwrap().remove(key);
            assert!(record(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }
    }

    #[test]
    fn care_plan_requires_current_nullable_keys() {
        care_plan(care_plan_payload())
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();

        for key in [
            "custom_item_client_uuid",
            "note",
            "created_by_membership_id",
            "fulfilled_record_client_uuid",
            "fulfilled_at",
        ] {
            let mut payload = care_plan_payload();
            payload.as_object_mut().unwrap().remove(key);
            assert!(care_plan(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }
    }

    /// Public model seam: CarePlan wire status ↔ fulfillment pair bidirectional
    /// invariant. Completed requires both fields; non-completed forbids either;
    /// partial pairs never validate.
    #[test]
    fn care_plan_fulfillment_pair_is_atomic_with_status() {
        let record_id = Uuid::new_v4();
        let fulfilled_at = 1_700_000_100_000i64;

        let mut completed = care_plan_payload();
        completed["status"] = json!("completed");
        completed["fulfilled_record_client_uuid"] = json!(record_id);
        completed["fulfilled_at"] = json!(fulfilled_at);
        care_plan(completed)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .expect("completed with full pair must accept");

        for status in ["pending", "missed", "skipped"] {
            let mut open = care_plan_payload();
            open["status"] = json!(status);
            open["fulfilled_record_client_uuid"] = Value::Null;
            open["fulfilled_at"] = Value::Null;
            care_plan(open)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .unwrap_or_else(|_| panic!("{status} with empty pair must accept"));
        }

        // completed with either field empty/missing is rejected (no complete-then-bind).
        for (record, at) in [
            (Value::Null, json!(fulfilled_at)),
            (json!(record_id), Value::Null),
            (Value::Null, Value::Null),
        ] {
            let mut incomplete = care_plan_payload();
            incomplete["status"] = json!("completed");
            incomplete["fulfilled_record_client_uuid"] = record;
            incomplete["fulfilled_at"] = at;
            assert!(
                care_plan(incomplete)
                    .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                    .is_err(),
                "completed without full pair must reject"
            );
        }

        // Partial pair on non-completed (and completed covered above) — both-or-neither.
        for status in ["pending", "missed", "skipped", "completed"] {
            for (record, at) in [
                (json!(record_id), Value::Null),
                (Value::Null, json!(fulfilled_at)),
            ] {
                let mut partial = care_plan_payload();
                partial["status"] = json!(status);
                partial["fulfilled_record_client_uuid"] = record;
                partial["fulfilled_at"] = at;
                assert!(
                    care_plan(partial)
                        .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                        .is_err(),
                    "{status} with partial fulfillment pair must reject"
                );
            }
        }

        // Fail closed: non-completed must not carry a full fulfillment pair.
        for status in ["pending", "missed", "skipped"] {
            let mut with_pair = care_plan_payload();
            with_pair["status"] = json!(status);
            with_pair["fulfilled_record_client_uuid"] = json!(record_id);
            with_pair["fulfilled_at"] = json!(fulfilled_at);
            assert!(
                care_plan(with_pair)
                    .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                    .is_err(),
                "{status} must not carry fulfillment pair"
            );
        }
    }

    #[test]
    fn optional_current_input_is_canonicalized_to_the_android_pull_shape() {
        let baby_payload = json!({
            "nickname": "年年",
            "sex": null,
            "birthday": "2025-01-02",
            "avatar_media_uuid": null,
            "birth_weight_grams": null,
        });
        entity("baby", baby_payload.clone())
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();
        let mut missing_birth_weight = baby_payload.clone();
        missing_birth_weight
            .as_object_mut()
            .unwrap()
            .remove("birth_weight_grams");
        let canonical_baby = entity("baby", missing_birth_weight)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();
        assert_eq!(canonical_baby.payload["birth_weight_grams"], Value::Null);
        let mut old_sort_order = baby_payload;
        old_sort_order["sort_order"] = json!(7);
        assert!(entity("baby", old_sort_order)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .is_err());

        let media_payload = json!({
            "kind": "log",
            "record_client_uuid": Uuid::new_v4(),
            "care_plan_client_uuid": null,
            "baby_client_uuid": null,
            "mime": "image/jpeg",
            "width": null,
            "height": null,
            "byte_size": 3,
        });
        entity("media", media_payload.clone())
            .validate_as(1024, EntityValidationContext::AtomicBundleMedia)
            .unwrap();
        for key in [
            "care_plan_client_uuid",
            "baby_client_uuid",
            "mime",
            "width",
            "height",
        ] {
            let mut missing = media_payload.clone();
            missing.as_object_mut().unwrap().remove(key);
            let canonical = entity("media", missing)
                .validate_as(1024, EntityValidationContext::AtomicBundleMedia)
                .unwrap();
            assert_eq!(canonical.payload[key], Value::Null);
        }
        let mut care_plan_media = media_payload.clone();
        care_plan_media["care_plan_client_uuid"] = json!(Uuid::new_v4());
        care_plan_media
            .as_object_mut()
            .unwrap()
            .remove("record_client_uuid");
        let canonical_care_plan_media = entity("media", care_plan_media)
            .validate_as(1024, EntityValidationContext::AtomicBundleMedia)
            .unwrap();
        assert_eq!(
            canonical_care_plan_media.payload["record_client_uuid"],
            Value::Null
        );
        let mut live_without_byte_size = media_payload.clone();
        live_without_byte_size
            .as_object_mut()
            .unwrap()
            .remove("byte_size");
        assert!(entity("media", live_without_byte_size)
            .validate_as(1024, EntityValidationContext::AtomicBundleMedia)
            .is_err());
        let mut tombstone_payload = media_payload;
        tombstone_payload
            .as_object_mut()
            .unwrap()
            .remove("byte_size");
        let mut tombstone = entity("media", tombstone_payload);
        tombstone.deleted_at = Some(2);
        let canonical_tombstone = tombstone
            .validate_as(1024, EntityValidationContext::AtomicBundleMedia)
            .unwrap();
        assert_eq!(canonical_tombstone.payload["byte_size"], json!(0));

        let candidate_payload = json!({
            "care_plan_client_uuid": Uuid::new_v4(),
            "record_client_uuid": Uuid::new_v4(),
            "actual_timestamp": null,
        });
        entity("fulfillment_candidate", candidate_payload.clone())
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();
        let mut missing_actual_timestamp = candidate_payload;
        missing_actual_timestamp
            .as_object_mut()
            .unwrap()
            .remove("actual_timestamp");
        let canonical_candidate = entity("fulfillment_candidate", missing_actual_timestamp)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();
        assert_eq!(canonical_candidate.payload["actual_timestamp"], Value::Null);
    }

    #[test]
    fn current_wire_rejects_values_the_android_parser_cannot_apply() {
        for invalid_sex in [json!("unknown"), json!("")] {
            let payload = json!({
                "nickname": "年年",
                "sex": invalid_sex,
                "birthday": "2025-01-02",
                "avatar_media_uuid": null,
                "birth_weight_grams": null,
            });
            assert!(entity("baby", payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }
        let blank_nickname = json!({
            "nickname": "   ",
            "sex": null,
            "birthday": "2025-01-02",
            "avatar_media_uuid": null,
            "birth_weight_grams": null,
        });
        assert!(entity("baby", blank_nickname)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .is_err());

        let blank_custom_item = json!({
            "name": "   ",
            "icon_slot": 1,
        });
        assert!(entity("custom_item", blank_custom_item)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .is_err());

        let mut invalid_interval = record_payload();
        invalid_interval["timestamp"] = json!(100);
        invalid_interval["end_timestamp"] = json!(99);
        assert!(record(invalid_interval)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .is_err());

        let mut invalid_zone = care_plan_payload();
        invalid_zone["scheduled_zone_id"] = json!("Mars/Olympus");
        assert!(care_plan(invalid_zone)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .is_err());
    }

    #[test]
    fn optional_typed_fields_and_numbers_are_stored_in_android_canonical_shape() {
        for (record_type, input, expected) in [
            ("pee", json!({}), json!({"pee_amount": 2})),
            (
                "poop",
                json!({}),
                json!({"stool_amount": 3, "stool_consistency": 3, "stool_color": 0}),
            ),
            (
                "both_diaper",
                json!({}),
                json!({
                    "pee_amount": 2,
                    "stool_amount": 3,
                    "stool_consistency": 3,
                    "stool_color": 0,
                }),
            ),
            (
                "sleep",
                json!({"anomaly_flag": true}),
                json!({"is_nap": false, "anomaly_flag": true}),
            ),
        ] {
            let mut payload = record_payload();
            payload["type"] = json!(record_type);
            payload["payload_json"] = input;
            let canonical = record(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .unwrap();
            assert_eq!(canonical.payload["payload_json"], expected);
        }

        let mut temperature = record_payload();
        temperature["type"] = json!("temperature");
        temperature["payload_json"] = json!({"celsius": 37});
        let canonical_temperature = record(temperature)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();
        assert_eq!(
            serde_json::to_string(&canonical_temperature.payload["payload_json"]).unwrap(),
            r#"{"celsius":37.0}"#,
        );

        let mut measurement = care_plan_payload();
        measurement["type"] = json!("height");
        measurement["payload_json"] = json!({"value": 65.0, "unit": "cm"});
        let canonical_measurement = care_plan(measurement)
            .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
            .unwrap();
        assert_eq!(
            canonical_measurement.payload["payload_json"],
            json!({"value": 65, "unit": "cm"}),
        );
    }

    #[test]
    fn record_and_care_plan_types_are_a_closed_current_set() {
        let accepted = [
            "nursing",
            "formula",
            "pumped_feed",
            "pump_express",
            "pee",
            "poop",
            "both_diaper",
            "sleep",
            "temperature",
            "diary",
            "bath",
            "walk",
            "cough",
            "rash",
            "vomit",
            "injury",
            "medicine",
            "hospital",
            "height",
            "weight",
            "baby_food",
            "snack",
            "drink",
            "head",
            "chest",
            "foot_size",
            "vaccine",
            "custom",
        ];
        for record_type in accepted {
            let mut record_payload = record_payload();
            record_payload["type"] = json!(record_type);
            record_payload["payload_json"] = valid_nested_payload(record_type);
            record(record_payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .unwrap();

            let mut plan_payload = care_plan_payload();
            plan_payload["type"] = json!(record_type);
            plan_payload["payload_json"] = valid_nested_payload(record_type);
            care_plan(plan_payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .unwrap();
        }

        for record_type in ["memo", "other", "unknown"] {
            let mut record_payload = record_payload();
            record_payload["type"] = json!(record_type);
            assert!(record(record_payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());

            let mut plan_payload = care_plan_payload();
            plan_payload["type"] = json!(record_type);
            assert!(care_plan(plan_payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }
    }

    #[test]
    fn record_and_care_plan_require_strict_current_typed_payloads() {
        for record_type in [
            "nursing",
            "formula",
            "pumped_feed",
            "pump_express",
            "pee",
            "poop",
            "both_diaper",
            "sleep",
            "temperature",
            "diary",
            "bath",
            "walk",
            "cough",
            "rash",
            "vomit",
            "injury",
            "medicine",
            "hospital",
            "height",
            "weight",
            "baby_food",
            "snack",
            "drink",
            "head",
            "chest",
            "foot_size",
            "vaccine",
            "custom",
        ] {
            let mut payload = record_payload();
            payload["type"] = json!(record_type);
            payload["payload_json"] = valid_nested_payload(record_type);
            record(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .unwrap();

            let mut payload = care_plan_payload();
            payload["type"] = json!(record_type);
            payload["payload_json"] = valid_nested_payload(record_type);
            care_plan(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .unwrap();
        }

        for (record_type, nested) in [
            ("formula", json!({})),
            ("formula", json!({"amount_ml": "120"})),
            (
                "formula",
                json!({"amount_ml": 120, "prepared_milliliters": 150}),
            ),
            ("pumped_feed", json!({"amount_ml": 90, "duration_min": 10})),
            ("pee", json!({"pee_amount": 4})),
            ("sleep", json!({"is_nap": true})),
            ("temperature", json!({"value": 36.7})),
            (
                "diary",
                json!({"body": "今天很好", "photos": ["/data/a.jpg"]}),
            ),
            ("bath", json!({"detail": "晚间"})),
            (
                "nursing",
                json!({
                    "left_min": 10,
                    "right_min": 0,
                    "order": "left-first",
                    "record_mode": "end",
                }),
            ),
            ("custom", json!({"title": "抚触", "custom_item_id": 7})),
        ] {
            let mut payload = record_payload();
            payload["type"] = json!(record_type);
            payload["payload_json"] = nested.clone();
            assert!(record(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());

            let mut payload = care_plan_payload();
            payload["type"] = json!(record_type);
            payload["payload_json"] = nested;
            assert!(care_plan(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }
    }

    #[test]
    fn care_plan_allows_intent_only_feed_without_weakening_record_facts() {
        for (record_type, nested) in [
            (
                "nursing",
                json!({
                    "left_min": 0,
                    "right_min": 0,
                    "order": "LR",
                    "record_mode": "end",
                }),
            ),
            ("formula", json!({"amount_ml": 0})),
            ("pumped_feed", json!({"amount_ml": 0})),
        ] {
            let mut plan_payload = care_plan_payload();
            plan_payload["type"] = json!(record_type);
            plan_payload["payload_json"] = nested.clone();
            plan_payload["note"] = json!(NEXT_FEED_PLAN_MARKER);
            care_plan(plan_payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .unwrap();

            let mut unmarked_plan_payload = care_plan_payload();
            unmarked_plan_payload["type"] = json!(record_type);
            unmarked_plan_payload["payload_json"] = nested.clone();
            assert!(care_plan(unmarked_plan_payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());

            let mut fact_payload = record_payload();
            fact_payload["type"] = json!(record_type);
            fact_payload["payload_json"] = nested;
            assert!(record(fact_payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }
    }

    #[test]
    fn next_feed_plan_marker_matches_cross_language_fixture() {
        let fixture: Value = serde_json::from_str(NEXT_FEED_MARKER_FIXTURE)
            .expect("next-feed marker fixture must parse");
        assert_eq!(
            fixture["contract"].as_str(),
            Some("lezi.next-feed-plan-marker")
        );
        assert_eq!(fixture["version"].as_i64(), Some(1));
        assert_eq!(
            fixture["marker"].as_str(),
            Some(NEXT_FEED_PLAN_MARKER),
            "production constant must match versioned fixture marker"
        );

        let samples = fixture["samples"]
            .as_array()
            .expect("fixture samples array");
        let mut ids = BTreeSet::new();
        let mut saw_marker_only = false;
        let mut saw_marker_plus_visible = false;
        let mut saw_illegal = false;
        for sample in samples {
            let id = sample["id"].as_str().expect("sample id");
            ids.insert(id.to_owned());
            let note = sample["note"].as_str().expect("sample note");
            let expected = sample["is_next_feed"]
                .as_bool()
                .expect("sample is_next_feed");
            assert_eq!(
                is_next_feed_plan_note(Some(note)),
                expected,
                "recognition mismatch for sample {id}"
            );
            assert_eq!(
                note.starts_with(NEXT_FEED_PLAN_MARKER),
                expected,
                "starts_with must match is_next_feed for sample {id}"
            );
            if expected {
                let expected_visible = sample["visible_note"].as_str();
                assert_eq!(
                    visible_next_feed_note(note),
                    expected_visible,
                    "strip mismatch for sample {id}"
                );
                if expected_visible.is_none() {
                    saw_marker_only = true;
                } else {
                    saw_marker_plus_visible = true;
                }
            } else {
                assert!(
                    sample["visible_note"].is_null(),
                    "non-marker sample {id} must not declare strip output"
                );
                saw_illegal = true;
            }
        }
        assert!(ids.contains("marker_only"));
        assert!(ids.contains("marker_plus_visible"));
        assert!(ids.contains("illegal_v2_prefix"));
        assert!(ids.contains("illegal_single_bracket"));
        assert!(ids.contains("illegal_embedded_not_prefix"));
        assert!(saw_marker_only && saw_marker_plus_visible && saw_illegal);

        // Illegal similar prefixes must not unlock intent-only feed validation.
        for illegal in [
            "[[lezi:next-feed:v2]]",
            "[lezi:next-feed:v1]",
            "note [[lezi:next-feed:v1]]",
        ] {
            let mut plan_payload = care_plan_payload();
            plan_payload["type"] = json!("formula");
            plan_payload["payload_json"] = json!({"amount_ml": 0});
            plan_payload["note"] = json!(illegal);
            assert!(
                care_plan(plan_payload)
                    .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                    .is_err(),
                "illegal note {illegal:?} must not allow intent-only feed"
            );
        }
    }

    /// Shared with Kotlin: `config/next-feed-plan-marker.v1.json`.
    const NEXT_FEED_MARKER_FIXTURE: &str =
        include_str!("../../../config/next-feed-plan-marker.v1.json");
}
