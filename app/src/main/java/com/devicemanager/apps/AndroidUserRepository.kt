package com.devicemanager.apps

import android.os.Process

object AndroidUserRepository {
    data class UserProfile(
        val id: Int,
        val name: String,
        val flags: String = ""
    ) {
        override fun toString(): String = "$name · user $id"
    }

    fun currentUserId(): Int = Process.myUid() / 100_000

    /** Parses `pm list users`; never assumes a particular secondary/profile ID. */
    fun parseUsers(output: String, fallbackId: Int = currentUserId()): List<UserProfile> {
        val parsed = Regex("UserInfo\\{(\\d+):([^}:]+)(?::([^}]+))?}")
            .findAll(output)
            .mapNotNull { match ->
                val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                UserProfile(id, match.groupValues[2].ifBlank { "user $id" }, match.groupValues[3])
            }
            .distinctBy(UserProfile::id)
            .sortedBy(UserProfile::id)
            .toList()
        return if (parsed.isNotEmpty()) parsed else listOf(UserProfile(fallbackId, "profil aktif"))
    }

    fun parsePackages(output: String): List<String> = output.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").substringBefore('=') }
        .filter { it.matches(Regex("[A-Za-z0-9_.]+")) }
        .distinct()
        .sorted()
        .toList()
}
