package pl.lokalizator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class WifiAp(val bssid: String, val ssid: String, val rssi: Int)

/** Dane z trybu szukania, które przysłał drugi telefon. */
data class PeerLive(
    val ts: Long,
    val wifi: List<WifiAp>,
    val wifiConnected: String?,
    /** Jak silnie drugi telefon słyszy MNIE przez Bluetooth (dBm). */
    val bleRssiOfMe: Int?,
    val moving: Boolean?,
    val receivedAt: Long,
)

data class Peer(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?,
    val acc: Float?,
    val fixTs: Long,
    val lastSeen: Long,
    val battery: Int?,
    val charging: Boolean,
    val pressure: Float?,
    val findUntil: Long,
    val live: PeerLive?,
)

data class BleSeen(val rssi: Double, val raw: Int, val ts: Long)

/** To, co mierzy MÓJ telefon w trybie szukania. */
data class LocalSense(
    val pressure: Float? = null,
    val wifi: List<WifiAp> = emptyList(),
    val wifiTs: Long = 0,
    val wifiConnected: String? = null,
    /** tag urządzenia -> wygładzony RSSI */
    val ble: Map<String, BleSeen> = emptyMap(),
    val bleError: String? = null,
    val moving: Boolean? = null,
)

data class MyFix(val lat: Double, val lon: Double, val acc: Float, val ts: Long)

object AppState {
    val serviceRunning = MutableStateFlow(false)
    val connection = MutableStateFlow("rozłączony")
    val myFix = MutableStateFlow<MyFix?>(null)
    val findUntil = MutableStateFlow(0L)
    val peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val local = MutableStateFlow(LocalSense())

    fun updatePeer(id: String, f: (Peer?) -> Peer) = peers.update { it + (id to f(it[id])) }
}
