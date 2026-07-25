use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EntityDto {
    #[serde(rename = "type")]
    pub entity_type: String,
    pub client_uuid: String,
    #[serde(default)]
    pub payload: Value,
    pub updated_at: i64,
    #[serde(default)]
    pub deleted_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rev: Option<i64>,
}

#[derive(Debug, Deserialize)]
pub struct PushRequest {
    pub family_id: String,
    #[serde(default)]
    pub device_id: Option<String>,
    #[serde(default)]
    pub entities: Vec<EntityDto>,
}

#[derive(Debug, Serialize)]
pub struct PushResponse {
    pub applied: u32,
}

#[derive(Debug, Serialize)]
pub struct PullResponse {
    pub entities: Vec<EntityDto>,
    pub cursor: i64,
}

#[derive(Debug, Deserialize)]
pub struct InviteRequest {
    pub family_id: String,
}

#[derive(Debug, Serialize)]
pub struct InviteResponse {
    pub code: String,
    pub expires_at: i64,
}

#[derive(Debug, Deserialize)]
pub struct JoinRequest {
    pub code: String,
    #[serde(default)]
    pub device_id: Option<String>,
    #[serde(default)]
    pub display_name: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct JoinResponse {
    pub family_id: String,
    pub entities: Vec<EntityDto>,
    pub cursor: i64,
}

#[derive(Debug, Serialize)]
pub struct HealthResponse {
    pub ok: bool,
    pub version: String,
    pub data_dir: String,
}

#[derive(Debug, Serialize)]
pub struct ErrorBody {
    pub error: String,
}
