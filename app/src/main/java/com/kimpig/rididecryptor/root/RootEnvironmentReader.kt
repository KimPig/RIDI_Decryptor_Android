package com.kimpig.rididecryptor.root

class RootEnvironmentReader(private val shell: RootShell = RootShell()) {
    fun readSuVersion(): String = runCatching {
        shell.text("su -v 2>/dev/null || su --version 2>/dev/null", 20).trim()
    }.getOrDefault("").ifBlank { "Unavailable" }
}
