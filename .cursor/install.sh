#!/usr/bin/env bash
# Cloud Agent environment bootstrap for the lezi repository.
#
# Prepares both products in this repo for a Cloud Agent:
#   * Android app (Kotlin/Jetpack Compose, JDK 21, Gradle 8.11.1, compileSdk 35)
#   * lezi-sync server (Rust, tools/lezi-sync)
#
# Design notes:
#   * Idempotent: safe to run repeatedly and against a cached/snapshotted disk.
#   * With environment builds, this runs once to create the baseline snapshot;
#     files under $HOME persist, but the checked-out /workspace is reset per pod,
#     so gitignored local files (local.properties, keystore) are (re)created here.
#   * No long-running services are required for development, so there is no
#     `start`/`terminals` phase.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"

log() { printf '\n=== %s ===\n' "$*"; }

# ---------------------------------------------------------------------------
# 1. Rust toolchain: the committed Cargo.lock pulls crates that require the
#    edition2024 feature (Cargo >= 1.85). Ensure a recent stable is default.
# ---------------------------------------------------------------------------
log "Rust toolchain"
if command -v rustup >/dev/null 2>&1; then
    rustup toolchain install stable --profile minimal --component clippy,rustfmt
    rustup default stable
fi
rustc --version || true
cargo --version || true

# ---------------------------------------------------------------------------
# 2. Android SDK (matches .github/workflows/android-test.yml).
# ---------------------------------------------------------------------------
log "Android SDK"
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
    echo "Installing Android command-line tools into $ANDROID_SDK_ROOT"
    tmp_zip="$(mktemp -d)/cmdline-tools.zip"
    curl -fsSL -o "$tmp_zip" \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest" "$ANDROID_SDK_ROOT/cmdline-tools/tmp"
    unzip -q "$tmp_zip" -d "$ANDROID_SDK_ROOT/cmdline-tools/tmp"
    mv "$ANDROID_SDK_ROOT/cmdline-tools/tmp/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/tmp"
fi
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
"$SDKMANAGER" "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# ---------------------------------------------------------------------------
# 3. Gradle overrides for this VM (kept in $HOME, never committed).
#    a) Drop the China maven.aliyun.com mirrors declared first in
#       settings.gradle.kts: they are unreachable/flaky from the Cloud region
#       (observed HTTP 502) and break plugin/dependency resolution. Resolution
#       then falls back to google()/mavenCentral()/gradlePluginPortal().
#    b) Cap worker parallelism: this VM has few CPUs and Gradle's default
#       parallel test execution starves kotlinx-coroutines-test cases.
# ---------------------------------------------------------------------------
log "Gradle repository + parallelism overrides"
mkdir -p "$HOME/.gradle/init.d"
cat > "$HOME/.gradle/init.d/00-cloud-repos.init.gradle.kts" <<'KTS'
fun org.gradle.api.artifacts.dsl.RepositoryHandler.dropAliyunMirrors() {
    removeAll {
        it is org.gradle.api.artifacts.repositories.MavenArtifactRepository &&
            it.url.toString().contains("maven.aliyun.com")
    }
}
settingsEvaluated {
    pluginManagement.repositories.dropAliyunMirrors()
    dependencyResolutionManagement.repositories.dropAliyunMirrors()
}
KTS
if ! grep -qs 'org.gradle.workers.max' "$HOME/.gradle/gradle.properties" 2>/dev/null; then
    printf 'org.gradle.workers.max=2\n' >> "$HOME/.gradle/gradle.properties"
fi

# ---------------------------------------------------------------------------
# 4. Gitignored local files the build expects (recreated per checkout).
#    - local.properties: Android SDK location.
#    - keystore + keystore.properties: the repo keeps release signing local-only;
#      `./gradlew test` and any release build need a signing config to exist.
#      This is a throwaway DEV keystore for the sandbox only — never production,
#      never committed (both paths are gitignored).
# ---------------------------------------------------------------------------
log "Local (gitignored) build config"
printf 'sdk.dir=%s\n' "$ANDROID_SDK_ROOT" > "$REPO_ROOT/local.properties"

DEV_KEYSTORE="$REPO_ROOT/lezi-dev-release.jks"
if [ ! -f "$REPO_ROOT/keystore.properties" ] || [ ! -f "$DEV_KEYSTORE" ]; then
    rm -f "$DEV_KEYSTORE"
    keytool -genkeypair -v -keystore "$DEV_KEYSTORE" -alias lezidev \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass lezidev123 -keypass lezidev123 \
        -dname "CN=Lezi Dev, OU=Dev, O=Lezi, L=NA, ST=NA, C=NA"
    cat > "$REPO_ROOT/keystore.properties" <<PROPS
storeFile=lezi-dev-release.jks
storePassword=lezidev123
keyAlias=lezidev
keyPassword=lezidev123
PROPS
fi

# ---------------------------------------------------------------------------
# 5. Warm caches so the first agent action is fast, and prove the toolchains
#    resolve end-to-end. Deterministic build/fetch only (no flaky test gating).
#
#    Note: Matt Pocock's coding skills are NOT installed here. Cloud Agents do
#    not surface skills written to $HOME/.cursor/skills at install time; they
#    load repo-committed skills from .cursor/skills/. Those skills therefore
#    live in-repo at .cursor/skills/mattpocock/ instead of in this script.
# ---------------------------------------------------------------------------
log "Warm Gradle (build debug APK)"
./gradlew :app:assembleDebug --no-daemon

log "Warm Cargo (build lezi-sync)"
( cd tools/lezi-sync && cargo build --locked )

log "Environment bootstrap complete"
