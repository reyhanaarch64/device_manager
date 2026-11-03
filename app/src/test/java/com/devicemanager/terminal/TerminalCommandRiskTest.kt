package com.devicemanager.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandRiskTest {
    @Test fun catchesDestructiveShellCommands() {
        listOf("rm -rf /sdcard/test", "sh -c 'rm -rf /data/local/tmp'", "pm uninstall --user 0 com.example.app", "settings put global window_animation_scale 0", "wm size 720x1600", "reboot").forEach {
            assertTrue("expected confirmation for: $it", TerminalCommandRisk.requiresConfirmation(it))
        }
    }

    @Test fun allowsReadOnlyCommands() {
        listOf("getprop", "pm list packages", "settings list global", "wm size").forEach {
            assertFalse("unexpected confirmation for: $it", TerminalCommandRisk.requiresConfirmation(it))
        }
    }
}
