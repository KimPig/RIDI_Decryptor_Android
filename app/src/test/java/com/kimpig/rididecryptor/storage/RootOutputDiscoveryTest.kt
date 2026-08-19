package com.kimpig.rididecryptor.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootOutputDiscoveryTest {
    @Test
    fun acceptsSupportedOutputHeaders() {
        assertTrue(RootOutputDiscovery.isValidOutput("book.epub", byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertTrue(RootOutputDiscovery.isValidOutput("book.zip", byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertTrue(RootOutputDiscovery.isValidOutput("book.pdf", "%PDF".toByteArray()))
    }

    @Test
    fun rejectsMismatchedOrUnsupportedOutputs() {
        assertFalse(RootOutputDiscovery.isValidOutput("book.pdf", byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
        assertFalse(RootOutputDiscovery.isValidOutput("book.txt", "%PDF".toByteArray()))
        assertFalse(RootOutputDiscovery.isValidOutput("book.epub", byteArrayOf(0, 1, 2, 3)))
    }
}
