package pl.lokalizator.ui

import android.content.Context
import android.content.Intent
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pl.lokalizator.AppState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Aktualny czas odświeżany co sekundę – do liczników "x s temu". */
@Composable
fun rememberNow(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now.longValue = System.currentTimeMillis()
        }
    }
    return now
}

/** Kierunek, w którym jest zwrócona góra telefonu (stopnie od północy geograficznej). */
@Composable
fun rememberHeading(): State<Float?> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        var sx = 0.0
        var sy = 0.0
        val rot = FloatArray(9)
        val ori = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                val a = ori[0].toDouble()
                // wygładzanie na okręgu (bez skoków 359° -> 0°)
                sx = sx * 0.85 + cos(a) * 0.15
                sy = sy * 0.85 + sin(a) * 0.15
                var deg = Math.toDegrees(atan2(sy, sx)).toFloat()
                AppState.myFix.value?.let {
                    deg += GeomagneticField(it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis()).declination
                }
                state.value = (deg + 360) % 360
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensor?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm?.unregisterListener(l) }
    }
    return state
}

@Composable
fun InfoCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

fun toast(ctx: Context, s: String) = Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()

fun openNavigation(ctx: Context, lat: Double, lon: Double, label: String) {
    val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: Exception) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=18/$lat/$lon")))
    }
}
