package pl.lokalizator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import org.osmdroid.config.Configuration

class LokApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = packageName
        Configuration.getInstance().osmdroidBasePath = cacheDir
        Configuration.getInstance().osmdroidTileCache = java.io.File(cacheDir, "tiles")

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACK, "Udostępnianie lokalizacji", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Szukanie i dzwonek", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        const val CH_TRACK = "track"
        const val CH_ALERT = "alert"
    }
}
