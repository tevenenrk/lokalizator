package pl.lokalizator

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** Głośny dzwonek + wibracje + miganie latarką – żeby usłyszeć/zobaczyć zgubiony telefon. */
object Alarm {
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume = -1

    fun ring(ctx: Context, seconds: Int = 30) {
        stop(ctx)
        val am = ctx.getSystemService(AudioManager::class.java)
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
        try {
            ctx.getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
            )
        } catch (_: Exception) {}
        main.postDelayed({ stop(ctx) }, seconds * 1000L)
    }

    fun stop(ctx: Context) {
        main.removeCallbacksAndMessages(null)
        player?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        player = null
        try { ctx.getSystemService(Vibrator::class.java)?.cancel() } catch (_: Exception) {}
        if (savedVolume >= 0) {
            try {
                ctx.getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: Exception) {}
            savedVolume = -1
        }
    }

    fun torch(ctx: Context, blinks: Int = 15) {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return
        val id = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) { null } ?: return
        for (i in 0 until blinks * 2) {
            main.postDelayed({
                try { cm.setTorchMode(id, i % 2 == 0) } catch (_: Exception) {}
            }, i * 350L)
        }
        main.postDelayed({ try { cm.setTorchMode(id, false) } catch (_: Exception) {} }, blinks * 700L + 100)
    }
}
