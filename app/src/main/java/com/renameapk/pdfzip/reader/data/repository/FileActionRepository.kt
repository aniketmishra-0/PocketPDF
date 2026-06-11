package com.renameapk.pdfzip.reader.data.repository

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileActionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recentPdfRepository: RecentPdfRepository,
) {
    fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    fun shareIntent(uri: Uri, displayName: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, displayName, uri)
        }

    suspend fun rename(uri: Uri, displayName: String): Uri? {
        val cleanName = displayName.trim().ifBlank { return null }
        val renamed = when (uri.scheme) {
            "content" -> runCatching {
                DocumentsContract.renameDocument(context.contentResolver, uri, cleanName)
            }.getOrNull()
            "file" -> renameFileUri(uri, cleanName)
            else -> null
        } ?: return null
        recentPdfRepository.rename(uri, renamed, cleanName)
        return renamed
    }

    suspend fun delete(uri: Uri): Boolean {
        val deleted = when (uri.scheme) {
            "content" -> runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            }.getOrDefault(false)
            "file" -> uri.path?.let { File(it).delete() } ?: false
            else -> false
        }
        if (deleted) {
            recentPdfRepository.remove(uri)
        }
        return deleted
    }

    fun fileProviderUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun renameFileUri(uri: Uri, displayName: String): Uri? {
        val file = uri.path?.let(::File) ?: return null
        val target = File(file.parentFile, displayName)
        return if (file.renameTo(target)) Uri.fromFile(target) else null
    }
}

