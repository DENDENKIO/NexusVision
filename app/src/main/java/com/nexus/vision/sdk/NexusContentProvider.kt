package com.nexus.vision.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import android.database.MatrixCursor
import com.nexus.vision.cache.InferenceCacheEntry
import com.nexus.vision.cache.InferenceCacheEntry_
import io.objectbox.BoxStore

class NexusContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.nexus.vision.provider"
        private const val CACHE = 1
        private const val CACHE_ID = 2
        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, "cache", CACHE)
            addURI(AUTHORITY, "cache/#", CACHE_ID)
        }
    }

    private var boxStore: BoxStore? = null

    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        boxStore = try {
            val clazz = Class.forName("com.nexus.vision.cache.MyObjectBox")
            val builder = clazz.getMethod("builder").invoke(null, app)
            builder.javaClass.getMethod("build").invoke(builder) as BoxStore
        } catch (_: Exception) {
            null
        }
        return boxStore != null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val store = boxStore ?: return null
        val box = store.boxFor(InferenceCacheEntry::class.java)
        val list = when (matcher.match(uri)) {
            CACHE -> box.query().orderDesc(InferenceCacheEntry_.lastAccessedAt).build().find()
            CACHE_ID -> {
                val id = uri.lastPathSegment?.toLongOrNull() ?: return null
                listOfNotNull(box.get(id))
            }
            else -> return null
        }
        val cursor = MatrixCursor(arrayOf("id", "queryText", "responseText", "lastAccessedAt"))
        list.forEach { e ->
            cursor.addRow(arrayOf(e.id, e.queryText, e.responseText, e.lastAccessedAt))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CACHE -> "vnd.android.cursor.dir/vnd.$AUTHORITY.cache"
        CACHE_ID -> "vnd.android.cursor.item/vnd.$AUTHORITY.cache"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
