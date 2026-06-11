package com.renameapk.pdfzip.reader.pdfium

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfiumDocumentManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdfiumCore: PdfiumCoreKt,
) {
    suspend fun open(
        uri: Uri,
        displayName: String,
        sizeBytes: Long?,
        password: String? = null,
    ): PdfiumDocumentSession {
        val pfd = requireNotNull(context.contentResolver.openFileDescriptor(uri, "r")) {
            "Unable to open file descriptor for $uri"
        }
        val document = pdfiumCore.newDocument(pfd, password)
        val pageCount = document.getPageCount()
        return PdfiumDocumentSession(
            uri = uri,
            displayName = displayName,
            sizeBytes = sizeBytes,
            fileDescriptor = pfd,
            document = document,
            pageCount = pageCount,
        )
    }
}

