package com.mirroronthewall.receiver

import android.content.Context
import androidx.core.content.edit
import com.mirroronthewall.common.pairing.TrustStore
import java.util.UUID

/** Persistent receiver identity and paired-sender token hashes. */
class ReceiverPrefs(context: Context) : TrustStore {

    private val identity = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val trust = context.getSharedPreferences("trusted_senders", Context.MODE_PRIVATE)

    /** Stable random ID of this TV, advertised in the NSD TXT record and sent in Welcome. */
    val receiverId: String
        @Synchronized get() = identity.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            identity.edit { putString(KEY_ID, it) }
        }

    override fun tokenHash(senderId: String): String? = trust.getString(senderId, null)

    override fun putTokenHash(senderId: String, tokenHash: String) {
        trust.edit { putString(senderId, tokenHash) }
    }

    override fun clear() {
        trust.edit { clear() }
    }

    private companion object {
        const val KEY_ID = "receiver_id"
    }
}
