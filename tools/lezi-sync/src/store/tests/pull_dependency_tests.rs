//! Dependency-query semantics and work bounds on the unchanged production schema.
//! Direct rows isolate planning work; this is not restore/API throughput evidence.

use super::*;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

fn database() -> Connection {
    let connection = Connection::open_in_memory().unwrap();
    connection
        .execute_batch(super::super::schema::CURRENT_SCHEMA_SQL)
        .unwrap();
    connection
        .execute_batch("PRAGMA foreign_keys = ON;")
        .unwrap();
    for family in ["f", "other"] {
        connection
            .execute(
                "INSERT INTO families(id, created_at) VALUES (?1, 0)",
                [family],
            )
            .unwrap();
    }
    connection
}

fn insert(
    connection: &Connection,
    family: &str,
    kind: &str,
    id: &str,
    rev: i64,
    deleted: bool,
    payload: Value,
) {
    connection.execute(
        "INSERT INTO entities(family_id, entity_type, client_uuid, updated_at, deleted_at, payload_json, rev) VALUES (?1, ?2, ?3, 0, ?4, ?5, ?6)",
        params![family, kind, id, deleted.then_some(1), payload.to_string(), rev],
    ).unwrap();
}

#[test]
fn dependency_index_keeps_disjoint_owner_types_order_and_family_scope() {
    let connection = database();
    for family in ["f", "other"] {
        for (kind, id, rev, deleted, payload) in [
            ("baby", "b", 0, false, serde_json::json!({})),
            (
                "record",
                "same",
                1,
                false,
                serde_json::json!({"baby_client_uuid":"b"}),
            ),
            (
                "care_plan",
                "same",
                2,
                false,
                serde_json::json!({"baby_client_uuid":"b","status":"completed","fulfilled_record_client_uuid":"same"}),
            ),
            (
                "wake_observation",
                "w",
                3,
                false,
                serde_json::json!({"sleep_record_client_uuid":"same"}),
            ),
            (
                "fulfillment_candidate",
                "c",
                4,
                false,
                serde_json::json!({"record_client_uuid":"same","care_plan_client_uuid":"same"}),
            ),
            (
                "media",
                "both",
                5,
                false,
                serde_json::json!({"kind":"log","record_client_uuid":"same","care_plan_client_uuid":"same"}),
            ),
            (
                "media",
                "record",
                6,
                false,
                serde_json::json!({"kind":"log","record_client_uuid":"same"}),
            ),
            (
                "media",
                "plan",
                7,
                false,
                serde_json::json!({"kind":"log","care_plan_client_uuid":"same"}),
            ),
            (
                "media",
                "wake",
                8,
                false,
                serde_json::json!({"kind":"wake","record_client_uuid":"w"}),
            ),
            (
                "media",
                "deleted",
                9,
                true,
                serde_json::json!({"kind":"log","record_client_uuid":"same"}),
            ),
            (
                "media",
                "null",
                10,
                false,
                serde_json::json!({"kind":"log","record_client_uuid":null,"care_plan_client_uuid":null}),
            ),
            (
                "media",
                "missing",
                11,
                false,
                serde_json::json!({"kind":"log"}),
            ),
            (
                "media",
                "unknown-kind",
                12,
                false,
                serde_json::json!({"kind":"unknown","record_client_uuid":"same"}),
            ),
        ] {
            insert(&connection, family, kind, id, rev, deleted, payload);
        }
    }
    // A foreign-family-only match must not be returned even when owner IDs match.
    insert(
        &connection,
        "other",
        "media",
        "foreign",
        13,
        false,
        serde_json::json!({"kind":"log","record_client_uuid":"same"}),
    );
    for cursor in [0, 4] {
        let index = PullDependencyIndex::load(&connection, "f", cursor, 13).unwrap();
        assert_eq!(
            index.log_media_by_parent,
            BTreeMap::from([
                (
                    ("record".to_owned(), "same".to_owned()),
                    vec!["both".to_owned(), "record".to_owned()]
                ),
                (
                    ("care_plan".to_owned(), "same".to_owned()),
                    vec!["both".to_owned(), "plan".to_owned()]
                ),
                (
                    ("wake_observation".to_owned(), "w".to_owned()),
                    vec!["wake".to_owned()]
                ),
            ])
        );
        assert_eq!(
            index.completed_plans_by_record,
            BTreeMap::from([("same".to_owned(), vec!["same".to_owned()])])
        );
        assert!(!index
            .entities_by_key
            .contains_key(&("media".to_owned(), "foreign".to_owned())));
    }
    assert!(PullDependencyIndex::load(&connection, "f", 13, 13)
        .unwrap()
        .log_media_by_parent
        .is_empty());
}

#[test]
fn dependency_index_does_not_cross_join_page_parents_with_all_media() {
    let connection = database();
    insert(
        &connection,
        "f",
        "baby",
        "b",
        0,
        false,
        serde_json::json!({}),
    );
    for index in 0..1_000 {
        let record = format!("r{index}");
        insert(
            &connection,
            "f",
            "record",
            &record,
            2 * index + 1,
            false,
            serde_json::json!({"baby_client_uuid":"b"}),
        );
        insert(
            &connection,
            "f",
            "media",
            &format!("m{index}"),
            2 * index + 2,
            false,
            serde_json::json!({"kind":"log","record_client_uuid":record}),
        );
    }
    let count = Arc::new(AtomicU64::new(0));
    let observer = count.clone();
    connection.progress_handler(
        1,
        Some(move || {
            observer.fetch_add(1, Ordering::Relaxed);
            false
        }),
    );
    let index = PullDependencyIndex::load(&connection, "f", 0, 2_000).unwrap();
    connection.progress_handler(0, None::<fn() -> bool>);
    let steps = count.load(Ordering::Relaxed);
    assert_eq!(index.log_media_by_parent.len(), 100);
    assert_eq!(index.entities_by_key.len(), 201);
    for index_number in 0..100 {
        assert_eq!(
            index.log_media_by_parent[&("record".to_owned(), format!("r{index_number}"))],
            vec![format!("m{index_number}")]
        );
    }
    eprintln!("pull dependency index: 1,000 record/media pairs, 200-row base window, {steps} SQLite progress callbacks (prepare + execution)");
    // Counts prepare + execution callbacks, not exact execution-only VM steps.
    // A coarse algorithmic ceiling, not a latency SLA. The old disjunctive join
    // scans every parent for each media row and exceeds this by a wide margin.
    assert!(
        steps < 400_000,
        "dependency index performed {steps} progress callbacks"
    );
}

#[test]
fn dependency_prefetch_keeps_full_plan_fanout_beyond_base_window() {
    let connection = database();
    insert(
        &connection,
        "f",
        "baby",
        "b",
        0,
        false,
        serde_json::json!({}),
    );
    insert(
        &connection,
        "f",
        "record",
        "r",
        1,
        false,
        serde_json::json!({"baby_client_uuid":"b"}),
    );
    for index in 0..150 {
        let plan = format!("p{index}");
        insert(
            &connection,
            "f",
            "care_plan",
            &plan,
            2 * index + 2,
            false,
            serde_json::json!({"baby_client_uuid":"b","status":"completed","fulfilled_record_client_uuid":"r"}),
        );
        insert(
            &connection,
            "f",
            "media",
            &format!("m{index}"),
            2 * index + 3,
            false,
            serde_json::json!({"kind":"log","care_plan_client_uuid":plan}),
        );
    }
    let index = PullDependencyIndex::load(&connection, "f", 0, 1_000).unwrap();
    assert_eq!(
        index.completed_plans_by_record["r"],
        (0..150).map(|i| format!("p{i}")).collect::<Vec<_>>()
    );
    assert_eq!(index.log_media_by_parent.len(), 150);
    // The 200-row base window is not a cap on its dependency keys. The planner
    // still decides whether the complete group fits; prefetch must not truncate.
    assert_eq!(index.entities_by_key.len(), 302);
    for number in 0..150 {
        assert_eq!(
            index.log_media_by_parent[&("care_plan".to_owned(), format!("p{number}"))],
            vec![format!("m{number}")]
        );
        assert!(index
            .entities_by_key
            .contains_key(&("care_plan".to_owned(), format!("p{number}"))));
        assert!(index
            .entities_by_key
            .contains_key(&("media".to_owned(), format!("m{number}"))));
    }
    let empty = PullDependencyIndex::load(&connection, "f", 1_000, 1_000).unwrap();
    assert!(empty.log_media_by_parent.is_empty());
    assert!(empty.completed_plans_by_record.is_empty());
    assert!(empty.entities_by_key.is_empty());
}

#[test]
fn dependency_prefetch_keeps_baby_when_requested_media_keys_are_empty() {
    let connection = database();
    let baby = "00000000-0000-4000-8000-000000000001";
    let record = "00000000-0000-4000-8000-000000000002";
    insert(
        &connection,
        "f",
        "baby",
        baby,
        1,
        false,
        serde_json::json!({
            "nickname":"no media","sex":"female","birthday":"2023-01-02",
            "birth_weight_grams":null,"avatar_media_uuid":null
        }),
    );
    insert(
        &connection,
        "f",
        "record",
        record,
        2,
        false,
        serde_json::json!({
            "baby_client_uuid":baby,"type":"formula","custom_item_client_uuid":null,
            "timestamp":1700000000000_i64,"end_timestamp":null,"note":null,
            "payload_json":{"amount_ml":100},"schema_version":2
        }),
    );
    let index = PullDependencyIndex::load(&connection, "f", 1, 2).unwrap();
    assert!(index.log_media_by_parent.is_empty());
    assert!(index.completed_plans_by_record.is_empty());
    assert_eq!(
        index.entities_by_key.keys().cloned().collect::<Vec<_>>(),
        vec![("baby".to_owned(), baby.to_owned())]
    );
    assert_eq!(
        index.entities_by_key[&("baby".to_owned(), baby.to_owned())].rev,
        1
    );
}

fn insert_sidecar_root(connection: &Connection, family: &str, kind: &str, id: &str, deleted: bool) {
    insert(
        connection,
        family,
        kind,
        id,
        1,
        deleted,
        serde_json::json!({}),
    );
    let version = if family == "f" {
        format!("{kind}:{id}:v")
    } else {
        format!("{family}:{kind}:{id}:v")
    };
    connection.execute(
        "INSERT INTO entity_versions(family_id,version_id,entity_type,client_uuid,updated_at,deleted_at,payload_json,content_hash,origin,created_at) VALUES(?1,?2,?3,?4,0,?5,'{}','hash','accepted',0)",
        params![family,version,kind,id,deleted.then_some(1)],
    ).unwrap();
    connection
        .execute(
            "INSERT INTO entity_stable_heads VALUES(?1,?2,?3,?4)",
            params![family, kind, id, version],
        )
        .unwrap();
}

fn sidecar_group(connection: &Connection, keys: &[(&str, &str)]) -> PullGroup {
    let entities = keys
        .iter()
        .map(|(kind, id)| {
            load_pulled_entity(connection, "f", kind, id)
                .unwrap()
                .unwrap()
        })
        .collect::<Vec<_>>();
    let wire = entities
        .iter()
        .map(|entity| pulled_entity_wire(entity).unwrap())
        .collect();
    PullGroup {
        cursor_before: 0,
        base_rev: 1,
        entities,
        wire,
    }
}

#[test]
fn sidecar_projection_uses_page_keys_without_scanning_every_stable_head() {
    let connection = database();
    for index in 0..1_000 {
        insert_sidecar_root(&connection, "f", "record", &format!("r{index}"), false);
    }
    let ids = (0..200)
        .map(|index| format!("r{index}"))
        .collect::<Vec<_>>();
    let keys = ids
        .iter()
        .map(|id| ("record", id.as_str()))
        .collect::<Vec<_>>();
    let groups = [sidecar_group(&connection, &keys)];
    let count = Arc::new(AtomicU64::new(0));
    let observer = count.clone();
    connection.progress_handler(
        1,
        Some(move || {
            observer.fetch_add(1, Ordering::Relaxed);
            false
        }),
    );
    let projected = load_projected_sidecars(&connection, "f", &groups).unwrap();
    connection.progress_handler(0, None::<fn() -> bool>);
    assert_eq!(projected.len(), 200);
    for id in ids {
        assert_eq!(
            projected[&("record".to_owned(), id.clone())].stable_version_id,
            Some(format!("record:{id}:v"))
        );
    }
    let steps = count.load(Ordering::Relaxed);
    eprintln!("sidecar whole-query prepare+execute progress callbacks: {steps}");
    // 200 keyed lookups plus ordering/projection should stay well below this
    // generous 250 callbacks/key ceiling. A 1,000 x 200 loop exceeds 1.8m.
    assert!(
        steps < 50_000,
        "sidecar projection repeated family scans: {steps}"
    );
}

#[test]
fn sidecar_projection_preserves_type_family_tombstone_missing_and_duplicate_keys() {
    let connection = database();
    for family in ["f", "other"] {
        for kind in ["baby", "record", "care_plan"] {
            for id in ["same", "é", "Z"] {
                insert_sidecar_root(&connection, family, kind, id, id == "é");
            }
        }
    }
    connection
        .execute(
            "DELETE FROM entity_stable_heads WHERE family_id='f' AND client_uuid='Z'",
            [],
        )
        .unwrap();
    insert(
        &connection,
        "f",
        "media",
        "same",
        1,
        false,
        serde_json::json!({}),
    );
    let keys = [
        ("record", "same"),
        ("baby", "same"),
        ("record", "é"),
        ("care_plan", "same"),
        ("record", "Z"),
        ("media", "same"),
        ("record", "same"),
    ];
    let mut groups = [sidecar_group(&connection, &keys)];
    let projected = load_projected_sidecars(&connection, "f", &groups).unwrap();
    assert_eq!(projected.len(), 4);
    attach_projected_sidecars(&mut groups, &projected);
    refresh_sidecar_wire_bytes(&mut groups).unwrap();
    for (entity, bytes) in groups[0].entities.iter().zip(&groups[0].wire) {
        let expected = (entity.entity_type != "media" && entity.client_uuid != "Z")
            .then(|| format!("{}:{}:v", entity.entity_type, entity.client_uuid));
        assert_eq!(entity.version_id, expected);
        assert_eq!(*bytes, pulled_entity_wire(entity).unwrap());
    }
    assert!(load_projected_sidecars(&connection, "f", &[])
        .unwrap()
        .is_empty());
}

#[test]
fn sidecar_projection_retains_conflict_selection_and_relation_peer_multiplicity() {
    let connection = database();
    for id in ["r0", "r1", "r2"] {
        insert_sidecar_root(&connection, "f", "record", id, false);
    }
    for (id, root) in [("a", "r0"), ("z", "r0"), ("empty", "r1"), ("later", "r1")] {
        connection.execute("INSERT INTO conflicts VALUES('f',?1,'record',?2,NULL,?3,'open','concurrent',0,NULL)",
            params![id,root,format!("record:{root}:v")]).unwrap();
        if id != "empty" {
            for branch in ["record:r2:v", "record:r1:v"] {
                connection
                    .execute(
                        "INSERT INTO conflict_branches VALUES('f',?1,?2)",
                        params![id, branch],
                    )
                    .unwrap();
            }
        }
    }
    connection.execute("INSERT INTO source_relations VALUES('f','rel','r0',1,'author_declare','manual','owner',0)",[]).unwrap();
    for (id, role) in [("r2", "source"), ("r0", "display"), ("r1", "source")] {
        connection
            .execute(
                "INSERT INTO source_relation_members VALUES('f','rel',?1,?2)",
                params![id, role],
            )
            .unwrap();
    }
    let groups = [sidecar_group(
        &connection,
        &[
            ("record", "r2"),
            ("record", "r0"),
            ("record", "r1"),
            ("record", "r0"),
        ],
    )];
    let projected = load_projected_sidecars(&connection, "f", &groups).unwrap();
    let first = &projected[&("record".to_owned(), "r0".to_owned())];
    let conflict = first.conflict.as_ref().unwrap();
    assert_eq!(conflict.conflict_id, "a");
    assert_eq!(
        conflict.branch_version_ids,
        ["record:r1:v", "record:r1:v", "record:r2:v", "record:r2:v"]
    );
    let relation = first.relation.as_ref().unwrap();
    assert_eq!(relation.role, "display");
    assert_eq!(relation.peer_ids, ["r1", "r1", "r2", "r2"]);
    for auto_aligned in [false, true] {
        if auto_aligned {
            connection.execute("UPDATE source_relations SET mutation_id='auto-near-neighbor:fixture' WHERE family_id='f' AND relation_id='rel'", []).unwrap();
        }
        let mut groups = [sidecar_group(
            &connection,
            &[("record", "r0"), ("record", "r1")],
        )];
        let projected = load_projected_sidecars(&connection, "f", &groups).unwrap();
        attach_projected_sidecars(&mut groups, &projected);
        refresh_sidecar_wire_bytes(&mut groups).unwrap();
        assert!(groups[0].entities[0].conflict_summary.is_some());
        for (entity, bytes) in groups[0].entities.iter().zip(&groups[0].wire) {
            assert_eq!(
                entity
                    .source_relation_summary
                    .as_ref()
                    .unwrap()
                    .auto_aligned,
                auto_aligned.then_some(true)
            );
            assert_eq!(*bytes, pulled_entity_wire(entity).unwrap());
            let wire: Value = serde_json::from_slice(bytes).unwrap();
            assert_eq!(
                wire["source_relation_summary"]["auto_aligned"],
                if auto_aligned {
                    Value::Bool(true)
                } else {
                    Value::Null
                }
            );
        }
    }
    // The earlier empty conflict still suppresses the later nonempty one.
    assert!(projected[&("record".to_owned(), "r1".to_owned())]
        .conflict
        .is_none());
}
