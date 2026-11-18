package com.mirroronthewall.common

/**
 * Constants shared by sender (phone) and receiver (TV).
 *
 * See docs/ARCHITECTURE.md §4 (discovery and signaling) for what each one is for.
 */
object MirrorConstants {
    /** DNS-SD service type the receiver advertises over NSD/mDNS. */
    const val NSD_SERVICE_TYPE: String = "_lowmirror._tcp"

    /**
     * Port the receiver tries first for its WebSocket signaling server. If it is
     * taken, the receiver binds any free port and advertises that one. The TV
     * screen always shows the actual IP:port for manual entry.
     */
    const val DEFAULT_SIGNALING_PORT: Int = 47913

    /** WebSocket path of the signaling endpoint. */
    const val SIGNALING_PATH: String = "/signal"

    /**
     * Signaling protocol version. Raise it on any incompatible message change.
     * Both sides reject a peer whose version differs.
     */
    const val PROTOCOL_VERSION: Int = 1

    /** TXT record keys published with the NSD service. */
    object Txt {
        const val PROTOCOL_VERSION = "v"
        const val RECEIVER_ID = "id"
        const val DISPLAY_NAME = "name"
    }

    /** Number of digits in the pairing code shown on the TV. */
    const val PAIRING_CODE_LENGTH: Int = 4
}
