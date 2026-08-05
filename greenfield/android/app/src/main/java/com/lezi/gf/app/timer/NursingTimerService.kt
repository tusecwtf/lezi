package com.lezi.gf.app.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lezi.gf.app.LeziGfApp
import com.lezi.gf.app.R

/** FGS keeps nursing timer recoverable across process death intent (ticket 09). */
class NursingTimerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("喂奶计时中")
            .setContentText("乐记正在计时")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        startForeground(42, notification)
        // Tick into care store
        (application as? LeziGfApp)?.container?.care?.tickTimer()
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "喂奶计时", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        const val CHANNEL = "nursing_timer"
    }
}
