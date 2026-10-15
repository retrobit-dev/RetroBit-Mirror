package dev.retrobit.mirror

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dev.retrobit.mirror.input.ShizukuGate

class RetroBitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status pantulan layar RetroBit"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        ShizukuGate.listen()
    }

    companion object {
        const val CHANNEL_ID = "retrobit_mirror"
    }
}
