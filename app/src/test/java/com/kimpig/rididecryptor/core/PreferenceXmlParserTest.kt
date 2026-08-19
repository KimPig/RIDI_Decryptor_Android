package com.kimpig.rididecryptor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PreferenceXmlParserTest {
    @Test
    fun deviceIdPrefersCurrentKey() {
        val xml = """
            <map>
              <string name="uuid">legacy-value-1234567890</string>
              <string name="device_id">12345678-1234-1234-1234-123456789abc</string>
            </map>
        """.trimIndent()
        assertEquals("12345678-1234-1234-1234-123456789abc", PreferenceXmlParser.deviceId(xml))
    }
}
