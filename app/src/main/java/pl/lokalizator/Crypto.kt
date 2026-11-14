package pl.lokalizator

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wszystko, co idzie przez serwer, jest szyfrowane AES-256-GCM kluczem wyprowadzonym z kodu parowania.
 * Serwer widzi tylko losowo wyglądający temat i zaszyfrowane bajty.
 */
class Crypto(code: String) {
    private val norm = normalize(code)
    val topicRoot: String = "lokalizator/v1/" + hex(sha256("topic|$norm")).take(32)

    /** 4 bajty wspólne dla grupy – rozpoznawanie "swoich" telefonów przez Bluetooth. */
    val bleGroup: ByteArray = sha256("ble|$norm").copyOf(4)

    private val key: SecretKeySpec = run {
        val spec = PBEKeySpec(norm.toCharArray(), "lokalizator-salt-v1".toByteArray(), 20000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        SecretKeySpec(raw, "AES")
    }
    private val rnd = SecureRandom()

    fun encrypt(plain: String): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(data: ByteArray): String? = try {
        if (data.size < 29) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    companion object {
        fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

        /** 4-bajtowy skrót identyfikatora urządzenia, nadawany w reklamie BLE. */
        fun deviceTag(deviceId: String): String = hex(sha256("dev|$deviceId").copyOf(4))
    }
}
