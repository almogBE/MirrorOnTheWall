package com.mirroronthewall.common.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** JSON encoding of [SignalingMessage]: one message per WebSocket text frame. */
object SignalingCodec {

    private val json = Json {
        classDiscriminator = "type"
        // A newer peer may add fields. Ignoring them keeps minor additions compatible
        // without bumping PROTOCOL_VERSION.
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(message: SignalingMessage): String =
        json.encodeToString(SignalingMessage.serializer(), message)

    /** Returns null for malformed JSON or an unknown `type`. Never throws. */
    fun decode(text: String): SignalingMessage? =
        try {
            json.decodeFromString(SignalingMessage.serializer(), text)
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}
