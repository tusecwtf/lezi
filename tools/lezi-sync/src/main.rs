use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::time::Duration;

use axum_server::tls_rustls::RustlsConfig;
use axum_server::Handle;
use lezi_sync::{build_app, ServerConfig};
use tokio::net::TcpListener;
use tokio::sync::watch;
use tracing_subscriber::EnvFilter;

const DEFAULT_PUBLIC_PORT: u16 = 8765;
const DEFAULT_INTERNAL_PORT: u16 = 8766;

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

    let (certificate, private_key) = tls_files().unwrap_or_else(|error| {
        eprintln!("configuration error: {error}");
        std::process::exit(2);
    });
    let tls = RustlsConfig::from_pem_file(&certificate, &private_key)
        .await
        .unwrap_or_else(|error| {
            eprintln!("configuration error: cannot load TLS certificate/key: {error}");
            std::process::exit(2);
        });
    let config = ServerConfig::from_env().unwrap_or_else(|error| {
        eprintln!("configuration error: {error}");
        std::process::exit(2);
    });
    let host = std::env::var("LEZI_HOST").unwrap_or_else(|_| "0.0.0.0".to_owned());
    let port = env_port("LEZI_PORT", DEFAULT_PUBLIC_PORT);
    let address: SocketAddr = format!("{host}:{port}").parse().unwrap_or_else(|_| {
        eprintln!("configuration error: LEZI_HOST/LEZI_PORT is invalid");
        std::process::exit(2);
    });
    let internal_port = env_port("LEZI_INTERNAL_PORT", DEFAULT_INTERNAL_PORT);
    let internal_address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), internal_port);
    let app = build_app(config).unwrap_or_else(|error| {
        eprintln!("startup error: {error:?}");
        std::process::exit(1);
    });
    let internal_listener = TcpListener::bind(internal_address)
        .await
        .unwrap_or_else(|error| {
            eprintln!("cannot bind internal health endpoint {internal_address}: {error}");
            std::process::exit(1);
        });

    let tls_handle = Handle::new();
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    tokio::spawn(wait_for_shutdown(shutdown_tx, tls_handle.clone()));
    tracing::info!(%address, "lezi-sync HTTPS server listening");
    tracing::info!(%internal_address, "lezi-sync internal health endpoint listening");

    let public_server = axum_server::bind_rustls(address, tls)
        .handle(tls_handle)
        .serve(
            app.clone()
                .into_make_service_with_connect_info::<SocketAddr>(),
        );
    let internal_server = axum::serve(
        internal_listener,
        app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_requested(shutdown_rx));
    let (public_result, internal_result) = tokio::join!(public_server, internal_server);
    public_result.unwrap_or_else(|error| {
        eprintln!("HTTPS server error: {error}");
        std::process::exit(1);
    });
    internal_result.unwrap_or_else(|error| {
        eprintln!("internal health server error: {error}");
        std::process::exit(1);
    });
}

fn tls_files() -> Result<(PathBuf, PathBuf), &'static str> {
    let certificate = std::env::var_os("LEZI_TLS_CERTFILE").filter(|value| !value.is_empty());
    let private_key = std::env::var_os("LEZI_TLS_KEYFILE").filter(|value| !value.is_empty());
    match (certificate, private_key) {
        (Some(certificate), Some(private_key)) => {
            Ok((PathBuf::from(certificate), PathBuf::from(private_key)))
        }
        (None, None) => Err("LEZI_TLS_CERTFILE and LEZI_TLS_KEYFILE are required"),
        _ => Err("LEZI_TLS_CERTFILE and LEZI_TLS_KEYFILE must be set together"),
    }
}

fn env_port(name: &str, default: u16) -> u16 {
    std::env::var(name)
        .unwrap_or_else(|_| default.to_string())
        .parse()
        .unwrap_or_else(|_| {
            eprintln!("configuration error: {name} must be a valid port");
            std::process::exit(2);
        })
}

fn healthcheck() -> bool {
    let port =
        std::env::var("LEZI_INTERNAL_PORT").unwrap_or_else(|_| DEFAULT_INTERNAL_PORT.to_string());
    let Ok(mut stream) = TcpStream::connect(format!("127.0.0.1:{port}")) else {
        return false;
    };
    let _ = stream.set_read_timeout(Some(Duration::from_secs(2)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
    if stream
        .write_all(b"GET /ready HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut response = [0u8; 64];
    stream
        .read(&mut response)
        .is_ok_and(|read| response[..read].starts_with(b"HTTP/1.1 200"))
}

async fn wait_for_shutdown(shutdown: watch::Sender<bool>, tls_handle: Handle<SocketAddr>) {
    shutdown_signal().await;
    let _ = shutdown.send(true);
    tls_handle.graceful_shutdown(Some(Duration::from_secs(30)));
}

async fn shutdown_requested(mut shutdown: watch::Receiver<bool>) {
    while !*shutdown.borrow() {
        if shutdown.changed().await.is_err() {
            break;
        }
    }
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
