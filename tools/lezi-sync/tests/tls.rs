use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::Duration;

use serde_json::{json, Value};

#[test]
fn public_endpoint_is_https_only_and_keeps_the_same_certificate_across_restart() {
    let directory = tempfile::tempdir().unwrap();
    let certificate = directory.path().join("server.crt");
    let private_key = directory.path().join("server.key");
    generate_certificate(&certificate, &private_key);
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
    Command::new(env!("CARGO_BIN_EXE_lezi-sync"))
        .env("LEZI_DATA_DIR", data_root)
        .env("LEZI_BOOTSTRAP_SECRET", "tls-test-bootstrap-secret")
        .env("LEZI_HOST", "127.0.0.1")
        .env("LEZI_PORT", public_port.to_string())
        .env("LEZI_INTERNAL_PORT", internal_port.to_string())
        .env("LEZI_TLS_CERTFILE", certificate)
        .env("LEZI_TLS_KEYFILE", private_key)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap()
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
