//! Opt-in measurements over the real Store and server initialization paths.
//! See `docs/dev/server-hotpaths.md` for the baseline/candidate procedure.

use std::path::PathBuf;
use std::time::Instant;

use serde_json::json;
use uuid::Uuid;

use super::super::*;
use crate::{build_server_apps, ServerConfig};

#[test]
#[ignore = "release-profile timing probe; requires LEZI_TIMING_DATA_DIR and LEZI_TIMING_OPERATION"]
fn runtime_hotpath_timing() {
    let directory = PathBuf::from(std::env::var_os("LEZI_TIMING_DATA_DIR").unwrap());
    let database_path = directory.join("lezi.db");
    let operation = std::env::var("LEZI_TIMING_OPERATION").unwrap();
    if operation == "seed" {
        assert!(
            !database_path.exists(),
            "seed only into a fresh isolated directory"
        );
        let store = Store::open(&database_path).unwrap();
        let session = store
            .create_family(
                CreateFamilyInput {
                    now: 1_700_000_000,
                    create_request_id: "runtime-timing-synthetic-family",
                    display_name: "Synthetic owner",
                    display_name_key: "synthetic owner",
                    family_name: "Synthetic timing fixture",
                    device_name: "Synthetic device",
                    owner_root_fingerprint: None,
                },
                |_, _, _| ("timing-access".to_owned(), "timing-refresh".to_owned()),
            )
            .unwrap();
        let owner = store
            .authenticate(&session.access_token, 1_700_000_000)
            .unwrap()
            .unwrap();
        let baby_id = Uuid::from_u128(1);
        let root = json!({
            "nickname": "Synthetic baby", "sex": "female", "birthday": "2025-01-02",
            "avatar_media_uuid": null, "updated_at": 10,
        });
        store
            .causal_commit(&owner, vec![mutation("baby", baby_id, root)], 1_700_000_000)
            .unwrap();
        let rows: usize = std::env::var("LEZI_TIMING_ROWS")
            .unwrap_or_else(|_| "1000".to_owned())
            .parse()
            .unwrap();
        for batch in (0..rows).collect::<Vec<_>>().chunks(MAX_CAUSAL_UNITS) {
            let units = batch.iter().map(|index| mutation(
                "record",
                Uuid::from_u128(*index as u128 + 2),
                json!({
                    "baby_client_uuid": baby_id, "type": "formula", "custom_item_client_uuid": null,
                    "timestamp": 1_700_000_000_000i64 + *index as i64 * 60_000,
                    "end_timestamp": null, "note": "Synthetic timing record",
                    "payload_json": {"amount_ml": 100}, "schema_version": 2, "updated_at": 20,
                }),
            )).collect();
            let result = store
                .causal_commit(&owner, units, 1_700_000_061 + batch[0] as i64)
                .unwrap();
            assert!(result
                .results
                .iter()
                .all(|result| result.status == "accepted"));
        }
        let apps = build_server_apps(config(&directory)).unwrap();
        drop(apps);
        println!("runtime_timing_seed: records={rows}");
        return;
    }
    assert!(
        database_path.is_file(),
        "create the synthetic fixture first"
    );
    assert!(matches!(operation.as_str(), "store" | "startup"));
    let iterations: usize = std::env::var("LEZI_TIMING_ITERATIONS")
        .unwrap_or_else(|_| "51".to_owned())
        .parse()
        .unwrap();
    let mut samples = Vec::with_capacity(iterations);
    for _ in 0..iterations {
        let start = Instant::now();
        if operation == "store" {
            std::hint::black_box(Store::open(&database_path).unwrap());
        } else {
            std::hint::black_box(build_server_apps(config(&directory)).unwrap());
        }
        samples.push(start.elapsed().as_nanos());
    }
    println!(
        "runtime_timing: {}",
        json!({"operation": operation, "samples_ns": samples})
    );
}

fn config(directory: &std::path::Path) -> ServerConfig {
    let mut config = ServerConfig::new(directory);
    config.server_secret = Some(vec![0x42; 32]);
    config.generation = Some("synthetic-timing-generation".to_owned());
    config.clock = Arc::new(|| 1_700_100_000);
    config
}

fn mutation(entity_type: &str, client_uuid: Uuid, root: serde_json::Value) -> CausalMutation {
    CausalMutation {
        mutation_id: Uuid::new_v4().to_string(),
        base_version: None,
        entity_type: entity_type.to_owned(),
        client_uuid: client_uuid.to_string(),
        root: root.as_object().unwrap().clone(),
        media: vec![],
        deleted: false,
    }
}
