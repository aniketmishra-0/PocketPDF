package com.renameapk.pdfzip

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object ReaderLibraryStore {

    data class RecentDocument(
        val uriString: String,
        val displayName: String,
        val pageCount: Int,
        val lastPageIndex: Int,
        val lastOpenedAt: Long
    ) {
        val currentPageNumber: Int
            get() = (lastPageIndex + 1).coerceAtLeast(1)
    }

    enum class ReaderMode {
        SCROLL,
        SWIPE
    }

    enum class ReaderFitMode {
        FIT_PAGE,
        FIT_WIDTH
    }

    fun getRecentDocuments(context: Context): List<RecentDocument> {
        val rawJson = prefs(context).getString(KEY_RECENT_DOCUMENTS, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(rawJson)
            buildList {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val uriString = item.optString(KEY_URI_STRING)
                    val displayName = item.optString(KEY_DISPLAY_NAME)
                    if (uriString.isBlank() || displayName.isBlank()) {
                        continue
                    }
                    add(
                        RecentDocument(
                            uriString = uriString,
                            displayName = LocalPdfStore.presentableDisplayName(displayName),
                            pageCount = item.optInt(KEY_PAGE_COUNT, 0).coerceAtLeast(0),
                            lastPageIndex = item.optInt(KEY_LAST_PAGE_INDEX, 0).coerceAtLeast(0),
                            lastOpenedAt = item.optLong(KEY_LAST_OPENED_AT, 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun updateRecentDocument(
        context: Context,
        uri: Uri,
        displayName: String,
        pageCount: Int,
        lastPageIndex: Int
    ) {
        val uriString = uri.toString()
        val updatedDocuments = getRecentDocuments(context)
            .filterNot { it.uriString == uriString }
            .toMutableList()
        updatedDocuments.add(
            0,
            RecentDocument(
                uriString = uriString,
                displayName = LocalPdfStore.presentableDisplayName(displayName),
                pageCount = pageCount.coerceAtLeast(0),
                lastPageIndex = lastPageIndex.coerceAtLeast(0),
                lastOpenedAt = System.currentTimeMillis()
            )
        )
        persistRecentDocuments(context, updatedDocuments.take(MAX_RECENT_DOCUMENTS))
    }

    fun getLastPage(context: Context, uri: Uri): Int? {
        return getRecentDocuments(context)
            .firstOrNull { it.uriString == uri.toString() }
            ?.lastPageIndex
    }

    fun getStarredDocuments(context: Context): Set<String> {
        return prefs(context)
            .getStringSet(KEY_STARRED_DOCUMENTS, emptySet())
            .orEmpty()
    }

    fun isDocumentStarred(context: Context, uriString: String): Boolean {
        return uriString in getStarredDocuments(context)
    }

    fun toggleDocumentStar(context: Context, uriString: String): Set<String> {
        val starredDocuments = getStarredDocuments(context).toMutableSet()
        if (!starredDocuments.add(uriString)) {
            starredDocuments.remove(uriString)
        }
        prefs(context).edit().putStringSet(KEY_STARRED_DOCUMENTS, starredDocuments).apply()
        return starredDocuments
    }

    fun getBookmarks(context: Context, uri: Uri): Set<Int> {
        val rawValues = prefs(context)
            .getStringSet(bookmarkKey(uri.toString()), emptySet())
            .orEmpty()
        return rawValues.mapNotNull { value ->
            value.toIntOrNull()?.takeIf { it >= 0 }
        }.toSortedSet()
    }

    fun toggleBookmark(context: Context, uri: Uri, pageIndex: Int): Set<Int> {
        val bookmarkPages = getBookmarks(context, uri).toMutableSet()
        if (!bookmarkPages.add(pageIndex)) {
            bookmarkPages.remove(pageIndex)
        }

        val editor = prefs(context).edit()
        if (bookmarkPages.isEmpty()) {
            editor.remove(bookmarkKey(uri.toString()))
        } else {
            editor.putStringSet(
                bookmarkKey(uri.toString()),
                bookmarkPages.map { it.toString() }.toSet()
            )
        }
        editor.apply()
        return bookmarkPages.toSortedSet()
    }

    fun getReaderMode(context: Context): ReaderMode {
        val savedMode = prefs(context).getString(KEY_READER_MODE, ReaderMode.SCROLL.name)
        return runCatching { ReaderMode.valueOf(savedMode ?: ReaderMode.SCROLL.name) }
            .getOrDefault(ReaderMode.SCROLL)
    }

    fun setReaderMode(context: Context, mode: ReaderMode) {
        prefs(context).edit().putString(KEY_READER_MODE, mode.name).apply()
    }

    fun getReaderFitMode(context: Context): ReaderFitMode {
        val savedMode = prefs(context).getString(KEY_READER_FIT_MODE, ReaderFitMode.FIT_PAGE.name)
        return runCatching { ReaderFitMode.valueOf(savedMode ?: ReaderFitMode.FIT_PAGE.name) }
            .getOrDefault(ReaderFitMode.FIT_PAGE)
    }

    fun setReaderFitMode(context: Context, mode: ReaderFitMode) {
        prefs(context).edit().putString(KEY_READER_FIT_MODE, mode.name).apply()
    }

    private fun persistRecentDocuments(context: Context, documents: List<RecentDocument>) {
        val jsonArray = JSONArray()
        documents.forEach { document ->
            jsonArray.put(
                JSONObject().apply {
                    put(KEY_URI_STRING, document.uriString)
                    put(KEY_DISPLAY_NAME, document.displayName)
                    put(KEY_PAGE_COUNT, document.pageCount)
                    put(KEY_LAST_PAGE_INDEX, document.lastPageIndex)
                    put(KEY_LAST_OPENED_AT, document.lastOpenedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY_RECENT_DOCUMENTS, jsonArray.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun bookmarkKey(uriString: String): String = "bookmark_${uriString.hashCode()}"

    private const val PREFS_NAME = "reader_library_store"
    private const val KEY_RECENT_DOCUMENTS = "recent_documents_v2"
    private const val KEY_READER_MODE = "reader_mode"
    private const val KEY_READER_FIT_MODE = "reader_fit_mode"
    private const val KEY_STARRED_DOCUMENTS = "starred_documents_v1"
    private const val KEY_URI_STRING = "uri"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_PAGE_COUNT = "page_count"
    private const val KEY_LAST_PAGE_INDEX = "last_page_index"
    private const val KEY_LAST_OPENED_AT = "last_opened_at"
    private const val MAX_RECENT_DOCUMENTS = 6
}
