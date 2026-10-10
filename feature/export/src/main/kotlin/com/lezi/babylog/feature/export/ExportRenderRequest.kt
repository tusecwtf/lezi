package com.lezi.babylog.feature.export

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Private descriptor payload, never a Binder-sized copy of the user's export. */
internal data class ExportRenderRequest(
    val format: ExportFormat,
    val title: String,
    val body: String,
    val photos: List<String>,
) {
    fun writeTo(output: OutputStream, checkpoint: () -> Unit) {
        DataOutputStream(output.buffered()).use { data ->
            data.writeBoolean(format == ExportFormat.Pdf)
            fun text(value: String) {
                data.writeInt(value.length)
                value.forEachIndexed { index, character ->
                    if (index % 4_096 == 0) checkpoint()
                    data.writeChar(character.code)
                }
            }
            text(title)
            text(body)
            data.writeInt(photos.size)
            photos.forEach { checkpoint(); text(it) }
            checkpoint()
        }
    }

    companion object {
        fun readFrom(input: InputStream): ExportRenderRequest = DataInputStream(input.buffered()).use { data ->
            val format = if (data.readBoolean()) ExportFormat.Pdf else ExportFormat.Txt
            fun text(): String {
                val size = data.readInt()
                require(size >= 0)
                return buildString { repeat(size) { append(data.readChar()) } }
            }
            val title = text()
            val body = text()
            val count = data.readInt()
            require(count >= 0)
            ExportRenderRequest(format, title, body, List(count) { text() })
        }
    }
}
