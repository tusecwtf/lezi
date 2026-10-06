use serde_json::{Map, Value};
use std::collections::BTreeSet;

const SCHEMA: &str = include_str!("../../../config/conflict-v2-golden.schema.json");
const CORPUS: &str = include_str!("../../../config/conflict-v2-golden.json");

#[test]
fn shared_corpus_conforms_to_the_frozen_schema_and_cross_case_invariants() {
    let schema: Value = serde_json::from_str(SCHEMA).expect("golden schema must be JSON");
    let corpus: Value = serde_json::from_str(CORPUS).expect("golden corpus must be JSON");

    validate(&schema, &corpus, &schema, "$").unwrap_or_else(|error| panic!("{error}"));
    assert_eq!(schema["$id"], corpus["$schema"]);
    assert_eq!(
        corpus["capability"]["key"],
        lezi_sync::CAPABILITY_CAUSAL_SYNC_V2,
    );

    let schema_codes = strings(&schema["$defs"]["terminalCode"]["enum"]);
    assert_eq!(strings(&corpus["terminal_error_codes"]), schema_codes);
    let terminal_examples = corpus["terminal_error_examples"]
        .as_array()
        .expect("terminal examples");
    assert_eq!(
        terminal_examples
            .iter()
            .map(|example| example["code"].as_str().expect("example code"))
            .collect::<Vec<_>>(),
        schema_codes,
    );
    for example in terminal_examples {
        assert_eq!(example["code"], example["response"]["error"]["code"]);
    }

    let required_ids = schema["properties"]["cases"]["allOf"]
        .as_array()
        .expect("case coverage clauses")
        .iter()
        .map(|clause| {
            clause["contains"]["properties"]["id"]["const"]
                .as_str()
                .expect("covered case id")
        })
        .collect::<BTreeSet<_>>();
    let cases = corpus["cases"].as_array().expect("cases");
    let actual_ids = cases
        .iter()
        .map(|case| case["id"].as_str().expect("case id"))
        .collect::<BTreeSet<_>>();
    assert_eq!(actual_ids, required_ids);
    assert_eq!(actual_ids.len(), cases.len(), "case ids must be unique");

    assert_dataflow_02_care_plan(&corpus["dataflow_02_care_plan"]);
    assert_dataflow_01_wake_shapes(&corpus["dataflow_01_sleep_wake"]);
    assert_dataflow_04_fulfillment_candidate(&corpus["dataflow_04_fulfillment_candidate"]);
    assert_dataflow_05_custom_item(&corpus["dataflow_05_custom_item"]);
    assert_dataflow_06_media(&corpus["dataflow_06_media"]);
    assert_dataflow_07_record_non_sleep(&corpus["dataflow_07_record_non_sleep"]);

    assert_snapshot_invariants(case(cases, "snapshot-two-pages"));
    assert_choice_invariants(cases);
    assert_disjoint_case(case(cases, "snapshot-auto-conflict-disjoint"));
    assert_restore_invariants(cases);
    assert_rejection_has_one_invalid_dimension(case(cases, "canonical-wrong-type"));
    assert_replay_invariants(case(cases, "commit-exact-replay-marker"));
    assert_dataflow_03_portable_identity(&corpus);

    let mut missing_snapshot_field = corpus.clone();
    missing_snapshot_field["cases"]
        .as_array_mut()
        .expect("cases")
        .iter_mut()
        .find(|case| case["id"] == "snapshot-two-pages")
        .expect("snapshot case")["input"]["pages"][0]
        .as_object_mut()
        .expect("snapshot page")
        .remove("stable");
    assert!(
        validate(&schema, &missing_snapshot_field, &schema, "$").is_err(),
        "schema must reject drift in a case body",
    );

    let mut unbound_choice = corpus.clone();
    case_mut(
        unbound_choice["cases"].as_array_mut().expect("cases"),
        "resolve-choice-only-full-set",
    )["input"]["choices"][0]["choice_id"] = Value::String("choice_not_in_receipt".to_owned());
    assert!(
        !submitted_choices_belong_to_snapshot(unbound_choice["cases"].as_array().expect("cases")),
        "resolution must reject a shape-valid choice outside that path's receipt candidates",
    );

    let mut missing_restore_choice = corpus.clone();
    case_mut(
        missing_restore_choice["cases"]
            .as_array_mut()
            .expect("cases"),
        "resolve-direct-base-restore",
    )["input"]
        .as_object_mut()
        .expect("restore input")
        .remove("choice_id");
    assert!(
        validate(&schema, &missing_restore_choice, &schema, "$").is_err(),
        "successful restore requires a receipt choice",
    );

    let mut swapped_replay_markers = corpus.clone();
    let replay = case_mut(
        swapped_replay_markers["cases"]
            .as_array_mut()
            .expect("cases"),
        "commit-exact-replay-marker",
    );
    replay["expect"]["first"]["replay"] = Value::Bool(true);
    replay["expect"]["second"]["replay"] = Value::Bool(false);
    assert!(
        validate(&schema, &swapped_replay_markers, &schema, "$").is_err(),
        "first/replayed terminal markers must not swap",
    );

    assert_dataflow_01_sleep_wake(&schema, &corpus);

    let mut ambiguous_identity = corpus["terminal_error_examples"][0]["response"].clone();
    let response = ambiguous_identity
        .as_object_mut()
        .expect("terminal response");
    response.insert(
        "mutation_id".to_owned(),
        Value::String("00000000-0000-0000-0000-000000000001".to_owned()),
    );
    response.insert(
        "resolution_mutation_id".to_owned(),
        Value::String("00000000-0000-0000-0000-000000000002".to_owned()),
    );
    assert!(
        validate(
            &schema["$defs"]["terminalResponse"],
            &ambiguous_identity,
            &schema,
            "$terminalResponse",
        )
        .is_err(),
        "terminal identity fields must be mutually exclusive",
    );
}

fn assert_dataflow_02_care_plan(table: &Value) {
    assert_eq!(table["entity_type"], "care_plan");
    let mutation = strings(&table["mutation_keys"]);
    let stable = strings(&table["stable_keys"]);
    let forbidden = strings(&table["stamp_rules"]["mutation_forbidden"]);
    let stable_required = strings(&table["stamp_rules"]["stable_required"]);
    // Emit-set semantics: the client mutation emit set is the corpus mutation
    // closure minus the forbidden stamps, so the stable closure is exactly the
    // emit set plus the stable_required server stamps.
    let stable_only: Vec<&str> = stable
        .iter()
        .copied()
        .filter(|key| !mutation.contains(key))
        .collect();
    assert_eq!(
        stable_only, stable_required,
        "stable closure must be the emit set plus stable_required stamps"
    );
    assert!(
        !mutation.iter().any(|key| forbidden.contains(key)),
        "mutation emit set must not carry forbidden stamps"
    );
    assert!(mutation.contains(&"source_record_client_uuid"));
    assert!(mutation.contains(&"fulfilled_record_client_uuid"));
    assert!(mutation.contains(&"fulfilled_at"));
    assert!(forbidden.contains(&"created_by_membership_id"));
    assert!(stable_required.contains(&"created_by_membership_id"));
    assert_eq!(
        table["fulfillment_pair"]["completed_requires_full_pair"],
        true
    );
    assert_eq!(
        table["fulfillment_pair"]["pending_requires_empty_pair"],
        true
    );
    assert_eq!(
        table["fulfillment_pair"]["skipped_requires_empty_pair"],
        true
    );
    let not_on_wire = strings(&table["not_on_family_wire"]);
    assert!(not_on_wire.contains(&"system_calendar_event_id"));
    assert!(not_on_wire.contains(&"sync_dirty"));
    for key in &not_on_wire {
        assert!(!mutation.contains(key), "{key} must not be a mutation key");
        assert!(!stable.contains(key), "{key} must not be a stable key");
    }
}

fn assert_dataflow_01_wake_shapes(section: &Value) {
    let shapes = &section["wake_shapes"];
    let wake_table = &section["wake_observation"];
    // The three-shape table and the root closure table are one contract: a
    // one-sided corpus edit must redden this cross-binding, not just one table.
    let mutation_set: BTreeSet<&str> = strings(&wake_table["mutation_keys"]).into_iter().collect();
    let stable_set: BTreeSet<&str> = strings(&wake_table["stable_keys"]).into_iter().collect();
    let local_mutation: BTreeSet<&str> = strings(&shapes["local_mutation_keys"])
        .into_iter()
        .collect();
    let stable_root: BTreeSet<&str> = strings(&shapes["stable_root_keys"]).into_iter().collect();
    assert_eq!(local_mutation, mutation_set);
    assert_eq!(stable_root, stable_set);
    assert_eq!(
        strings(&shapes["local_mutation_keys"]),
        [
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn",
            "updated_at"
        ]
    );
    assert_eq!(
        strings(&shapes["pull_keys"]),
        [
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn"
        ]
    );
    assert_eq!(shapes["pull_stamp_optional"], "observer_membership_id");
    assert_eq!(
        strings(&shapes["stable_root_keys"]),
        [
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn",
            "observer_membership_id",
            "updated_at"
        ]
    );
}

fn assert_dataflow_04_fulfillment_candidate(table: &Value) {
    assert_eq!(table["entity_type"], "fulfillment_candidate");
    assert_eq!(table["publish_channel"], "atomic_bundle_only");
    let mutation = strings(&table["mutation_keys"]);
    let stable = strings(&table["stable_keys"]);
    assert_eq!(
        mutation,
        [
            "care_plan_client_uuid",
            "record_client_uuid",
            "actual_timestamp",
            "submitter_membership_id",
            "submitter_role",
            "confirmed_at",
            "updated_at"
        ]
    );
    assert_eq!(stable, mutation);
    let trail = strings(&table["stamp_rules"]["mutation_trail"]);
    let stable_required = strings(&table["stamp_rules"]["stable_required"]);
    assert_eq!(
        trail,
        ["submitter_membership_id", "submitter_role", "confirmed_at"]
    );
    assert_eq!(stable_required, trail);
    assert_eq!(
        table["stamp_rules"]["server_on_mutation"],
        "ignore_and_restamp"
    );
    let frozen = strings(&table["frozen_evidence"]);
    for key in &trail {
        assert!(
            !frozen.contains(key),
            "{key} is server-stamped, not frozen evidence"
        );
    }
    assert_eq!(table["idempotent_replay"], "exact_evidence_lww_noop");
}

fn assert_dataflow_05_custom_item(table: &Value) {
    assert_eq!(table["entity_type"], "custom_item");
    let mutation = strings(&table["mutation_keys"]);
    let stable = strings(&table["stable_keys"]);
    assert_eq!(mutation, ["name", "icon_slot", "updated_at"]);
    assert_eq!(
        stable,
        [
            "name",
            "icon_slot",
            "created_by_membership_id",
            "updated_at"
        ]
    );
    assert_eq!(
        table["stamp_rules"]["mutation_stamp_optional"],
        "created_by_membership_id"
    );
    let stable_required = strings(&table["stamp_rules"]["stable_required"]);
    assert_eq!(stable_required, ["created_by_membership_id"]);
    assert_eq!(
        table["stamp_rules"]["server_on_mutation"],
        "ignore_and_restamp"
    );
    let not_on_wire = strings(&table["not_on_family_wire"]);
    assert_eq!(not_on_wire, ["sortOrder", "hide", "slots"]);
    for key in &not_on_wire {
        assert!(!mutation.contains(key), "{key} must not be a mutation key");
        assert!(!stable.contains(key), "{key} must not be a stable key");
    }
    assert_eq!(table["max_undeleted"], 10);
}

fn assert_dataflow_06_media(table: &Value) {
    assert_eq!(
        strings(&table["manifest_item_keys"]),
        [
            "media_uuid",
            "role",
            "sha256",
            "byte_size",
            "mime",
            "width",
            "height"
        ]
    );
    assert_eq!(
        strings(&table["entity_keys"]),
        [
            "kind",
            "record_client_uuid",
            "care_plan_client_uuid",
            "baby_client_uuid",
            "mime",
            "width",
            "height",
            "byte_size"
        ]
    );
    assert_eq!(table["entity_has_content_identity"], false);
    assert_eq!(table["wake_download_without_expectation"], "fail_closed");
}

fn assert_dataflow_07_record_non_sleep(table: &Value) {
    assert_eq!(table["entity_type"], "record");
    assert_eq!(table["shape"], "end_timestamp_null_injected");
    let mutation = strings(&table["mutation_keys"]);
    let stable = strings(&table["stable_keys"]);
    assert_eq!(
        mutation,
        [
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "end_timestamp",
            "note",
            "payload_json",
            "schema_version",
            "updated_at"
        ]
    );
    let forbidden = strings(&table["stamp_rules"]["mutation_forbidden"]);
    assert_eq!(forbidden, ["created_by_membership_id"]);
    assert!(
        !mutation.iter().any(|key| forbidden.contains(key)),
        "mutation emit set must not carry forbidden stamps"
    );
    let stable_required = strings(&table["stamp_rules"]["stable_required"]);
    assert_eq!(stable_required, ["created_by_membership_id"]);
    let stable_only: Vec<&str> = stable
        .iter()
        .copied()
        .filter(|key| !mutation.contains(key))
        .collect();
    assert_eq!(
        stable_only, stable_required,
        "stable closure must be the emit set plus stable_required stamps"
    );
}

fn assert_snapshot_invariants(case: &Value) {
    let pages = case["input"]["pages"].as_array().expect("snapshot pages");
    let first = &pages[0];
    let identity_keys = [
        "contract",
        "conflict_id",
        "entity_type",
        "client_uuid",
        "snapshot_token",
        "expires_at",
        "stable",
    ];
    for (index, page) in pages.iter().enumerate() {
        assert_eq!(page["page_index"], index);
        for key in identity_keys {
            assert_eq!(page[key], first[key], "snapshot {key} drifted across pages");
        }
        assert_eq!(page["complete"], index + 1 == pages.len());
        assert_eq!(page["continuation"].is_null(), index + 1 == pages.len());
    }

    let branch_ids = pages
        .iter()
        .flat_map(|page| page["branches"].as_array().expect("branches"))
        .map(|branch| branch["version_id"].as_str().expect("branch version"))
        .collect::<Vec<_>>();
    assert_eq!(branch_ids, strings(&case["expect"]["full_branch_set"]));
    assert_eq!(first["snapshot_token"], case["expect"]["resolution_token"]);

    let conflicting = pages
        .iter()
        .flat_map(|page| page["conflicting"].as_array().expect("conflicting"))
        .map(|item| item["path"].as_str().expect("conflicting path"))
        .collect::<BTreeSet<_>>();
    let auto_merged = pages
        .iter()
        .flat_map(|page| page["auto_merged"].as_array().expect("auto_merged"))
        .map(|item| item["path"].as_str().expect("auto path"))
        .collect::<BTreeSet<_>>();
    assert!(conflicting.is_disjoint(&auto_merged));
}

fn assert_choice_invariants(cases: &[Value]) {
    let pages = case(cases, "snapshot-choice-stable-across-pages");
    let page_choices = strings(&pages["input"]["page_choice_ids"]);
    assert!(page_choices.iter().all(|choice| *choice == page_choices[0]));
    assert_eq!(pages["expect"]["choice_id"], page_choices[0]);

    let restart = case(cases, "snapshot-choice-stable-after-restart");
    assert_eq!(
        restart["input"]["before_restart"],
        restart["input"]["after_restart"]
    );
    assert_eq!(restart["expect"]["same"], true);

    let resolve = case(cases, "resolve-choice-only-full-set");
    let keys = resolve["input"]
        .as_object()
        .expect("resolution request")
        .keys()
        .map(String::as_str)
        .collect::<BTreeSet<_>>();
    assert_eq!(
        keys,
        strings(&resolve["expect"]["request_keys"])
            .into_iter()
            .collect()
    );
    let snapshot = case(cases, "snapshot-two-pages");
    assert_eq!(
        resolve["input"]["snapshot_token"],
        snapshot["expect"]["resolution_token"]
    );
    let conflicting_paths = snapshot["input"]["pages"]
        .as_array()
        .expect("snapshot pages")
        .iter()
        .flat_map(|page| page["conflicting"].as_array().expect("conflicting"))
        .map(|item| item["path"].as_str().expect("conflicting path"))
        .collect::<BTreeSet<_>>();
    let choice_paths = resolve["input"]["choices"]
        .as_array()
        .expect("choices")
        .iter()
        .map(|choice| choice["path"].as_str().expect("choice path"))
        .collect::<Vec<_>>();
    assert!(choice_paths.windows(2).all(|pair| pair[0] < pair[1]));
    assert_eq!(
        choice_paths.into_iter().collect::<BTreeSet<_>>(),
        conflicting_paths
    );
    assert!(submitted_choices_belong_to_snapshot(cases));
}

fn submitted_choices_belong_to_snapshot(cases: &[Value]) -> bool {
    let snapshot = case(cases, "snapshot-two-pages");
    let resolve = case(cases, "resolve-choice-only-full-set");
    resolve["input"]["choices"]
        .as_array()
        .expect("choices")
        .iter()
        .all(|choice| {
            snapshot["input"]["pages"]
                .as_array()
                .expect("snapshot pages")
                .iter()
                .flat_map(|page| page["conflicting"].as_array().expect("conflicting"))
                .find(|conflict| conflict["path"] == choice["path"])
                .is_some_and(|conflict| {
                    conflict["candidates"]
                        .as_array()
                        .expect("candidates")
                        .iter()
                        .any(|candidate| candidate["choice_id"] == choice["choice_id"])
                })
        })
}

fn assert_restore_invariants(cases: &[Value]) {
    let direct = case(cases, "resolve-direct-base-restore");
    assert_eq!(
        direct["input"]["stable"]["base_version"],
        direct["input"]["direct_base"]["version_id"]
    );
    assert_eq!(
        direct["expect"]["restore_version"],
        direct["input"]["direct_base"]["version_id"]
    );

    let missing = case(cases, "resolve-missing-direct-base");
    assert!(missing["input"]["direct_base"].is_null());
    assert_eq!(missing["expect"]["error"]["code"], "missing_restore_base");

    let missing_media = case(cases, "resolve-missing-direct-base-media");
    assert_eq!(
        missing_media["input"]["direct_base"]["media_bytes_readable"],
        false
    );
    assert_eq!(
        missing_media["expect"]["error"]["code"],
        "missing_restore_media"
    );
}

fn assert_disjoint_case(case: &Value) {
    let conflicting = strings(&case["input"]["conflicting_paths"])
        .into_iter()
        .collect::<BTreeSet<_>>();
    let auto_merged = strings(&case["input"]["auto_merged_paths"])
        .into_iter()
        .collect::<BTreeSet<_>>();
    assert!(conflicting.is_disjoint(&auto_merged));
    assert_eq!(case["expect"]["intersection"], Value::Array(vec![]));
}

fn assert_dataflow_01_sleep_wake(schema: &Value, corpus: &Value) {
    let required = strings(&schema["required"]);
    assert!(
        required.contains(&"dataflow_01_sleep_wake"),
        "schema must require the sleep/wake closed-set table",
    );
    assert!(schema["properties"]
        .as_object()
        .expect("schema properties")
        .contains_key("dataflow_01_sleep_wake"));

    let table = &corpus["dataflow_01_sleep_wake"];
    assert_eq!(
        strings(&table["sleep_record"]["mutation_keys"]),
        [
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "note",
            "payload_json",
            "schema_version",
            "updated_at",
            "effective_wake_observation_client_uuid",
        ]
    );
    assert_eq!(
        strings(&table["sleep_record"]["stable_keys"]),
        [
            "baby_client_uuid",
            "type",
            "custom_item_client_uuid",
            "timestamp",
            "note",
            "payload_json",
            "schema_version",
            "updated_at",
            "effective_wake_observation_client_uuid",
            "created_by_membership_id",
        ]
    );
    assert_eq!(
        strings(&table["sleep_record"]["forbidden_keys"]),
        ["end_timestamp"]
    );
    assert_eq!(table["sleep_record"]["effective_wake"], "required_nullable");
    assert_eq!(
        strings(&table["wake_observation"]["mutation_keys"]),
        [
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn",
            "updated_at",
        ]
    );
    assert_eq!(
        strings(&table["wake_observation"]["stable_keys"]),
        [
            "sleep_record_client_uuid",
            "wake_timestamp",
            "note",
            "withdrawn",
            "updated_at",
            "observer_membership_id",
        ]
    );
    assert_eq!(
        strings(&table["wake_observation"]["forbidden_keys"]),
        ["end_timestamp", "deleted", "deleted_at"]
    );

    let roles = table["media_roles"]
        .as_array()
        .expect("media_roles")
        .iter()
        .map(|role| {
            (
                role["role"].as_str().expect("role"),
                role["owner_entity_type"].as_str().expect("owner"),
                role["kind"].as_str().expect("kind"),
                role["owner_wire_field"].as_str().expect("field"),
                role["max_items"].as_u64().expect("max"),
            )
        })
        .collect::<Vec<_>>();
    assert_eq!(
        roles,
        vec![
            ("wake", "wake_observation", "wake", "record_client_uuid", 3),
            ("log", "record", "log", "record_client_uuid", 3),
            ("plan", "care_plan", "log", "care_plan_client_uuid", 3),
            ("avatar", "baby", "avatar", "baby_client_uuid", 1),
        ]
    );
}

fn assert_replay_invariants(case: &Value) {
    assert_eq!(
        strings(&case["expect"]["batch_keys"]),
        ["generation", "results"]
    );
    assert_eq!(
        strings(&case["expect"]["unit_required_keys"]),
        ["status", "mutation_id", "request_hash", "replay", "stable"]
    );
    assert_eq!(
        strings(&case["expect"]["unit_optional_keys"]),
        ["branch_version_id", "conflict_id"]
    );
    assert_eq!(
        strings(&case["expect"]["stable_keys"]),
        ["version_id", "root", "media", "deleted", "deleted_at"]
    );
    assert_eq!(
        case["expect"]["first"]["status"],
        case["expect"]["second"]["status"]
    );
    assert_eq!(case["expect"]["first"]["replay"], false);
    assert_eq!(case["expect"]["second"]["replay"], true);
    assert_eq!(case["expect"]["new_status"], false);
}

fn assert_dataflow_03_portable_identity(corpus: &Value) {
    let section = &corpus["dataflow_03_portable_identity"];
    let tables = section["typed_payload_keys"]
        .as_object()
        .expect("typed payload keys");
    assert_eq!(
        tables.keys().map(String::as_str).collect::<BTreeSet<_>>(),
        [
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
        ]
        .into_iter()
        .collect::<BTreeSet<_>>(),
    );
    assert_eq!(
        strings(&section["typed_payload_keys"]["custom"]["required"]),
        ["title"]
    );
    assert_eq!(
        strings(&section["typed_payload_keys"]["custom"]["optional"]),
        ["detail", "icon_slot"]
    );
    assert_eq!(
        strings(&section["typed_payload_keys"]["custom"]["forbidden_on_portable"]),
        ["custom_item_id"]
    );
    assert_eq!(
        strings(&section["typed_payload_keys"]["formula"]["required"]),
        ["amount_ml"]
    );
    assert_eq!(
        strings(&section["baby_closed_set"]["business_keys"]),
        [
            "nickname",
            "sex",
            "birthday",
            "birth_weight_grams",
            "avatar_media_uuid"
        ]
    );
    assert_eq!(
        section["baby_closed_set"]["stamp_key"],
        "created_by_membership_id"
    );
    assert_eq!(section["stamp_fields"]["mutation"], "forbid");
    assert_eq!(section["stamp_fields"]["stable"], "require");
    assert_eq!(section["stamp_fields"]["snapshot"], "require");
    assert_eq!(
        section["stamp_fields"]["server_on_mutation"],
        "ignore_and_restamp"
    );
    assert_eq!(
        strings(&section["stamp_fields"]["keys"]),
        ["created_by_membership_id", "observer_membership_id"]
    );
    assert!(!section["samples"]["portable_custom_payload"]
        .as_object()
        .expect("portable custom")
        .contains_key("custom_item_id"));
    assert!(section["samples"]["baby_mutation"]
        .as_object()
        .expect("baby mutation")
        .contains_key("birth_weight_grams"));
    assert!(!section["samples"]["baby_mutation"]
        .as_object()
        .expect("baby mutation")
        .contains_key("created_by_membership_id"));
    assert!(section["samples"]["baby_stable"]
        .as_object()
        .expect("baby stable")
        .contains_key("created_by_membership_id"));
}

fn assert_rejection_has_one_invalid_dimension(case: &Value) {
    let root = &case["input"]["root"];
    assert_eq!(root["type"], "formula", "record type must stay valid");
    assert!(
        root["timestamp"].is_string(),
        "timestamp is the sole wrong type"
    );
    assert_eq!(case["expect"]["error"]["code"], "wrong_type");
}

fn case<'a>(cases: &'a [Value], id: &str) -> &'a Value {
    cases
        .iter()
        .find(|case| case["id"] == id)
        .unwrap_or_else(|| panic!("missing case {id}"))
}

fn case_mut<'a>(cases: &'a mut [Value], id: &str) -> &'a mut Value {
    cases
        .iter_mut()
        .find(|case| case["id"] == id)
        .unwrap_or_else(|| panic!("missing case {id}"))
}

fn strings(value: &Value) -> Vec<&str> {
    value
        .as_array()
        .expect("string array")
        .iter()
        .map(|item| item.as_str().expect("string item"))
        .collect()
}

fn validate(schema: &Value, instance: &Value, root: &Value, path: &str) -> Result<(), String> {
    if let Some(accept) = schema.as_bool() {
        return accept
            .then_some(())
            .ok_or_else(|| format!("{path}: rejected by false schema"));
    }
    let object = schema
        .as_object()
        .ok_or_else(|| format!("{path}: schema is not an object"))?;
    reject_unknown_keywords(object, path)?;

    if let Some(reference) = object.get("$ref") {
        let reference = reference
            .as_str()
            .ok_or_else(|| format!("{path}: non-string $ref"))?;
        let name = reference
            .strip_prefix("#/$defs/")
            .ok_or_else(|| format!("{path}: only local $defs refs are supported"))?;
        validate(&root["$defs"][name], instance, root, path)?;
    }
    if let Some(expected_type) = object.get("type") {
        let matches = match expected_type {
            Value::String(kind) => matches_type(instance, kind),
            Value::Array(kinds) => kinds.iter().any(|kind| {
                kind.as_str()
                    .is_some_and(|kind| matches_type(instance, kind))
            }),
            _ => false,
        };
        if !matches {
            return Err(format!("{path}: type mismatch"));
        }
    }
    if object
        .get("const")
        .is_some_and(|expected| expected != instance)
    {
        return Err(format!("{path}: const mismatch"));
    }
    if object
        .get("enum")
        .and_then(Value::as_array)
        .is_some_and(|values| !values.contains(instance))
    {
        return Err(format!("{path}: not in enum"));
    }
    for subschema in object.get("allOf").into_iter().flat_map(array) {
        validate(subschema, instance, root, path)?;
    }
    if let Some(subschemas) = object.get("anyOf").map(array) {
        if !subschemas
            .iter()
            .any(|item| validate(item, instance, root, path).is_ok())
        {
            return Err(format!("{path}: no anyOf branch matched"));
        }
    }
    if let Some(subschemas) = object.get("oneOf").map(array) {
        let matches = subschemas
            .iter()
            .filter(|item| validate(item, instance, root, path).is_ok())
            .count();
        if matches != 1 {
            return Err(format!("{path}: expected one oneOf match, got {matches}"));
        }
    }
    if object
        .get("not")
        .is_some_and(|item| validate(item, instance, root, path).is_ok())
    {
        return Err(format!("{path}: matched forbidden schema"));
    }

    if let Some(value) = instance.as_object() {
        let properties = object.get("properties").and_then(Value::as_object);
        for required in object.get("required").into_iter().flat_map(array) {
            let required = required.as_str().expect("required key must be a string");
            if !value.contains_key(required) {
                return Err(format!("{path}: missing {required}"));
            }
        }
        if let Some(properties) = properties {
            for (key, subschema) in properties {
                if let Some(child) = value.get(key) {
                    validate(subschema, child, root, &format!("{path}/{key}"))?;
                }
            }
            if object.get("additionalProperties") == Some(&Value::Bool(false)) {
                for key in value.keys() {
                    if !properties.contains_key(key) {
                        return Err(format!("{path}: unexpected {key}"));
                    }
                }
            }
        }
    }
    if let Some(values) = instance.as_array() {
        if let Some(minimum) = object.get("minItems").and_then(Value::as_u64) {
            if values.len() < minimum as usize {
                return Err(format!("{path}: too few items"));
            }
        }
        if let Some(maximum) = object.get("maxItems").and_then(Value::as_u64) {
            if values.len() > maximum as usize {
                return Err(format!("{path}: too many items"));
            }
        }
        if object.get("uniqueItems") == Some(&Value::Bool(true)) {
            for (index, value) in values.iter().enumerate() {
                if values[..index].contains(value) {
                    return Err(format!("{path}: duplicate item"));
                }
            }
        }
        if let Some(items) = object.get("items") {
            for (index, value) in values.iter().enumerate() {
                validate(items, value, root, &format!("{path}/{index}"))?;
            }
        }
        if let Some(contains) = object.get("contains") {
            if !values
                .iter()
                .any(|value| validate(contains, value, root, path).is_ok())
            {
                return Err(format!("{path}: contains did not match"));
            }
        }
    }
    if let Some(value) = instance.as_str() {
        if let Some(minimum) = object.get("minLength").and_then(Value::as_u64) {
            if value.chars().count() < minimum as usize {
                return Err(format!("{path}: string too short"));
            }
        }
        if let Some(pattern) = object.get("pattern").and_then(Value::as_str) {
            if !matches_pattern(value, pattern) {
                return Err(format!("{path}: pattern mismatch"));
            }
        }
    }
    if let Some(minimum) = object.get("minimum").and_then(Value::as_f64) {
        if instance.as_f64().is_none_or(|value| value < minimum) {
            return Err(format!("{path}: below minimum"));
        }
    }
    Ok(())
}

fn reject_unknown_keywords(schema: &Map<String, Value>, path: &str) -> Result<(), String> {
    const SUPPORTED: &[&str] = &[
        "$schema",
        "$id",
        "$defs",
        "$ref",
        "title",
        "type",
        "additionalProperties",
        "required",
        "properties",
        "const",
        "enum",
        "oneOf",
        "anyOf",
        "allOf",
        "not",
        "items",
        "contains",
        "minItems",
        "maxItems",
        "uniqueItems",
        "minLength",
        "pattern",
        "minimum",
    ];
    for key in schema.keys() {
        if !SUPPORTED.contains(&key.as_str()) {
            return Err(format!(
                "{path}: validator does not support schema keyword {key}"
            ));
        }
    }
    Ok(())
}

fn array(value: &Value) -> &[Value] {
    value.as_array().expect("schema keyword must be an array")
}

fn matches_type(value: &Value, kind: &str) -> bool {
    match kind {
        "object" => value.is_object(),
        "array" => value.is_array(),
        "string" => value.is_string(),
        "integer" => value.as_i64().is_some() || value.as_u64().is_some(),
        "boolean" => value.is_boolean(),
        "null" => value.is_null(),
        _ => false,
    }
}

fn matches_pattern(value: &str, pattern: &str) -> bool {
    match pattern {
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" => {
            let lengths = [8, 4, 4, 4, 12];
            value.split('-').map(str::len).eq(lengths)
                && value.chars().all(|character| {
                    character == '-'
                        || character.is_ascii_hexdigit() && !character.is_ascii_uppercase()
                })
        }
        "^[0-9a-f]{64,}$" => value.len() >= 64 && is_lower_hex(value),
        "^[0-9a-f]{64}$" => value.len() == 64 && is_lower_hex(value),
        "^[A-Za-z0-9_-]{16,}$" => {
            value.len() >= 16
                && value
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-')
        }
        "^/(?:[^~/]|~0|~1)+(?:/(?:[^~/]|~0|~1)+)*$" => {
            value.starts_with('/')
                && value.len() > 1
                && !value.split('/').skip(1).any(|segment| {
                    segment.is_empty()
                        || segment.match_indices('~').any(|(index, _)| {
                            !matches!(segment.as_bytes().get(index + 1), Some(b'0' | b'1'))
                        })
                })
        }
        _ => panic!("unsupported regex pattern {pattern}"),
    }
}

fn is_lower_hex(value: &str) -> bool {
    value
        .bytes()
        .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}
