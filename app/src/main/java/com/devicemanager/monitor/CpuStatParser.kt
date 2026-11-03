package com.devicemanager.monitor

data class CpuStatCounter(val total: Long, val idle: Long)

object CpuStatParser {
    /** Parses the aggregate `cpu` row from Linux /proc/stat. */
    fun parse(line: String): CpuStatCounter? = runCatching {
        val fields = line.trim().split(Regex("\\s+"))
        require(fields.firstOrNull() == "cpu")
        val values = fields.drop(1).take(8).map(String::toLong)
        require(values.size >= 4 && values.all { it >= 0L })
        CpuStatCounter(
            total = values.sum(),
            idle = values[3] + (values.getOrNull(4) ?: 0L)
        )
    }.getOrNull()

    fun percentage(previous: CpuStatCounter, current: CpuStatCounter): Float? {
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L || idleDelta < 0L || idleDelta > totalDelta) return null
        return ((totalDelta - idleDelta).toFloat() / totalDelta.toFloat() * 100f).coerceIn(0f, 100f)
    }
}
