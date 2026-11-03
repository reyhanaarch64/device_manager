package com.devicemanager.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidUserRepositoryTest {
    @Test
    fun parsesUsersWithoutAssumingIds() {
        val users = AndroidUserRepository.parseUsers(
            """Users:
                UserInfo{0:Owner:13} running
                UserInfo{12:Work profile:30}
                UserInfo{105:Guest:4}
            """.trimIndent(), fallbackId = 0
        )
        assertEquals(listOf(0, 12, 105), users.map { it.id })
        assertEquals("Work profile", users[1].name)
    }

    @Test
    fun fallsBackToRuntimeUserWhenOutputIsUnrecognized() {
        val users = AndroidUserRepository.parseUsers("not a pm users response", fallbackId = 23)
        assertEquals(23, users.single().id)
    }

    @Test
    fun parsesPackageListAndRemovesDuplicates() {
        val packages = AndroidUserRepository.parsePackages(
            """package:com.example.app
                package:com.android.systemui
                package:com.example.app
                not-a-package
            """.trimIndent()
        )
        assertEquals(listOf("com.android.systemui", "com.example.app"), packages)
    }
}
