package com.kimpig.rididecryptor.core

object PreferenceXmlParser {
    private val stringEntry = Regex(
        """<string\s+name\s*=\s*[\"']([^\"']+)[\"']\s*>(.*?)</string>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    fun strings(xml: String): Map<String, String> = buildMap {
        stringEntry.findAll(xml).forEach { match ->
            put(match.groupValues[1], decodeXml(match.groupValues[2].trim()))
        }
    }

    fun deviceId(xml: String): String? {
        val values = strings(xml)
        return sequenceOf(values["device_id"], values["uuid"])
            .filterNotNull()
            .map(String::trim)
            .firstOrNull { it.length >= 18 && it.none(Char::isWhitespace) }
    }

    fun accountId(xml: String): String? = strings(xml)["user_id_v2"]
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun decodeXml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
