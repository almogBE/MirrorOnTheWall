package com.mirroronthewall.sender

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** Persistent sender identity, plus trust tokens issued by TVs. */
class SenderPrefs(context: Context) {

    private val identity = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val tokens = context.getSharedPreferences("receiver_tokens", Context.MODE_PRIVATE)
    private val hosts = context.getSharedPreferences("receiver_hosts", Context.MODE_PRIVATE)

    /** Stable random ID of this phone. TVs key their trust on it. */
    val senderId: String
        @Synchronized get() = identity.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            identity.edit { putString(KEY_ID, it) }
        }

    var lastManualAddress: String?
        get() = identity.getString(KEY_LAST_MANUAL, null)
        set(value) = identity.edit { putString(KEY_LAST_MANUAL, value) }

    fun trustToken(receiverId: String): String? = tokens.getString(receiverId, null)

    fun saveTrustToken(receiverId: String, token: String) = tokens.edit { putString(receiverId, token) }

    fun forgetTrustToken(receiverId: String) = tokens.edit { remove(receiverId) }

    /** Remembers which receiver answered at [host], so a manual connection can reuse its token. */
    fun rememberHost(host: String, receiverId: String) = hosts.edit { putString(host, receiverId) }

    fun receiverIdForHost(host: String): String? = hosts.getString(host, null)

    private companion object {
        const val KEY_ID = "sender_id"
        const val KEY_LAST_MANUAL = "last_manual_address"
    }
}
