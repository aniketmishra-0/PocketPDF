package com.renameapk.pdfzip

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

object LocalPdfStore {

    data class LocalPdfDocument(
        val uri: Uri,
        val file: File,
        val displayName: String,
        val sizeBytes: Long
    )

    @Throws(IOException::class)
    fun prepareForRead(
        context: Context,
        sourceUri: Uri,
        preferredDisplayName: String? = null,
        refreshExisting: Boolean = false
    ): LocalPdfDocument {
        val localFile = requireLocalFile(
            context = context,
            sourceUri = sourceUri,
            preferredDisplayName = preferredDisplayName,
            refreshExisting = refreshExisting
        )
        return LocalPdfDocument(
            uri = Uri.fromFile(localFile),
            file = localFile,
            displayName = presentableDisplayName(localFile.name),
            sizeBytes = localFile.length().coerceAtLeast(0L)
        )
    }

    @Throws(IOException::class)
    fun requireLocalFile(
        context: Context,
        sourceUri: Uri,
        preferredDisplayName: String? = null,
        refreshExisting: Boolean = false
    ): File {
        resolveFileFromUri(sourceUri)?.takeIf { it.exists() }?.let { return it }

        val displayName = sanitizePdfFileName(
            preferredDisplayName ?: queryDisplayName(context, sourceUri)
        )
        val targetDirectory = File(
            File(context.filesDir, LOCAL_DOCUMENTS_DIRECTORY),
            sha256(sourceUri.toString())
        )
        val targetFile = File(targetDirectory, displayName)
        if (!refreshExisting && targetFile.exists() && targetFile.length() > 0L) {
            return targetFile
        }

        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw IOException("Unable to create local PDF storage.")
        }

        targetDirectory.listFiles()
            ?.filterNot { it == targetFile }
            ?.forEach { staleFile ->
                staleFile.deleteRecursively()
            }

        val tempFile = File(targetDirectory, "$displayName.partial")
        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IOException("Unable to read the selected PDF.")

        if (targetFile.exists() && !targetFile.delete()) {
            tempFile.delete()
            throw IOException("Unable to refresh the local PDF copy.")
        }

        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }

        if (!targetFile.exists() || targetFile.length() <= 0L) {
            throw IOException("The local PDF copy is empty.")
        }
        return targetFile
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        resolveFileFromUri(uri)?.name?.takeIf { it.isNotBlank() }?.let { fileName ->
            return presentableDisplayName(fileName)
        }

        return presentableDisplayName(
            context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getString(0)
                } else {
                    null
                }
            }
            ?: uri.lastPathSegment?.substringAfterLast('/')
        )
    }

    fun queryFileSize(context: Context, uri: Uri): Long? {
        resolveFileFromUri(uri)?.takeIf { it.exists() }?.let { file ->
            return file.length().coerceAtLeast(0L)
        }

        return context.contentResolver
            .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getLong(0)
                } else {
                    null
                }
            }
    }

    @Throws(IOException::class)
    fun createShareUri(context: Context, uri: Uri): Uri {
        val localFile = requireLocalFile(context, uri)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            localFile
        )
    }

    private fun resolveFileFromUri(uri: Uri): File? {
        if (uri.scheme != "file") {
            return null
        }
        val path = uri.path ?: return null
        return File(path)
    }

    private fun sanitizePdfFileName(rawName: String?): String {
        val normalized = rawName
            ?.trim()
            ?.ifBlank { null }
            ?.replace(INVALID_FILE_NAME_CHARS, "_")
            ?: DEFAULT_FILE_NAME
        return if (normalized.endsWith(".pdf", ignoreCase = true)) {
            normalized
        } else {
            "$normalized.pdf"
        }
    }

    fun presentableDisplayName(rawName: String?): String {
        val trimmed = rawName
            ?.substringAfterLast('/')
            ?.trim()
            ?.ifBlank { null }
            ?: DEFAULT_FILE_NAME
        val withoutHashPrefix = trimmed.replace(HEX_PREFIX_PATTERN, "")
        return withoutHashPrefix.ifBlank { trimmed }
    }

    private fun sha256(value: String): String {
        val hashBytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(hashBytes.size * 2) {
            hashBytes.forEach { byte ->
                append("%02x".format(byte))
            }
        }
    }

    private val INVALID_FILE_NAME_CHARS = Regex("[^A-Za-z0-9._-]+")
    private val HEX_PREFIX_PATTERN = Regex("^[0-9a-fA-F]{16,64}[_-]+")
    private const val LOCAL_DOCUMENTS_DIRECTORY = "local_pdfs"
    private const val DEFAULT_FILE_NAME = "document.pdf"
}
