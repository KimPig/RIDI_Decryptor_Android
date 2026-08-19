package com.kimpig.rididecryptor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CandidateDiscoveryTest {
    @Test
    fun groupsOfficialBookDirectory() {
        val root = "/data/user/0/com.initialcoms.ridi/files/books/2036000000"
        val books = CandidateDiscovery.fromRootPaths(
            listOf("$root/2036000000_raw.zip", "$root/2036000000.dat")
        )
        assertEquals(1, books.size)
        assertEquals("2036000000", books.single().bookId)
        assertNotNull(books.single().rawPackage)
    }
}
