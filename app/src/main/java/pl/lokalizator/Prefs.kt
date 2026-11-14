package pl.lokalizator

import android.content.Context
import java.security.SecureRandom
import java.util.UUID

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("lok", Context.MODE_PRIVATE)

    val deviceId: String
        get() = sp.getString("deviceId", null)
            ?: UUID.randomUUID().toString().replace("-", "").take(12).also {
                sp.edit().putString("deviceId", it).apply()
            }

    var name: String
        get() = sp.getString("name", null) ?: android.os.Build.MODEL
        set(v) = sp.edit().putString("name", v.trim()).apply()

    var code: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v.trim().uppercase()).apply()

    /** Pusty = domyślne serwery (dwa naraz). Inaczej: własny adres, np. ssl://xxx.hivemq.cloud:8883 */
    var broker: String
        get() = sp.getString("broker", "") ?: ""
        set(v) = sp.edit().putString("broker", v.trim()).apply()

    val usesDefaultServers: Boolean get() = broker.isBlank() || broker in LEGACY_DEFAULTS

    /** Lista (nazwa, adresy do wypróbowania po kolei). */
    fun servers(): List<Pair<String, List<String>>> =
        if (usesDefaultServers) DEFAULT_SERVERS
        else listOf(broker.substringAfter("://").substringBefore(":").substringBefore("/") to listOf(broker))

    var user: String
        get() = sp.getString("user", "") ?: ""
        set(v) = sp.edit().putString("user", v.trim()).apply()

    var pass: String
        get() = sp.getString("pass", "") ?: ""
        set(v) = sp.edit().putString("pass", v).apply()

    /** Co ile sekund wysyłać pozycję w trybie normalnym. */
    var intervalSec: Int
        get() = sp.getInt("interval", 60)
        set(v) = sp.edit().putInt("interval", v).apply()

    /** Dokładny GPS także w trybie normalnym (więcej baterii). */
    var preciseNormal: Boolean
        get() = sp.getBoolean("precise", false)
        set(v) = sp.edit().putBoolean("precise", v).apply()

    var sharing: Boolean
        get() = sp.getBoolean("sharing", false)
        set(v) = sp.edit().putBoolean("sharing", v).apply()

    var findMinutes: Int
        get() = sp.getInt("findMin", 5)
        set(v) = sp.edit().putInt("findMin", v).apply()

    var lastCmdId: String
        get() = sp.getString("lastCmd", "") ?: ""
        set(v) = sp.edit().putString("lastCmd", v).apply()

    /** Różnica ciśnień (hPa) między barometrami: peer - ja, zmierzona gdy telefony były obok siebie. */
    fun baroOffset(peerId: String): Float = sp.getFloat("baro_$peerId", 0f)
    fun hasBaroOffset(peerId: String): Boolean = sp.contains("baro_$peerId")
    fun setBaroOffset(peerId: String, v: Float) = sp.edit().putFloat("baro_$peerId", v).apply()

    val isConfigured: Boolean get() = code.replace("-", "").length >= 6

    companion object {
        val DEFAULT_SERVERS = listOf(
            "HiveMQ" to listOf("ssl://broker.hivemq.com:8883", "wss://broker.hivemq.com:8884/mqtt"),
            "EMQX" to listOf("ssl://broker.emqx.io:8883", "wss://broker.emqx.io:8084/mqtt"),
        )
        /** Adresy zapisane przez wersję 1.0 – traktujemy je jak "domyślne". */
        val LEGACY_DEFAULTS = setOf(
            "ssl://broker.hivemq.com:8883", "ssl://broker.emqx.io:8883", "ssl://test.mosquitto.org:8886",
        )
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun newCode(): String {
            val r = SecureRandom()
            val s = (1..10).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
            return s.substring(0, 5) + "-" + s.substring(5)
        }
    }
}
