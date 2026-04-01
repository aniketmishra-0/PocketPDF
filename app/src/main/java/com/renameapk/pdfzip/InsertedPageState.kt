package com.renameapk.pdfzip

import java.util.UUID

data class InsertedPageState(
    val id: String = UUID.randomUUID().toString(),
    val bytes: ByteArray,
    val sourceName: String = "",
    val position: Int = Int.MAX_VALUE
)
