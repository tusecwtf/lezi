use std::sync::Arc;

use axum::body::Body;
use axum::extract::State;
use axum::http::header::{
    CACHE_CONTROL, CONTENT_DISPOSITION, CONTENT_LENGTH, CONTENT_SECURITY_POLICY, CONTENT_TYPE,
    REFERRER_POLICY,
};
use axum::http::{HeaderName, HeaderValue};
use axum::response::Response;
use serde_json::Value;

use crate::{run_blocking, AppState};

const X_CONTENT_TYPE_OPTIONS: HeaderName = HeaderName::from_static("x-content-type-options");
const X_FRAME_OPTIONS: HeaderName = HeaderName::from_static("x-frame-options");

pub(crate) async fn join(State(state): State<Arc<AppState>>) -> Response {
    let blocking_state = state.clone();
    let release = match run_blocking(move || {
        blocking_state.app_update_cache.load_verified(
            &blocking_state.app_update_metadata_path,
            &blocking_state.app_update_apk_path,
        )
    })
    .await
    {
        Ok(release) => release,
        Err(error) => {
            tracing::warn!(detail = %error.detail, "LAN APK install page is unavailable");
            return html_response(
                axum::http::StatusCode::SERVICE_UNAVAILABLE,
                unavailable_page(),
            );
        }
    };
    let version_name = release
        .metadata
        .get("version_name")
        .and_then(Value::as_str)
        .expect("normalized app update metadata has version_name");
    let release_notes = release
        .metadata
        .get("release_notes")
        .and_then(Value::as_str);
    html_response(
        axum::http::StatusCode::OK,
        available_page(version_name, release_notes),
    )
}

pub(crate) async fn download(State(state): State<Arc<AppState>>) -> Response {
    let blocking_state = state.clone();
    let release = match run_blocking(move || {
        blocking_state.app_update_cache.load_verified(
            &blocking_state.app_update_metadata_path,
            &blocking_state.app_update_apk_path,
        )
    })
    .await
    {
        Ok(release) => release,
        Err(error) => {
            tracing::warn!(detail = %error.detail, "LAN APK download is unavailable");
            return html_response(
                axum::http::StatusCode::SERVICE_UNAVAILABLE,
                unavailable_page(),
            );
        }
    };
    let content_length = HeaderValue::from_str(&release.bytes.len().to_string())
        .expect("APK byte length is a legal HTTP header");
    let mut response = Response::new(Body::from(release.bytes));
    let headers = response.headers_mut();
    headers.insert(
        CONTENT_TYPE,
        HeaderValue::from_static("application/vnd.android.package-archive"),
    );
    headers.insert(
        CONTENT_DISPOSITION,
        HeaderValue::from_static("attachment; filename=\"lezi.apk\""),
    );
    headers.insert(CONTENT_LENGTH, content_length);
    headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    headers.insert(X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    response
}

fn available_page(version_name: &str, release_notes: Option<&str>) -> String {
    let notes = release_notes.map_or_else(String::new, |notes| {
        format!(
            "<section><h2>本次更新</h2><p class=\"notes\">{}</p></section>",
            escape_html(notes),
        )
    });
    format!(
        r#"<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>下载乐记</title>
<script>history.replaceState(null, "", location.pathname + location.search);</script>
<style>
:root{{color-scheme:light;font-family:system-ui,-apple-system,sans-serif;background:#fffaf6;color:#332b27}}
body{{margin:0;padding:24px}}main{{max-width:560px;margin:48px auto;background:white;border-radius:24px;padding:28px;box-shadow:0 12px 40px #5b39221f}}
h1{{margin:0 0 8px}}h2{{font-size:1.05rem;margin-top:28px}}p{{line-height:1.65}}.version{{color:#74645b}}.notes{{white-space:pre-wrap}}
.download{{display:block;margin:28px 0 20px;padding:15px 20px;border-radius:14px;background:#d95d39;color:white;text-align:center;text-decoration:none;font-weight:700}}
ol{{padding-left:1.4rem;line-height:1.8}}.hint{{font-size:.92rem;color:#74645b}}
</style>
</head>
<body><main>
<h1>下载乐记</h1>
<p>这是家庭局域网内提供的乐记安装包。</p>
<p class="version">版本 {version}</p>
{notes}
<a class="download" href="/download/lezi.apk" download>下载 APK</a>
<h2>安装步骤</h2>
<ol><li>下载完成后打开 APK，并按系统提示允许本次安装。</li><li>安装完成后打开乐记。</li><li>请用乐记重新扫描同一个邀请二维码完成加入。</li></ol>
<p class="hint">邀请二维码有效期为 10 分钟；若已超时，请管理员重新生成。</p>
</main></body>
</html>"#,
        version = escape_html(version_name),
    )
}

fn unavailable_page() -> String {
    r#"<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>乐记安装包暂不可用</title>
<script>history.replaceState(null, "", location.pathname + location.search);</script>
<style>
:root{color-scheme:light;font-family:system-ui,-apple-system,sans-serif;background:#fffaf6;color:#332b27}
body{margin:0;padding:24px}main{max-width:560px;margin:48px auto;background:white;border-radius:24px;padding:28px;box-shadow:0 12px 40px #5b39221f}
h1{margin:0 0 12px}p{line-height:1.65}.hint{color:#74645b}
</style>
</head>
<body><main>
<h1>乐记安装包暂不可用</h1>
<p>服务器没有可验证的安装包，因此未提供下载。</p>
<p class="hint">请联系管理员检查发布文件后再试。</p>
</main></body>
</html>"#
        .to_owned()
}

fn html_response(status: axum::http::StatusCode, html: String) -> Response {
    let mut response = Response::new(Body::from(html));
    *response.status_mut() = status;
    let headers = response.headers_mut();
    headers.insert(
        CONTENT_TYPE,
        HeaderValue::from_static("text/html; charset=utf-8"),
    );
    headers.insert(CACHE_CONTROL, HeaderValue::from_static("no-store"));
    headers.insert(
        CONTENT_SECURITY_POLICY,
        HeaderValue::from_static(
            "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        ),
    );
    headers.insert(REFERRER_POLICY, HeaderValue::from_static("no-referrer"));
    headers.insert(X_CONTENT_TYPE_OPTIONS, HeaderValue::from_static("nosniff"));
    headers.insert(X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
    response
}

fn escape_html(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '&' => escaped.push_str("&amp;"),
            '<' => escaped.push_str("&lt;"),
            '>' => escaped.push_str("&gt;"),
            '"' => escaped.push_str("&quot;"),
            '\'' => escaped.push_str("&#39;"),
            _ => escaped.push(character),
        }
    }
    escaped
}
