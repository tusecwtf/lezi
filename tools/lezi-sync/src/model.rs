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
    pub device_id: String,
    pub generation: String,
    pub entities: Vec<RawEntity>,
}

pub struct ValidatedPush {
    pub device_id: String,
    pub generation: String,
    pub entities: Vec<Entity>,
}

impl PushRequest {
    pub fn validate(self, max_media_bytes: usize) -> Result<ValidatedPush, ApiError> {
        validate_required_string(&self.device_id, 128, "device_id")?;
        validate_required_string(&self.generation, 128, "generation")?;
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
    pub generation: String,
}

impl BundleCommitRequest {
    pub fn validate(&self) -> Result<(), ApiError> {
        validate_required_string(&self.generation, 128, "generation")
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

fn validate_record(payload: &Map<String, Value>) -> Result<(), ApiError> {
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
    let record_type = current_record_type(payload)?;
    optional_nullable_uuid(payload, "custom_item_client_uuid")?;
    integer(payload, "timestamp", 0, i64::MAX)?;
    optional_integer(payload, "end_timestamp", 0, i64::MAX)?;
    optional_nullable_string(payload, "note", 0, 20_000)?;
    validate_current_payload_json(record_type, payload.get("payload_json"))?;
    integer(payload, "schema_version", 2, 2)?;
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
    let record_type = current_record_type(payload)?;
    optional_nullable_uuid(payload, "custom_item_client_uuid")?;
    integer(payload, "scheduled_at", 0, i64::MAX)?;
    string(payload, "scheduled_zone_id", 1, 64)?;
    optional_nullable_string(payload, "note", 0, 20_000)?;
    validate_current_payload_json(record_type, payload.get("payload_json"))?;
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
    Ok(())
}

fn validate_current_payload_json(
    record_type: &str,
    payload_json: Option<&Value>,
) -> Result<(), ApiError> {
    let payload = payload_json
        .and_then(Value::as_object)
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
            if left.saturating_add(right) <= 0 {
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
            nested_integer(payload, "amount_ml", 1, 999)?;
            nested_optional_integer(payload, "prepared_ml", 0, 999)?;
            nested_optional_integer(payload, "duration_min", 0, 1_440)?;
        }
        "pumped_feed" | "pump_express" => {
            require_keys(payload, &["amount_ml"])?;
            allow_keys(payload, &["amount_ml"])?;
            nested_integer(payload, "amount_ml", 1, 999)?;
        }
        "pee" => {
            allow_keys(payload, &["pee_amount"])?;
            nested_optional_integer(payload, "pee_amount", 1, 3)?;
        }
        "poop" => {
            allow_keys(
                payload,
                &["stool_amount", "stool_consistency", "stool_color"],
            )?;
            nested_optional_integer(payload, "stool_amount", 1, 4)?;
            nested_optional_integer(payload, "stool_consistency", 1, 4)?;
            nested_optional_integer(payload, "stool_color", 0, 7)?;
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
        }
        "sleep" => {
            require_keys(payload, &["anomaly_flag"])?;
            allow_keys(payload, &["is_nap", "anomaly_flag"])?;
            nested_optional_boolean(payload, "is_nap")?;
            nested_boolean(payload, "anomaly_flag")?;
        }
        "temperature" => {
            require_keys(payload, &["celsius"])?;
            allow_keys(payload, &["celsius"])?;
            nested_number(payload, "celsius", 34.0, 43.0)?;
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
    use serde_json::{json, Value};
    use uuid::Uuid;

    use super::{EntityValidationContext, RawEntity};

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
            .validate_as(1024, EntityValidationContext::OrdinaryPush)
            .unwrap();

        for invalid in [Value::Null, json!(1), json!(3), json!("2")] {
            let mut payload = record_payload();
            payload["schema_version"] = invalid;
            assert!(record(payload)
                .validate_as(1024, EntityValidationContext::OrdinaryPush)
                .is_err());
        }

        let mut missing = record_payload();
        missing.as_object_mut().unwrap().remove("schema_version");
        assert!(record(missing)
            .validate_as(1024, EntityValidationContext::OrdinaryPush)
            .is_err());
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
            .validate_as(1024, EntityValidationContext::OrdinaryPush)
            .unwrap();

        for key in ["custom_item_client_uuid", "end_timestamp", "note"] {
            let mut payload = record_payload();
            payload.as_object_mut().unwrap().remove(key);
            assert!(record(payload)
                .validate_as(1024, EntityValidationContext::OrdinaryPush)
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
                .validate_as(1024, EntityValidationContext::OrdinaryPush)
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
                .validate_as(1024, EntityValidationContext::OrdinaryPush)
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
                .validate_as(1024, EntityValidationContext::OrdinaryPush)
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
                .validate_as(1024, EntityValidationContext::OrdinaryPush)
                .is_err());

            let mut payload = care_plan_payload();
            payload["type"] = json!(record_type);
            payload["payload_json"] = nested;
            assert!(care_plan(payload)
                .validate_as(1024, EntityValidationContext::AtomicBundleRoot)
                .is_err());
        }
    }
}
