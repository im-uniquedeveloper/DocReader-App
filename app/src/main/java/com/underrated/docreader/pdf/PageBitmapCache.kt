package com.underrated.docreader.pdf

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * A rendered page (or patch) plus its Compose wrapper, so we do not re-wrap on every frame.
 *
 * [contentWidthPx] is the full-page width this was rendered for. Keeping it lets the
 * canvas draw a stale bitmap correctly scaled while a fresh one is still rendering,
 * which is what makes zooming feel instant instead of blank.
 */
class RenderedPage(
    val bitmap: Bitmap,
    val contentWidthPx: Int,
    val patchLeft: Int = 0,
    val patchTop: Int = 0,
) {
    val image: ImageBitmap = bitmap.asImageBitmap()
    val widthPx: Int get() = bitmap.width
    val heightPx: Int get() = bitmap.height
    val sizeBytes: Int get() = bitmap.allocationByteCount
}

/**
 * Low-resolution whole-page previews, kept in an LRU bounded by a fraction of the
 * app's heap.
 *
 * Deliberately NOT recycling evicted bitmaps: a draw pass may still hold a reference,
 * and recycling under it crashes. Letting the GC take them is the safe trade.
 */
class PageBitmapCache(maxBytes: Int = defaultBudget()) {

    private val cache = object : LruCache<String, RenderedPage>(maxBytes) {
        override fun sizeOf(key: String, value: RenderedPage): Int = value.sizeBytes
    }

    operator fun get(key: String): RenderedPage? = cache.get(key)

    fun put(key: String, page: RenderedPage) {
        if (page.sizeBytes <= cache.maxSize()) cache.put(key, page)
    }

    fun clear() = cache.evictAll()

    /** Drop roughly half the cache; used when the system reports memory pressure. */
    fun trim() = cache.trimToSize(cache.maxSize() / 2)

    companion object {
        fun key(pageIndex: Int, widthPx: Int) = "$pageIndex@$widthPx"

        private fun defaultBudget(): Int {
            val heap = Runtime.getRuntime().maxMemory()
            // A quarter of the heap, clamped so tiny devices keep headroom and large
            // ones do not hoard. Previews are small; patches live outside this cache.
            val quarter = (heap / 4).coerceIn(8L * 1024 * 1024, 96L * 1024 * 1024)
            return quarter.toInt()
        }
    }
}
