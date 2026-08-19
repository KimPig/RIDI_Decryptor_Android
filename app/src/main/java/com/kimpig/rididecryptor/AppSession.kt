package com.kimpig.rididecryptor

import com.kimpig.rididecryptor.core.BookCandidate
import com.kimpig.rididecryptor.root.RootScanResult

object AppSession {
    var scanResult: RootScanResult? = null
    var detailBook: BookCandidate? = null
    val manualBooks = mutableListOf<BookCandidate>()
}
