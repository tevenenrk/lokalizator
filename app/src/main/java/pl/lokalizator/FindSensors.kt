package pl.lokalizator

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

/**
 * Czujniki używane TYLKO w trybie szukania (kilka minut):
 *  - Bluetooth LE: telefon nadaje swój znacznik i nasłuchuje drugiego -> siła sygnału = odległość
 *  - Wi-Fi: lista okolicznych routerów (porównanie "odcisków" obu telefonów)
 *  - Barometr: różnica wysokości / pięter
 *  - Akcelerometr: czy druga osoba idzie, czy stoi
 */
class FindSensors(
    private val ctx: Context,
    private val bleGroup: ByteArray,
    myTag: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val myTagBytes = myTag.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private var running = false

    // ---------- Bluetooth LE ----------
    private val advCb = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "adv fail $errorCode")
            AppState.local.update { it.copy(bleError = "Nie można nadawać BT (kod $errorCode)") }
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onBle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onBle(it) }
        override fun onScanFailed(errorCode: Int) {
            AppState.local.update { it.copy(bleError = "Skanowanie BT nie działa (kod $errorCode)") }
        }
    }

    private fun onBle(r: ScanResult) {
        val sd = r.scanRecord?.getServiceData(BLE_UUID) ?: return
        if (sd.size < 8) return
        for (i in 0 until 4) if (sd[i] != bleGroup[i]) return
        val tag = Crypto.hex(sd.copyOfRange(4, 8))
        val now = System.currentTimeMillis()
        AppState.local.update { s ->
            val prev = s.ble[tag]
            // wygładzanie – RSSI bardzo skacze
            val v = if (prev == null || now - prev.ts > 10_000) r.rssi.toDouble()
            else prev.rssi * 0.7 + r.rssi * 0.3
            s.copy(ble = s.ble + (tag to BleSeen(v, r.rssi, now)), bleError = null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        val a = bt ?: run {
            AppState.local.update { it.copy(bleError = "Brak Bluetooth") }; return
        }
        if (!a.isEnabled) {
            AppState.local.update { it.copy(bleError = "Bluetooth wyłączony – włącz go, by mierzyć odległość") }
            return
        }
        try {
            a.bluetoothLeAdvertiser?.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .setTimeout(0)
                    .build(),
                AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .addServiceData(BLE_UUID, bleGroup + myTagBytes)
                    .build(),
                advCb
            )
        } catch (e: Exception) {
            Log.w(TAG, "adv", e)
        }
        try {
            val filter = ScanFilter.Builder()
                .setServiceData(BLE_UUID, bleGroup + ByteArray(4), byteArrayOf(-1, -1, -1, -1, 0, 0, 0, 0))
                .build()
            a.bluetoothLeScanner?.startScan(
                listOf(filter),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build(),
                scanCb
            )
        } catch (e: Exception) {
            Log.w(TAG, "scan", e)
            AppState.local.update { it.copy(bleError = "Brak uprawnień Bluetooth") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        try { bt?.bluetoothLeAdvertiser?.stopAdvertising(advCb) } catch (_: Exception) {}
        try { bt?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    // ---------- Wi-Fi ----------
    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = readWifi()
    }

    private val wifiTick = object : Runnable {
        override fun run() {
            if (!running) return
            @Suppress("DEPRECATION")
            try { wifi?.startScan() } catch (_: Exception) {}
            readWifi()
            // Android pozwala na ~4 skany / 2 min – i tak dostajemy też wyniki skanów systemowych
            main.postDelayed(this, 30_000)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readWifi() {
        val w = wifi ?: return
        val list = try { w.scanResults } catch (e: Exception) { null } ?: return
        val nowBoot = SystemClock.elapsedRealtime() * 1000
        val aps = list
            .filter { nowBoot - it.timestamp < 120_000_000L } // świeższe niż 2 min
            .sortedByDescending { it.level }
            .take(15)
            .map { WifiAp(it.BSSID ?: "", (it.SSID ?: "").take(32), it.level) }
        val connected = try {
            w.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        } catch (_: Exception) { null }
        AppState.local.update { it.copy(wifi = aps, wifiTs = System.currentTimeMillis(), wifiConnected = connected) }
    }

    // ---------- Barometr + ruch ----------
    private val mags = ArrayDeque<Float>()
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_PRESSURE -> {
                    val p = e.values[0]
                    AppState.local.update { s ->
                        s.copy(pressure = s.pressure?.let { it * 0.9f + p * 0.1f } ?: p)
                    }
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val (x, y, z) = e.values
                    mags.addLast(sqrt(x * x + y * y + z * z))
                    if (mags.size > 40) mags.removeFirst()
                    if (mags.size >= 20) {
                        val m = mags.average()
                        val sd = sqrt(mags.sumOf { (it - m) * (it - m) } / mags.size)
                        val moving = sd > 0.9
                        if (AppState.local.value.moving != moving) {
                            AppState.local.update { it.copy(moving = moving) }
                        }
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        if (running) return
        running = true
        AppState.local.update { it.copy(ble = emptyMap(), bleError = null) }
        startBle()
        try {
            ContextCompat.registerReceiver(
                ctx, wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Exception) {}
        main.post(wifiTick)
        sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        stopBle()
        main.removeCallbacks(wifiTick)
        try { ctx.unregisterReceiver(wifiReceiver) } catch (_: Exception) {}
        sm?.unregisterListener(sensorListener)
        AppState.local.update { it.copy(moving = null) }
    }

    companion object {
        const val TAG = "FindSensors"
        val BLE_UUID: ParcelUuid = ParcelUuid.fromString("6c6f6b61-6c69-7a61-746f-720000000001")

        /** Jednorazowy odczyt barometru (tryb normalny – prawie zero energii). */
        fun readPressureOnce(ctx: Context, cb: (Float) -> Unit) {
            val sm = ctx.getSystemService(SensorManager::class.java) ?: return
            val s = sm.getDefaultSensor(Sensor.TYPE_PRESSURE) ?: return
            val l = object : SensorEventListener {
                var n = 0
                override fun onSensorChanged(e: SensorEvent) {
                    if (++n >= 3) { // pierwsze odczyty bywają niestabilne
                        sm.unregisterListener(this)
                        cb(e.values[0])
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
            Handler(Looper.getMainLooper()).postDelayed({ sm.unregisterListener(l) }, 5000)
        }
    }
}
