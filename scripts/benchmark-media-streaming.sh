#!/usr/bin/env bash
# Host successful response-body -> adopted-file microbenchmark, not HTTP/Android latency.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
baseline=${1:-c7461bcd8b2d52c482d14746aba380295626be25}
git cat-file -e "$baseline^{commit}" 2>/dev/null || {
    printf 'Missing baseline %s; fetch it first (including in shallow clones).\n' "$baseline" >&2
    exit 1
}
: "${KOTLIN_COMPILER_CLASSPATH:?Set Kotlin 2.1 compiler/runtime/coroutines classpath}"
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
export LEZI_BENCHMARK_BASELINE="$baseline" LEZI_BENCHMARK_TEMP="$temporary"
python3 - <<'PY'
import os, pathlib, subprocess, hashlib
out = pathlib.Path(os.environ['LEZI_BENCHMARK_TEMP'])
base = os.environ['LEZI_BENCHMARK_BASELINE']
def old(path):
    return subprocess.check_output(['git','show',f'{base}:{path}'],text=True)
def report(name, text):
    print(f'extracted_source={name},sha256={hashlib.sha256(text.encode()).hexdigest()}')
http_path='sync/src/main/kotlin/com/lezi/babylog/sync/backend/HttpSyncBackend.kt'
store_path='sync/src/main/kotlin/com/lezi/babylog/sync/media/SyncMediaFileStore.kt'
# Exact baseline read loop; only visibility changes so the harness can call it.
reader=old(http_path).split('private fun InputStream.readBytesUpTo(',1)[1].split('\nprivate fun SyncEntity.toJson()',1)[0]
reader='fun InputStream.readBytesUpTo('+reader
report('baseline InputStream.readBytesUpTo',reader)
# Exact baseline Android save method after replacing only the context carrier and override.
store=old(store_path)
method=store.split('    override suspend fun saveDownloaded(',1)[1].split('\n    override suspend fun delete(',1)[0]
method='    suspend fun saveDownloaded('+method
extension=store.split('    private fun extensionForMime(',1)[1].split('\n    private fun readExifOrientation(',1)[0]
report('baseline AndroidSyncMediaFileStore.saveDownloaded',method)
(out/'BaselineBodyAndStore.kt').write_text('''package com.lezi.babylog.baseline
import java.io.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.lezi.babylog.sync.backend.SyncResponseTooLargeException
'''+reader+'''
class BaselineContext(val filesDir: File)
class BaselineStore(val context: BaselineContext) {
'''+method+'\n    private fun extensionForMime('+extension+'''
private companion object { const val AVATAR_DIRECTORY="baby_avatars"; const val RECORD_MEDIA_DIRECTORY="record-media" }
}
''')
digest=old('core/common/src/main/kotlin/com/lezi/babylog/core/common/MediaContentDigest.kt')
(out/'BaselineDigest.kt').write_text(digest.replace('package com.lezi.babylog.core.common','package com.lezi.babylog.baseline',1))
# Exact current successful copy/hash loop, without connection lifecycle/status work.
# Its only adaptations are the input carrier, receipt type alias and omission of keepAlive=true.
http=pathlib.Path(http_path).read_text()
stream=http.split('            val hasher = MediaContentDigest.newHasher()',1)[1].split('\n        }\n    }',1)[0]
stream='            val hasher = MediaContentDigest.newHasher()'+stream
assert stream.count('keepAlive = true') == 1
stream=stream.replace('            keepAlive = true\n','')
report('current HttpExchange streaming copy/hash loop',stream)
exception=http.split('internal class SyncResponseTooLargeException(',1)[1].split('\n\n',1)[0]
(out/'CurrentBody.kt').write_text('''package com.lezi.babylog.sync.backend
import java.io.*
import com.lezi.babylog.core.common.MediaContentDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
private typealias AppUpdateApkDownload = MediaDownloadReceipt
class BodyConnection(val inputStream: InputStream)
suspend fun copyCurrentBody(connection: BodyConnection, target: OutputStream, successLimitBytes: Int, declaredBytes: Long): MediaDownloadReceipt {
val successResponseKind = "媒体"
'''+stream+'\n}\ninternal class SyncResponseTooLargeException('+exception+'\n')
PY
sources=(
    "$temporary/BaselineBodyAndStore.kt" "$temporary/BaselineDigest.kt" "$temporary/CurrentBody.kt"
    core/common/src/main/kotlin/com/lezi/babylog/core/common/MediaContentDigest.kt
    sync/src/main/kotlin/com/lezi/babylog/sync/backend/MediaDownloadReceipt.kt
    sync/src/main/kotlin/com/lezi/babylog/sync/media/OwnedMediaDownloadStore.kt
    scripts/benchmarks/MediaStreamingBenchmark.kt
)
java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -classpath "$KOTLIN_COMPILER_CLASSPATH" \
    -d "$temporary/classes" "${sources[@]}"
printf 'baseline=%s\ncandidate=%s\n' "$(git rev-parse "$baseline")" "$(git rev-parse HEAD)"
sha256sum scripts/benchmark-media-streaming.sh scripts/benchmarks/MediaStreamingBenchmark.kt \
    core/common/src/main/kotlin/com/lezi/babylog/core/common/MediaContentDigest.kt \
    sync/src/main/kotlin/com/lezi/babylog/sync/{backend/HttpSyncBackend.kt,backend/MediaDownloadReceipt.kt,media/OwnedMediaDownloadStore.kt}
java -Xms256m -Xmx256m -XX:+UseSerialGC -cp "$temporary/classes:$KOTLIN_COMPILER_CLASSPATH" \
    com.lezi.babylog.MediaStreamingBenchmarkKt
