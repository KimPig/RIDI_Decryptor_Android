package com.kimpig.rididecryptor

import android.content.Context
import com.kimpig.rididecryptor.core.BookCandidate
import com.kimpig.rididecryptor.root.RootScanResult
import com.kimpig.rididecryptor.storage.ScanSessionStore

object AppSession {
    private var initialized = false
    var scanResult: RootScanResult? = null
    var detailBook: BookCandidate? = null
    val manualBooks = mutableListOf<BookCandidate>()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        ScanSessionStore.clearAtProcessStart(context.applicationContext)
        initialized = true
    }
}
