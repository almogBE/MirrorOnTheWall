package com.mirroronthewall.common.pairing

import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.common.protocol.Hello
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Persistent storage of paired senders. Holds only token *hashes*, never the tokens. */
interface TrustStore {
    fun tokenHash(senderId: String): String?
    fun putTokenHash(senderId: String, tokenHash: String)
    fun clear()
}

class InMemoryTrustStore : TrustStore {
    private val map = HashMap<String, String>()

    @Synchronized
    override fun tokenHash(senderId: String): String? = map[senderId]

    @Synchronized
    override fun putTokenHash(senderId: String, tokenHash: String) {
        map[senderId] = tokenHash
    }

    @Synchronized
    override fun clear() = map.clear()
}

/**
 * Receiver-side pairing policy (docs/ARCHITECTURE.md §5.2).
 *
 * - A sender with a valid trust token is accepted without a code.
 * - Otherwise it must send the [pairingCode] shown on the TV. When it does, it gets
 *   a new random token. The code is then rotated, since it has been used and was visible.
 * - After [maxFailures] wrong codes in a row, the code is rotated. This caps a brute-force
 *   attempt at [maxFailures] guesses out of 10^4 per code.
 *
 * This is LAN-level access control, not strong authentication. The media is
 * separately DTLS-SRTP encrypted by WebRTC.
 */
class PairingAuthority(
    private val trustStore: TrustStore,
    private val random: SecureRandom = SecureRandom(),
    private val maxFailures: Int = 5,
) {
    sealed interface Decision {
        data class Accepted(val trustToken: String, val newlyPaired: Boolean) : Decision

        /** No valid token, and no code supplied. */
        data object CodeRequired : Decision

        /** A code was supplied but it was wrong. */
        data object WrongCode : Decision
    }

    private val _pairingCode = MutableStateFlow(newCode())

    /** The code to show on the TV. Changes after a successful pairing or too many failures. */
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private var consecutiveFailures = 0

    @Synchronized
    fun authorize(hello: Hello): Decision {
        val token = hello.trustToken
        if (token != null) {
            val stored = trustStore.tokenHash(hello.senderId)
            if (stored != null && constantTimeEquals(stored, hash(token))) {
                return Decision.Accepted(token, newlyPaired = false)
            }
        }

        val code = hello.pairingCode ?: return Decision.CodeRequired
        if (!constantTimeEquals(code, _pairingCode.value)) {
            consecutiveFailures++
            if (consecutiveFailures >= maxFailures) rotateCodeLocked()
            return Decision.WrongCode
        }

        val newToken = newToken()
        trustStore.putTokenHash(hello.senderId, hash(newToken))
        rotateCodeLocked()
        return Decision.Accepted(newToken, newlyPaired = true)
    }

    @Synchronized
    fun forgetAllSenders() {
        trustStore.clear()
        rotateCodeLocked()
    }

    private fun rotateCodeLocked() {
        consecutiveFailures = 0
        var next = newCode()
        while (next == _pairingCode.value) next = newCode()
        _pairingCode.value = next
    }

    private fun newCode(): String {
        val bound = pow10(MirrorConstants.PAIRING_CODE_LENGTH)
        return random.nextInt(bound).toString().padStart(MirrorConstants.PAIRING_CODE_LENGTH, '0')
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private companion object {
        fun pow10(n: Int): Int = (1..n).fold(1) { acc, _ -> acc * 10 }

        fun hash(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        }

        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
