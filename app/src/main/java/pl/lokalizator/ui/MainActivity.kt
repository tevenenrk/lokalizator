package pl.lokalizator.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import pl.lokalizator.Prefs
import pl.lokalizator.TrackerService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        // jeśli udostępnianie było włączone, upewnij się, że usługa działa
        if (prefs.sharing && prefs.isConfigured && hasLocation(this)) TrackerService.send(this)

        setContent {
            LokTheme {
                Surface(Modifier.fillMaxSize()) {
                    val ctx = LocalContext.current
                    var screen by remember { mutableStateOf(if (prefs.isConfigured) "home" else "setup") }
                    var findPeer by remember { mutableStateOf<String?>(null) }

                    BackHandler(enabled = screen != "home" && prefs.isConfigured) { screen = "home" }

                    when (screen) {
                        "setup" -> SetupScreen(
                            prefs = prefs,
                            canCancel = prefs.isConfigured,
                            onDone = { screen = "home" },
                            onCancel = { screen = "home" },
                        )
                        "find" -> FindScreen(
                            prefs = prefs,
                            peerId = findPeer,
                            onBack = { screen = "home" },
                        )
                        else -> HomeScreen(
                            prefs = prefs,
                            onSettings = { screen = "setup" },
                            onFind = { id ->
                                findPeer = id
                                TrackerService.send(ctx, TrackerService.ACTION_FIND)
                                screen = "find"
                            },
                            onOpenFind = { id -> findPeer = id; screen = "find" },
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun hasLocation(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Uprawnienia potrzebne do działania (bez lokalizacji "zawsze", o którą pytamy osobno). */
        fun neededPermissions(): Array<String> {
            val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                l += Manifest.permission.BLUETOOTH_SCAN
                l += Manifest.permission.BLUETOOTH_ADVERTISE
                l += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (Build.VERSION.SDK_INT >= 33) {
                l += Manifest.permission.POST_NOTIFICATIONS
                l += Manifest.permission.NEARBY_WIFI_DEVICES
            }
            return l.toTypedArray()
        }
    }
}
