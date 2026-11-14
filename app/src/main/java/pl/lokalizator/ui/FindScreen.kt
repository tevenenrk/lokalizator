@file:OptIn(ExperimentalLayoutApi::class)

package pl.lokalizator.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.lokalizator.AppState
import pl.lokalizator.Crypto
import pl.lokalizator.Geo
import pl.lokalizator.Prefs
import pl.lokalizator.TrackerService
import kotlin.math.roundToInt

@Composable
fun FindScreen(prefs: Prefs, peerId: String?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val peers by AppState.peers.collectAsState()
    val me by AppState.myFix.collectAsState()
    val local by AppState.local.collectAsState()
    val findUntil by AppState.findUntil.collectAsState()
    val heading by rememberHeading()
    val now by rememberNow()
    val peer = peers[peerId] ?: peers.values.firstOrNull()
    val active = findUntil > now

    // --- Bluetooth: obie strony mierzą siłę sygnału; łączymy w jedną wartość ---
    val peerTag = peer?.let { Crypto.deviceTag(it.id) }
    val myBle = peerTag?.let { local.ble[it] }?.takeIf { now - it.ts < 12_000 }?.rssi
    val theirBle = peer?.live?.takeIf { now - it.receivedAt < 12_000 }?.bleRssiOfMe?.toDouble()
    val ble = listOfNotNull(myBle, theirBle).takeIf { it.isNotEmpty() }?.average()

    // historia do "ciepło / zimno"
    val hist = remember { mutableStateListOf<Pair<Long, Double>>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val s = AppState.local.value
            val p = AppState.peers.value[peer?.id]
            val t = System.currentTimeMillis()
            val a = peerTag?.let { s.ble[it] }?.takeIf { t - it.ts < 12_000 }?.rssi
            val b = p?.live?.takeIf { t - it.receivedAt < 12_000 }?.bleRssiOfMe?.toDouble()
            val v = listOfNotNull(a, b).takeIf { it.isNotEmpty() }?.average()
            if (v != null) hist.add(t to v)
            while (hist.isNotEmpty() && t - hist.first().first > 20_000) hist.removeAt(0)
        }
    }
    val trend: Double? = run {
        val recent = hist.filter { now - it.first < 5_000 }.map { it.second }
        val older = hist.filter { now - it.first in 8_000..18_000 }.map { it.second }
        if (recent.size >= 2 && older.size >= 2) recent.average() - older.average() else null
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Wróć") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("Szukam: ${peer?.name ?: "—"}", fontWeight = FontWeight.Bold)
                Text(
                    if (active) "zostało ${fmtLeft(findUntil - now)}" else "tryb szukania wyłączony",
                    color = if (active) Hot else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (peer == null) {
            Text("Brak drugiego telefonu. Sprawdź, czy ma ten sam kod i włączone udostępnianie.")
            return@Column
        }

        // ---------- Werdykt ----------
        val gpsDist = if (me != null && peer.lat != null && peer.lon != null)
            Geo.distance(me!!.lat, me!!.lon, peer.lat, peer.lon) else null
        val gpsUnc = (me?.acc ?: 50f) + (peer.acc ?: 50f)
        val wifiCmp = peer.live?.let { Geo.compareWifi(local.wifi, it.wifi) }
        val verdict = when {
            ble != null -> Geo.bleLabel(ble)
            wifiCmp != null && wifiCmp.similarity >= 35 -> "W tym samym miejscu/budynku (Wi-Fi)"
            gpsDist != null && gpsDist > gpsUnc -> "Ok. ${Geo.fmtDist(gpsDist)} stąd"
            gpsDist != null -> "W promieniu ok. ${Geo.fmtDist(gpsUnc)}"
            else -> "Czekam na dane…"
        }
        Text(verdict, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        trend?.let {
            val (txt, col) = when {
                it > 2.5 -> "CIEPLEJ – zbliżasz się" to Hot
                it < -2.5 -> "ZIMNIEJ – oddalasz się" to Cold
                else -> "bez zmian" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(txt, color = col, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }

        // ---------- Strzałka ----------
        Spacer(Modifier.height(8.dp))
        val bearing = if (me != null && peer.lat != null && peer.lon != null)
            Geo.bearing(me!!.lat, me!!.lon, peer.lat, peer.lon) else null
        val reliable = gpsDist != null && gpsDist > gpsUnc * 0.8f
        Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
            val ring = MaterialTheme.colorScheme.outlineVariant
            val col = if (reliable) Hot else Color.Gray
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(ring, style = Stroke(4f))
            }
            if (bearing != null && heading != null) {
                Canvas(Modifier.fillMaxSize().rotate(bearing - heading!!)) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w / 2, h * 0.08f)
                        lineTo(w * 0.72f, h * 0.52f)
                        lineTo(w * 0.56f, h * 0.48f)
                        lineTo(w * 0.56f, h * 0.85f)
                        lineTo(w * 0.44f, h * 0.85f)
                        lineTo(w * 0.44f, h * 0.48f)
                        lineTo(w * 0.28f, h * 0.52f)
                        close()
                    }
                    drawPath(path, col.copy(alpha = if (reliable) 1f else 0.45f))
                    drawCircle(col, radius = 6f, center = Offset(w / 2, h / 2))
                }
            } else {
                Text(if (heading == null) "Brak kompasu" else "Czekam na GPS…", textAlign = TextAlign.Center)
            }
        }
        Text(
            when {
                bearing == null -> ""
                reliable -> "Kierunek z GPS: ${gpsDist?.let { Geo.fmtDist(it) }}. Trzymaj telefon płasko."
                else -> "GPS jest za mało dokładny na tę odległość (±${gpsUnc.roundToInt()} m) – " +
                    "kieruj się Bluetooth (ciepło/zimno) i Wi-Fi poniżej."
            },
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
        )

        // ---------- Bluetooth ----------
        InfoCard("Bluetooth (najlepsze w sklepie, do ~30–50 m)") {
            if (ble != null) {
                Text("${Geo.bleLabel(ble)}  •  ~${Geo.bleMeters(ble).let { if (it < 10) "%.1f".format(it) else it.roundToInt().toString() }} m",
                    fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(
                    progress = { ((ble + 100) / 60).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    color = Hot,
                )
                Text("Ty słyszysz ${peer.name}: ${myBle?.roundToInt()?.let { "$it dBm" } ?: "—"}  •  " +
                    "${peer.name} słyszy Ciebie: ${theirBle?.roundToInt()?.let { "$it dBm" } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Obróć się powoli dookoła – sygnał jest najsłabszy, gdy Twoje ciało zasłania telefon. " +
                    "Idź tam, gdzie robi się „cieplej”.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(local.bleError ?: if (active) "Brak sygnału – jesteście dalej niż ~30–50 m (albo Bluetooth jest wyłączony na którymś telefonie)."
                else "Włącz tryb szukania, aby mierzyć.")
            }
        }

        // ---------- Wi-Fi ----------
        InfoCard("Wi-Fi w okolicy") {
            val live = peer.live
            if (wifiCmp != null && live != null) {
                Text("Podobieństwo otoczenia: ${wifiCmp.similarity}%  (${wifiCmp.common} wspólnych routerów)",
                    fontWeight = FontWeight.SemiBold)
                wifiCmp.peerStrongest?.let { ap ->
                    val name = ap.ssid.ifBlank { ap.bssid.takeLast(8) }
                    Text("Najbliższy router przy ${peer.name}: „$name” (${ap.rssi} dBm u niej/niego).")
                    Text(
                        wifiCmp.myRssiOfPeerStrongest?.let { "Ty widzisz go z siłą $it dBm – idź tak, żeby ta liczba rosła (bliżej zera)." }
                            ?: "Ty go nie widzisz – jesteś w innej części budynku.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                live.wifiConnected?.let { Text("${peer.name} jest podłączony(a) do sieci: „$it”", style = MaterialTheme.typography.bodySmall) }
            } else {
                Text(
                    when {
                        !active -> "Włącz tryb szukania, aby porównać sieci."
                        local.wifi.isEmpty() -> "Czekam na skan Wi-Fi (Wi-Fi musi być włączone; Android pozwala skanować co ~30 s)."
                        else -> "Czekam na dane Wi-Fi z drugiego telefonu…"
                    }
                )
            }
        }

        // ---------- Piętro ----------
        altDiff(prefs, peer, local.pressure)?.let { d ->
            InfoCard("Piętro (barometr)") {
                Text(floorsText(d), fontWeight = FontWeight.SemiBold)
                if (!prefs.hasBaroOffset(peer.id)) Text(
                    "Nieskalibrowane – barometry różnych telefonów różnią się. Gdy będziecie razem, użyj „kalibruj” na ekranie głównym.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // ---------- Stan drugiego telefonu ----------
        InfoCard("Telefon: ${peer.name}") {
            val live = peer.live
            live?.moving?.let { Text(if (it) "Jest w ruchu (ktoś idzie z telefonem)" else "Leży / stoi w miejscu") }
            Text("Bateria: ${peer.battery?.let { "$it%" } ?: "?"}${if (peer.charging) " (ładuje się)" else ""}")
            Text("GPS: ±${peer.acc?.roundToInt() ?: "?"} m, pozycja ${Geo.ago(peer.fixTs, now)}")
            Text("Ostatnie dane: ${Geo.ago(maxOf(peer.lastSeen, live?.receivedAt ?: 0), now)}",
                style = MaterialTheme.typography.bodySmall)
            if (active && (live == null || now - live.receivedAt > 15_000)) Text(
                "Drugi telefon jeszcze nie odpowiada w trybie szukania – może nie mieć internetu lub włączonej aplikacji.",
                color = Hot, style = MaterialTheme.typography.bodySmall
            )
        }

        // ---------- Akcje ----------
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = {
                TrackerService.send(ctx, TrackerService.ACTION_RING); toast(ctx, "Dzwonek wysłany (30 s)")
            }, colors = ButtonDefaults.buttonColors(containerColor = Hot)) { Text("Zadzwoń dzwonkiem") }
            OutlinedButton(onClick = { TrackerService.send(ctx, TrackerService.ACTION_RING_STOP_REMOTE) }) { Text("Wycisz") }
            OutlinedButton(onClick = { TrackerService.send(ctx, TrackerService.ACTION_TORCH) }) { Text("Mignij latarką") }
            if (peer.lat != null && peer.lon != null) {
                OutlinedButton(onClick = { openNavigation(ctx, peer.lat, peer.lon, peer.name) }) { Text("Mapy") }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { TrackerService.send(ctx, TrackerService.ACTION_FIND) }) {
                    Text("Przedłuż (+${prefs.findMinutes} min od teraz)")
                }
                OutlinedButton(onClick = { TrackerService.send(ctx, TrackerService.ACTION_FIND_STOP) }) { Text("Zakończ") }
            }
        } else {
            Button(onClick = { TrackerService.send(ctx, TrackerService.ACTION_FIND) }, modifier = Modifier.fillMaxWidth()) {
                Text("Włącz tryb szukania (${prefs.findMinutes} min)")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
