package com.lezi.babylog.feature.log

import com.lezi.babylog.core.model.RecordPhotoResourcePolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoundedRecordPhotoImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun zeroAndThreeInputsCompleteAsOneBoundedBatch() = runBlocking {
        var sniffCount = 0
        val importer = BoundedRecordPhotoImporter(
            directory = temporaryFolder.root,
            sniff = { file ->
                sniffCount += 1
                when (file.readBytes().single().toInt()) {
                    1 -> RecordPhotoFileInspection("image/jpeg", 40, 30)
                    2 -> RecordPhotoFileInspection("image/png", 30, 20)
                    else -> RecordPhotoFileInspection("image/webp", 20, 10)
                }
            },
        )

        assertTrue(importer.import(emptyList()).isEmpty())
        val imported = importer.import(
            listOf(
                source("image/jpg", 1),
                source("image/*", 2),
                source(null, 3),
            ),
        )

        assertEquals(3, sniffCount)
        assertEquals(listOf("jpg", "png", "webp"), imported.map { File(it).extension })
        imported.forEachIndexed { index, path ->
            assertArrayEquals(byteArrayOf((index + 1).toByte()), File(path).readBytes())
        }
        assertEquals(imported.map(::File).toSet(), temporaryFolder.root.listFiles().orEmpty().toSet())
    }

    @Test
    fun secondItemSniffFailureRollsBackTheWholeBatchBeforeOpeningThird() {
        var openCount = 0
        var sniffCount = 0
        val importer = BoundedRecordPhotoImporter(
            directory = temporaryFolder.root,
            sniff = {
                sniffCount += 1
                if (sniffCount == 1) {
                    RecordPhotoFileInspection("image/jpeg", 40, 30)
                } else {
                    null
                }
            },
        )
        val inputs = (1..3).map { index ->
            RecordPhotoImportSource(
                declaredMime = "image/jpeg",
                openStream = {
                    openCount += 1
                    ByteArrayInputStream(byteArrayOf(index.toByte()))
                },
            )
        }

        val failure = runCatching { runBlocking { importer.import(inputs) } }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(2, openCount)
        assertEquals(2, sniffCount)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sourceByteLimitAcceptsTheBoundaryAndRejectsOneByteOverWithoutResidue() = runBlocking {
        val directory = temporaryFolder.newFolder("byte-limit")
        val importer = importer(directory) { RecordPhotoFileInspection("image/jpeg", 40, 30) }

        val accepted = importer.import(
            listOf(source("image/jpeg", RecordPhotoResourcePolicy.maxSourceBytes)),
        ).single()
        assertEquals(RecordPhotoResourcePolicy.maxSourceBytes, File(accepted).length())

        val failure = runCatching {
            importer.import(
                listOf(source("image/jpeg", RecordPhotoResourcePolicy.maxSourceBytes + 1)),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(setOf(File(accepted)), directory.listFiles().orEmpty().toSet())
    }

    @Test
    fun edgeAndPixelLimitsAcceptExactBoundsAndKeepEarlierImportsOnLaterRejection() = runBlocking {
        val directory = temporaryFolder.newFolder("pixel-limits")
        val inspections = ArrayDeque(
            listOf(
                RecordPhotoFileInspection(
                    "image/jpeg",
                    RecordPhotoResourcePolicy.maxSourceEdge,
                    1,
                ),
                RecordPhotoFileInspection(
                    "image/jpeg",
                    RecordPhotoResourcePolicy.maxSourceEdge + 1,
                    1,
                ),
                RecordPhotoFileInspection("image/jpeg", 16_384, 16_384),
                RecordPhotoFileInspection("image/jpeg", 16_384, 16_385),
            ),
        )
        val importer = importer(directory) { inspections.removeFirst() }

        val edgeBoundary = importer.import(listOf(source("image/jpeg", 1L))).single()
        val edgeFailure = runCatching {
            importer.import(listOf(source("image/jpeg", 1L)))
        }.exceptionOrNull()
        val pixelBoundary = importer.import(listOf(source("image/jpeg", 1L))).single()
        val pixelFailure = runCatching {
            importer.import(listOf(source("image/jpeg", 1L)))
        }.exceptionOrNull()

        assertTrue(edgeFailure is IllegalArgumentException)
        assertTrue(pixelFailure is IllegalArgumentException)
        assertEquals(
            setOf(File(edgeBoundary), File(pixelBoundary)),
            directory.listFiles().orEmpty().toSet(),
        )
    }

    @Test
    fun declaredMimeCannotDisguiseAConcreteDifferentOrUnsupportedType() {
        var openCount = 0
        var sniffCount = 0
        val importer = BoundedRecordPhotoImporter(
            directory = temporaryFolder.root,
            sniff = {
                sniffCount += 1
                RecordPhotoFileInspection("image/jpeg", 40, 30)
            },
        )
        val disguised = RecordPhotoImportSource("image/png") {
            openCount += 1
            ByteArrayInputStream(byteArrayOf(1))
        }
        val unsupported = RecordPhotoImportSource("image/gif") {
            openCount += 1
            ByteArrayInputStream(byteArrayOf(2))
        }

        val disguisedFailure = runCatching {
            runBlocking { importer.import(listOf(disguised)) }
        }.exceptionOrNull()
        val unsupportedFailure = runCatching {
            runBlocking { importer.import(listOf(unsupported)) }
        }.exceptionOrNull()

        assertTrue(disguisedFailure is IllegalArgumentException)
        assertTrue(unsupportedFailure is IllegalArgumentException)
        assertEquals(1, openCount)
        assertEquals(1, sniffCount)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationCleansEveryBatchFileAndTheSameImporterCanRetry() {
        val cancellation = CancellationException("picker closed")
        var shouldCancel = true
        var openCount = 0
        val importer = importer(temporaryFolder.root) {
            RecordPhotoFileInspection("image/jpeg", 40, 30)
        }
        fun retryableSource(marker: Int) = RecordPhotoImportSource("image/jpeg") {
            openCount += 1
            if (marker == 2 && shouldCancel) {
                object : InputStream() {
                    override fun read(): Int = throw cancellation
                }
            } else {
                ByteArrayInputStream(byteArrayOf(marker.toByte()))
            }
        }

        val firstFailure = runCatching {
            runBlocking { importer.import(listOf(retryableSource(1), retryableSource(2))) }
        }.exceptionOrNull()
        assertTrue(firstFailure is CancellationException)
        assertTrue(temporaryFolder.root.listFiles().orEmpty().isEmpty())

        shouldCancel = false
        val retried = runBlocking {
            importer.import(listOf(retryableSource(1), retryableSource(2)))
        }

        assertEquals(4, openCount)
        assertEquals(2, retried.size)
        assertEquals(retried.map(::File).toSet(), temporaryFolder.root.listFiles().orEmpty().toSet())
    }

    private fun source(declaredMime: String?, marker: Int) = RecordPhotoImportSource(
        declaredMime = declaredMime,
        openStream = { ByteArrayInputStream(byteArrayOf(marker.toByte())) },
    )

    private fun source(declaredMime: String?, byteCount: Long) = RecordPhotoImportSource(
        declaredMime = declaredMime,
        openStream = { SizedInputStream(byteCount) },
    )

    private fun importer(
        directory: File,
        sniff: (File) -> RecordPhotoFileInspection?,
    ) = BoundedRecordPhotoImporter(directory = directory, sniff = sniff)

    private class SizedInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining -= 1
            return 0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }
}
