package io.github.egoushka.tin.home.signals

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Debug-only stand-in for the approvals app's provider (the real one is the approvals repo's N1), with the agreed
 * wire shape: `items` (id, title, source, created_at, held, deep_link) and `counts` (open, held). Drive it from adb:
 * `content insert --uri content://<authority>/items --bind id:s:a1 --bind title:s:Deploy --bind source:s:pr`,
 * `content delete --uri content://<authority>/items --where "id='a1'"`.
 */
class FakeNeedsYouProvider : ContentProvider() {
    private val prefs get() = context!!.getSharedPreferences("fake.needsyou", Context.MODE_PRIVATE)

    private fun rows(): MutableList<JSONObject> = JSONArray(prefs.getString("rows", "[]")).let { a -> MutableList(a.length()) { a.getJSONObject(it) } }

    private fun save(rows: List<JSONObject>) {
        prefs.edit().putString("rows", JSONArray(rows).toString()).apply()
        context!!.contentResolver.notifyChange(Uri.parse("content://${BuildConfigAuthority}"), null)
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, order: String?): Cursor {
        val all = rows()
        if (uri.lastPathSegment == "counts") {
            return MatrixCursor(arrayOf("open", "held")).apply { addRow(arrayOf(all.count { it.getInt("held") == 0 }, all.count { it.getInt("held") != 0 })) }
        }
        return MatrixCursor(arrayOf("id", "title", "source", "created_at", "held", "deep_link")).apply {
            all.forEach { addRow(arrayOf(it.getString("id"), it.getString("title"), it.getString("source"), it.getLong("created_at"), it.getInt("held"), it.getString("deep_link"))) }
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val v = values ?: return null
        val id = v.getAsString("id") ?: return null
        val all = rows().filterNot { it.getString("id") == id }.toMutableList()
        all += JSONObject()
            .put("id", id).put("title", v.getAsString("title") ?: id).put("source", v.getAsString("source") ?: "")
            .put("created_at", v.getAsLong("created_at") ?: System.currentTimeMillis()).put("held", v.getAsInteger("held") ?: 0)
            .put("deep_link", v.getAsString("deep_link") ?: "approvals://item/$id")
        save(all)
        return uri
    }

    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
        val id = args?.firstOrNull() ?: selection?.let { Regex("id\\s*=\\s*'([^']*)'").find(it)?.groupValues?.get(1) }
        val before = rows()
        val after = if (id == null) emptyList() else before.filterNot { it.getString("id") == id }
        save(after)
        return before.size - after.size
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    override fun getType(uri: Uri): String? = null

    private companion object {
        const val BuildConfigAuthority = "io.github.egoushka.tin.home.needsyou.fake"
    }
}
