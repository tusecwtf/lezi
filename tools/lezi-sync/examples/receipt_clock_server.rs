//! Loopback-only paired-test runner. Production main has no externally adjustable clock.
use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use axum_server::tls_rustls::RustlsConfig;
use lezi_sync::{build_server_apps, ServerConfig};

#[tokio::main]
async fn main() {
    let config = ServerConfig::from_env().expect("isolated server configuration");
    let clock_path = config.data_dir.join("receipt-test-clock");
    let config = config.with_clock(move || {
        std::fs::read_to_string(&clock_path)
            .expect("test clock file")
            .trim()
            .parse::<i64>()
            .expect("test clock epoch seconds")
    });
    let apps = build_server_apps(config).expect("isolated server startup and real GC");
    let tls = RustlsConfig::from_pem_file(
        std::env::var("LEZI_TLS_CERTFILE").expect("test certificate"),
        std::env::var("LEZI_TLS_KEYFILE").expect("test private key"),
    )
    .await
    .expect("isolated TLS");
    let port = std::env::var("LEZI_PORT")
        .expect("test port")
        .parse::<u16>()
        .expect("valid test port");
    let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
    axum_server::bind_rustls(address, tls)
        .serve(
            apps.public
                .into_make_service_with_connect_info::<SocketAddr>(),
        )
        .await
        .expect("isolated HTTPS server");
}
