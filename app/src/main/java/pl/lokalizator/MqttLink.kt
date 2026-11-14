package pl.lokalizator

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/**
 * Łącze między telefonami przez serwer MQTT (lekki protokół "publikuj/subskrybuj",
 * stworzony do urządzeń na baterii). Jedno stałe połączenie z rzadkim "keep-alive".
 *
 * Tematy (pod wspólnym, tajnym korzeniem wyliczonym z kodu):
 *   <root>/<id>/state     – ostatnia pozycja (retained = serwer pamięta ostatnią)
 *   <root>/<id>/live      – dane trybu szukania co kilka sekund
 *   <root>/<id>/cmd/mode  – start/stop trybu szukania (retained)
 *   <root>/<id>/cmd/act   – jednorazowe akcje: dzwonek, latarka
 */
class MqttLink(
    private val prefs: Prefs,
    private val crypto: Crypto,
    private val onMessage: (senderId: String, kind: String, json: JSONObject) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    private var stopped = false
    private val pendingRetained = LinkedHashMap<String, JSONObject>()
    private val root = crypto.topicRoot
    private val myId = prefs.deviceId

    val isConnected: Boolean get() = client?.isConnected == true

    fun start() {
        stopped = false
        connect()
    }

    private fun connect() {
        if (stopped) return
        AppState.connection.value = "łączenie…"
        val c = try {
            MqttAsyncClient(prefs.broker, "lok-$myId-${(1000..9999).random()}", MemoryPersistence())
        } catch (e: Exception) {
            AppState.connection.value = "zły adres serwera"
            return
        }
        client = c
        c.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                AppState.connection.value = "połączony"
                try {
                    c.subscribe("$root/#", 1)
                } catch (e: Exception) {
                    Log.w(TAG, "subscribe", e)
                }
                flushPending()
            }

            override fun connectionLost(cause: Throwable?) {
                AppState.connection.value = "ponowne łączenie…"
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                handle(topic, message)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            keepAliveInterval = 240 // rzadkie pingi = mniej wybudzeń radia
            connectionTimeout = 20
            maxReconnectDelay = 60_000
            if (prefs.user.isNotEmpty()) {
                userName = prefs.user
                password = prefs.pass.toCharArray()
            }
        }
        try {
            c.connect(opts, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "connect failed", exception)
                    AppState.connection.value = "brak połączenia z serwerem – ponawiam"
                    try { c.close(true) } catch (_: Exception) {}
                    // Automatyczne ponawianie Paho działa dopiero po pierwszym udanym połączeniu.
                    main.postDelayed({ if (client === c) connect() }, 30_000)
                }
            })
        } catch (e: Exception) {
            main.postDelayed({ connect() }, 30_000)
        }
    }

    private fun handle(topic: String, message: MqttMessage) {
        if (!topic.startsWith("$root/")) return
        val parts = topic.removePrefix("$root/").split("/")
        if (parts.size < 2) return
        val sender = parts[0]
        if (sender == myId) return
        val kind = parts.drop(1).joinToString("/")
        val txt = crypto.decrypt(message.payload) ?: return
        val json = try { JSONObject(txt) } catch (e: Exception) { return }
        main.post { onMessage(sender, kind, json) }
    }

    fun publish(kind: String, json: JSONObject, retained: Boolean) {
        val c = client
        if (c == null || !c.isConnected) {
            if (retained) synchronized(pendingRetained) { pendingRetained[kind] = json }
            return
        }
        try {
            val msg = MqttMessage(crypto.encrypt(json.toString())).apply {
                qos = 1
                isRetained = retained
            }
            c.publish("$root/$myId/$kind", msg)
        } catch (e: Exception) {
            Log.w(TAG, "publish", e)
            if (retained) synchronized(pendingRetained) { pendingRetained[kind] = json }
        }
    }

    private fun flushPending() {
        val items = synchronized(pendingRetained) {
            pendingRetained.toMap().also { pendingRetained.clear() }
        }
        items.forEach { (k, v) -> publish(k, v, true) }
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        val c = client
        client = null
        AppState.connection.value = "rozłączony"
        Thread {
            try {
                c?.disconnect(2000)?.waitForCompletion(3000)
            } catch (_: Exception) {}
            try { c?.close(true) } catch (_: Exception) {}
        }.start()
    }

    companion object {
        const val TAG = "MqttLink"
    }
}
