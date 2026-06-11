package com.renameapk.pdfzip.reader.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UriMetadataReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun displayName(uri: Uri): String =
        queryOpenable(uri, OpenableColumns.DISPLAY_NAME)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "document.pdf"

    fun sizeBytes(uri: Uri): Long? =
        queryOpenable(uri, OpenableColumns.SIZE)?.toLongOrNull()

    private fun queryOpenable(uri: Uri, column: String): String? {
        val cursor: Cursor = context.contentResolver.query(uri, arrayOf(column), null, null, null)
            ?: return null
        return cursor.use {
            val index = it.getColumnIndex(column)
            if (index >= 0 && it.moveToFirst()) it.getString(index) else null
        }
    }
}

