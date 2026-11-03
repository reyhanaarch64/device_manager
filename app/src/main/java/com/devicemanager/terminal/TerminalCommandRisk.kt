package com.devicemanager.terminal

object TerminalCommandRisk {
    private val patterns = listOf(
        Regex("(?i)\\brm\\s+|\\bunlink\\s+|\\bshred\\s+"),
        Regex("(?i)\\bwipe\\b|\\bformat\\b|\\bmkfs\\b|\\bblockdev\\s+--?setro"),
        Regex("(?i)\\breboot\\b|\\bshutdown\\b|\\bpoweroff\\b|\\bfastboot\\b"),
        Regex("(?i)\\bchmod\\s+-R\\b|\\bchown\\s+-R\\b"),
        Regex("(?i)\\bpm\\s+(uninstall|disable-user|clear)\\b"),
        Regex("(?i)\\bam\\s+force-stop\\b"),
        Regex("(?i)\\bcmd\\s+package\\s+(install|uninstall|clear|disable|suspend)\\b"),
        Regex("(?i)\\bresetprop\\b|\\bdd\\s+if="),
        Regex("(?i)\\bsettings\\s+put\\b|\\bwm\\s+(size|density)\\s+\\S+"),
        Regex("(?i)\\bsvc\\s+(wifi|data|bluetooth)\\s+(disable|enable)\\b")
    )

    fun requiresConfirmation(command: String): Boolean = patterns.any { it.containsMatchIn(command) }
}
