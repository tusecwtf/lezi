#!/usr/bin/env bash
# Compare the current production digest paths with their exact baseline source.
set -euo pipefail
root=$(git rev-parse --show-toplevel)
cd "$root"
baseline=${1:-9e47fd6c9111eb5352cef585f4672a71fb578af4}
if ! git cat-file -e "$baseline^{commit}" 2>/dev/null; then
    printf 'Baseline commit %s is missing; fetch it first (shallow clones may omit it).\n' "$baseline" >&2
    exit 1
fi
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
export LEZI_BENCHMARK_BASELINE="$baseline" LEZI_BENCHMARK_TEMP="$temporary"
python3 - <<'PY'
import os
import pathlib
import subprocess

base = os.environ['LEZI_BENCHMARK_BASELINE']
out = pathlib.Path(os.environ['LEZI_BENCHMARK_TEMP'])

def source(path):
    return subprocess.check_output(['git', 'show', f'{base}:{path}'], text=True)

# Only rename the package; the old production digest implementation stays intact.
digest = source('core/common/src/main/kotlin/com/lezi/babylog/core/common/MediaContentDigest.kt')
(out / 'BaselineMediaContentDigest.kt').write_text(digest.replace(
    'package com.lezi.babylog.core.common', 'package com.lezi.babylog.baseline', 1))

# Extract the old retained-media hashing body verbatim, after path resolution.
# Keeping the file resolver outside the benchmark gives both paths the same file.
upgrade = source('app/src/main/kotlin/com/lezi/babylog/CausalRoomUpgradeStep.kt')
method = upgrade.split('private fun sha256HexForLocalUri(localUri: String): String {', 1)[1]
body = method.split('        val bytes = file.readBytes()', 1)[1].split('\n    }', 1)[0]
(out / 'BaselineRetainedMediaDigest.kt').write_text(
    'package com.lezi.babylog.baseline\n'
    'import java.io.File\nimport java.security.MessageDigest\n'
    'fun retainedMediaSha256(file: File): String {\n'
    '        val localUri = file.path\n'
    '        val bytes = file.readBytes()' + body + '\n}\n')
PY
sources=(
    "$temporary/BaselineMediaContentDigest.kt"
    "$temporary/BaselineRetainedMediaDigest.kt"
    core/common/src/main/kotlin/com/lezi/babylog/core/common/MediaContentDigest.kt
    app/src/main/kotlin/com/lezi/babylog/RetainedMediaDigest.kt
    scripts/benchmarks/MediaDigestBenchmark.kt
)
if [[ -n ${KOTLIN_COMPILER_CLASSPATH:-} ]]; then
    java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -no-reflect -jvm-target 17 -classpath "$KOTLIN_COMPILER_CLASSPATH" \
        -d "$temporary/classes" "${sources[@]}"
    runtime="$temporary/classes:$KOTLIN_COMPILER_CLASSPATH"
else
    kotlinc -jvm-target 17 -include-runtime -d "$temporary/benchmark.jar" "${sources[@]}"
    runtime="$temporary/benchmark.jar"
fi
printf 'baseline=%s\ncurrent=working-tree (production source SHA-256 below)\n' "$(git rev-parse "$baseline")"
sha256sum core/common/src/main/kotlin/com/lezi/babylog/core/common/MediaContentDigest.kt \
    app/src/main/kotlin/com/lezi/babylog/RetainedMediaDigest.kt
java -Xms256m -Xmx256m -XX:+UseSerialGC -cp "$runtime" \
    com.lezi.babylog.MediaDigestBenchmarkKt
