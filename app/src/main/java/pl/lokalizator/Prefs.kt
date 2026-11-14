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

    var broker: String
        get() = sp.getString("broker", DEFAULT_BROKER) ?: DEFAULT_BROKER
        set(v) = sp.edit().putString("broker", v.trim()).apply()

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
        const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"
        val BROKERS = listOf(
            "ssl://broker.hivemq.com:8883",
            "ssl://broker.emqx.io:8883",
            "ssl://test.mosquitto.org:8886",
        )
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun newCode(): String {
            val r = SecureRandom()
            val s = (1..10).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
            return s.substring(0, 5) + "-" + s.substring(5)
        }
    }
}
