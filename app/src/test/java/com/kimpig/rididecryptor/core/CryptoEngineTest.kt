package com.kimpig.rididecryptor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoEngineTest {
    private val engine = CryptoEngine()

    @Test
    fun extractsOfficialContentKeyEnvelope() {
        val deviceId = "12345678-1234-1234-1234-123456789abc"
        val key = "0123456789abcdef".toByteArray()
        val plain = deviceId.toByteArray() + ByteArray(32) + key + ByteArray(16)
        assertArrayEquals(key, engine.extractContentKey(plain, deviceId))
    }

    @Test
    fun decryptsEcbPayload() {
        val key = "0123456789abcdef".toByteArray()
        val plain = "PK\u0003\u0004test-data".toByteArray().copyOf(16)
        val encrypted = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(plain)
        }
        assertArrayEquals(plain, engine.decryptEcb(encrypted, key))
    }

    @Test
    fun decryptsCbcWithPkcsPadding() {
        val key = "0123456789abcdef".toByteArray()
        val iv = ByteArray(16) { it.toByte() }
        val plain = "%PDF-local-test".toByteArray()
        val padded = plain + ByteArray(16 - plain.size % 16) { (16 - plain.size % 16).toByte() }
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(padded)
        }
        assertArrayEquals(plain, engine.decryptCbc(iv + encrypted, key))
    }

    @Test
    fun comicKeyMatchesLegacyDerivation() {
        val id = "2036000000"
        val source = "stream${id.dropLast(6)}$id".toByteArray()
        val expected = MessageDigest.getInstance("SHA-1").digest(source)
            .joinToString("") { "%02x".format(it) }
            .take(16)
        assertEquals(expected, engine.comicKey(id).toString(Charsets.UTF_8))
    }

    @Test
    fun sanitizesTitleAndKeepsBookIdInOutputName() {
        assertEquals(
            "A_B_C (2036000000)",
            engine.safeOutputBaseName(" A/B:C. ", "2036000000")
        )
    }

    @Test
    fun comicNamesUseCoverZeroAndShiftPagesByOne() {
        assertEquals("001.jpg", engine.canonicalComicName(1, 79, "jpg"))
        assertEquals("002.jpg", engine.canonicalComicName(2, 79, "jpg"))
        assertEquals("014.jpg", engine.canonicalComicName(14, 79, "jpg"))
        assertEquals("081.jpg", engine.canonicalComicName(81, 79, "jpg"))
        assertEquals("1001.png", engine.canonicalComicName(1001, 999, "png"))
    }

    @Test
    fun comicOutputNamesUseFriendlyQualityLabels() {
        assertEquals("Title (2036000000) [Original].zip", engine.comicOutputName("Title", "2036000000", "original"))
        assertEquals("Title (2036000000) [Standard].zip", engine.comicOutputName("Title", "2036000000", "recommended"))
        assertEquals("Title (2036000000).zip", engine.comicOutputName("Title", "2036000000", null))
    }
}
