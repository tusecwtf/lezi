package com.lezi.babylog.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CareLogCatalogIntegrityTest {
    @Test
    fun editingADeletedDefinitionFailsInsteadOfReportingSaved() = runTest {
        val care = Fakes().careLog()
        care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.addCustomItem("运动", 1)
        val draft = care.observeCustomItems().first().single { it.id == id }.copy(name = "练习")
        care.deleteCustomItem(id)
        assertThat(runCatching { care.updateCustomItem(draft) }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(care.observeCustomItems().first()).isEmpty()
    }

    @Test
    fun deletionRechecksTheCurrentDefinitionInsideTheCommit() = runTest {
        val fakes = Fakes()
        val care = fakes.careLog()
        care.createBaby(CreateBabyInput(nickname = "豆豆", birthdayEpochDay = 1))
        val id = care.addCustomItem("运动", 1)
        val draft = care.observeCustomItems().first().single { it.id == id }
        fakes.transactions.beforeNextRun = {
            fakes.customItems.update(fakes.customItems.getById(id)!!.copy(
                name = "家庭已修改", updatedAt = Long.MAX_VALUE - 100,
            ))
        }
        care.deleteCustomItem(id)
        assertThat(care.observeCustomItems().first()).isEmpty()
        assertThat(runCatching { care.updateCustomItem(draft) }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }
}
