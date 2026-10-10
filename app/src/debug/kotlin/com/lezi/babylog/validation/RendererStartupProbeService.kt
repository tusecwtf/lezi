package com.lezi.babylog.validation

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.lezi.babylog.LeziApp

/** Same-UID, debug-only observer. Runs beside the real, unmodified renderer service. */
class RendererStartupProbeService : Service() {
    private val receiver = Messenger(Handler(Looper.getMainLooper()) { request ->
        if (request.sendingUid != Process.myUid() || request.what != 1) return@Handler true
        val nonce = request.data.getString("nonce") ?: return@Handler true
        val app = application
        request.replyTo?.send(Message.obtain().apply {
            what = 2
            data = Bundle().apply {
                putString("nonce", nonce)
                putInt("pid", Process.myPid())
                putString("application", app.javaClass.name)
                putBoolean("productionApplication", app is LeziApp)
                putStringArrayList("boundaries", ArrayList(AppStartupObservation.snapshot()))
            }
        })
        true
    })

    override fun onBind(intent: Intent): IBinder = receiver.binder
}
