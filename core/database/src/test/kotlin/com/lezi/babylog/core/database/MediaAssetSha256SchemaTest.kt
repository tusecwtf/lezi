package com.lezi.babylog.core.database

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class MediaAssetSha256SchemaTest {
    @Test
    fun room29AddsNullableMediaAssetsSha256WithoutTouchingReferences() {
        val schema = schemaFile(29).readText()
        assertThat(schema).contains("\"version\": 29")
        assertThat(schema).contains("\"identityHash\": \"de94eda8425832877fd3fc91a7950030\"")
        assertThat(schema).contains("`sha256` TEXT DEFAULT NULL")
        assertThat(schema).contains("\"columnName\": \"sha256\"")
        assertThat(schema).contains("\"fieldPath\": \"sha256\"")
        val shaField = schema.substringAfter("\"fieldPath\": \"sha256\"")
            .substringBefore("\"fieldPath\":")
        assertThat(shaField).contains("\"notNull\": false")
        assertThat(shaField).contains("\"defaultValue\": \"NULL\"")

        val references = schema.substringAfter("\"tableName\": \"media_references\"")
            .substringBefore("\"tableName\":")
        assertThat(references).doesNotContain("\"columnName\": \"sha256\"")
    }

    private fun schemaFile(version: Int): File {
        val candidates = listOf(
            File("schemas/com.lezi.babylog.core.database.LeziDatabase/$version.json"),
            File("core/database/schemas/com.lezi.babylog.core.database.LeziDatabase/$version.json"),
        )
        return candidates.first(File::isFile)
    }
}
