package com.lezi.babylog.sync.disasterrecovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlinx.serialization.json.*

class RestoreAuthorityTest {
    @Test fun `shared new generation vectors preserve root and batch namespace separation`() {
        val corpus = Json.parseToJsonElement(requireNotNull(javaClass.classLoader
            ?.getResource("restore-authority-v1-golden.json")).readText()).jsonObject
        for (value in corpus.getValue("vectors").jsonArray) {
            val row = value.jsonObject
            fun field(key: String) = row.getValue(key).jsonPrimitive.content
            assertThat(RestoreAuthority.baseline(field("batch"), field("type"), field("entity")))
                .isEqualTo(field("baseline"))
            assertThat(RestoreAuthority.operation(field("batch"), field("type"), field("entity")))
                .isEqualTo(field("operation"))
        }
    }

    @Test fun `authenticated restore tuple has stable namespace separated identifiers`() {
        val batch = "11111111-1111-4111-8111-111111111111"
        val entity = "22222222-2222-4222-8222-222222222222"
        assertThat(RestoreAuthority.baseline(batch, "record", entity))
            .isEqualTo("e0bec011-0509-5e15-98e1-0f7e7a49ee87")
        assertThat(RestoreAuthority.operation(batch, "record", entity))
            .isEqualTo("048708c5-d2e5-5e67-bab6-de063addc130")
        assertThat(RestoreAuthority.baseline(batch, "baby", entity))
            .isNotEqualTo(RestoreAuthority.baseline(batch, "record", entity))
    }
}
