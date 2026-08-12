//! Three-way merge for causal atomic roots (wire §8–§9).
//!
//! Pure functions: no I/O. Observed only via commit outcomes at the
//! Store façade; tests also exercise this module's public merge result shape
//! as the merge decision seam.

#![allow(clippy::too_many_arguments)]

use std::collections::{BTreeMap, BTreeSet};

use serde_json::{Map, Value};

/// Paths never treated as business conflicts (wire §4.0 / §9.4).
const NON_CONFLICT_ROOT_KEYS: &[&str] = &[
    "updated_at",
    "created_by_membership_id",
    "observer_membership_id",
];

/// One media manifest member (wire §4.6).
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CausalMediaItem {
    pub media_uuid: String,
    pub role: String,
    pub sha256: String,
    pub byte_size: i64,
    pub mime: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub width: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub height: Option<i64>,
}

impl CausalMediaItem {
    /// Wire §4.6 closed validation. `entity_type` selects allowed roles.
    pub fn validate_for_entity(&self, entity_type: &str) -> Result<(), &'static str> {
        if uuid::Uuid::parse_str(&self.media_uuid).is_err() {
            return Err("invalid_media_uuid");
        }
        let allowed = match entity_type {
            "baby" => &["avatar"][..],
            "record" => &["log"][..],
            "care_plan" => &["plan"][..],
            "wake_observation" => &["wake"][..],
            "custom_item" => &[][..],
            _ => return Err("unsupported_entity_type"),
        };
        if !allowed.contains(&self.role.as_str()) {
            return Err("invalid_media_role");
        }
        if self.sha256.len() != 64
            || !self
                .sha256
                .chars()
                .all(|c| matches!(c, '0'..='9' | 'a'..='f'))
        {
            return Err("invalid_media_sha256");
        }
        if self.byte_size <= 0 {
            return Err("invalid_media_byte_size");
        }
        if self.mime.is_empty() || self.mime.len() > 128 {
            return Err("invalid_media_mime");
        }
        Ok(())
    }

    pub fn to_value(&self) -> Value {
        let mut map = Map::new();
        map.insert(
            "media_uuid".to_owned(),
            Value::String(self.media_uuid.clone()),
        );
        map.insert("role".to_owned(), Value::String(self.role.clone()));
        map.insert("sha256".to_owned(), Value::String(self.sha256.clone()));
        map.insert("byte_size".to_owned(), Value::Number(self.byte_size.into()));
        map.insert("mime".to_owned(), Value::String(self.mime.clone()));
        map.insert(
            "width".to_owned(),
            self.width
                .map(|w| Value::Number(w.into()))
                .unwrap_or(Value::Null),
        );
        map.insert(
            "height".to_owned(),
            self.height
                .map(|h| Value::Number(h.into()))
                .unwrap_or(Value::Null),
        );
        Value::Object(map)
    }

    pub fn from_value(value: &Value) -> Option<Self> {
        let obj = value.as_object()?;
        Some(Self {
            media_uuid: obj.get("media_uuid")?.as_str()?.to_owned(),
            role: obj.get("role")?.as_str()?.to_owned(),
            sha256: obj.get("sha256")?.as_str()?.to_owned(),
            byte_size: obj.get("byte_size")?.as_i64()?,
            mime: obj.get("mime")?.as_str()?.to_owned(),
            width: match obj.get("width") {
                None | Some(Value::Null) => None,
                Some(v) => Some(v.as_i64()?),
            },
            height: match obj.get("height") {
                None | Some(Value::Null) => None,
                Some(v) => Some(v.as_i64()?),
            },
        })
    }
}

/// Outcome of comparing base → stable and base → incoming.
#[derive(Debug, Clone, PartialEq)]
pub enum MergeDecision {
    /// Incoming is identical to stable (after stamp-normalize).
    Identical,
    /// Only one side changed relative to base, or disjoint paths.
    AutoMerge {
        merged_root: Map<String, Value>,
        merged_media: Vec<CausalMediaItem>,
        merged_deleted: bool,
        /// Paths that were auto-combined (frozen for resolution UI).
        auto_merged: BTreeMap<String, Value>,
    },
    /// Same path different results (or media delete/edit).
    Conflict {
        conflicting_paths: Vec<String>,
        auto_merged: BTreeMap<String, Value>,
    },
}

/// Flatten a root object into leaf path → value (wire §9.1).
/// Arrays and non-object values are atomic leaves. Nested objects recurse.
pub fn leaf_paths(root: &Map<String, Value>) -> BTreeMap<String, Value> {
    let mut out = BTreeMap::new();
    for (key, value) in root {
        if NON_CONFLICT_ROOT_KEYS.contains(&key.as_str()) {
            continue;
        }
        flatten_value(&format!("/{key}"), value, &mut out);
    }
    out
}

fn flatten_value(path: &str, value: &Value, out: &mut BTreeMap<String, Value>) {
    match value {
        Value::Object(map) => {
            if map.is_empty() {
                out.insert(path.to_owned(), value.clone());
                return;
            }
            for (key, child) in map {
                flatten_value(&format!("{path}/{key}"), child, out);
            }
        }
        // Arrays are atomic (wire §9.2).
        _ => {
            out.insert(path.to_owned(), value.clone());
        }
    }
}

fn media_map(items: &[CausalMediaItem]) -> BTreeMap<String, CausalMediaItem> {
    items
        .iter()
        .map(|item| (item.media_uuid.clone(), item.clone()))
        .collect()
}

fn media_path(uuid: &str) -> String {
    format!("/media/{uuid}")
}

/// Three-way merge of root + delete envelope + media manifests.
pub fn three_way_merge(
    base_root: &Map<String, Value>,
    base_media: &[CausalMediaItem],
    base_deleted: bool,
    stable_root: &Map<String, Value>,
    stable_media: &[CausalMediaItem],
    stable_deleted: bool,
    incoming_root: &Map<String, Value>,
    incoming_media: &[CausalMediaItem],
    incoming_deleted: bool,
) -> MergeDecision {
    let base_leaves = leaf_paths(base_root);
    let stable_leaves = leaf_paths(stable_root);
    let incoming_leaves = leaf_paths(incoming_root);

    let mut all_paths: BTreeSet<String> = BTreeSet::new();
    all_paths.extend(base_leaves.keys().cloned());
    all_paths.extend(stable_leaves.keys().cloned());
    all_paths.extend(incoming_leaves.keys().cloned());

    let mut conflicting = BTreeSet::new();
    let mut auto_merged: BTreeMap<String, Value> = BTreeMap::new();
    let mut merged_leaves: BTreeMap<String, Value> = BTreeMap::new();

    for path in &all_paths {
        let b = base_leaves.get(path);
        let s = stable_leaves.get(path);
        let i = incoming_leaves.get(path);
        let stable_changed = s != b;
        let incoming_changed = i != b;
        match (stable_changed, incoming_changed) {
            (false, false) => {
                if let Some(v) = s.or(b) {
                    merged_leaves.insert(path.clone(), v.clone());
                }
            }
            (true, false) => {
                if let Some(v) = s {
                    merged_leaves.insert(path.clone(), v.clone());
                    auto_merged.insert(path.clone(), v.clone());
                }
            }
            (false, true) => {
                if let Some(v) = i {
                    merged_leaves.insert(path.clone(), v.clone());
                    auto_merged.insert(path.clone(), v.clone());
                }
            }
            (true, true) => {
                if s == i {
                    // Same resulting value is not a conflict (wire 例 C).
                    if let Some(v) = s {
                        merged_leaves.insert(path.clone(), v.clone());
                        auto_merged.insert(path.clone(), v.clone());
                    }
                } else {
                    conflicting.insert(path.clone());
                }
            }
        }
    }

    // Delete envelope path.
    // Concurrent live↔delete from a shared base always branches (wire 例 E):
    // even when only one side flipped `deleted`, the other side's business edit
    // means three-way is active and delete must not auto-merge over it.
    let delete_path = "/_mutation.deleted".to_owned();
    let stable_del_changed = stable_deleted != base_deleted;
    let incoming_del_changed = incoming_deleted != base_deleted;
    let mut merged_deleted = base_deleted;
    if stable_deleted != incoming_deleted {
        conflicting.insert(delete_path.clone());
    } else {
        match (stable_del_changed, incoming_del_changed) {
            (false, false) => merged_deleted = stable_deleted,
            (true, false) | (false, true) | (true, true) => {
                merged_deleted = stable_deleted;
                if stable_del_changed || incoming_del_changed {
                    auto_merged.insert(delete_path.clone(), Value::Bool(stable_deleted));
                }
            }
        }
    }

    // Media keyed merge (wire §9.3).
    let base_m = media_map(base_media);
    let stable_m = media_map(stable_media);
    let incoming_m = media_map(incoming_media);
    let mut all_media: BTreeSet<String> = BTreeSet::new();
    all_media.extend(base_m.keys().cloned());
    all_media.extend(stable_m.keys().cloned());
    all_media.extend(incoming_m.keys().cloned());

    let mut merged_media_map: BTreeMap<String, CausalMediaItem> = BTreeMap::new();
    for uuid in &all_media {
        let b = base_m.get(uuid);
        let s = stable_m.get(uuid);
        let i = incoming_m.get(uuid);
        let path = media_path(uuid);
        let stable_changed = s != b;
        let incoming_changed = i != b;
        match (stable_changed, incoming_changed) {
            (false, false) => {
                if let Some(item) = s.or(b) {
                    merged_media_map.insert(uuid.clone(), item.clone());
                }
            }
            (true, false) => {
                if let Some(item) = s {
                    merged_media_map.insert(uuid.clone(), item.clone());
                    auto_merged.insert(path, item.to_value());
                } else {
                    // stable deleted this media
                    auto_merged.insert(path, Value::Null);
                }
            }
            (false, true) => {
                if let Some(item) = i {
                    merged_media_map.insert(uuid.clone(), item.clone());
                    auto_merged.insert(path, item.to_value());
                } else {
                    auto_merged.insert(path, Value::Null);
                }
            }
            (true, true) => {
                if s == i {
                    if let Some(item) = s {
                        merged_media_map.insert(uuid.clone(), item.clone());
                        auto_merged.insert(path, item.to_value());
                    } else {
                        auto_merged.insert(path, Value::Null);
                    }
                } else {
                    // delete vs edit or divergent content → whole-root branch
                    conflicting.insert(path);
                }
            }
        }
    }

    if !conflicting.is_empty() {
        return MergeDecision::Conflict {
            conflicting_paths: conflicting.into_iter().collect(),
            auto_merged,
        };
    }

    // Rebuild root from stable skeleton + merged leaves (preserve stamp fields).
    let mut merged_root = stable_root.clone();
    // Apply incoming stamp-independent business leaves.
    apply_leaves_to_root(&mut merged_root, &merged_leaves);

    // Normalize updated_at: max(stable, incoming) (wire §4.0).
    let stable_updated = stable_root
        .get("updated_at")
        .and_then(Value::as_i64)
        .unwrap_or(0);
    let incoming_updated = incoming_root
        .get("updated_at")
        .and_then(Value::as_i64)
        .unwrap_or(0);
    merged_root.insert(
        "updated_at".to_owned(),
        Value::Number(stable_updated.max(incoming_updated).into()),
    );

    // Preserve server stamps from stable (or base if creating).
    if let Some(v) = stable_root
        .get("created_by_membership_id")
        .or_else(|| base_root.get("created_by_membership_id"))
    {
        merged_root.insert("created_by_membership_id".to_owned(), v.clone());
    }
    if let Some(v) = stable_root
        .get("observer_membership_id")
        .or_else(|| base_root.get("observer_membership_id"))
    {
        merged_root.insert("observer_membership_id".to_owned(), v.clone());
    }

    let merged_media: Vec<CausalMediaItem> = merged_media_map.into_values().collect();

    // Identical if merged equals stable (media set + deleted + business leaves).
    let stable_equiv = leaf_paths(stable_root) == leaf_paths(&merged_root)
        && media_map(stable_media) == media_map(&merged_media)
        && stable_deleted == merged_deleted
        && stable_root.get("updated_at") == merged_root.get("updated_at");

    if stable_equiv {
        return MergeDecision::Identical;
    }

    MergeDecision::AutoMerge {
        merged_root,
        merged_media,
        merged_deleted,
        auto_merged,
    }
}

fn apply_leaves_to_root(root: &mut Map<String, Value>, leaves: &BTreeMap<String, Value>) {
    // Clear non-stamp keys and rebuild from leaves for deterministic shape.
    let stamps: Vec<(String, Value)> = root
        .iter()
        .filter(|(k, _)| NON_CONFLICT_ROOT_KEYS.contains(&k.as_str()))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    // Keep only stamps; re-apply leaves.
    root.retain(|k, _| NON_CONFLICT_ROOT_KEYS.contains(&k.as_str()));
    for (path, value) in leaves {
        set_path(root, path, value.clone());
    }
    for (k, v) in stamps {
        root.entry(k).or_insert(v);
    }
}

/// Public path writer for resolution rebuild (wire §8.2).
pub fn set_path(root: &mut Map<String, Value>, path: &str, value: Value) {
    let parts: Vec<&str> = path
        .trim_start_matches('/')
        .split('/')
        .filter(|p| !p.is_empty())
        .collect();
    if parts.is_empty() {
        return;
    }
    set_path_parts(root, &parts, value);
}

fn set_path_parts(map: &mut Map<String, Value>, parts: &[&str], value: Value) {
    if parts.is_empty() {
        return;
    }
    if parts.len() == 1 {
        map.insert(parts[0].to_owned(), value);
        return;
    }
    let entry = map
        .entry(parts[0].to_owned())
        .or_insert_with(|| Value::Object(Map::new()));
    if !entry.is_object() {
        *entry = Value::Object(Map::new());
    }
    let child = entry.as_object_mut().expect("object");
    set_path_parts(child, &parts[1..], value);
}

/// Canonical request content hash for mutation identity (wire content_drift).
pub fn mutation_content_hash(
    entity_type: &str,
    client_uuid: &str,
    base_version: Option<&str>,
    deleted: bool,
    root: &Map<String, Value>,
    media: &[CausalMediaItem],
) -> String {
    use sha2::{Digest, Sha256};
    let mut media_sorted = media.to_vec();
    media_sorted.sort_by(|a, b| a.media_uuid.cmp(&b.media_uuid));
    let payload = serde_json::json!({
        "entity_type": entity_type,
        "client_uuid": client_uuid,
        "base_version": base_version,
        "deleted": deleted,
        "root": root,
        "media": media_sorted,
    });
    // serde_json Map is ordered by insertion; re-serialize via sorted value:
    let canonical = canonical_json(&payload);
    let digest = Sha256::digest(canonical.as_bytes());
    hex::encode(digest)
}

fn canonical_json(value: &Value) -> String {
    match value {
        Value::Object(map) => {
            let keys: BTreeSet<_> = map.keys().cloned().collect();
            let inner = keys
                .into_iter()
                .map(|k| {
                    format!(
                        "{}:{}",
                        serde_json::to_string(&k).unwrap_or_default(),
                        canonical_json(map.get(&k).unwrap_or(&Value::Null))
                    )
                })
                .collect::<Vec<_>>()
                .join(",");
            format!("{{{inner}}}")
        }
        Value::Array(items) => {
            let inner = items
                .iter()
                .map(canonical_json)
                .collect::<Vec<_>>()
                .join(",");
            format!("[{inner}]")
        }
        other => serde_json::to_string(other).unwrap_or_else(|_| "null".to_owned()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn map(v: Value) -> Map<String, Value> {
        v.as_object().unwrap().clone()
    }

    #[test]
    fn mutation_content_hash_golden_matches_client_canonical() {
        // Shared with Android causalMutationContentHash JVM golden (correctness-02).
        let root = map(json!({
            "baby_client_uuid": "22222222-2222-2222-2222-222222222222",
            "custom_item_client_uuid": null,
            "note": null,
            "payload_json": {"amount_ml": 90},
            "schema_version": 2,
            "timestamp": 100,
            "type": "formula",
            "updated_at": 100
        }));
        let digest = mutation_content_hash(
            "record",
            "11111111-1111-1111-1111-111111111111",
            Some("v-r0"),
            false,
            &root,
            &[],
        );
        assert_eq!(
            digest,
            "ff2cec4612265f208e3c3a06029fdd1e24c0d75a2081c89e8468812720e9a83a"
        );
    }

    #[test]
    fn disjoint_fields_auto_merge_even_when_updated_at_differs() {
        // wire 例 A
        let base = map(json!({
            "note": "a",
            "payload_json": {"amount_ml": 100},
            "updated_at": 10
        }));
        let left = map(json!({
            "note": "b",
            "payload_json": {"amount_ml": 100},
            "updated_at": 20
        }));
        let right = map(json!({
            "note": "a",
            "payload_json": {"amount_ml": 120},
            "updated_at": 30
        }));
        let decision = three_way_merge(&base, &[], false, &left, &[], false, &right, &[], false);
        match decision {
            MergeDecision::AutoMerge {
                merged_root,
                merged_deleted,
                ..
            } => {
                assert!(!merged_deleted);
                assert_eq!(merged_root.get("note").and_then(Value::as_str), Some("b"));
                assert_eq!(
                    merged_root
                        .get("payload_json")
                        .and_then(|v| v.get("amount_ml"))
                        .and_then(Value::as_i64),
                    Some(120)
                );
                assert_eq!(
                    merged_root.get("updated_at").and_then(Value::as_i64),
                    Some(30)
                );
            }
            other => panic!("expected AutoMerge, got {other:?}"),
        }
    }

    #[test]
    fn same_path_different_value_conflicts() {
        // wire 例 B
        let base = map(json!({"note": "a", "updated_at": 1}));
        let left = map(json!({"note": "b", "updated_at": 2}));
        let right = map(json!({"note": "c", "updated_at": 3}));
        let decision = three_way_merge(&base, &[], false, &left, &[], false, &right, &[], false);
        match decision {
            MergeDecision::Conflict {
                conflicting_paths, ..
            } => {
                assert!(conflicting_paths.iter().any(|p| p == "/note"));
            }
            other => panic!("expected Conflict, got {other:?}"),
        }
    }

    #[test]
    fn same_path_same_value_not_conflict() {
        let base = map(json!({"note": "a", "updated_at": 1}));
        let left = map(json!({"note": "b", "updated_at": 2}));
        let right = map(json!({"note": "b", "updated_at": 3}));
        let decision = three_way_merge(&base, &[], false, &left, &[], false, &right, &[], false);
        assert!(matches!(decision, MergeDecision::AutoMerge { .. }));
    }

    #[test]
    fn concurrent_delete_and_edit_conflicts() {
        let base = map(json!({"note": "a", "updated_at": 1}));
        let del = map(json!({"note": "a", "updated_at": 2}));
        let edit = map(json!({"note": "b", "updated_at": 3}));
        let decision = three_way_merge(&base, &[], false, &del, &[], true, &edit, &[], false);
        match decision {
            MergeDecision::Conflict {
                conflicting_paths, ..
            } => {
                assert!(conflicting_paths
                    .iter()
                    .any(|p| p == "/_mutation.deleted" || p == "/note"));
            }
            other => panic!("expected Conflict, got {other:?}"),
        }
    }

    #[test]
    fn independent_media_uuids_merge() {
        let m1 = CausalMediaItem {
            media_uuid: "m1".into(),
            role: "log".into(),
            sha256: "a".repeat(64),
            byte_size: 1,
            mime: "image/jpeg".into(),
            width: None,
            height: None,
        };
        let m2 = CausalMediaItem {
            media_uuid: "m2".into(),
            role: "log".into(),
            sha256: "b".repeat(64),
            byte_size: 2,
            mime: "image/jpeg".into(),
            width: None,
            height: None,
        };
        let empty = map(json!({"note": null, "updated_at": 1}));
        let decision = three_way_merge(
            &empty,
            &[],
            false,
            &empty,
            std::slice::from_ref(&m1),
            false,
            &empty,
            std::slice::from_ref(&m2),
            false,
        );
        match decision {
            MergeDecision::AutoMerge { merged_media, .. } => {
                let ids: BTreeSet<_> = merged_media.into_iter().map(|m| m.media_uuid).collect();
                assert_eq!(ids, BTreeSet::from(["m1".into(), "m2".into()]));
            }
            other => panic!("expected AutoMerge, got {other:?}"),
        }
    }

    #[test]
    fn same_media_delete_vs_edit_branches() {
        let m1 = CausalMediaItem {
            media_uuid: "m1".into(),
            role: "log".into(),
            sha256: "a".repeat(64),
            byte_size: 1,
            mime: "image/jpeg".into(),
            width: None,
            height: None,
        };
        let mut m1_edit = m1.clone();
        m1_edit.sha256 = "c".repeat(64);
        let root = map(json!({"note": null, "updated_at": 1}));
        let decision = three_way_merge(
            &root,
            std::slice::from_ref(&m1),
            false,
            &root,
            &[], // deleted m1
            false,
            &root,
            std::slice::from_ref(&m1_edit),
            false,
        );
        match decision {
            MergeDecision::Conflict {
                conflicting_paths, ..
            } => {
                assert!(conflicting_paths.iter().any(|p| p == "/media/m1"));
            }
            other => panic!("expected Conflict, got {other:?}"),
        }
    }
}
