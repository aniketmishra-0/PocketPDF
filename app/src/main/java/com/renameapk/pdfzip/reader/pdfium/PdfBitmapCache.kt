package com.renameapk.pdfzip.reader.pdfium

import android.graphics.Bitmap
import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class PdfBitmapCache @Inject constructor() {
    private val maxTileCacheKb = (Runtime.getRuntime().maxMemory() / 1024 / 5)
        .coerceIn(24 * 1024L, 128 * 1024L)
        .toInt()
    private val maxThumbnailCacheKb = (Runtime.getRuntime().maxMemory() / 1024 / 18)
        .coerceIn(6 * 1024L, 32 * 1024L)
        .toInt()

    private val tileCache = object : LruCache<TileKey, Bitmap>(maxTileCacheKb) {
        override fun sizeOf(key: TileKey, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    private val thumbnailCache = object : LruCache<String, Bitmap>(maxThumbnailCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    @Synchronized
    fun getTile(key: TileKey): Bitmap? = tileCache.get(key)

    @Synchronized
    fun putTile(key: TileKey, bitmap: Bitmap) {
        tileCache.put(key, bitmap)
    }

    @Synchronized
    fun getThumbnail(documentKey: String, pageIndex: Int): Bitmap? =
        thumbnailCache.get(thumbnailKey(documentKey, pageIndex))

    @Synchronized
    fun putThumbnail(documentKey: String, pageIndex: Int, bitmap: Bitmap) {
        thumbnailCache.put(thumbnailKey(documentKey, pageIndex), bitmap)
    }

    @Synchronized
    fun trimAggressively() {
        tileCache.trimToSize((maxTileCacheKb * 0.35f).roundToInt())
        thumbnailCache.trimToSize((maxThumbnailCacheKb * 0.5f).roundToInt())
    }

    @Synchronized
    fun clear() {
        tileCache.evictAll()
        thumbnailCache.evictAll()
    }

    private fun thumbnailKey(documentKey: String, pageIndex: Int): String = "$documentKey:$pageIndex"
}

