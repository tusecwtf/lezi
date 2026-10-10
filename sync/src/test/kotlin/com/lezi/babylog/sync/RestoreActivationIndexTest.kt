package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import com.lezi.babylog.core.database.MediaAssetEntity
import com.lezi.babylog.sync.disasterrecovery.CapturedRestoreRows
import org.junit.Test

class RestoreActivationIndexTest {
    @Test
    fun repeatedActivationAndAvatarLookupDoesNotRescanMediaOrResolveAmbiguousIds() {
        val source = List(10_000) { index -> MediaAssetEntity(clientUuid = "photo-$index",
            recordId = 1, localUri = "path-$index", createdAt = 1, updatedAt = 1) }
        var observations = 0
        val counted = object : AbstractList<MediaAssetEntity>() {
            override val size = source.size + 1
            override fun get(index: Int): MediaAssetEntity {
                observations += 1
                return if (index == source.size) source.first().copy(localUri = "another-generation") else source[index]
            }
        }
        val capture = CapturedRestoreRows(emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), counted)
        val indexedWork = observations
        for (row in source) {
            val found = capture.mediaRow(row.clientUuid)
            if (row.clientUuid == "photo-0") assertThat(found).isNull() else assertThat(found).isEqualTo(row)
        }
        assertThat(observations).isEqualTo(indexedWork)
        assertThat(indexedWork).isAtMost(3 * counted.size)
    }
}
