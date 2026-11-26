package com.blockapp.android.sync

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * The shared catalog (shared/catalog.json, copied into assets by `make sync`): which Android
 * packages, websites and desktop apps are the same "target". A limit on "youtube" is one budget
 * across the YouTube app, youtube.com and any desktop client, because every device names it
 * the same way. Anything not in the catalog is `android:<package>`.
 */
object Catalog {

    class Target(val id: String, val name: String, val category: String, val packages: List<String>)

    @Volatile private var loaded: Pair<List<Target>, Set<String>>? = null

    private fun load(context: Context): Pair<List<Target>, Set<String>> {
        loaded?.let { return it }
        val result = try {
            val json = JSONObject(context.assets.open("catalog.json").bufferedReader().use { it.readText() })
            val cats = json.getJSONArray("categories")
            val distracting = (0 until cats.length()).map { cats.getJSONObject(it) }
                .filter { it.optBoolean("distracting") }.map { it.getString("id") }.toSet()
            val arr = json.getJSONArray("targets")
            val targets = (0 until arr.length()).map { i ->
                val t = arr.getJSONObject(i)
                val pk = t.optJSONArray("android")
                Target(
                    id = t.getString("id"),
                    name = t.getString("name"),
                    category = t.optString("category", "other"),
                    packages = if (pk == null) emptyList() else (0 until pk.length()).map { pk.getString(it) },
                )
            }
            targets to distracting
        } catch (e: Exception) {
            // A broken catalog must not take enforcement down; unknown apps still map by package.
            Log.w("Catalog", "catalog.json unreadable", e)
            emptyList<Target>() to setOf("social", "video", "games", "news", "shopping")
        }
        loaded = result
        return result
    }

    fun targetFor(context: Context, packageName: String): String =
        load(context).first.firstOrNull { packageName in it.packages }?.id ?: "android:$packageName"

    fun name(context: Context, target: String): String? = load(context).first.firstOrNull { it.id == target }?.name

    fun category(context: Context, target: String, overrides: Map<String, String>): String =
        overrides[target] ?: load(context).first.firstOrNull { it.id == target }?.category ?: "other"

    fun distracting(context: Context): Set<String> = load(context).second
}
