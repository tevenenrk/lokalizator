package pl.lokalizator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import pl.lokalizator.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Usługa działająca w tle.
 *
 * Tryb NORMALNY (oszczędny): pozycja z Wi-Fi/sieci komórkowej co [Prefs.intervalSec] s,
 * jedna wiadomość na serwer na minutę, stałe połączenie z rzadkim keep-alive.
 *
 * Tryb SZUKANIA (domyślnie 5 min, potem sam się wyłącza): GPS co 2 s, Bluetooth LE,
 * skan Wi-Fi, barometr, akcelerometr; oba telefony wymieniają dane co 3 s.
 */
class TrackerService : Service() {
    private lateinit var prefs: Prefs
    private lateinit var fused: FusedLocationProviderClient
    private val main = Handler(Looper.getMainLooper())
    private var crypto: Crypto? = null
    private var link: MqttLink? = null
    private var sensors: FindSensors? = null
    private var lastLoc: Location? = null
    private var lastStatePublish = 0L
    private var findMode = false
    private var findUntil = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var myTag = ""
    private var lastPressure: Float? = null
    private val seenCmds = ArrayDeque<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        myTag = Crypto.deviceTag(prefs.deviceId)
        seenCmds.addAll(prefs.lastCmdId.split(",").filter { it.isNotBlank() })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.sharing = false
                link?.publish("state", stateJson().put("off", true), true)
                main.postDelayed({ shutdown(); stopSelf() }, 800)
                return START_NOT_STICKY
            }
            ACTION_RESTART -> { shutdown(); ensureStarted() }
            ACTION_FIND -> { ensureStarted(); startFind(System.currentTimeMillis() + prefs.findMinutes * 60_000L, true, null) }
            ACTION_FIND_STOP -> { ensureStarted(); stopFind(true) }
            ACTION_RING -> { ensureStarted(); sendAct("ring") }
            ACTION_RING_STOP_REMOTE -> { ensureStarted(); sendAct("ringstop") }
            ACTION_TORCH -> { ensureStarted(); sendAct("torch") }
            ACTION_SILENCE -> { Alarm.stop(this); cancelAlert() }
            ACTION_PUBLISH_NOW -> { ensureStarted(); publishState() }
            else -> ensureStarted()
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun ensureStarted() {
        if (link != null || !prefs.isConfigured) return
        val c = Crypto(prefs.code)
        crypto = c
        link = MqttLink(prefs, c, ::onMessage).also { it.start() }
        AppState.serviceRunning.value = true
        requestLocation()
        main.post(heartbeat)
    }

    private fun shutdown() {
        if (findMode) stopFind(false)
        main.removeCallbacksAndMessages(null)
        try { fused.removeLocationUpdates(locCb) } catch (_: Exception) {}
        link?.stop()
        link = null
        AppState.serviceRunning.value = false
    }

    override fun onDestroy() {
        shutdown()
        Alarm.stop(this)
        super.onDestroy()
    }

    // ---------------- Lokalizacja ----------------

    private val locCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            val l = r.lastLocation ?: return
            lastLoc = l
            AppState.myFix.value = MyFix(l.latitude, l.longitude, l.accuracy, l.time)
            val interval = prefs.intervalSec * 1000L
            if (!findMode && System.currentTimeMillis() - lastStatePublish > interval / 2) publishState()
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        try { fused.removeLocationUpdates(locCb) } catch (_: Exception) {}
        val req = if (findMode) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
                .setMinUpdateIntervalMillis(1000)
                .build()
        } else {
            val interval = prefs.intervalSec * 1000L
            LocationRequest.Builder(
                if (prefs.preciseNormal) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                interval
            ).setMinUpdateIntervalMillis(interval / 2).build()
        }
        try {
            fused.requestLocationUpdates(req, locCb, Looper.getMainLooper())
            fused.lastLocation.addOnSuccessListener { l -> if (l != null && lastLoc == null) locCb.onLocationResult(LocationResult.create(listOf(l))) }
        } catch (e: SecurityException) {
            AppState.connection.value = "brak uprawnień do lokalizacji"
        }
    }

    /** Co interwał: wyślij stan nawet bez nowej pozycji (żeby było widać, że telefon żyje). */
    private val heartbeat = object : Runnable {
        override fun run() {
            FindSensors.readPressureOnce(this@TrackerService) { p ->
                lastPressure = p
                if (!findMode) AppState.local.update { it.copy(pressure = p) }
            }
            if (!findMode && System.currentTimeMillis() - lastStatePublish > prefs.intervalSec * 900L) publishState()
            main.postDelayed(this, prefs.intervalSec * 1000L)
        }
    }

    // ---------------- Wysyłanie ----------------

    private fun battery(): Pair<Int?, Boolean> {
        val bm = getSystemService(BatteryManager::class.java) ?: return null to false
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        return lvl to bm.isCharging
    }

    private fun stateJson(): JSONObject {
        val (bat, chg) = battery()
        return JSONObject().apply {
            put("name", prefs.name)
            put("ts", System.currentTimeMillis())
            lastLoc?.let {
                put("lat", it.latitude); put("lon", it.longitude)
                put("acc", it.accuracy.toDouble()); put("fix", it.time)
            }
            bat?.let { put("bat", it) }
            put("chg", chg)
            (AppState.local.value.pressure.takeIf { findMode } ?: lastPressure)?.let { put("prs", it.toDouble()) }
            put("find", if (findMode) findUntil else 0L)
        }
    }

    private fun publishState() {
        lastStatePublish = System.currentTimeMillis()
        link?.publish("state", stateJson(), true)
    }

    private val liveLoop = object : Runnable {
        override fun run() {
            if (!findMode) return
            val s = AppState.local.value
            val j = stateJson().apply {
                put("wifi", JSONArray().apply {
                    s.wifi.forEach { put(JSONArray().put(it.bssid).put(it.ssid).put(it.rssi)) }
                })
                s.wifiConnected?.let { put("wcon", it) }
                put("ble", JSONArray().apply {
                    val now = System.currentTimeMillis()
                    s.ble.filter { now - it.value.ts < 15_000 }
                        .forEach { (tag, v) -> put(JSONArray().put(tag).put(v.rssi.toInt())) }
                })
                s.moving?.let { put("mov", it) }
            }
            link?.publish("live", j, false)
            main.postDelayed(this, LIVE_MS)
        }
    }

    private fun sendAct(act: String) {
        link?.publish(
            "cmd/act",
            JSONObject().put("cmd", act).put("cid", UUID.randomUUID().toString()).put("ts", System.currentTimeMillis())
                .put("name", prefs.name),
            false
        )
    }

    // ---------------- Tryb szukania ----------------

    private val endFind = Runnable { stopFind(true) }

    private fun startFind(until: Long, notifyPeers: Boolean, byWhom: String?) {
        findUntil = until
        AppState.findUntil.value = until
        if (!findMode) {
            findMode = true
            sensors = FindSensors(this, crypto!!.bleGroup, myTag).also { it.start() }
            requestLocation()
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lokalizator:find").apply {
                    acquire((until - System.currentTimeMillis()).coerceIn(10_000, 31 * 60_000L))
                }
            main.post(liveLoop)
        }
        main.removeCallbacks(endFind)
        main.postDelayed(endFind, (until - System.currentTimeMillis()).coerceAtLeast(1000))
        if (notifyPeers) {
            link?.publish(
                "cmd/mode",
                JSONObject().put("cmd", "find").put("cid", UUID.randomUUID().toString())
                    .put("ts", System.currentTimeMillis()).put("until", until).put("name", prefs.name),
                true
            )
        }
        if (byWhom != null) {
            showAlert(
                "$byWhom szuka Twojego telefonu",
                "Tryb szukania włączony do ${hhmm(until)} (Bluetooth, Wi-Fi, GPS). Potem wyłączy się sam."
            )
        }
        updateNotification()
        publishState()
    }

    private fun stopFind(notifyPeers: Boolean) {
        if (notifyPeers) {
            link?.publish(
                "cmd/mode",
                JSONObject().put("cmd", "stop").put("cid", UUID.randomUUID().toString())
                    .put("ts", System.currentTimeMillis()).put("name", prefs.name),
                true
            )
        }
        if (!findMode) return
        findMode = false
        findUntil = 0
        AppState.findUntil.value = 0
        main.removeCallbacks(endFind)
        main.removeCallbacks(liveLoop)
        sensors?.stop()
        sensors = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        requestLocation()
        updateNotification()
        publishState()
    }

    // ---------------- Odbiór ----------------

    private fun onMessage(sender: String, kind: String, j: JSONObject) {
        val now = System.currentTimeMillis()
        when (kind) {
            "state", "live" -> {
                // stare wpisy (np. z poprzedniej instalacji) pomijamy
                if (now - j.optLong("ts") > 3 * 86_400_000L) return
                if (j.optBoolean("off")) {
                    AppState.peers.value = AppState.peers.value - sender
                    return
                }
                AppState.updatePeer(sender) { old ->
                    val hasLoc = j.has("lat")
                    val live = if (kind == "live") {
                        val wifi = j.optJSONArray("wifi")?.let { a ->
                            (0 until a.length()).mapNotNull { i ->
                                a.optJSONArray(i)?.let { WifiAp(it.optString(0), it.optString(1), it.optInt(2)) }
                            }
                        } ?: emptyList()
                        var bleOfMe: Int? = null
                        j.optJSONArray("ble")?.let { a ->
                            for (i in 0 until a.length()) {
                                val e = a.optJSONArray(i) ?: continue
                                if (e.optString(0) == myTag) bleOfMe = e.optInt(1)
                            }
                        }
                        PeerLive(
                            ts = j.optLong("ts"), wifi = wifi, wifiConnected = j.optString("wcon").ifEmpty { null },
                            bleRssiOfMe = bleOfMe, moving = if (j.has("mov")) j.optBoolean("mov") else null,
                            receivedAt = now,
                        )
                    } else old?.live
                    Peer(
                        id = sender,
                        name = j.optString("name", old?.name ?: "?"),
                        lat = if (hasLoc) j.optDouble("lat") else old?.lat,
                        lon = if (hasLoc) j.optDouble("lon") else old?.lon,
                        acc = if (hasLoc) j.optDouble("acc").toFloat() else old?.acc,
                        fixTs = if (hasLoc) j.optLong("fix") else old?.fixTs ?: 0,
                        lastSeen = maxOf(j.optLong("ts"), old?.lastSeen ?: 0),
                        battery = if (j.has("bat")) j.optInt("bat") else old?.battery,
                        charging = j.optBoolean("chg"),
                        pressure = if (j.has("prs")) j.optDouble("prs").toFloat() else old?.pressure,
                        findUntil = j.optLong("find"),
                        live = live,
                    )
                }
            }
            "cmd/mode" -> {
                val cid = j.optString("cid")
                if (cid.isEmpty() || cid in seenCmds) return
                rememberCmd(cid)
                val who = j.optString("name", "Drugi telefon")
                when (j.optString("cmd")) {
                    "find" -> {
                        val until = j.optLong("until")
                        // ignoruj stare polecenia zapamiętane na serwerze
                        if (until > now + 10_000 && until - now < 31 * 60_000L) startFind(until, false, who)
                    }
                    "stop" -> if (findMode) stopFind(false)
                }
            }
            "cmd/act" -> {
                val cid = j.optString("cid")
                if (cid in seenCmds) return
                rememberCmd(cid)
                if (now - j.optLong("ts") > 120_000) return
                val who = j.optString("name", "Drugi telefon")
                when (j.optString("cmd")) {
                    "ring" -> {
                        Alarm.ring(this)
                        showAlert("$who uruchomił(a) dzwonek", "Dotknij „Wycisz”, aby zatrzymać.", silenceAction = true)
                    }
                    "ringstop" -> { Alarm.stop(this); cancelAlert() }
                    "torch" -> Alarm.torch(this)
                }
            }
        }
    }

    private fun rememberCmd(cid: String) {
        seenCmds.addLast(cid)
        while (seenCmds.size > 20) seenCmds.removeFirst()
        prefs.lastCmdId = seenCmds.joinToString(",")
    }

    // ---------------- Powiadomienia ----------------

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

    private fun pi(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, TrackerService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(): Notification {
        val b = NotificationCompat.Builder(this, LokApp.CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setContentIntent(openApp())
            .setOnlyAlertOnce(true)
        if (findMode) {
            b.setContentTitle("TRYB SZUKANIA do ${hhmm(findUntil)}")
                .setContentText("GPS, Bluetooth i Wi-Fi aktywne – wyłączy się samo")
                .addAction(0, "Zakończ szukanie", pi(ACTION_FIND_STOP, 1))
        } else {
            val m = prefs.intervalSec / 60.0
            val every = if (m >= 1) "${m.toInt()} min" else "${prefs.intervalSec} s"
            b.setContentTitle("Udostępniam lokalizację")
                .setContentText("Tryb oszczędny – co $every")
                .addAction(0, "Wyłącz", pi(ACTION_STOP, 2))
        }
        return b.build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun showAlert(title: String, text: String, silenceAction: Boolean = false) {
        val b = NotificationCompat.Builder(this, LokApp.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
        if (silenceAction) b.addAction(0, "Wycisz", pi(ACTION_SILENCE, 3))
        try {
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, b.build())
        } catch (_: Exception) {}
    }

    private fun cancelAlert() = getSystemService(NotificationManager::class.java).cancel(ALERT_ID)

    companion object {
        private const val NOTIF_ID = 1
        private const val ALERT_ID = 2
        private const val LIVE_MS = 3000L

        const val ACTION_STOP = "stop"
        const val ACTION_RESTART = "restart"
        const val ACTION_FIND = "find"
        const val ACTION_FIND_STOP = "find_stop"
        const val ACTION_RING = "ring"
        const val ACTION_RING_STOP_REMOTE = "ring_stop_remote"
        const val ACTION_TORCH = "torch"
        const val ACTION_SILENCE = "silence"
        const val ACTION_PUBLISH_NOW = "publish"

        fun send(ctx: Context, action: String? = null) {
            val i = Intent(ctx, TrackerService::class.java)
            if (action != null) i.action = action
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}
