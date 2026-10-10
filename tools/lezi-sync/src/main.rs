use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::time::Duration;

use axum_server::tls_rustls::RustlsConfig;
use axum_server::Handle;
use lezi_sync::{build_server_apps, ServerApps, ServerConfig};
use tokio::net::TcpListener;
use tokio::sync::watch;
use tracing_subscriber::EnvFilter;

const DEFAULT_PUBLIC_PORT: u16 = 8765;
const DEFAULT_INTERNAL_PORT: u16 = 8766;
const LAN_APK_DOWNLOAD_PORT: u16 = 8767;

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();

    let mut args = std::env::args().skip(1);
    match args.next().as_deref() {
        Some("healthcheck") => {
            std::process::exit(if healthcheck() { 0 } else { 1 });
        }
        Some("offline-migrate") => {
            // Private NAS v3→current ops CLI (ticket 05). Remaining argv are
            // subcommand + flags; never starts the HTTPS server.
            let rest: Vec<String> = std::iter::once("offline-migrate".to_owned())
                .chain(args)
                .collect();
            std::process::exit(i32::from(lezi_sync::offline_migrate_main(&rest)));
        }
        _ => {}
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
    let ServerApps {
        public: public_app,
        internal: internal_app,
        lan_apk_download,
    } = build_server_apps(config).unwrap_or_else(|error| {
        eprintln!("startup error: {error:?}");
        std::process::exit(1);
    });
    // Bind every required socket before exposing any readiness endpoint.
    let public_listener = std::net::TcpListener::bind(address).unwrap_or_else(|error| {
        eprintln!("cannot bind HTTPS endpoint {address}: {error}");
        std::process::exit(1);
    });
    public_listener
        .set_nonblocking(true)
        .unwrap_or_else(|error| {
            eprintln!("cannot configure nonblocking HTTPS endpoint {address}: {error}");
            std::process::exit(1);
        });
    let public_server =
        axum_server::from_tcp_rustls(public_listener, tls).unwrap_or_else(|error| {
            eprintln!("cannot configure HTTPS endpoint {address}: {error}");
            std::process::exit(1);
        });
    let internal_listener = TcpListener::bind(internal_address)
        .await
        .unwrap_or_else(|error| {
            eprintln!("cannot bind internal health endpoint {internal_address}: {error}");
            std::process::exit(1);
        });
    let lan_apk_address = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), LAN_APK_DOWNLOAD_PORT);
    let lan_apk_listener = if lan_apk_download.is_some() {
        Some(
            TcpListener::bind(lan_apk_address)
                .await
                .unwrap_or_else(|error| {
                    eprintln!("cannot bind LAN APK download endpoint {lan_apk_address}: {error}");
                    std::process::exit(1);
                }),
        )
    } else {
        None
    };

    let tls_handle = Handle::new();
    let (shutdown_tx, shutdown_rx) = watch::channel(false);
    let signal_task = tokio::spawn(wait_for_shutdown(shutdown_tx.clone(), tls_handle.clone()));
    tracing::info!(%address, "lezi-sync HTTPS server listening");
    tracing::info!(%internal_address, "lezi-sync internal health endpoint listening");
    if lan_apk_listener.is_some() {
        tracing::info!(%lan_apk_address, "lezi-sync LAN APK download endpoint listening");
    }

    let public_server = public_server
        .handle(tls_handle.clone())
        .serve(public_app.into_make_service_with_connect_info::<SocketAddr>());
    let internal_server = axum::serve(
        internal_listener,
        internal_app.into_make_service_with_connect_info::<SocketAddr>(),
    )
    .with_graceful_shutdown(shutdown_requested(shutdown_rx.clone()));
    let lan_apk_server = async move {
        match (lan_apk_listener, lan_apk_download) {
            (Some(listener), Some(app)) => {
                axum::serve(
                    listener,
                    app.into_make_service_with_connect_info::<SocketAddr>(),
                )
                .with_graceful_shutdown(shutdown_requested(shutdown_rx))
                .await
            }
            // An absent optional listener is not a completed required task.
            _ => {
                shutdown_requested(shutdown_rx).await;
                Ok(())
            }
        }
    };
    let mut servers = tokio::task::JoinSet::new();
    servers.spawn(async move { ("HTTPS", public_server.await) });
    servers.spawn(async move { ("internal health", internal_server.await) });
    servers.spawn(async move { ("LAN APK download", lan_apk_server.await) });
    let first = servers.join_next().await;
    let expected_shutdown = *shutdown_tx.borrow();
    let _ = shutdown_tx.send(true);
    tls_handle.graceful_shutdown(Some(Duration::from_secs(5)));
    signal_task.abort();
    let failed = !expected_shutdown
        || first
            .as_ref()
            .is_some_and(|result| !matches!(result, Ok((_, Ok(())))));
    if failed {
        eprintln!("required server task terminated: {first:?}");
        // Stop siblings immediately: an unavailable public server must not
        // leave internal readiness reporting success during a drain window.
        tls_handle.shutdown();
        servers.abort_all();
    }
    let drained = tokio::time::timeout(Duration::from_secs(6), async {
        while servers.join_next().await.is_some() {}
    })
    .await;
    if drained.is_err() {
        servers.abort_all();
    }
    if failed {
        std::process::exit(1);
    }
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
