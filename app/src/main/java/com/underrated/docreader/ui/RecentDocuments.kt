package com.underrated.docreader.ui

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(val uri: String, val name: String, val opened: Long)
class RecentDocuments(context: Context) {
    private val prefs = context.getSharedPreferences("recent_documents", Context.MODE_PRIVATE)
    fun read(): List<RecentDocument> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { item ->
            RecentDocument(item.getString("uri"), item.getString("name"), item.getLong("opened"))
        } }
    }.getOrDefault(emptyList())
    fun remove(uri: String) = write(read().filterNot { it.uri == uri })
    fun add(uri: Uri, name: String) = write((listOf(RecentDocument(uri.toString(), name, System.currentTimeMillis())) + read().filterNot { it.uri == uri.toString() }))
    private fun write(items: List<RecentDocument>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("uri", it.uri).put("name", it.name).put("opened", it.opened)) }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
