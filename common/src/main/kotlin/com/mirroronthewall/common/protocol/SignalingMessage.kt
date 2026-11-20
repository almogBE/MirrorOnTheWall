package com.mirroronthewall.common.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every message exchanged over the signaling WebSocket. Each message is one
 * JSON text frame, discriminated by its `type` field (see [SignalingCodec]).
 *
 * The sender (phone) is always the WebRTC offerer. The receiver (TV) only
 * answers. See docs/ARCHITECTURE.md §5.3.
 */
@Serializable
sealed interface SignalingMessage

/** First message from the sender after the socket opens. */
@Serializable
@SerialName("hello")
data class Hello(
    val protocolVersion: Int,
    val senderId: String,
    val senderName: String,
    /** The code shown on the TV. Needed when the sender has no valid [trustToken]. */
    val pairingCode: String? = null,
    /** Token from an earlier [Welcome] by this receiver, if any. */
    val trustToken: String? = null,
) : SignalingMessage

/** The receiver accepted the sender. The session is open. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val protocolVersion: Int,
    val receiverId: String,
    val receiverName: String,
    /** Store it per [receiverId]. Send it as [Hello.trustToken] next time to skip the pairing code. */
    val trustToken: String,
    val display: DisplayInfo,
) : SignalingMessage

/** Physical display of the receiver. The sender uses it to choose its capture size. */
@Serializable
data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val refreshHz: Float,
)

@Serializable
enum class RejectReason {
    /** No valid trust token and no pairing code was supplied. */
    PAIRING_REQUIRED,

    /** A pairing code was supplied but it was wrong. */
    BAD_CODE,

    /** Another phone is already connected. */
    BUSY,

    /** The two apps speak different protocol versions. */
    VERSION_MISMATCH,

    /** The first message was not a valid [Hello]. */
    PROTOCOL_ERROR,
}

/** The receiver refused the sender. The receiver closes the socket right after this. */
@Serializable
@SerialName("reject")
data class Reject(
    val reason: RejectReason,
    val receiverProtocolVersion: Int,
) : SignalingMessage

/** SDP offer (sender → receiver). Used from Phase 2. */
@Serializable
@SerialName("offer")
data class Offer(val sdp: String) : SignalingMessage

/** SDP answer (receiver → sender). Used from Phase 2. */
@Serializable
@SerialName("answer")
data class Answer(val sdp: String) : SignalingMessage

/** Trickle ICE candidate, in both directions. Used from Phase 2. */
@Serializable
@SerialName("ice")
data class IceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
) : SignalingMessage

/** The receiver saw ICE fail and asks the sender (the offerer) to restart ICE. Used from Phase 3. */
@Serializable
@SerialName("iceRestartRequest")
data object IceRestartRequest : SignalingMessage

/** Orderly end of the session, in either direction. */
@Serializable
@SerialName("bye")
data class Bye(val reason: String) : SignalingMessage

/**
 * Application-level liveness probe from the sender. The receiver echoes it
 * back as a [Pong] with the same fields. The sender measures signaling RTT
 * with its own monotonic clock, so the two devices' clocks never need to agree.
 */
@Serializable
@SerialName("ping")
data class Ping(val seq: Long, val senderTimeNanos: Long) : SignalingMessage

@Serializable
@SerialName("pong")
data class Pong(val seq: Long, val senderTimeNanos: Long) : SignalingMessage
