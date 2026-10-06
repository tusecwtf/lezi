package com.lezi.babylog.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedCameraCaptureSessionsTest {
    @Test
    fun beginCreatesUniqueRecoverableOwnedFilesAndReleaseIsIdempotent() {
        val fileSystem = FakeCaptureFileSystem()
        val tokens = ArrayDeque(listOf("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"))
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { tokens.removeFirst() },
        )

        val first = sessions.begin()
        val second = sessions.begin()

        assertNotEquals(first.token, second.token)
        assertEquals(first, sessions.recover(first.token.value))
        assertTrue(sessions.release(first.token))
        assertFalse(sessions.release(first.token))
        assertNull(sessions.recover(first.token.value))
        assertNotNull(sessions.recover(second.token.value))
    }

    @Test
    fun exclusiveCreateRetriesACollidingTokenWithoutReusingItsFile() {
        val fileSystem = FakeCaptureFileSystem()
        val collision = "00000000-0000-0000-0000-000000000010"
        val unique = "00000000-0000-0000-0000-000000000011"
        val first = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { collision },
        ).begin()
        val retries = ArrayDeque(listOf(collision, unique))
        val retrying = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { retries.removeFirst() },
        )

        val second = retrying.begin()

        assertEquals(collision, first.token.value)
        assertEquals(unique, second.token.value)
        assertEquals(2, fileSystem.ownedNames().size)
    }

    @Test
    fun supersedeReleasesOnlyTheCaptureThisModuleOwns() {
        val fileSystem = FakeCaptureFileSystem().apply {
            addExternal("picker-photo.jpg")
        }
        val tokens = ArrayDeque(listOf("00000000-0000-0000-0000-000000000003", "00000000-0000-0000-0000-000000000004"))
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { tokens.removeFirst() },
        )
        val first = sessions.begin()

        val second = sessions.begin(replacing = first.token)

        assertNull(sessions.recover(first.token.value))
        assertNotNull(sessions.recover(second.token.value))
        assertTrue(fileSystem.contains("picker-photo.jpg"))
    }

    @Test
    fun recreatedConsumerCanRecoverPendingOwnershipBeforeOrphanCollection() {
        val fileSystem = FakeCaptureFileSystem()
        val token = "00000000-0000-0000-0000-000000000005"
        val firstProcess = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { token },
        )
        val pending = firstProcess.begin()

        firstProcess.preserveForRecreation(pending.token)
        val recreated = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { error("unused") },
            nowMillis = { 2 * DAY },
        )
        assertEquals(pending, recreated.recover(token))
        assertEquals(0, recreated.collectOrphans())
        assertNotNull(recreated.recover(token))

        recreated.preserveForRecreation(pending.token)
        val noConsumerProcess = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { error("unused") },
            nowMillis = { 2 * DAY },
        )
        assertEquals(1, noConsumerProcess.collectOrphans())
        assertNull(noConsumerProcess.recover(token))
    }

    @Test
    fun cancelFailureAndSuccessfulCommitUseTheSameExplicitRelease() {
        val fileSystem = FakeCaptureFileSystem()
        val tokens = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000006",
                "00000000-0000-0000-0000-000000000007",
                "00000000-0000-0000-0000-000000000008",
            ),
        )
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { tokens.removeFirst() },
        )

        val cancelled = sessions.begin()
        val failedImport = sessions.begin()
        val committed = sessions.begin()

        assertTrue(sessions.release(cancelled.token))
        assertTrue(sessions.release(failedImport.token))
        assertTrue(sessions.release(committed.token))
        assertTrue(fileSystem.ownedNames().isEmpty())
    }

    @Test
    fun transientDeleteFailureDoesNotCrashAndLeavesAnOrphanForBoundedGcRetry() {
        val fileSystem = FakeCaptureFileSystem()
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { "00000000-0000-0000-0000-00000000000c" },
            nowMillis = { 2 * DAY },
        )
        val capture = sessions.begin()
        fileSystem.failDeletes = true

        assertFalse(sessions.release(capture.token))
        assertNotNull(sessions.recover(capture.token.value))

        sessions.preserveForRecreation(capture.token)
        fileSystem.failDeletes = false
        assertEquals(1, sessions.collectOrphans())
        assertNull(sessions.recover(capture.token.value))
    }

    @Test
    fun repeatedCancelKeepsOwnedCacheInventoryBounded() {
        val fileSystem = FakeCaptureFileSystem()
        var sequence = 20
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = {
                "00000000-0000-0000-0000-${sequence++.toString().padStart(12, '0')}"
            },
        )

        repeat(100) {
            val capture = sessions.begin()
            sessions.release(capture.token)
        }

        assertTrue(fileSystem.ownedNames().isEmpty())
        assertEquals(0, sessions.collectOrphans())
    }

    @Test
    fun controllerIsSingleFlightAndIgnoresCallbacksForAnotherToken() {
        val fileSystem = FakeCaptureFileSystem()
        val tokens = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000020",
                "00000000-0000-0000-0000-000000000021",
            ),
        )
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { tokens.removeFirst() },
        )
        val controller = OwnedCameraCaptureController(sessions, null, null)
        val first = requireNotNull(controller.beginPicture())

        assertNull(controller.beginPicture())
        assertEquals(
            CameraPictureCompletion.Ignored,
            controller.finishPicture(success = false, callbackToken = tokens.first()),
        )
        assertNotNull(sessions.recover(first.token.value))

        assertEquals(
            CameraPictureCompletion.Cancelled,
            controller.finishPicture(success = false, callbackToken = first.token.value),
        )
        assertNull(sessions.recover(first.token.value))
        assertNotNull(controller.beginPicture())
    }

    @Test
    fun disposeWaitsOutTheOldResultBeforeAllowingANewerCapture() {
        val fileSystem = FakeCaptureFileSystem()
        val tokens = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000022",
                "00000000-0000-0000-0000-000000000023",
            ),
        )
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { tokens.removeFirst() },
        )
        val controller = OwnedCameraCaptureController(sessions, null, null)
        val old = requireNotNull(controller.beginPicture())

        controller.dispose()
        assertEquals(CameraCapturePhase.DiscardingPicture, controller.snapshot.phase)
        assertNull(controller.beginPicture())
        assertNull(sessions.recover(old.token.value))

        assertEquals(
            CameraPictureCompletion.Ignored,
            controller.finishPicture(success = true, callbackToken = old.token.value),
        )
        val current = requireNotNull(controller.beginPicture())
        assertEquals(
            CameraPictureCompletion.Ignored,
            controller.finishPicture(success = true, callbackToken = old.token.value),
        )
        assertNotNull(sessions.recover(current.token.value))
    }

    @Test
    fun restoredControllerRecoversExactPendingTokenBeforeGcAndCompletesIt() {
        val fileSystem = FakeCaptureFileSystem()
        val token = "00000000-0000-0000-0000-000000000024"
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { token },
            nowMillis = { 2 * DAY },
        )
        var saved = CameraCaptureSnapshot(CameraCapturePhase.Idle, null)
        val first = OwnedCameraCaptureController(sessions, null, null) { saved = it }
        val pending = requireNotNull(first.beginPicture())
        first.preserveForRecreation()

        val restoredSessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { error("unused") },
            nowMillis = { 2 * DAY },
        )
        val restored = OwnedCameraCaptureController(
            sessions = restoredSessions,
            restoredPhase = saved.phase.name,
            restoredToken = saved.token,
        )
        assertEquals(pending, restored.recoverBeforeCollection())
        assertEquals(0, restoredSessions.collectOrphans())

        val completion = restored.finishPicture(success = true, callbackToken = token)
        assertTrue(completion is CameraPictureCompletion.Captured)
        assertEquals(CameraCapturePhase.Idle, restored.snapshot.phase)
        restored.release(token)
        assertNull(restoredSessions.recover(token))
    }

    @Test
    fun matchingReleaseResetsPendingPhaseEvenWhenCachePressureAlreadyRemovedTheFile() {
        val fileSystem = FakeCaptureFileSystem()
        val token = "00000000-0000-0000-0000-000000000025"
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { token },
        )
        val controller = OwnedCameraCaptureController(sessions, null, null)
        requireNotNull(controller.beginPicture())
        assertTrue(fileSystem.delete("capture_$token.jpg"))

        controller.release(token)

        assertEquals(CameraCapturePhase.Idle, controller.snapshot.phase)
        assertNotNull(controller.beginPicture())
    }

    @Test
    fun repeatedMissingFileReleaseEndsEveryLeaseSoBoundedGcCanRetryNames() {
        val fileSystem = FakeCaptureFileSystem()
        var sequence = 100
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = {
                "00000000-0000-0000-0000-${sequence++.toString().padStart(12, '0')}"
            },
            nowMillis = { 2 * DAY },
        )

        repeat(100) {
            val capture = sessions.begin()
            assertTrue(fileSystem.delete(capture.fileName))
            assertFalse(sessions.releaseRaw(capture.token.value))
            fileSystem.addOwnedLooking(
                capture.fileName,
                realPath = "${fileSystem.rootRealPath}/${capture.fileName}",
                regular = true,
            )
        }

        assertEquals(100, sessions.collectOrphans())
        assertTrue(fileSystem.ownedNames().isEmpty())
    }

    @Test
    fun missingFilesStillConvergeThroughCancelDisposeAndRecreationPreserve() {
        val fileSystem = FakeCaptureFileSystem()
        val tokens = ArrayDeque(
            listOf(
                "00000000-0000-0000-0000-000000000300",
                "00000000-0000-0000-0000-000000000301",
                "00000000-0000-0000-0000-000000000302",
            ),
        )
        val sessions = OwnedCameraCaptureSessions(
            fileSystem,
            { tokens.removeFirst() },
            { 2 * DAY },
        )

        val cancelled = OwnedCameraCaptureController(sessions, null, null)
        val cancelledFile = requireNotNull(cancelled.beginPicture())
        assertTrue(fileSystem.delete(cancelledFile.fileName))
        assertEquals(
            CameraPictureCompletion.Cancelled,
            cancelled.finishPicture(false, cancelledFile.token.value),
        )

        val disposed = OwnedCameraCaptureController(sessions, null, null)
        val disposedFile = requireNotNull(disposed.beginPicture())
        assertTrue(fileSystem.delete(disposedFile.fileName))
        disposed.dispose()

        val recreated = OwnedCameraCaptureController(sessions, null, null)
        val recreatedFile = requireNotNull(recreated.beginPicture())
        assertTrue(fileSystem.delete(recreatedFile.fileName))
        recreated.preserveForRecreation()

        listOf(cancelledFile, disposedFile, recreatedFile).forEach { capture ->
            fileSystem.addOwnedLooking(
                capture.fileName,
                realPath = "${fileSystem.rootRealPath}/${capture.fileName}",
                regular = true,
            )
        }
        assertEquals(3, sessions.collectOrphans())
    }

    @Test
    fun pathEscapeSymlinkAndNonRegularEntryAreNeverDeleted() {
        val fileSystem = FakeCaptureFileSystem()
        val escape = "capture_00000000-0000-0000-0000-000000000009.jpg"
        val symlink = "capture_00000000-0000-0000-0000-00000000000a.jpg"
        val directory = "capture_00000000-0000-0000-0000-00000000000b.jpg"
        fileSystem.addOwnedLooking(escape, realPath = "/outside/$escape", regular = true)
        fileSystem.addOwnedLooking(symlink, realPath = "/outside/target.jpg", regular = false)
        fileSystem.addOwnedLooking(directory, realPath = "/cache/camera/$directory", regular = false)
        val sessions = OwnedCameraCaptureSessions(
            fileSystem = fileSystem,
            nextToken = { error("unused") },
            nowMillis = { 2 * DAY },
        )

        assertEquals(0, sessions.collectOrphans())

        assertTrue(fileSystem.contains(escape))
        assertTrue(fileSystem.contains(symlink))
        assertTrue(fileSystem.contains(directory))
    }

    private class FakeCaptureFileSystem : CameraCaptureFileSystem {
        private data class FakeEntry(
            override val name: String,
            override val realPath: String,
            override val isRegularFile: Boolean,
            override val modifiedAtMillis: Long = 0,
        ) : CameraCaptureFileEntry

        override val rootRealPath: String = "/cache/camera"
        private val entries = linkedMapOf<String, FakeEntry>()
        var failDeletes: Boolean = false

        override fun createExclusive(name: String): CameraCaptureFileEntry? {
            if (entries.containsKey(name)) return null
            return FakeEntry(name, "$rootRealPath/$name", true).also { entries[name] = it }
        }

        override fun find(name: String): CameraCaptureFileEntry? = entries[name]

        override fun list(): List<CameraCaptureFileEntry> = entries.values.toList()

        override fun delete(name: String): Boolean {
            if (failDeletes) error("transient delete failure")
            return entries.remove(name) != null
        }

        fun addExternal(name: String) {
            entries[name] = FakeEntry(name, "$rootRealPath/$name", true)
        }

        fun addOwnedLooking(name: String, realPath: String, regular: Boolean) {
            entries[name] = FakeEntry(name, realPath, regular)
        }

        fun contains(name: String): Boolean = entries.containsKey(name)

        fun ownedNames(): List<String> = entries.keys.filter { it.startsWith("capture_") }
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1_000
    }
}
