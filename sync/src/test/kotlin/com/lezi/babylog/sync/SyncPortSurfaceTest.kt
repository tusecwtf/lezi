package com.lezi.babylog.sync
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Public SyncPort surface contract (ticket 16): production zero-call and
 * familyId-ignoring wrappers must not remain on the shared façade.
 *
 * Live session, endpoint, sync trigger, and app-update paths stay public.
 */
class SyncPortSurfaceTest {
    @Test
    fun publicSyncPortDoesNotExposeDeadWrappers() {
        val names = publicMethodNames(SyncPort::class.java)

        assertThat(names).doesNotContain("isEnabled")
        assertThat(names).doesNotContain("saveServer")
        // familyId-taking push/pull wrappers (not backend session pull/push).
        assertThat(names).doesNotContain("pull")
        assertThat(names).doesNotContain("push")
    }

    @Test
    fun publicSyncPortLeaveHasNoFamilyIdParameter() {
        val leaveMethods = SyncPort::class.java.methods
            .filter { it.name.substringBefore('-') == "leave" }
        assertThat(leaveMethods).isNotEmpty()
        leaveMethods.forEach { method ->
            // Suspend methods carry a Continuation; no product params (e.g. familyId).
            assertThat(productParameterTypes(method)).isEmpty()
        }
    }

    @Test
    fun publicSyncPortKeepsLiveSessionEndpointSyncAndAppUpdateSeams() {
        val names = publicMethodNames(SyncPort::class.java)

        assertThat(names).containsAtLeast(
            "status",
            "session",
            "requestSync",
            "sync",
            "saveEndpointConfig",
            "leave",
            "clearLocalData",
            "checkAppUpdate",
            "availableForcedAppUpdate",
            "availableOptionalAppUpdate",
            "installAvailableAppUpdate",
            "cleanupAppUpdateStaging",
        )
    }

    @Test
    fun noOpSyncPortDoesNotDeclareDeadWrappers() {
        val names = publicMethodNames(NoOpSyncPort::class.java)

        assertThat(names).doesNotContain("isEnabled")
        assertThat(names).doesNotContain("saveServer")
        assertThat(names).doesNotContain("pull")
        assertThat(names).doesNotContain("push")
    }

    @Test
    fun realSyncPortLeaveHasNoFamilyIdParameter() {
        val declared = RealSyncPort::class.java.declaredMethods
            .filter { it.name.substringBefore('-') == "leave" }
        assertThat(declared).isNotEmpty()
        declared.forEach { method ->
            assertThat(productParameterTypes(method)).isEmpty()
        }
    }

    /** Strip Kotlin JVM name mangling on suspend methods (`foo-gIAlu-s` → `foo`). */
    private fun publicMethodNames(type: Class<*>): Set<String> =
        type.methods.map { it.name.substringBefore('-') }.toSet()

    /** Parameters excluding Kotlin coroutine Continuation. */
    private fun productParameterTypes(method: java.lang.reflect.Method): List<Class<*>> =
        method.parameterTypes.filterNot {
            it.name == "kotlin.coroutines.Continuation"
        }
}
