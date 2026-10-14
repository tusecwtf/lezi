use super::*;

#[tokio::test]
async fn opt_in_probe_observes_real_sync_stages_without_changing_wire() {
    use lezi_sync::diagnostics::{PerformanceProbe, Phase, Stage};
    let probe = Arc::new(PerformanceProbe::default());
    let rig = Rig::with_config(|config| config.performance_probe = Some(probe.clone()));
    let owner = create_family(&rig.app, "stage-owner", "stage-owner-request-000000000001").await;
    let token = owner["access_token"].as_str().unwrap();
    let baby = seed_baby(&rig.app, token).await;
    seed_record_then_log_media(
        &rig.app,
        token,
        &Uuid::new_v4().to_string(),
        &Uuid::new_v4().to_string(),
        &baby,
        20,
    )
    .await;
    let (status, heartbeat) = get_json(&rig.app, "/v1/sync/heartbeat", Some(token)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(heartbeat.as_object().unwrap().len(), 3);
    let (status, pull) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    assert_eq!(status, StatusCode::OK);
    assert!(pull["entities"].as_array().unwrap().len() >= 3);
    let snapshot = probe.snapshot();
    for (stage, counts) in snapshot.stages.iter().take(5) {
        assert!(counts.submitted > 0, "{stage:?}");
        assert_eq!(counts.finished, counts.submitted, "{stage:?}");
        assert_eq!(
            counts.queued + counts.running + counts.awaiting,
            0,
            "{stage:?}"
        );
        assert_eq!(counts.await_abandoned, 0, "{stage:?}");
        for phase in [Phase::BlockingQueue, Phase::BlockingWork] {
            assert!(snapshot
                .samples
                .iter()
                .any(|sample| sample.stage == *stage && sample.phase == phase));
        }
    }
    for stage in [Stage::HeartbeatRead, Stage::PullRead, Stage::CommitDurable] {
        for phase in [Phase::FamilyLockWait, Phase::FamilyLockHold] {
            assert!(snapshot
                .samples
                .iter()
                .any(|sample| sample.stage == stage && sample.phase == phase));
        }
    }
}

/// Synthetic in-process Router evidence, not phone/network/NAS latency.
/// Run the same source/fixture on both compared revisions in release mode.
#[tokio::test]
#[ignore = "exploratory raw stage samples: cargo test --release --test api sync_stage_timing -- --ignored --nocapture"]
async fn sync_stage_timing() {
    use lezi_sync::diagnostics::PerformanceProbe;
    let probe = Arc::new(PerformanceProbe::default());
    let rig = Rig::with_config(|config| {
        config.performance_probe = Some(probe.clone());
        config.heartbeat_rate_limit.max_attempts = 1000;
    });
    let owner = create_family(
        &rig.app,
        "timing-owner",
        "timing-owner-request-000000000001",
    )
    .await;
    let token = owner["access_token"].as_str().unwrap();
    seed_baby(&rig.app, token).await;
    let (_, initial) = get_json(&rig.app, "/v1/pull?cursor=0", Some(token)).await;
    let cursor = initial["cursor"].as_i64().unwrap();
    let uri = format!("/v1/pull?cursor={cursor}&include_live_census=true");
    println!(
        "sync_stage_fixture,one_baby,no_media,warmup=5,samples=30,transport=in_process_router"
    );
    println!("iteration,stage,phase,nanoseconds");
    for iteration in 0..35 {
        let before = probe.snapshot();
        let total_before = before.overwritten_samples + before.samples.len() as u64;
        let started = Instant::now();
        let (status, _) = get_json(&rig.app, "/v1/sync/heartbeat", Some(token)).await;
        assert_eq!(status, StatusCode::OK);
        let heartbeat_wall = started.elapsed();
        let started = Instant::now();
        let (status, pull) = get_json(&rig.app, &uri, Some(token)).await;
        assert_eq!(status, StatusCode::OK);
        assert!(pull["entities"].as_array().unwrap().is_empty());
        assert_eq!(pull["cursor"], cursor);
        let pull_wall = started.elapsed();
        if iteration >= 5 {
            let after = probe.snapshot();
            let offset = total_before
                .checked_sub(after.overwritten_samples)
                .expect("one iteration must fit the bounded sample ring")
                as usize;
            for sample in &after.samples[offset..] {
                println!(
                    "{},{:?},{:?},{}",
                    iteration - 5,
                    sample.stage,
                    sample.phase,
                    sample.elapsed.as_nanos()
                );
            }
            println!(
                "{},Heartbeat,Request,{}",
                iteration - 5,
                heartbeat_wall.as_nanos()
            );
            println!("{},Pull,Request,{}", iteration - 5, pull_wall.as_nanos());
        }
    }
}

async fn wait_for_worker(
    probe: &lezi_sync::diagnostics::PerformanceProbe,
    stage: lezi_sync::diagnostics::Stage,
) {
    tokio::time::timeout(Duration::from_secs(5), async {
        while probe.snapshot().stages[stage as usize].1.running == 0 {
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
}

async fn assert_pending<T>(task: &mut tokio::task::JoinHandle<T>) {
    assert!(tokio::time::timeout(Duration::from_millis(50), task)
        .await
        .is_err());
}

#[tokio::test]
async fn cancelled_commit_keeps_pull_serialized_until_real_work_finishes() {
    use lezi_sync::diagnostics::{PerformanceProbe, Stage};
    let probe = Arc::new(PerformanceProbe::default());
    let rig = Rig::with_config(|config| config.performance_probe = Some(probe.clone()));
    let owner = create_family(&rig.app, "cancel-owner", "cancel-owner-request-00000000001").await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    // Populate the auth cache before deliberately occupying SQLite's writer.
    assert_eq!(
        get_json(&rig.app, "/v1/sync/heartbeat", Some(&token))
            .await
            .0,
        StatusCode::OK
    );
    let baby_id = Uuid::new_v4();
    let mut connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let transaction = connection
        .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
        .unwrap();
    let app = rig.app.clone();
    let worker_token = token.clone();
    let request = tokio::spawn(async move {
        causal_commit_units(
            &app,
            &worker_token,
            vec![causal_unit(
                Uuid::new_v4(),
                None,
                "baby",
                baby_id,
                causal_baby_root("synthetic", None, 10),
                vec![],
                false,
            )],
        )
        .await
    });
    wait_for_worker(&probe, Stage::CommitDurable).await;
    request.abort();
    assert!(request.await.unwrap_err().is_cancelled());
    let cancelled = probe.snapshot().stages[Stage::CommitDurable as usize].1;
    assert_eq!(cancelled.running, 1);
    assert_eq!(cancelled.await_abandoned, 1);
    let app = rig.app.clone();
    let mut pull =
        tokio::spawn(async move { get_json(&app, "/v1/pull?cursor=0", Some(&token)).await });
    assert_pending(&mut pull).await;
    assert_eq!(
        probe.snapshot().stages[Stage::PullRead as usize]
            .1
            .submitted,
        0
    );
    transaction.rollback().unwrap();
    let (status, after) = tokio::time::timeout(Duration::from_secs(5), pull)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(status, StatusCode::OK, "{after}");
    assert!(after["entities"]
        .as_array()
        .unwrap()
        .iter()
        .any(|entity| entity["client_uuid"] == baby_id.to_string()));
    let finished = probe.snapshot().stages[Stage::CommitDurable as usize].1;
    assert_eq!(finished.finished_after_await_abandoned, 1);
    assert_eq!(finished.running, 0);
}

#[tokio::test]
async fn cancelled_bundle_stage_and_commit_keep_same_family_reads_serialized() {
    use lezi_sync::diagnostics::{PerformanceProbe, Stage};
    let probe = Arc::new(PerformanceProbe::default());
    let rig = Rig::with_config(|config| config.performance_probe = Some(probe.clone()));
    let owner = create_family(&rig.app, "bundle-owner", "bundle-owner-request-00000000001").await;
    let token = owner["access_token"].as_str().unwrap().to_owned();
    let baby = seed_baby(&rig.app, &token).await;
    let plan = Uuid::new_v4().to_string();
    let (status, body) = publish_root_bundle(
        &rig.app,
        &token,
        entity_wire(
            "care_plan",
            &plan,
            1,
            care_plan_payload(&baby, "bath"),
            None,
        ),
    )
    .await;
    assert_eq!(status, StatusCode::OK, "{body}");
    let record = Uuid::new_v4().to_string();
    seed_record_with_id(&rig.app, &token, &record, 2, record_payload(&baby)).await;
    let bundle = Uuid::new_v4().to_string();
    let stage_body = json!({
        "bundle_id": bundle,
        "root": entity_wire("fulfillment_candidate", &Uuid::new_v4().to_string(), 3,
            json!({"care_plan_client_uuid": plan, "record_client_uuid": record}), None),
        "media": [],
    });
    for (stage, uri, body, expected_status) in [
        (
            Stage::MediaStage,
            "/v1/bundles".to_owned(),
            stage_body,
            "staging",
        ),
        (
            Stage::MediaCommit,
            format!("/v1/bundles/{bundle}/commit"),
            json!({}),
            "committed",
        ),
    ] {
        let mut connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
        let transaction = connection
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .unwrap();
        let app = rig.app.clone();
        let worker_token = token.clone();
        let request = tokio::spawn(async move {
            json_request(&app, Method::POST, &uri, Some(&worker_token), body).await
        });
        wait_for_worker(&probe, stage).await;
        request.abort();
        assert!(request.await.unwrap_err().is_cancelled());
        assert_eq!(probe.snapshot().stages[stage as usize].1.running, 1);
        let app = rig.app.clone();
        let worker_token = token.clone();
        let uri = format!("/v1/bundles/{bundle}");
        let mut follower =
            tokio::spawn(async move { get_json(&app, &uri, Some(&worker_token)).await });
        assert_pending(&mut follower).await;
        transaction.rollback().unwrap();
        let (status, body) = tokio::time::timeout(Duration::from_secs(5), follower)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(status, StatusCode::OK, "{body}");
        assert_eq!(body["status"], expected_status, "{body}");
        let completed = probe.snapshot().stages[stage as usize].1;
        assert_eq!(completed.running, 0);
        assert_eq!(completed.finished_after_await_abandoned, 1);
    }
}

#[tokio::test]
async fn cancelled_family_create_keeps_provisioning_lock_until_worker_finishes() {
    use lezi_sync::diagnostics::{PerformanceProbe, Stage};
    let probe = Arc::new(PerformanceProbe::default());
    let rig = Rig::with_config(|config| config.performance_probe = Some(probe.clone()));
    let mut connection = Connection::open(rig.directory.path().join("lezi.db")).unwrap();
    let transaction = connection
        .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
        .unwrap();
    let app = rig.app.clone();
    let request = tokio::spawn(async move {
        create_family(
            &app,
            "provision-owner",
            "provision-request-00000000000000000001",
        )
        .await
    });
    wait_for_worker(&probe, Stage::FamilyCreate).await;
    request.abort();
    assert!(request.await.unwrap_err().is_cancelled());
    let app = rig.app.clone();
    let mut replay = tokio::spawn(async move {
        create_family(
            &app,
            "provision-owner",
            "provision-request-00000000000000000001",
        )
        .await
    });
    assert_pending(&mut replay).await;
    assert_eq!(
        probe.snapshot().stages[Stage::FamilyCreate as usize]
            .1
            .submitted,
        1
    );
    transaction.rollback().unwrap();
    let result = tokio::time::timeout(Duration::from_secs(5), replay)
        .await
        .unwrap()
        .unwrap();
    assert!(result["access_token"].is_string());
    let families: i64 = connection
        .query_row("SELECT count(*) FROM families", [], |row| row.get(0))
        .unwrap();
    assert_eq!(families, 1);
    let completed = probe.snapshot().stages[Stage::FamilyCreate as usize].1;
    assert_eq!(completed.finished, 2);
    assert_eq!(completed.finished_after_await_abandoned, 1);
}
