package com.mirroronthewall.common.pairing

import com.mirroronthewall.common.MirrorConstants
import com.mirroronthewall.common.protocol.Hello
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAuthorityTest {

    private val store = InMemoryTrustStore()
    private val auth = PairingAuthority(store, maxFailures = 3)

    private fun hello(code: String? = null, token: String? = null, id: String = "phone-1") =
        Hello(MirrorConstants.PROTOCOL_VERSION, id, "Phone", code, token)

    @Test
    fun codeHasConfiguredLengthAndOnlyDigits() {
        val code = auth.pairingCode.value
        assertEquals(MirrorConstants.PAIRING_CODE_LENGTH, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun noCodeNoToken_requiresCode() {
        assertEquals(PairingAuthority.Decision.CodeRequired, auth.authorize(hello()))
    }

    @Test
    fun correctCode_issuesTokenAndRotatesCode() {
        val code = auth.pairingCode.value
        val d = auth.authorize(hello(code = code))
        assertTrue(d is PairingAuthority.Decision.Accepted && d.newlyPaired)
        assertNotEquals(code, auth.pairingCode.value)
        // Only the hash is stored.
        val token = (d as PairingAuthority.Decision.Accepted).trustToken
        assertNotEquals(token, store.tokenHash("phone-1"))
    }

    @Test
    fun issuedToken_isAcceptedWithoutCode_onlyForSameSender() {
        val token = (auth.authorize(hello(code = auth.pairingCode.value)) as PairingAuthority.Decision.Accepted).trustToken
        val again = auth.authorize(hello(token = token))
        assertEquals(PairingAuthority.Decision.Accepted(token, newlyPaired = false), again)
        // Another sender presenting the stolen token is not accepted.
        assertEquals(PairingAuthority.Decision.CodeRequired, auth.authorize(hello(token = token, id = "phone-2")))
    }

    @Test
    fun invalidToken_fallsBackToCode() {
        val code = auth.pairingCode.value
        val d = auth.authorize(hello(code = code, token = "bogus"))
        assertTrue(d is PairingAuthority.Decision.Accepted && d.newlyPaired)
    }

    @Test
    fun wrongCode_isRejected_andRepeatedFailuresRotateTheCode() {
        val code = auth.pairingCode.value
        val wrong = if (code == "0000") "0001" else "0000"
        assertEquals(PairingAuthority.Decision.WrongCode, auth.authorize(hello(code = wrong)))
        assertEquals(PairingAuthority.Decision.WrongCode, auth.authorize(hello(code = wrong)))
        assertEquals(code, auth.pairingCode.value)
        assertEquals(PairingAuthority.Decision.WrongCode, auth.authorize(hello(code = wrong)))
        assertNotEquals("third failure rotates the code", code, auth.pairingCode.value)
        // The old code no longer works.
        assertEquals(PairingAuthority.Decision.WrongCode, auth.authorize(hello(code = code)))
    }

    @Test
    fun forgetAllSenders_revokesTokens() {
        val token = (auth.authorize(hello(code = auth.pairingCode.value)) as PairingAuthority.Decision.Accepted).trustToken
        auth.forgetAllSenders()
        assertEquals(PairingAuthority.Decision.CodeRequired, auth.authorize(hello(token = token)))
    }
}
