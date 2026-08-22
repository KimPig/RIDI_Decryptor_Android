package com.kimpig.rididecryptor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class EpubPrivacyProcessorTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun removesKnownMarkersNormalizesTimesAndProducesReproducibleEpubs() {
        val sourceOne = temporary.newFile("account-one.epub")
        val sourceTwo = temporary.newFile("account-two.epub")
        createEpub(sourceOne, 1_600_000_000_000L, 'A')
        createEpub(sourceTwo, 1_700_000_000_000L, 'B')
        val outputOne = File(temporary.root, "normalized-one.epub")
        val outputTwo = File(temporary.root, "normalized-two.epub")

        val normalizedTimestamp = EpubIntegrityValidator.normalizedTimestampMillis()
        val first = EpubPrivacyProcessor.process(
            sourceOne,
            outputOne,
            normalizedTimestampMillis = normalizedTimestamp
        )
        val second = EpubPrivacyProcessor.process(
            sourceTwo,
            outputTwo,
            normalizedTimestampMillis = normalizedTimestamp
        )

        assertTrue(first.warnings.joinToString(), first.success)
        assertTrue(second.warnings.joinToString(), second.success)
        assertEquals(2, first.modifiedEntries.size)
        assertEquals(2, second.modifiedEntries.size)
        assertArrayEquals(sha256(outputOne), sha256(outputTwo))

        listOf(outputOne, outputTwo).forEach { output ->
            val integrity = EpubIntegrityValidator.validate(output)
            assertTrue(integrity.summary, integrity.isValid)
            assertTrue(integrity.warnings.joinToString(), integrity.warnings.isEmpty())
            val scan = EpubPrivacyProcessor.scan(output)
            assertTrue(scan.failureReason, scan.completed)
            assertFalse(scan.hasKnownMarkers)
            ZipFile(output).use { zip ->
                val entries = zip.entries().asSequence().toList()
                assertEquals("mimetype", entries.first().name)
                assertEquals(ZipEntry.STORED, entries.first().method)
                assertTrue(entries.all { EpubIntegrityValidator.isNormalizedTimestamp(it.time) })
            }
            val attributes = ZipCentralDirectory.externalAttributes(output)
            assertTrue(attributes.filterKeys { it.endsWith('/') }.values.all { it == 16L })
        }
    }

    @Test
    fun removesKnownMarkersWhilePreservingSourceEntryTimesByDefault() {
        val source = temporary.newFile("source-times.epub")
        val sourceTimestamp = 1_600_000_000_000L
        createEpub(source, sourceTimestamp, 'A')
        val output = File(temporary.root, "preserved-times.epub")

        val result = EpubPrivacyProcessor.process(source, output)

        assertTrue(result.warnings.joinToString(), result.success)
        ZipFile(source).use { sourceZip ->
            ZipFile(output).use { outputZip ->
                sourceZip.entries().asSequence().forEach { sourceEntry ->
                    assertEquals(sourceEntry.time, outputZip.getEntry(sourceEntry.name).time)
                }
            }
        }
    }

    @Test
    fun normalizingDirectoryAttributesChangesNeitherSizeNorEntryContent() {
        val archive = temporary.newFile("directory-attributes.epub")
        createEpub(archive, 1_700_000_000_000L, 'A')
        val sizeBefore = archive.length()
        val hashesBefore = entryHashes(archive)

        assertEquals(2, ZipCentralDirectory.normalizeDirectoryAttributes(archive))

        assertEquals(sizeBefore, archive.length())
        assertEquals(hashesBefore, entryHashes(archive))
        val attributes = ZipCentralDirectory.externalAttributes(archive)
        assertEquals(16L, attributes.getValue("META-INF/"))
        assertEquals(16L, attributes.getValue("OEBPS/"))
    }

    private fun createEpub(file: File, timestamp: Long, accountCode: Char) {
        val invisible = buildString {
            repeat(66) { append(if ((it + accountCode.code) % 2 == 0) '\u2060' else '\u2063') }
        }
        val token = accountCode.toString().repeat(43) + "="
        val entries = linkedMapOf(
            "mimetype" to "application/epub+zip".toByteArray(Charsets.US_ASCII),
            "META-INF/" to ByteArray(0),
            "META-INF/container.xml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray(),
            "OEBPS/" to ByteArray(0),
            "OEBPS/content.opf" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata><meta name="book-token">$token</meta></metadata>
                  <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>
            """.trimIndent().toByteArray(),
            "OEBPS/chapter.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml"><body><p>Same text$invisible</p></body></html>
            """.trimIndent().toByteArray()
        )
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name).apply { time = timestamp }
                if (name == "mimetype") {
                    val crc = CRC32().apply { update(bytes) }
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = crc.value
                }
                output.putNextEntry(entry)
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun sha256(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun entryHashes(file: File): Map<String, String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry ->
            val hash = zip.getInputStream(entry).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            entry.name to hash
        }
    }
}
