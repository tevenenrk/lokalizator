@file:OptIn(ExperimentalLayoutApi::class)

package pl.lokalizator.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.lokalizator.AppState
import pl.lokalizator.FindSensors
import pl.lokalizator.Geo
import pl.lokalizator.Peer
import pl.lokalizator.Prefs
import pl.lokalizator.TrackerService
import kotlin.math.roundToInt

@Composable
fun HomeScreen(prefs: Prefs, onSettings: () -> Unit, onFind: (String) -> Unit, onOpenFind: (String) -> Unit) {
    val ctx = LocalContext.current
    val running by AppState.serviceRunning.collectAsState()
    val conn by AppState.connection.collectAsState()
    val me by AppState.myFix.collectAsState()
    val peersMap by AppState.peers.collectAsState()
    val findUntil by AppState.findUntil.collectAsState()
    val local by AppState.local.collectAsState()
    val now by rememberNow()
    var refit by remember { mutableIntStateOf(0) }
    var permTick by remember { mutableIntStateOf(0) }
    val peers = peersMap.values.sortedBy { it.name }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        if (MainActivity.hasLocation(ctx)) {
            prefs.sharing = true
            TrackerService.send(ctx)
        }
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permTick++ }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) permTick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lokalizator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Kod: ${prefs.code}  •  ${prefs.name}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSettings) { Text("Ustawienia") }
        }

        InfoCard("Udostępnianie mojej lokalizacji") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (running) "Włączone" else "Wyłączone", fontWeight = FontWeight.SemiBold)
                    Text("Serwer: $conn", style = MaterialTheme.typography.bodySmall)
                    me?.let {
                        Text("Moja pozycja: ±${it.acc.roundToInt()} m, ${Geo.ago(it.ts, now)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Switch(checked = running, onCheckedChange = { on ->
                    if (on) permLauncher.launch(MainActivity.neededPermissions())
                    else {
                        prefs.sharing = false
                        TrackerService.send(ctx, TrackerService.ACTION_STOP)
                    }
                })
            }
            if (findUntil > now) {
                Spacer(Modifier.height(6.dp))
                Text("TRYB SZUKANIA aktywny jeszcze ${fmtLeft(findUntil - now)}",
                    color = Hot, fontWeight = FontWeight.Bold)
            }
        }

        // Ostrzeżenia, które realnie wpływają na niezawodność działania w tle
        @Suppress("UNUSED_EXPRESSION") permTick
        if (running && !MainActivity.hasBackground(ctx)) {
            Warn(
                "Zalecane: zezwól na lokalizację „Zawsze”, żeby udostępnianie nie urywało się po restarcie telefonu.",
                "Zezwól"
            ) {
                if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (running && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Warn(
                "Zalecane: wyłącz optymalizację baterii dla Lokalizatora – inaczej telefon może „uśpić” aplikację " +
                    "(zużycie i tak jest małe: 1 pozycja na minutę).",
                "Ustaw"
            ) {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")))
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Card(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp))) {
            val pts = peers.count { it.lat != null } + (if (me != null) 1 else 0)
            MapPanel(me, peers, fitKey = refit * 10 + pts, modifier = Modifier.fillMaxSize())
        }
        TextButton(onClick = { refit++ }) { Text("Pokaż wszystkich na mapie") }

        if (peers.isEmpty()) {
            InfoCard("Czekam na drugi telefon") {
                Text(
                    "Zainstaluj aplikację na drugim telefonie i wpisz ten sam kod: ${prefs.code}. " +
                        "Pojawi się tutaj, gdy tylko wyśle pierwszą pozycję."
                )
            }
        }

        peers.forEach { p ->
            PeerCard(p, me, local.pressure, prefs, now,
                onFind = { onFind(p.id) },
                onOpenFind = { onOpenFind(p.id) },
                findActive = findUntil > now,
                onCalibrate = {
                    FindSensors.readPressureOnce(ctx) { mine ->
                        val theirs = p.pressure
                        if (theirs == null) toast(ctx, "Drugi telefon nie ma barometru albo nie przysłał odczytu.")
                        else {
                            prefs.setBaroOffset(p.id, theirs - mine)
                            toast(ctx, "Skalibrowano barometry (różnica %.2f hPa).".format(theirs - mine))
                        }
                    }
                })
        }
    }
}

@Composable
private fun Warn(text: String, button: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClick) { Text(button) }
        }
    }
}

fun fmtLeft(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Różnica wysokości z barometrów (peer - ja) w metrach, z uwzględnieniem kalibracji. */
fun altDiff(prefs: Prefs, peer: Peer, myPressure: Float?): Float? {
    val pp = peer.pressure ?: return null
    val mp = myPressure ?: return null
    val corrected = pp - prefs.baroOffset(peer.id)
    return Geo.altitude(corrected) - Geo.altitude(mp)
}

fun floorsText(diff: Float): String {
    val floors = (diff / 3.5f).roundToInt()
    return when {
        floors == 0 -> "to samo piętro (różnica ${diff.roundToInt()} m)"
        floors > 0 -> "ok. $floors piętro/a WYŻEJ (+${diff.roundToInt()} m)"
        else -> "ok. ${-floors} piętro/a NIŻEJ (${diff.roundToInt()} m)"
    }
}

@Composable
private fun PeerCard(
    p: Peer, me: pl.lokalizator.MyFix?, myPressure: Float?, prefs: Prefs, now: Long,
    onFind: () -> Unit, onOpenFind: () -> Unit, findActive: Boolean, onCalibrate: () -> Unit,
) {
    val ctx = LocalContext.current
    InfoCard(p.name) {
        val stale = now - p.lastSeen > (prefs.intervalSec * 3 + 60) * 1000L
        Text("Ostatni kontakt: ${Geo.ago(p.lastSeen, now)}" + if (stale) "  (telefon mógł stracić internet)" else "",
            color = if (stale) Hot else MaterialTheme.colorScheme.onSurface)
        if (p.lat != null && p.lon != null) {
            val dist = me?.let { Geo.distance(it.lat, it.lon, p.lat, p.lon) }
            Text(
                (dist?.let { "Odległość: ${Geo.fmtDist(it)}  •  " } ?: "") +
                    "dokładność ±${p.acc?.roundToInt() ?: "?"} m  •  pozycja ${Geo.ago(p.fixTs, now)}"
            )
        } else Text("Brak pozycji")
        Text(buildString {
            append("Bateria: ${p.battery?.let { "$it%" } ?: "?"}")
            if (p.charging) append(" (ładuje się)")
        })
        altDiff(prefs, p, myPressure)?.let {
            Text("Wysokość: ${floorsText(it)}" + if (!prefs.hasBaroOffset(p.id)) " – nieskalibrowane" else "",
                style = MaterialTheme.typography.bodySmall)
        }
        if (p.findUntil > now) Text("Ten telefon jest w trybie szukania", color = Hot, fontWeight = FontWeight.SemiBold)

        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = if (findActive) onOpenFind else onFind,
                colors = ButtonDefaults.buttonColors(containerColor = Hot)
            ) { Text(if (findActive) "Otwórz szukanie" else "Szukaj (${prefs.findMinutes} min)") }
            OutlinedButton(onClick = {
                TrackerService.send(ctx, TrackerService.ACTION_RING)
                toast(ctx, "Wysłano: dzwonek na telefonie ${p.name} (30 s)")
            }) { Text("Dzwonek") }
            if (p.lat != null && p.lon != null) {
                OutlinedButton(onClick = { openNavigation(ctx, p.lat, p.lon, p.name) }) { Text("Nawiguj") }
            }
        }
        TextButton(onClick = onCalibrate) { Text("Jesteśmy obok siebie – kalibruj barometry (piętra)") }
    }
}
