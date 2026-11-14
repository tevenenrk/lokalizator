package pl.lokalizator

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.update
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID

/**
 * Łącze między telefonami przez serwery MQTT (lekki protokół "publikuj/subskrybuj",
 * stworzony do urządzeń na baterii).
 *
 * Dla niezawodności telefon łączy się RÓWNOLEGLE z dwoma darmowymi serwerami i wysyła na oba;
 * duplikaty są odrzucane. Dla każdego serwera najpierw TLS (port 8883), a gdy sieć go blokuje –
 * WebSocket TLS. Wystarczy, że oba telefony mają wspólny choć jeden działający serwer.
 *
 * Tematy (pod wspólnym, tajnym korzeniem wyliczonym z kodu):
 *   <root>/<id>/state     – ostatnia pozycja (retained = serwer pamięta ostatnią)
 *   <root>/<id>/live      – dane trybu szukania co kilka sekund
 *   <root>/<id>/cmd/mode  – start/stop trybu szukania (retained)
 *   <root>/<id>/cmd/act   – jednorazowe akcje: dzwonek, latarka
 *   <root>/<id>/ping      – test łączności (telefon odbiera własną wiadomość)
 */
class MqttLink(
    private val prefs: Prefs,
    private val crypto: Crypto,
    private val onMessage: (senderId: String, kind: String, json: JSONObject) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val root = crypto.topicRoot
    private val myId = prefs.deviceId
    private val conns: List<Conn> = prefs.servers().map { Conn(it.first, it.second) }
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 400
    }
    private var stopped = false
    private val pingSent = HashMap<String, Long>()

    fun start() {
        stopped = false
        AppState.servers.value = conns.associate { it.label to "łączenie…" }
        AppState.diag.value = Diag()
        conns.forEach { it.connect() }
        refreshSummary()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        conns.forEach { it.close() }
        AppState.connection.value = "rozłączony"
        AppState.servers.value = emptyMap()
    }

    fun publish(kind: String, json: JSONObject, retained: Boolean) {
        val payload = crypto.encrypt(json.toString())
        var ok = false
        conns.forEach { if (it.publish(kind, payload, retained)) ok = true }
        if (ok) AppState.diag.update { it.copy(sent = it.sent + 1, lastTx = System.currentTimeMillis()) }
    }

    /** Wysyła wiadomość testową do siebie przez każdy serwer i mierzy czas powrotu. */
    fun ping() {
        val cid = UUID.randomUUID().toString()
        synchronized(pingSent) { pingSent[cid] = System.currentTimeMillis() }
        AppState.diag.update { it.copy(ping = conns.associate { c -> c.label to if (c.connected) "czekam…" else "brak połączenia" }) }
        val payload = crypto.encrypt(JSONObject().put("cid", cid).put("ts", System.currentTimeMillis()).toString())
        conns.forEach { it.publish("ping", payload, false) }
        main.postDelayed({
            AppState.diag.update { d ->
                d.copy(ping = d.ping.mapValues { (_, v) -> if (v == "czekam…") "BRAK ODPOWIEDZI (10 s)" else v })
            }
        }, 10_000)
    }

    private fun refreshSummary() {
        if (stopped) return
        val n = conns.count { it.connected }
        AppState.connection.value = when {
            n == conns.size -> "połączony ($n/${conns.size} serw.)"
            n > 0 -> "połączony ($n/${conns.size} serw.)"
            else -> "brak połączenia z serwerem – ponawiam"
        }
    }

    private fun setStatus(label: String, s: String) {
        AppState.servers.update { it + (label to s) }
        refreshSummary()
    }

    private fun handle(label: String, topic: String, message: MqttMessage) {
        if (!topic.startsWith("$root/")) return
        val parts = topic.removePrefix("$root/").split("/")
        if (parts.size < 2) return
        val sender = parts[0]
        val kind = parts.drop(1).joinToString("/")
        if (message.payload.isEmpty()) return
        val txt = crypto.decrypt(message.payload) ?: run {
            AppState.diag.update { it.copy(badDecrypt = it.badDecrypt + 1) }
            return
        }
        val json = try { JSONObject(txt) } catch (e: Exception) { return }

        if (sender == myId) {
            if (kind == "ping") {
                val t0 = synchronized(pingSent) { pingSent[json.optString("cid")] } ?: return
                val ms = System.currentTimeMillis() - t0
                AppState.diag.update { it.copy(ping = it.ping + (label to "działa ($ms ms)")) }
            }
            return
        }
        if (kind == "ping") return
        // ta sama wiadomość przychodzi z dwóch serwerów – bierzemy pierwszą
        val key = "$sender|$kind|${json.optLong("ts")}|${json.optString("cid")}"
        synchronized(seen) {
            if (seen.containsKey(key)) return
            seen[key] = true
        }
        AppState.diag.update { it.copy(received = it.received + 1, lastRx = System.currentTimeMillis()) }
        main.post { onMessage(sender, kind, json) }
    }

    private inner class Conn(val label: String, val uris: List<String>) {
        @Volatile var client: MqttAsyncClient? = null
        val connected: Boolean get() = try { client?.isConnected == true } catch (_: Exception) { false }
        private val pending = LinkedHashMap<String, ByteArray>()
        private var failures = 0

        fun connect() {
            if (stopped) return
            val c = try {
                MqttAsyncClient(uris.first(), "lok-$myId-${(1000..9999).random()}", MemoryPersistence())
            } catch (e: Exception) {
                setStatus(label, "zły adres: ${e.message}")
                return
            }
            client = c
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    failures = 0
                    val how = if (serverURI?.startsWith("ws") == true) "WebSocket" else "TLS"
                    setStatus(label, "połączony ($how)")
                    try {
                        c.subscribe("$root/#", 1)
                    } catch (e: Exception) {
                        setStatus(label, "błąd subskrypcji: ${e.message}")
                    }
                    flush()
                }

                override fun connectionLost(cause: Throwable?) {
                    setStatus(label, "utracono połączenie – ponawiam (${cause?.message ?: "?"})")
                }

                override fun messageArrived(topic: String, message: MqttMessage) = handle(label, topic, message)
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            val opts = MqttConnectOptions().apply {
                serverURIs = uris.toTypedArray() // Paho próbuje kolejno: TLS, potem WebSocket
                isAutomaticReconnect = true
                isCleanSession = true
                keepAliveInterval = 240 // rzadkie pingi = mniej wybudzeń radia
                connectionTimeout = 15
                maxReconnectDelay = 60_000
                if (prefs.user.isNotEmpty() && !prefs.usesDefaultServers) {
                    userName = prefs.user
                    password = prefs.pass.toCharArray()
                }
            }
            try {
                c.connect(opts, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {}
                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        Log.w(TAG, "connect $label failed", exception)
                        failures++
                        val why = exception?.cause?.message ?: exception?.message ?: "nieznany błąd"
                        setStatus(label, "nie można połączyć: $why")
                        try { c.close(true) } catch (_: Exception) {}
                        // Automatyczne ponawianie Paho działa dopiero po pierwszym udanym połączeniu.
                        val delay = if (failures < 3) 10_000L else 60_000L
                        main.postDelayed({ if (client === c) connect() }, delay)
                    }
                })
            } catch (e: Exception) {
                setStatus(label, "błąd: ${e.message}")
                main.postDelayed({ if (client === c) connect() }, 30_000)
            }
        }

        fun publish(kind: String, payload: ByteArray, retained: Boolean): Boolean {
            val c = client
            if (c == null || !connected) {
                if (retained) synchronized(pending) { pending[kind] = payload }
                return false
            }
            return try {
                c.publish("$root/$myId/$kind", MqttMessage(payload).apply { qos = 1; isRetained = retained })
                true
            } catch (e: Exception) {
                Log.w(TAG, "publish $label", e)
                if (retained) synchronized(pending) { pending[kind] = payload }
                false
            }
        }

        fun flush() {
            val items = synchronized(pending) { pending.toMap().also { pending.clear() } }
            items.forEach { (k, v) -> publish(k, v, true) }
        }

        fun close() {
            val c = client
            client = null
            Thread {
                try { c?.disconnect(2000)?.waitForCompletion(3000) } catch (_: Exception) {}
                try { c?.close(true) } catch (_: Exception) {}
            }.start()
        }
    }

    companion object {
        const val TAG = "MqttLink"
    }
}
