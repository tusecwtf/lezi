use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::Duration;

use serde_json::{json, Value};
use sha2::{Digest, Sha256};

#[test]
fn public_endpoint_is_https_only_and_keeps_the_same_certificate_across_restart() {
    let directory = tempfile::tempdir().unwrap();
    let certificate = directory.path().join("server.crt");
    let private_key = directory.path().join("server.key");
    generate_certificate(&certificate, &private_key);
    write_protocol_cutover_release(directory.path());
    let certificate_before = std::fs::read(&certificate).unwrap();
    let public_port = free_port();
    let internal_port = free_port();

    for _ in 0..2 {
        let mut server = spawn_server(
            directory.path(),
            &certificate,
            &private_key,
            public_port,
            internal_port,
        );
        let setup = wait_for_https(&certificate, public_port);
        assert_eq!(
            setup,
            json!({
                "protocol_version": 1,
                "capabilities": [
                    "trusted_https_endpoint_v1",
                    "device_sessions_v1",
                    "membership_devices_v1",
                    "atomic_bundle",
                    "record_membership_author",
                    "device_disaster_restore_v1",
                    "authoritative_reconcile_v1",
                    "validated_deferred_fulfillment_v1",
                    "causal_versions",
                    "wake_observation",
                    "source_relations",
                ],
                "family_state": "empty",
            })
        );
        assert!(internal_ready(internal_port));
        assert!(!plain_http_succeeds(public_port));
        server.kill().unwrap();
        server.wait().unwrap();
    }

    assert_eq!(std::fs::read(certificate).unwrap(), certificate_before);
}

#[test]
fn authenticated_sync_handshake_succeeds_over_isolated_tls() {
    let directory = tempfile::tempdir().unwrap();
    let certificate = directory.path().join("server.crt");
    let private_key = directory.path().join("server.key");
    generate_certificate(&certificate, &private_key);
    write_protocol_cutover_release(directory.path());
    let public_port = free_port();
    let internal_port = free_port();
    let mut server = spawn_server(
        directory.path(),
        &certificate,
        &private_key,
        public_port,
        internal_port,
    );
    let _ = wait_for_https(&certificate, public_port);

    let create = curl_json(
        &certificate,
        public_port,
        "/v1/family/create",
        &[
            "X-Lezi-Bootstrap-Secret: tls-test-bootstrap-secret",
            "Content-Type: application/json",
        ],
        json!({
            "create_request_id": "14141414-1414-4141-8141-141414141414",
            "display_name": "Owner",
            "device_name": "TLS test device",
            "family_name": "TLS test family",
        }),
    );
    let token = create["access_token"].as_str().unwrap();
    let authorization = format!("Authorization: Bearer {token}");
    let handshake = curl_json(
        &certificate,
        public_port,
        "/v1/sync/handshake",
        &[
            authorization.as_str(),
            "X-Lezi-Client-Version-Code: 20",
            "Content-Type: application/json",
        ],
        json!({
            "protocol_version": 1,
            "required_capabilities": [
                "causal_versions",
                "source_relations",
                "wake_observation",
            ],
        }),
    );

    assert_eq!(handshake["ready"], true);
    assert_eq!(handshake["principal"]["device_id"], create["device_id"]);
    assert_eq!(
        handshake["principal"]["membership_id"],
        create["membership_id"]
    );
    assert!(handshake["directory_generation"]
        .as_str()
        .is_some_and(|it| !it.is_empty()));
    assert!(!plain_http_succeeds(public_port));
    server.kill().unwrap();
    server.wait().unwrap();
}

fn write_protocol_cutover_release(data_root: &Path) {
    let apk_bytes = b"tls-black-box-release-apk";
    std::fs::write(data_root.join("app-release.apk"), apk_bytes).unwrap();
    std::fs::write(
        data_root.join("app-update.json"),
        json!({
            "package_name": "com.lezi.babylog",
            "version_code": 20,
            "version_name": "0.3.13",
            "min_supported_version_code": 20,
            "sha256": hex::encode(Sha256::digest(apk_bytes)),
        })
        .to_string(),
    )
    .unwrap();
}

#[test]
fn configured_lan_apk_listener_serves_plain_http_and_shuts_down_with_the_server() {
    let directory = tempfile::tempdir().unwrap();
    let certificate = directory.path().join("server.crt");
    let private_key = directory.path().join("server.key");
    generate_certificate(&certificate, &private_key);
    write_protocol_cutover_release(directory.path());
    let public_port = free_port();
    let internal_port = free_port();
    let lan_port = 8767;
    drop(
        TcpListener::bind(("127.0.0.1", lan_port))
            .expect("the fixed LAN APK port 8767 must be free for this black-box test"),
    );
    let mut server = spawn_server_with_lan(
        directory.path(),
        &certificate,
        &private_key,
        public_port,
        internal_port,
        Some("http://127.0.0.1:8767"),
    );

    let _ = wait_for_https(&certificate, public_port);
    let join = wait_for_plain_http(lan_port, "/join");
    assert!(join.starts_with("HTTP/1.1 200"), "{join}");
    assert!(join.contains("下载乐记"), "{join}");
    assert!(!plain_http_succeeds(public_port));

    unsafe {
        libc::kill(server.id() as libc::pid_t, libc::SIGTERM);
    }
    for _ in 0..50 {
        if server.try_wait().unwrap().is_some() {
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }
    server.kill().unwrap();
    server.wait().unwrap();
    panic!("server did not shut down after SIGTERM");
}

fn generate_certificate(certificate: &Path, private_key: &Path) {
    let status = Command::new("openssl")
        .args([
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-sha256",
            "-days",
            "3650",
            "-nodes",
            "-subj",
            "/CN=localhost",
            "-addext",
            "subjectAltName=DNS:localhost,IP:127.0.0.1",
            "-keyout",
        ])
        .arg(private_key)
        .arg("-out")
        .arg(certificate)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .expect("openssl must be installed for the TLS black-box test");
    assert!(status.success());
}

fn spawn_server(
    data_root: &Path,
    certificate: &Path,
    private_key: &Path,
    public_port: u16,
    internal_port: u16,
) -> Child {
    spawn_server_with_lan(
        data_root,
        certificate,
        private_key,
        public_port,
        internal_port,
        None,
    )
}

fn spawn_server_with_lan(
    data_root: &Path,
    certificate: &Path,
    private_key: &Path,
    public_port: u16,
    internal_port: u16,
    lan_origin: Option<&str>,
) -> Child {
    let mut command = Command::new(env!("CARGO_BIN_EXE_lezi-sync"));
    command
        .env("LEZI_DATA_DIR", data_root)
        .env("LEZI_BOOTSTRAP_SECRET", "tls-test-bootstrap-secret")
        .env("LEZI_HOST", "127.0.0.1")
        .env("LEZI_PORT", public_port.to_string())
        .env("LEZI_INTERNAL_PORT", internal_port.to_string())
        .env("LEZI_TLS_CERTFILE", certificate)
        .env("LEZI_TLS_KEYFILE", private_key)
        .env_remove("LEZI_LAN_APK_DOWNLOAD_ORIGIN")
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    if let Some(origin) = lan_origin {
        command.env("LEZI_LAN_APK_DOWNLOAD_ORIGIN", origin);
    }
    command.spawn().unwrap()
}

fn wait_for_https(certificate: &Path, port: u16) -> Value {
    for _ in 0..40 {
        let output = Command::new("curl")
            .args(["--fail", "--silent", "--show-error", "--cacert"])
            .arg(certificate)
            .arg(format!("https://localhost:{port}/v1/setup-status"))
            .output()
            .expect("curl must be installed for the TLS black-box test");
        if output.status.success() {
            return serde_json::from_slice(&output.stdout).unwrap();
        }
        thread::sleep(Duration::from_millis(100));
    }
    panic!("TLS server did not become ready");
}

fn curl_json(certificate: &Path, port: u16, path: &str, headers: &[&str], body: Value) -> Value {
    let mut command = Command::new("curl");
    command
        .args(["--fail", "--silent", "--show-error", "--cacert"])
        .arg(certificate)
        .args(["--request", "POST"]);
    for header in headers {
        command.args(["--header", header]);
    }
    let output = command
        .args(["--data", &body.to_string()])
        .arg(format!("https://localhost:{port}{path}"))
        .output()
        .expect("curl must be installed for the TLS black-box test");
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    serde_json::from_slice(&output.stdout).unwrap()
}

fn internal_ready(port: u16) -> bool {
    let Ok(mut stream) = TcpStream::connect(("127.0.0.1", port)) else {
        return false;
    };
    stream
        .write_all(b"GET /ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .unwrap();
    let mut response = [0u8; 64];
    stream
        .read(&mut response)
        .is_ok_and(|read| response[..read].starts_with(b"HTTP/1.1 200"))
}

fn wait_for_plain_http(port: u16, path: &str) -> String {
    for _ in 0..40 {
        if let Ok(mut stream) = TcpStream::connect(("127.0.0.1", port)) {
            stream
                .set_read_timeout(Some(Duration::from_secs(1)))
                .unwrap();
            stream
                .write_all(
                    format!("GET {path} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .as_bytes(),
                )
                .unwrap();
            let mut response = String::new();
            if stream.read_to_string(&mut response).is_ok() && response.starts_with("HTTP/1.1 200")
            {
                return response;
            }
        }
        thread::sleep(Duration::from_millis(100));
    }
    panic!("LAN APK HTTP server did not become ready");
}

fn plain_http_succeeds(port: u16) -> bool {
    let Ok(mut stream) = TcpStream::connect(("127.0.0.1", port)) else {
        return false;
    };
    stream
        .set_read_timeout(Some(Duration::from_secs(1)))
        .unwrap();
    stream
        .write_all(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .unwrap();
    let mut response = [0u8; 64];
    stream
        .read(&mut response)
        .is_ok_and(|read| response[..read].starts_with(b"HTTP/1.1 200"))
}

fn free_port() -> u16 {
    TcpListener::bind(("127.0.0.1", 0))
        .unwrap()
        .local_addr()
        .unwrap()
        .port()
}
