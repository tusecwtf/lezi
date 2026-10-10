package com.lezi.babylog.sync.disasterrecovery

import com.lezi.babylog.sync.backend.SyncEntity
import com.lezi.babylog.sync.media.parseRawMediaMime
import com.lezi.babylog.sync.media.requireRawMediaMime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Valid raw data that cannot yet continue losslessly through the historical canonical format. */
class LosslessRestoreCompatibilityException(val mediaClientUuid: String) : IllegalStateException(
    "部分照片元数据超出当前兼容恢复范围；未上传或修改原数据",
)

class RestoreAuthorityUnsupportedException : IllegalStateException(
    "当前家庭服务器尚不支持安全灾难恢复，请更新服务器后再试；原数据已保留",
)

internal fun requireSchema13RestoreMime(mediaUuid: String, mime: String?) {
    requireRawMediaMime(mime)
    if (mime == null || mime.isEmpty() || mime.toByteArray(Charsets.UTF_8).size > 128)
        throw LosslessRestoreCompatibilityException(mediaUuid)
}

internal fun requireSchema13RestoreEntities(entities: List<SyncEntity>) {
    entities.filter { it.type == "media" && it.deletedAt == null }.forEach { entity ->
        requireSchema13RestoreMime(entity.clientUuid,
            parseRawMediaMime(Json.parseToJsonElement(entity.payloadJson).jsonObject["mime"]))
    }
}
