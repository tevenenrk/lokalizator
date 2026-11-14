package pl.lokalizator

import android.hardware.SensorManager
import android.location.Location
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

object Geo {
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = FloatArray(2)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0]
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = FloatArray(2)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[1]
    }

    /** Bardzo przybliżona odległość z siły sygnału BLE (zależy od telefonu, kieszeni, ludzi wokół). */
    fun bleMeters(rssi: Double): Double = 10.0.pow((-62.0 - rssi) / (10 * 2.3))

    fun bleLabel(rssi: Double): String = when {
        rssi > -55 -> "Tuż obok (1–2 m)"
        rssi > -67 -> "Bardzo blisko (~2–6 m)"
        rssi > -78 -> "Blisko (~6–15 m)"
        rssi > -88 -> "W pobliżu (~15–30 m)"
        else -> "Na skraju zasięgu (30 m+)"
    }

    fun fmtDist(m: Float): String = when {
        m < 1000 -> "${m.roundToInt()} m"
        else -> "%.1f km".format(m / 1000)
    }

    fun ago(ts: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - ts) / 1000).coerceAtLeast(0)
        return when {
            ts <= 0 -> "nigdy"
            s < 10 -> "teraz"
            s < 60 -> "$s s temu"
            s < 3600 -> "${s / 60} min temu"
            s < 86400 -> "${s / 3600} h temu"
            else -> "${s / 86400} dni temu"
        }
    }

    fun altitude(hpa: Float) = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, hpa)

    data class WifiCompare(
        val common: Int,
        val similarity: Int, // 0–100
        val peerStrongest: WifiAp?,
        val myRssiOfPeerStrongest: Int?,
    )

    /**
     * Porównanie "odcisków Wi-Fi": im więcej tych samych routerów o podobnej sile, tym bliżej.
     * Najsilniejszy router drugiego telefonu jest najpewniej tuż przy nim – jeśli Ty też go widzisz,
     * idź tak, by jego sygnał u Ciebie rósł.
     */
    fun compareWifi(mine: List<WifiAp>, theirs: List<WifiAp>): WifiCompare? {
        if (mine.isEmpty() || theirs.isEmpty()) return null
        val my = mine.associateBy { it.bssid }
        val th = theirs.associateBy { it.bssid }
        val common = my.keys intersect th.keys
        val union = (my.keys union th.keys).size.coerceAtLeast(1)
        val diffPenalty = if (common.isEmpty()) 1.0 else
            common.map { abs(my[it]!!.rssi - th[it]!!.rssi) }.average().coerceAtMost(30.0) / 30.0
        val sim = (common.size.toDouble() / union) * (1 - 0.6 * diffPenalty)
        val top = theirs.maxByOrNull { it.rssi }
        return WifiCompare(
            common = common.size,
            similarity = (sim * 100).roundToInt().coerceIn(0, 100),
            peerStrongest = top,
            myRssiOfPeerStrongest = top?.let { my[it.bssid]?.rssi },
        )
    }
}
