//! US-087 / BN-05: authenticated media boundaries survive the production lifecycle.
//! All listeners, certificates, identities and bytes belong to isolated temporary roots.
#[path = "support/media_lifecycle_budget.rs"]
mod request_budget;

use request_budget::{curl_seconds, startup_remaining, startup_step, RequestBudget};
use rusqlite::{Connection, OpenFlags};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Write,
    net::{TcpListener, TcpStream},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    thread,
    time::{Duration, Instant},
};
use uuid::Uuid;

const ROOT: &str = "synthetic-media-boundary-root-password";
// Valid 1x1 RGBA PNG. Manifest dimensions deliberately vary independently:
// this probes accepted metadata boundaries, not decoded-image dimensions.
const BYTES: &[u8] = &[
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4,
    0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63, 0xb0, 0x9d, 0x74, 0xe7,
    0x3f, 0x00, 0x05, 0x66, 0x02, 0xab, 0xa4, 0x48, 0x1d, 0x2e, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45,
    0x4e, 0x44, 0xae, 0x42, 0x60, 0x82,
];

struct Server {
    child: Child,
    directory: PathBuf,
    public_port: u16,
    internal_port: u16,
    startup_deadline: Option<Instant>,
}

impl Server {
    fn start(directory: &Path) -> Self {
        // Reserve distinct ephemeral ports together before handing them to the child.
        let public = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let internal = TcpListener::bind(("127.0.0.1", 0)).unwrap();
        let public_port = public.local_addr().unwrap().port();
        let internal_port = internal.local_addr().unwrap().port();
        drop((public, internal));
        let log = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(directory.join("server.log"))
            .unwrap();
        let child = Command::new(env!("CARGO_BIN_EXE_lezi-sync"))
            .env("LEZI_DATA_DIR", directory)
            .env("LEZI_BOOTSTRAP_SECRET", ROOT)
            .env("LEZI_HOST", "127.0.0.1")
            .env("LEZI_PORT", public_port.to_string())
            .env("LEZI_INTERNAL_PORT", internal_port.to_string())
            .env("LEZI_TLS_CERTFILE", directory.join("server.crt"))
            .env("LEZI_TLS_KEYFILE", directory.join("server.key"))
            // Default TraceLayer records method/URI/status/latency, with both
            // request and response headers disabled; never enable header traces.
            .env("RUST_LOG", "lezi_sync=info,tower_http::trace=debug")
            .env_remove("LEZI_LAN_APK_DOWNLOAD_ORIGIN")
            .env_remove("LEZI_INVITE_INSTALL_ORIGIN")
            .env_remove("LEZI_APP_UPDATE_METADATA_PATH")
            .env_remove("LEZI_APP_UPDATE_APK_PATH")
            .stdout(log.try_clone().unwrap())
            .stderr(log)
            .spawn()
            .unwrap();
        let deadline = Instant::now() + Duration::from_secs(15);
        let mut server = Self {
            child,
            directory: directory.to_owned(),
            public_port,
            internal_port,
            startup_deadline: Some(deadline),
        };
        loop {
            startup_remaining(deadline, Instant::now());
            if let Some(status) = server.child.try_wait().unwrap() {
                panic!(
                    "media fixture startup exited {status}: {}",
                    fs::read_to_string(directory.join("server.log")).unwrap()
                );
            }
            // Check successful probes too: a late ready/setup response must
            // never bypass the unchanged 15s startup deadline.
            let ready = startup_step(deadline, Instant::now, |remaining| {
                Command::new("curl")
                    .args(["--disable", "--fail", "--silent"])
                    .args([
                        "--connect-timeout",
                        &curl_seconds(Duration::from_secs(1).min(remaining)),
                        "--max-time",
                        &curl_seconds(Duration::from_secs(2).min(remaining)),
                    ])
                    .arg(format!("http://127.0.0.1:{internal_port}/ready"))
                    .output()
                    .expect("curl is required for isolated process tests")
            });
            if ready.status.success() {
                let ready: Value = serde_json::from_slice(&ready.stdout).unwrap();
                assert_eq!(ready["status"], "ready");
                assert_eq!(ready["ok"], true);
                startup_step(deadline, Instant::now, |_| {
                    assert_eq!(
                        server.json("GET", "/v1/setup-status", None, None)["protocol_version"],
                        1
                    );
                });
                server.startup_deadline = None;
                break;
            }
            thread::sleep(
                Duration::from_millis(50).min(startup_remaining(deadline, Instant::now())),
            );
        }
        server
    }

    fn request(
        &self,
        method: &str,
        path: &str,
        token: Option<&str>,
        body: Option<&[u8]>,
        media: bool,
    ) -> Vec<u8> {
        let mut command = Command::new("curl");
        // Ignore personal curlrc settings (including retries) in this isolated
        // fixture. --disable must be the first curl argument.
        command.arg("--disable");
        RequestBudget::for_request(method, path).apply(
            &mut command,
            self.startup_deadline
                .map(|deadline| startup_remaining(deadline, Instant::now())),
        );
        command
            .args([
                "--fail-with-body",
                "--silent",
                "--show-error",
                "--cacert",
            ])
            .arg(self.directory.join("server.crt"))
            .args(["--request", method])
            .args([
                "--write-out",
                "%{stderr}curl timings: status=%{http_code} dns=%{time_namelookup} tcp=%{time_connect} tls=%{time_appconnect} total=%{time_total} remote=%{remote_ip}:%{remote_port} local=%{local_ip}:%{local_port}\n",
            ])
            .args(["--header", &format!("X-Lezi-Bootstrap-Secret: {ROOT}")])
            .args(["--header", "X-Lezi-Client-Version-Code: 35"])
            .args([
                "--header",
                "X-Lezi-Sync-Capabilities: nursing_plan_intent_v1,restore_authority_v1",
            ])
            .args(["--header", "X-Lezi-Media-Identity: v1"]);
        if let Some(token) = token {
            command.args(["--header", &format!("Authorization: Bearer {token}")]);
        }
        if media {
            command.args(["--header", "Content-Type: application/octet-stream"]);
            command.args([
                "--header",
                &format!(
                    "X-Lezi-Media-Sha256: {}",
                    hex::encode(Sha256::digest(body.unwrap()))
                ),
            ]);
        } else {
            command.args(["--header", "Content-Type: application/json"]);
        }
        if body.is_some() {
            command.args(["--data-binary", "@-"]).stdin(Stdio::piped());
        }
        let mut request = command
            .arg(format!("https://localhost:{}{path}", self.public_port))
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        if let Some(body) = body {
            request.stdin.take().unwrap().write_all(body).unwrap();
        }
        let response = request.wait_with_output().unwrap();
        if !response.status.success() {
            self.log_failure_scale();
        }
        assert!(
            response.status.success(),
            "{method} {path}: response_bytes={} {}\nserver log: {}",
            response.stdout.len(),
            String::from_utf8_lossy(&response.stderr),
            fs::read_to_string(self.directory.join("server.log")).unwrap_or_default()
        );
        response.stdout
    }

    // Post-failure diagnostics only: no extra HTTP request, retry, writer, or
    // retained directory containing synthetic credentials. Counts are separate
    // read-only observations and may straddle an in-flight writer's commit.
    fn log_failure_scale(&self) {
        for name in ["lezi.db", "lezi.db-wal", "lezi.db-shm"] {
            match fs::metadata(self.directory.join(name)) {
                Ok(metadata) => eprintln!("US-087 failure file {name}: {} bytes", metadata.len()),
                Err(error) => eprintln!("US-087 failure file {name}: unavailable: {error}"),
            }
        }
        let db = match Connection::open_with_flags(
            self.directory.join("lezi.db"),
            OpenFlags::SQLITE_OPEN_READ_ONLY,
        ) {
            Ok(db) => db,
            Err(error) => {
                eprintln!("US-087 failure read-only database unavailable: {error}");
                return;
            }
        };
        if let Err(error) = db.busy_timeout(Duration::ZERO) {
            eprintln!("US-087 failure zero-busy diagnostic unavailable: {error}");
            return;
        }
        for table in [
            "entities",
            "entity_versions",
            "entity_version_media",
            "entity_stable_heads",
            "conflicts",
            "conflict_branches",
            "conflict_resolutions",
            "media_publications",
            "causal_media_staging",
        ] {
            let rows = db.query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |row| {
                row.get::<_, i64>(0)
            });
            match rows {
                Ok(rows) => eprintln!("US-087 failure table {table}: {rows} rows"),
                Err(error) => eprintln!("US-087 failure table {table}: unavailable: {error}"),
            }
        }
    }

    fn json(&self, method: &str, path: &str, token: Option<&str>, body: Option<&Value>) -> Value {
        let bytes = body.map(|value| serde_json::to_vec(value).unwrap());
        serde_json::from_slice(&self.request(method, path, token, bytes.as_deref(), false)).unwrap()
    }

    fn commit(&self, session: &Value, unit: &Value, status: &str) -> Value {
        let result = self.json(
            "POST",
            "/v1/causal/commit",
            session["access_token"].as_str(),
            Some(&json!({"generation":session["generation"],"units":[unit]})),
        );
        assert_eq!(result["results"][0]["status"], status, "{result}");
        result["results"][0].clone()
    }

    fn stop(&mut self) {
        #[cfg(unix)]
        unsafe {
            assert_eq!(libc::kill(self.child.id() as libc::pid_t, libc::SIGTERM), 0);
        }
        #[cfg(not(unix))]
        self.child.kill().unwrap();
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            if let Some(status) = self.child.try_wait().unwrap() {
                #[cfg(unix)]
                assert!(
                    status.success(),
                    "SIGTERM did not shut down cleanly: {status}"
                );
                break;
            }
            assert!(
                Instant::now() < deadline,
                "server did not stop after SIGTERM"
            );
            thread::sleep(Duration::from_millis(50));
        }
        assert!(TcpStream::connect(("127.0.0.1", self.public_port)).is_err());
        assert!(TcpStream::connect(("127.0.0.1", self.internal_port)).is_err());
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        if thread::panicking() {
            match self.child.try_wait() {
                Ok(Some(status)) => eprintln!("US-087 child status at failure: exited {status}"),
                Ok(None) => eprintln!("US-087 child status at failure: still running"),
                Err(error) => eprintln!("US-087 child status at failure: unavailable: {error}"),
            }
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn prepare_directory(directory: &Path) {
    assert!(Command::new("openssl")
        .args([
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-sha256",
            "-days",
            "2",
            "-nodes",
            "-subj",
            "/CN=localhost",
            "-addext",
            "subjectAltName=DNS:localhost,IP:127.0.0.1",
            "-keyout"
        ])
        .arg(directory.join("server.key"))
        .arg("-out")
        .arg(directory.join("server.crt"))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .expect("openssl is required for isolated process tests")
        .success());
    let catalog: Value = serde_json::from_str(include_str!(
        "../../../config/android-release-compatibility.json"
    ))
    .unwrap();
    let apk = b"synthetic media boundary release APK";
    fs::write(directory.join("app-release.apk"), apk).unwrap();
    fs::write(
        directory.join("app-update.json"),
        json!({
            "package_name":catalog["application_id"],
            "version_code":catalog["upgrade_target"]["version_code"],
            "version_name":catalog["upgrade_target"]["version_name"],
            "min_supported_version_code":catalog["minimum_sync_version_code"],
            "sha256":hex::encode(Sha256::digest(apk)),
        })
        .to_string(),
    )
    .unwrap();
}

fn sessions(server: &Server) -> (Value, Value) {
    let owner = server.json("POST", "/v1/family/create", None, Some(&json!({"create_request_id":Uuid::new_v4(),"display_name":"Synthetic owner","device_name":"Owner device","family_name":"Media boundary family"})));
    assert!(
        owner["access_token"].is_string(),
        "missing owner access token"
    );
    let pending = server.json(
        "POST",
        "/v1/member/requests",
        None,
        Some(&json!({"display_name":"Synthetic member","device_name":"Member device"})),
    );
    server.json(
        "POST",
        &format!(
            "/v1/member/requests/{}/approve-new",
            pending["request_id"].as_str().unwrap()
        ),
        owner["access_token"].as_str(),
        Some(&json!({})),
    );
    let member = server.json(
        "POST",
        "/v1/member/requests/claim",
        None,
        Some(&json!({"pending_secret":pending["pending_secret"]})),
    );
    assert_eq!(member["role"], "member", "unexpected claimed role");
    (owner, member)
}

fn root(kind: &str, baby: Uuid, media: Option<&Value>) -> Value {
    match kind {
        "baby" => {
            json!({"nickname":"Synthetic baby","sex":"female","birthday":"2025-01-02","birth_weight_grams":null,"avatar_media_uuid":media.map(|item| item["media_uuid"].clone()),"updated_at":100})
        }
        "record" => {
            json!({"baby_client_uuid":baby,"type":"formula","custom_item_client_uuid":null,"timestamp":1000,"end_timestamp":null,"note":"base","payload_json":{"amount_ml":100},"schema_version":2,"updated_at":100})
        }
        "care_plan" => {
            json!({"baby_client_uuid":baby,"type":"bath","custom_item_client_uuid":null,"scheduled_at":1700000000000_i64,"scheduled_zone_id":"UTC","note":"base","status":"pending","payload_json":{},"schema_version":2,"created_by_membership_id":null,"fulfilled_record_client_uuid":null,"fulfilled_at":null,"source_record_client_uuid":null,"updated_at":100})
        }
        _ => unreachable!(),
    }
}

fn unit(kind: &str, id: Uuid, base: &Value, root: &Value, media: &[Value]) -> Value {
    json!({"mutation_id":Uuid::new_v4(),"base_version":base,"entity_type":kind,"client_uuid":id,"root":root,"media":media,"deleted":false})
}

fn stage(
    server: &Server,
    session: &Value,
    role: &str,
    width: Option<i64>,
    height: Option<i64>,
) -> Value {
    let id = Uuid::new_v4();
    let sha = hex::encode(Sha256::digest(BYTES));
    let staged: Value = serde_json::from_slice(&server.request(
        "PUT",
        &format!("/v1/causal/media/{id}"),
        session["access_token"].as_str(),
        Some(BYTES),
        true,
    ))
    .unwrap();
    assert_eq!(staged["status"], "staged", "{staged}");
    assert_eq!(staged["sha256"], sha);
    assert_eq!(staged["byte_size"], BYTES.len());
    json!({"media_uuid":id,"role":role,"sha256":sha,"byte_size":BYTES.len(),"mime":"image/png","width":width,"height":height})
}

fn connection(directory: &Path) -> Connection {
    Connection::open_with_flags(directory.join("lezi.db"), OpenFlags::SQLITE_OPEN_READ_ONLY)
        .unwrap()
}

fn schema(directory: &Path) -> (i64, Vec<(String, String, String)>) {
    let db = connection(directory);
    let version = db
        .pragma_query_value(None, "user_version", |row| row.get(0))
        .unwrap();
    let mut query = db.prepare("SELECT type,name,sql FROM sqlite_schema WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' ORDER BY type,name").unwrap();
    let objects = query
        .query_map([], |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))
        .unwrap()
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    (version, objects)
}

fn durable_state(directory: &Path) -> Vec<Vec<Vec<rusqlite::types::Value>>> {
    let db = connection(directory);
    [
        "entities",
        "entity_versions",
        "entity_version_parents",
        "entity_version_media",
        "entity_stable_heads",
        "mutation_receipts",
        "conflicts",
        "conflict_branches",
        "conflict_resolutions",
        "media_publications",
        "causal_media_staging",
        "family_meta",
    ]
    .iter()
    .map(|table| {
        let mut query = db
            .prepare(&format!("SELECT * FROM {table} ORDER BY rowid"))
            .unwrap();
        let columns = query.column_count();
        let rows = query
            .query_map([], |row| {
                (0..columns)
                    .map(|index| row.get(index))
                    .collect::<Result<Vec<_>, _>>()
            })
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        rows
    })
    .collect()
}

fn assert_replay(server: &Server, session: &Value, request: &Value, original: &Value) {
    let mut replay = server.commit(session, request, original["status"].as_str().unwrap());
    assert_eq!(replay["replay"], true, "{replay}");
    replay["replay"] = json!(false);
    assert_eq!(&replay, original);
}

fn assert_resolution_replay(
    server: &Server,
    session: &Value,
    path: &str,
    request: &Value,
    original: &Value,
) {
    let mut replay = server.json(
        "POST",
        path,
        session["access_token"].as_str(),
        Some(request),
    );
    assert_eq!(replay["replay"], true, "{replay}");
    replay["replay"] = json!(false);
    assert_eq!(&replay, original);
}

fn resolution_input(detail: &Value, branch_version: &Value) -> Value {
    assert_eq!(detail["complete"], true, "incomplete conflict detail");
    let choices: Vec<_> = detail["conflicting"]
        .as_array()
        .unwrap()
        .iter()
        .map(|path| {
            let candidate = path["candidates"]
                .as_array()
                .unwrap()
                .iter()
                .find(|candidate| {
                    candidate["sources"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|source| source["version_id"] == *branch_version)
                })
                .unwrap();
            json!({"path":path["path"],"choice_id":candidate["choice_id"]})
        })
        .collect();
    assert!(
        !choices.is_empty(),
        "boundary metadata must require a real media choice"
    );
    json!({"snapshot_token":detail["snapshot_token"],"resolution_mutation_id":Uuid::new_v4(),"choices":choices})
}

#[test]
fn authenticated_media_dimensions_commit_merge_resolve_replay_and_process_restart_ready() {
    for (kind, role) in [("record", "log"), ("care_plan", "plan"), ("baby", "avatar")] {
        eprintln!("US-087 {kind}: starting isolated HTTPS process");
        let directory = tempfile::tempdir().unwrap();
        prepare_directory(directory.path());
        let certificate = fs::read(directory.path().join("server.crt")).unwrap();
        let mut server = Server::start(directory.path());
        let original_schema = schema(directory.path());
        assert_eq!(original_schema.0, 13);
        assert_eq!(original_schema.1.len(), 53);
        let (owner, member) = sessions(&server);
        // Records and plans are genuinely authored by the authenticated member;
        // avatars remain Owner-only, without changing production ACLs.
        let session = if kind == "baby" { &owner } else { &member };
        let baby = Uuid::new_v4();
        server.commit(
            &owner,
            &unit("baby", baby, &Value::Null, &root("baby", baby, None), &[]),
            "accepted",
        );
        let mut replays = Vec::new();
        for width in [None, Some(1), Some(i64::from(i32::MAX))] {
            for height in [None, Some(1), Some(i64::from(i32::MAX))] {
                let media = stage(&server, session, role, width, height);
                let manifest = vec![media.clone()];
                let id = Uuid::new_v4();
                let create = unit(
                    kind,
                    id,
                    &Value::Null,
                    &root(kind, baby, Some(&media)),
                    &manifest,
                );
                let created = server.commit(session, &create, "accepted");
                assert_eq!(created["stable"]["media"], json!(manifest));
                assert_eq!(
                    created["stable"]["root"]["created_by_membership_id"],
                    session["membership_id"]
                );
                assert_replay(&server, session, &create, &created);
                let base = created["stable"]["version_id"].clone();
                let mut left = created["stable"]["root"].clone();
                left[if kind == "baby" { "nickname" } else { "note" }] = json!("left edit");
                left["updated_at"] = json!(101);
                server.commit(
                    session,
                    &unit(kind, id, &base, &left, &manifest),
                    "accepted",
                );
                let mut right = created["stable"]["root"].clone();
                match kind {
                    "baby" => right["birth_weight_grams"] = json!(3300),
                    "record" => right["payload_json"]["amount_ml"] = json!(180),
                    "care_plan" => right["scheduled_at"] = json!(1700000001000_i64),
                    _ => unreachable!(),
                }
                right["updated_at"] = json!(102);
                let merge = unit(kind, id, &base, &right, &manifest);
                let merged = server.commit(session, &merge, "merged");
                assert_eq!(merged["stable"]["media"], json!(manifest));
                assert_eq!(
                    merged["stable"]["root"][if kind == "baby" { "nickname" } else { "note" }],
                    "left edit"
                );
                assert_replay(&server, session, &merge, &merged);

                // Make the accepted boundary itself a media conflict candidate.
                // Dimensions 2 and 3 are distinct from every tested boundary.
                let mut two = media.clone();
                two["width"] = json!(2);
                two["height"] = json!(2);
                let mut three = media.clone();
                three["width"] = json!(3);
                three["height"] = json!(3);
                let merged_root = &merged["stable"]["root"];
                let resolution_base = server.commit(
                    session,
                    &unit(
                        kind,
                        id,
                        &merged["stable"]["version_id"],
                        merged_root,
                        &[two],
                    ),
                    "accepted",
                );
                let resolution_base_id = &resolution_base["stable"]["version_id"];
                server.commit(
                    session,
                    &unit(kind, id, resolution_base_id, merged_root, &[three]),
                    "accepted",
                );
                let branch = server.commit(
                    session,
                    &unit(kind, id, resolution_base_id, merged_root, &manifest),
                    "branched",
                );
                let conflict = branch["conflict_id"].as_str().unwrap();
                let detail = server.json(
                    "GET",
                    &format!("/v1/conflicts/{conflict}"),
                    session["access_token"].as_str(),
                    None,
                );
                let mut resolution = resolution_input(&detail, &branch["branch_version_id"]);
                let path = format!("/v1/conflicts/{conflict}/resolve");
                if kind != "baby" {
                    let before = durable_state(directory.path());
                    eprintln!("US-087 {kind} width={width:?} height={height:?}: member resolve submit, expect forbidden");
                    let forbidden = server.json(
                        "POST",
                        &path,
                        member["access_token"].as_str(),
                        Some(&resolution),
                    );
                    assert_eq!(forbidden["status"], "rejected", "{forbidden}");
                    assert_eq!(forbidden["error"]["code"], "forbidden", "{forbidden}");
                    assert_eq!(durable_state(directory.path()), before);
                    // Wire §8.2 permits only the Owner to adopt a live stable
                    // snapshot with open branches. Obtain that actor's snapshot
                    // after proving that its author cannot bypass this ACL.
                    let owner_detail = server.json(
                        "GET",
                        &format!("/v1/conflicts/{conflict}"),
                        owner["access_token"].as_str(),
                        None,
                    );
                    resolution = resolution_input(&owner_detail, &branch["branch_version_id"]);
                }
                eprintln!("US-087 {kind} width={width:?} height={height:?}: Owner resolve submit");
                let resolved = server.json(
                    "POST",
                    &path,
                    owner["access_token"].as_str(),
                    Some(&resolution),
                );
                assert_eq!(
                    resolved["status"], "accepted",
                    "{kind} {width:?}/{height:?}: {resolved}"
                );
                assert_eq!(resolved["stable_media"], json!(manifest));
                assert_eq!(
                    resolved["stable_root"]["created_by_membership_id"],
                    session["membership_id"]
                );
                eprintln!("US-087 {kind} width={width:?} height={height:?}: Owner resolve replay before restart");
                assert_resolution_replay(&server, &owner, &path, &resolution, &resolved);
                replays.push((
                    id, media, create, created, merge, merged, path, resolution, resolved,
                ));
            }
        }
        // Legal bytes and ownership reach the metadata validator. Rejections
        // must not partially publish, replace a head or create a receipt.
        for invalid in [0, -1, i64::from(i32::MAX) + 1, i64::MAX] {
            for field in ["width", "height"] {
                let mut media = stage(&server, session, role, Some(1), Some(1));
                media[field] = json!(invalid);
                let request = unit(
                    kind,
                    Uuid::new_v4(),
                    &Value::Null,
                    &root(kind, baby, Some(&media)),
                    &[media],
                );
                let before = durable_state(directory.path());
                let rejected = server.json(
                    "POST",
                    "/v1/causal/commit",
                    session["access_token"].as_str(),
                    Some(&json!({"generation":session["generation"],"units":[request]})),
                );
                assert_eq!(rejected["status"], "rejected", "{rejected}");
                assert_eq!(rejected["error"]["code"], "invalid_domain", "{rejected}");
                assert_eq!(
                    durable_state(directory.path()),
                    before,
                    "{kind} {field}={invalid}"
                );
            }
        }
        eprintln!("US-087 {kind}: 9 accepted dimension pairs completed commit/merge/resolve/replay; 8 invalid dimensions rejected without writes");
        server.stop();
        drop(server);
        eprintln!("US-087 {kind}: restarting persisted data and certificate");
        let mut restarted = Server::start(directory.path());
        eprintln!("US-087 {kind}: restarted process passed readiness and HTTPS setup");
        assert_eq!(schema(directory.path()), original_schema);
        assert_eq!(
            fs::read(directory.path().join("server.crt")).unwrap(),
            certificate
        );
        let db = connection(directory.path());
        for (id, media, create, created, merge, merged, path, resolution, resolved) in replays {
            assert_replay(&restarted, session, &create, &created);
            assert_replay(&restarted, session, &merge, &merged);
            eprintln!(
                "US-087 {kind} width={} height={}: Owner resolve replay after restart",
                media["width"], media["height"]
            );
            assert_resolution_replay(&restarted, &owner, &path, &resolution, &resolved);
            let stored: String = db.query_row("SELECT m.media_payload_json FROM entity_stable_heads AS h JOIN entity_version_media AS m ON m.family_id=h.family_id AND m.version_id=h.version_id WHERE h.family_id=?1 AND h.entity_type=?2 AND h.client_uuid=?3", rusqlite::params![session["family_id"].as_str().unwrap(), kind, id.to_string()], |row| row.get(0)).unwrap();
            assert_eq!(serde_json::from_str::<Value>(&stored).unwrap(), media);
            let bytes = restarted.request(
                "GET",
                &format!("/v1/media/{}", media["media_uuid"].as_str().unwrap()),
                session["access_token"].as_str(),
                None,
                false,
            );
            assert_eq!(bytes, BYTES);
            assert_eq!(hex::encode(Sha256::digest(&bytes)), media["sha256"]);
        }
        restarted.stop();
        eprintln!("US-087 {kind}: restart replays, exact PNG bytes and schema checks passed");
    }
}
