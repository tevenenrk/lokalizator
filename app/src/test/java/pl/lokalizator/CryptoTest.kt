package pl.lokalizator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CryptoTest {
    @Test
    fun roundTripAndCodeNormalization() {
        val a = Crypto("abcde-fgh23")
        val b = Crypto(" ABCDEFGH23 ")
        assertEquals(a.topicRoot, b.topicRoot)
        val msg = """{"lat":52.1,"name":"Żółć"}"""
        assertEquals(msg, b.decrypt(a.encrypt(msg)))
    }

    @Test
    fun wrongCodeCannotRead() {
        val a = Crypto("AAAAA-BBBBB")
        val c = Crypto("AAAAA-BBBBC")
        assertNotEquals(a.topicRoot, c.topicRoot)
        assertNull(c.decrypt(a.encrypt("tajne")))
    }

    @Test
    fun deviceTagIs4Bytes() {
        assertEquals(8, Crypto.deviceTag("abc123").length)
    }
}
