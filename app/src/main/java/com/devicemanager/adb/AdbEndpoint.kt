package com.devicemanager.adb

data class AdbEndpoint(val host: String, val port: Int) {
    fun asText(): String = if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    companion object {
        fun parse(raw: String): AdbEndpoint {
            val value = raw.trim()
            require(value.isNotEmpty()) { "alamat belum diisi" }
            val host: String
            val portText: String
            if (value.startsWith("[")) {
                val end = value.indexOf(']')
                require(end > 1 && value.getOrNull(end + 1) == ':') { "format ipv6 harus [alamat]:port" }
                host = value.substring(1, end)
                portText = value.substring(end + 2)
            } else {
                val separator = value.lastIndexOf(':')
                require(separator > 0 && separator < value.length - 1) { "format harus ip:port atau [ipv6]:port" }
                host = value.substring(0, separator)
                require(':' !in host) { "ipv6 harus ditulis dalam tanda kurung siku: [alamat]:port" }
                portText = value.substring(separator + 1)
            }
            require(portText.matches(Regex("\\d{1,5}"))) { "port harus berupa angka 1–5 digit" }
            val port = portText.toIntOrNull() ?: throw IllegalArgumentException("port harus berupa angka")
            require(port in 1..65535) { "port harus berada di rentang 1–65535" }
            require(host.isNotBlank() && host.none { it.isWhitespace() }) { "alamat host tidak valid" }
            return AdbEndpoint(host, port)
        }
    }
}
