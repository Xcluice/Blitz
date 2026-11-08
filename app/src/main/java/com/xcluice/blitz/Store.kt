package com.xcluice.blitz

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

class HistoryRec(
    val time: Long,
    val sending: Boolean,
    val peer: String,
    val avatar: Int,
    val count: Int,
    val bytes: Long,
    val avgBps: Double,
    val first: String
)

/** Profile and transfer history, kept on this phone only. */
object Store {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("blitz", Context.MODE_PRIVATE)

    fun hasProfile(ctx: Context): Boolean = sp(ctx).getBoolean("profile_set", false)

    fun name(ctx: Context): String =
        sp(ctx).getString("name", null) ?: (Build.MODEL ?: "My phone").take(14)

    fun avatar(ctx: Context): Int = sp(ctx).getInt("avatar", 0)

    fun saveProfile(ctx: Context, name: String, avatar: Int) {
        sp(ctx).edit().putString("name", name.trim().take(14)).putInt("avatar", avatar)
            .putBoolean("profile_set", true).apply()
    }

    fun history(ctx: Context): List<HistoryRec> {
        val raw = sp(ctx).getString("history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<HistoryRec>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    HistoryRec(
                        o.getLong("t"), o.getBoolean("s"), o.getString("p"), o.getInt("a"),
                        o.getInt("c"), o.getLong("b"), o.getDouble("v"), o.optString("f")
                    )
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun addHistory(ctx: Context, r: HistoryRec) {
        val list = listOf(r) + history(ctx)
        write(ctx, list.take(100))
    }

    fun clearHistory(ctx: Context) {
        sp(ctx).edit().remove("history").apply()
    }

    private fun write(ctx: Context, list: List<HistoryRec>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("t", r.time)
            o.put("s", r.sending)
            o.put("p", r.peer)
            o.put("a", r.avatar)
            o.put("c", r.count)
            o.put("b", r.bytes)
            o.put("v", r.avgBps)
            o.put("f", r.first)
            arr.put(o)
        }
        sp(ctx).edit().putString("history", arr.toString()).apply()
    }
}
