//! Near-neighbor grouping (wire §12 / ADR-0023).
//!
//! These helpers never tombstone a record or merge payload fields.

use serde_json::Value;
use unicode_normalization::UnicodeNormalization;

/// Inclusive grouping window on the record timestamp.
pub(crate) const SUSPECTED_DUPLICATE_WINDOW: i64 = 30 * 60 * 1000;

pub(crate) const AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX: &str = "auto-near-neighbor:";

/// Any live Record type participates. Empty keys are not records.
pub(crate) fn is_suspected_duplicate_type(record_type: &str) -> bool {
    !record_type.is_empty()
}

/// NFKC + lowercase + collapsed whitespace. Empty after normalize is None.
pub(crate) fn normalize_name_shard(raw: &str) -> Option<String> {
    let normalized = raw
        .nfkc()
        .flat_map(char::to_lowercase)
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if normalized.is_empty() {
        None
    } else {
        Some(normalized)
    }
}

/// Shard key inside one baby + exact type. Unique per record when the name/item
/// is missing so empty-name medicines never merge.
pub(crate) fn payload_shard(record_type: &str, root: &Value, client_uuid: &str) -> String {
    match record_type {
        "medicine" | "vaccine" => {
            match name_from_payload(root).and_then(|name| normalize_name_shard(&name)) {
                Some(name) => format!("name:{name}"),
                None => format!("empty:{client_uuid}"),
            }
        }
        "custom" => match root
            .get("custom_item_client_uuid")
            .and_then(Value::as_str)
            .filter(|value| !value.is_empty())
        {
            Some(item) => format!("item:{item}"),
            None => format!("empty:{client_uuid}"),
        },
        _ => String::new(),
    }
}

fn name_from_payload(root: &Value) -> Option<String> {
    if let Some(name) = root.get("payload_json").and_then(|payload| match payload {
        Value::Object(object) => object
            .get("name")
            .and_then(Value::as_str)
            .map(str::to_owned),
        Value::String(raw) => serde_json::from_str::<Value>(raw).ok().and_then(|parsed| {
            parsed
                .get("name")
                .and_then(Value::as_str)
                .map(str::to_owned)
        }),
        _ => None,
    }) {
        return Some(name);
    }
    root.get("name").and_then(Value::as_str).map(str::to_owned)
}

/// Deterministic display: Owner-authored earliest timestamp, else display_name_key.
pub(crate) fn pick_display_client_uuid(members: &[(String, i64, String, bool, String)]) -> String {
    // (client_uuid, timestamp, author, is_owner, display_name_key)
    let owner_authored: Vec<_> = members.iter().filter(|row| row.3).collect();
    if !owner_authored.is_empty() {
        return owner_authored
            .into_iter()
            .min_by(|left, right| left.1.cmp(&right.1).then(left.0.cmp(&right.0)))
            .map(|row| row.0.clone())
            .expect("owner pool is non-empty");
    }
    members
        .iter()
        .min_by(|left, right| {
            left.4
                .cmp(&right.4)
                .then(left.1.cmp(&right.1))
                .then(left.0.cmp(&right.0))
        })
        .map(|row| row.0.clone())
        .expect("near-neighbor component is non-empty")
}

pub(crate) fn sort_sources(
    members: &[(String, i64, String, bool, String)],
    display: &str,
) -> Vec<String> {
    let mut sources: Vec<_> = members.iter().filter(|row| row.0 != display).collect();
    sources.sort_by(|left, right| {
        left.4
            .cmp(&right.4)
            .then(left.1.cmp(&right.1))
            .then(left.0.cmp(&right.0))
    });
    sources.into_iter().map(|row| row.0.clone()).collect()
}

pub(crate) fn auto_align_mutation_id(sorted_members: &[String], display: &str) -> String {
    use sha2::{Digest, Sha256};
    let mut hasher = Sha256::new();
    hasher.update(sorted_members.join("\u{0}").as_bytes());
    hasher.update([0u8]);
    hasher.update(display.as_bytes());
    let digest = hasher.finalize();
    format!(
        "{AUTO_NEAR_NEIGHBOR_MUTATION_PREFIX}{}",
        digest
            .iter()
            .take(16)
            .map(|byte| format!("{byte:02x}"))
            .collect::<String>()
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn all_record_types_participate() {
        assert!(is_suspected_duplicate_type("bath"));
        assert!(is_suspected_duplicate_type("sleep"));
        assert!(is_suspected_duplicate_type("custom"));
        assert!(is_suspected_duplicate_type("walk"));
        assert!(!is_suspected_duplicate_type(""));
        assert_eq!(SUSPECTED_DUPLICATE_WINDOW, 1_800_000);
    }

    #[test]
    fn medicine_name_shard_is_normalized_and_empty_is_unique() {
        let named = json!({"payload_json": {"name": "  布洛芬  "}});
        assert_eq!(payload_shard("medicine", &named, "a"), "name:布洛芬");
        let other_case = json!({"payload_json": {"name": "Ibuprofen"}});
        let folded = json!({"payload_json": {"name": "ibuprofen"}});
        assert_eq!(
            payload_shard("medicine", &other_case, "a"),
            payload_shard("medicine", &folded, "b")
        );
        let empty = json!({"payload_json": {"name": "  "}});
        assert_eq!(payload_shard("medicine", &empty, "uuid-1"), "empty:uuid-1");
        assert_ne!(
            payload_shard("medicine", &empty, "uuid-1"),
            payload_shard("medicine", &empty, "uuid-2")
        );
    }

    #[test]
    fn custom_shards_by_item_uuid() {
        let item = json!({"custom_item_client_uuid": "item-a"});
        assert_eq!(payload_shard("custom", &item, "r1"), "item:item-a");
        let missing = json!({"custom_item_client_uuid": null});
        assert_eq!(payload_shard("custom", &missing, "r1"), "empty:r1");
    }

    #[test]
    fn owner_authored_earliest_timestamp_wins() {
        let members = vec![
            ("b".into(), 20, "m-member".into(), false, "爸爸".into()),
            ("a".into(), 10, "m-owner".into(), true, "妈妈".into()),
            ("c".into(), 5, "m-owner-2".into(), true, "妈妈".into()),
        ];
        assert_eq!(pick_display_client_uuid(&members), "c");
        assert_eq!(
            sort_sources(&members, "c"),
            vec!["a".to_owned(), "b".to_owned()]
        );
    }

    #[test]
    fn without_owner_sorts_by_display_name_then_time() {
        let members = vec![
            ("z".into(), 1, "m2".into(), false, "张三".into()),
            ("a".into(), 5, "m1".into(), false, "李四".into()),
        ];
        assert_eq!(pick_display_client_uuid(&members), "z");
    }
}
