package com.mirroronthewall.common.signaling

import com.mirroronthewall.common.MirrorConstants
import java.net.URI

/** Network address of a receiver's signaling server. */
data class Endpoint(val host: String, val port: Int) {

    init {
        require(host.isNotBlank()) { "host is blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }

    /** `ws://host:port/signal`, with IPv6 literals bracketed. */
    fun toUri(): URI {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return URI("ws://$h:$port${MirrorConstants.SIGNALING_PATH}")
    }

    /** For display: `192.168.1.20:47913` or `[fe80::1]:47913`. */
    override fun toString(): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    companion object {
        /**
         * Parses what a user types in the manual-IP field: `192.168.1.20`,
         * `192.168.1.20:47913`, `[fe80::1]:47913`, `tv.local`. Returns null if it
         * can't be parsed.
         */
        fun parse(text: String, defaultPort: Int = MirrorConstants.DEFAULT_SIGNALING_PORT): Endpoint? {
            val t = text.trim().removePrefix("ws://").removeSuffix(MirrorConstants.SIGNALING_PATH).trimEnd('/')
            if (t.isEmpty() || t.any { it.isWhitespace() }) return null
            return try {
                when {
                    t.startsWith("[") -> {
                        val close = t.indexOf(']')
                        if (close < 0) return null
                        val host = t.substring(1, close)
                        val rest = t.substring(close + 1)
                        val port = if (rest.isEmpty()) defaultPort else rest.removePrefix(":").toInt()
                        Endpoint(host, port)
                    }
                    // Bare IPv6 literal (more than one colon) without port.
                    t.count { it == ':' } > 1 -> Endpoint(t, defaultPort)
                    t.contains(':') -> {
                        val (host, port) = t.split(':', limit = 2)
                        Endpoint(host, port.toInt())
                    }
                    else -> Endpoint(t, defaultPort)
                }
            } catch (e: IllegalArgumentException) { // includes NumberFormatException
                null
            }
        }
    }
}
