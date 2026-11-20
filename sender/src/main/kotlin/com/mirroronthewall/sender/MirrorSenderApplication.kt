package com.mirroronthewall.sender

import android.app.Application
import android.content.Context

class MirrorSenderApplication : Application() {
    /** Process-wide session. It outlives the activity so that mirroring runs in the background. */
    val session: SenderSession by lazy { SenderSession(this) }
}

val Context.senderSession: SenderSession
    get() = (applicationContext as MirrorSenderApplication).session
