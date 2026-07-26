package com.lezi.babylog.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JoinFamilyContractTest {
    @Test
    fun joinSurfaceRequiresOneCompleteCommand() {
        val joinMethods = SyncPort::class.java.declaredMethods
            .map { it.name.substringBefore('-') }
            .filter { it.startsWith("join") }

        assertThat(joinMethods).containsExactly("joinFamily")

        val commandConstructors = JoinFamilyCommand::class.java.declaredConstructors
        assertThat(commandConstructors).hasLength(1)
        assertThat(commandConstructors.single().parameterTypes.asList()).containsExactly(
            String::class.java,
            HomeLanServerConfig::class.java,
            String::class.java,
        ).inOrder()
    }
}
