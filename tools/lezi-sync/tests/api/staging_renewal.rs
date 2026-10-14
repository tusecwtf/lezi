//! HTTP retry after an unrecorded receipt, with expiration injected under the
//! prepare lock so detached GC cannot turn this into an unrelated fresh upload.
use super::*;

#[tokio::test]
async fn causal_media_expired_full_stream_retry_renews_then_commits_and_cleans_upload() {
    for old_status in ["writing", "staged"] {
        let expire = Arc::new(Mutex::new(
            None::<(std::path::PathBuf, String, String, i64)>,
        ));
        let hook_expire = expire.clone();
        let rig = Rig::with_config(|config| {
            config.max_media_bytes = 64 * 1024;
            config.causal_media_prepare_blocking_hook = Some(Arc::new(move |phase| {
                if phase == "before_store" {
                    if let Some((database, family, media, now)) = hook_expire.lock().unwrap().take()
                    {
                        let connection = Connection::open(&database).unwrap();
                        assert_eq!(connection.execute(
                            "UPDATE causal_media_staging SET status=?1, expires_at=?2 WHERE family_id=?3 AND media_uuid=?4",
                            rusqlite::params![old_status, now, family, media],
                        ).unwrap(), 1);
                        fs::remove_file(
                            database
                                .parent()
                                .unwrap()
                                .join("media/.causal-stage")
                                .join(family)
                                .join(media),
                        )
                        .unwrap();
                    }
                }
            }));
        });
        let owner = create_family(
            &rig.app,
            "renewal-owner",
            "renewal-request-000000000000000000001",
        )
        .await;
        let token = owner["access_token"].as_str().unwrap();
        let family = owner["family_id"].as_str().unwrap();
        let baby = seed_causal_baby(&rig.app, token).await;
        let media = Uuid::new_v4();
        let bytes = b"retry-exact-streamed-bytes";
        let sha = hex::encode(Sha256::digest(bytes));
        let (status, first) = put_causal_media_bytes(&rig.app, token, media, bytes).await;
        assert_eq!(status, StatusCode::OK, "{first}");
        // The client discarded this receipt (for example, a lost response).
        // On retry it sends the same immutable spool, not a cached receipt.
        let database = rig.directory.path().join("lezi.db");
        let created: i64 = Connection::open(&database)
            .unwrap()
            .query_row(
                "SELECT created_at FROM causal_media_staging WHERE family_id=?1 AND media_uuid=?2",
                rusqlite::params![family, media.to_string()],
                |r| r.get(0),
            )
            .unwrap();
        let now = rig.now.fetch_add(1, Ordering::SeqCst) + 1;
        *expire.lock().unwrap() =
            Some((database.clone(), family.to_owned(), media.to_string(), now));
        let chunks = futures_util::stream::iter([
            Ok::<_, std::io::Error>(Bytes::from_static(b"retry-exact-")),
            Ok(Bytes::from_static(b"streamed-bytes")),
        ]);
        let response = request_with_headers(
            &rig.app,
            Method::PUT,
            &format!("/v1/causal/media/{media}"),
            Some(token),
            Body::from_stream(chunks),
            Some("application/octet-stream"),
            &[
                ("x-lezi-media-sha256", sha.as_str()),
                ("content-length", &bytes.len().to_string()),
            ],
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK);
        let renewed: Value = serde_json::from_slice(
            &axum::body::to_bytes(response.into_body(), usize::MAX)
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(renewed["status"], "staged");
        assert_eq!(
            renewed["expires_at"].as_i64().unwrap(),
            first["expires_at"].as_i64().unwrap() + 1
        );
        let connection = Connection::open(&database).unwrap();
        let unchanged_created: i64 = connection
            .query_row(
                "SELECT created_at FROM causal_media_staging WHERE family_id=?1 AND media_uuid=?2",
                rusqlite::params![family, media.to_string()],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(
            unchanged_created, created,
            "renewal must preserve the original row"
        );
        let uploads: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM causal_media_uploads WHERE family_id=?1",
                [family],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(uploads, 0);
        drop(connection);
        let (status, committed) = causal_commit_units(
            &rig.app,
            token,
            vec![causal_unit(
                Uuid::new_v4(),
                None,
                "record",
                Uuid::new_v4(),
                causal_formula_root(baby, "renewed-retry", 80, 20),
                vec![causal_media_item(media, "log", &sha, bytes.len())],
                false,
            )],
        )
        .await;
        assert_eq!(status, StatusCode::OK, "{committed}");
        assert_eq!(committed["results"][0]["status"], "accepted");
        assert_eq!(
            fs::read(
                rig.directory
                    .path()
                    .join("media")
                    .join(family)
                    .join(media.to_string())
            )
            .unwrap(),
            bytes
        );
    }
}
