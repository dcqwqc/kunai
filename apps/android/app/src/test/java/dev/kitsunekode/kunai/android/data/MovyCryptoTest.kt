package dev.kitsunekode.kunai.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Byte-for-byte parity with the existing Bun/TypeScript provider fixture. */
class MovyCryptoTest {
    private val fixture: List<String>
        get() = checkNotNull(javaClass.classLoader?.getResource("movy-fixture.txt"))
            .readText().trim().lines()

    @Test fun capturedLiveSourceDecryptsIdentically() {
        val (id, seed, body) = fixture
        val plaintext = MovyCrypto.decrypt(body, seed, id.toInt())
        val hash = MessageDigest.getInstance("SHA-256").digest(
            plaintext.toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
        assertEquals("962b51c64bb9a6b5424873a381ed0389bc2c526b745439b210e4f0aae32aa152", hash)
        assertTrue(plaintext.startsWith("{\"sources\":[{"))
        assertTrue(plaintext.contains("https://"))
    }
    @Test fun wrongSeedCannotBeTreatedAsPlayable() {
        val (id, _, body) = fixture
        assertThrows(IllegalArgumentException::class.java) {
            MovyCrypto.decrypt(body, "0.wrongseed", id.toInt())
        }
    }
    @Test fun wrongTitleCannotBeTreatedAsPlayable() {
        val (_, seed, body) = fixture
        assertThrows(IllegalArgumentException::class.java) {
            MovyCrypto.decrypt(body, seed, 999)
        }
    }
}
