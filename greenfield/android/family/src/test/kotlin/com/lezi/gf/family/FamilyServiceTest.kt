package com.lezi.gf.family

import com.google.common.truth.Truth.assertThat
import com.lezi.gf.kernel.GfResult
import com.lezi.gf.kernel.ProductVersion
import org.junit.Test

class FamilyServiceTest {
    @Test
    fun offlineBabyDoesNotForgeFamily() {
        val f = FamilyService()
        val baby = f.createOfflineBaby("豆豆") as GfResult.Ok
        assertThat(baby.value.nickname).isEqualTo("豆豆")
        assertThat(baby.value.familyAuthority).isFalse()
        assertThat(f.account().joinState).isEqualTo(JoinState.UNJOINED)
        assertThat(f.account().familyId).isNull()
        assertThat(f.account().endpoint).isEqualTo(ProductVersion.DEFAULT_ENDPOINT)
    }

    @Test
    fun memberCannotEditAuthorityArchive() {
        val f = FamilyService()
        f.markJoined("fam", "家", "mem", "member", "妈", "dev", "tok", "ref", null)
        f.applyAuthorityBabies(
            listOf(Baby(clientUuid = "auth", nickname = "宝", familyAuthority = true)),
        )
        val denied = f.memberEditAuthorityArchive("auth")
        assertThat(denied).isInstanceOf(GfResult.Err::class.java)
    }

    @Test
    fun clearLocalOnlyOnExplicitReasons() {
        val f = FamilyService()
        f.markJoined("fam", "家", "mem", "owner", "爸", "dev", "tok", "ref", "spki")
        f.clearFamilyLocalData("exit_device")
        assertThat(f.account().joinState).isEqualTo(JoinState.UNJOINED)
        try {
            f.clearFamilyLocalData("unauthorized")
            throw AssertionError("should refuse")
        } catch (_: IllegalArgumentException) {
        }
    }
}
