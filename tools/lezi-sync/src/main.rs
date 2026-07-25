use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use lezi_sync::{build_app, ServerConfig};
use tokio::net::TcpListener;
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        std::process::exit(if healthcheck() { 0 } else { 1 });
    }

    if std::env::var("LEZI_TLS_CERTFILE").is_ok_and(|value| !value.is_empty())
        || std::env::var("LEZI_TLS_KEYFILE").is_ok_and(|value| !value.is_empty())
    {
        eprintln!(
            "direct TLS was removed; terminate TLS at the NAS reverse proxy and keep lezi-sync on the private Docker network"
        );
        std::process::exit(2);
    }

    let config = ServerConfig::from_env().unwrap_or_else(|error| {
        eprintln!("configuration error: {error}");
        std::process::exit(2);
    });
    let host = std::env::var("LEZI_HOST").unwrap_or_else(|_| "0.0.0.0".to_owned());
    let port: u16 = std::env::var("LEZI_PORT")
        .unwrap_or_else(|_| "8765".to_owned())
        .parse()
        .unwrap_or_else(|_| {
            eprintln!("configuration error: LEZI_PORT must be a valid port");
            std::process::exit(2);
        });
    let address: SocketAddr = format!("{host}:{port}").parse().unwrap_or_else(|_| {
        eprintln!("configuration error: LEZI_HOST/LEZI_PORT is invalid");
        std::process::exit(2);
    });
    let app = build_app(config).unwrap_or_else(|error| {
        eprintln!("startup error: {error:?}");
        std::process::exit(1);
    });
    let listener = TcpListener::bind(address).await.unwrap_or_else(|error| {
        eprintln!("cannot bind {address}: {error}");
        std::process::exit(1);
    });
    tracing::info!(%address, "lezi-sync Rust server listening");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .unwrap_or_else(|error| {
            eprintln!("server error: {error}");
            std::process::exit(1);
        });
}

fn healthcheck() -> bool {
    let port = std::env::var("LEZI_PORT").unwrap_or_else(|_| "8765".to_owned());
    let Ok(mut stream) = TcpStream::connect(format!("127.0.0.1:{port}")) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
    if stream
        .write_all(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut response = [0u8; 64];
    stream
        .read(&mut response)
        .is_ok_and(|read| response[..read].starts_with(b"HTTP/1.1 200"))
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    #[cfg(unix)]
    let terminate = async {
        if let Ok(mut signal) =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        {
            signal.recv().await;
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
