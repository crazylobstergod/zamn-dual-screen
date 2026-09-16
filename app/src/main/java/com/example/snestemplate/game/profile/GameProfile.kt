package com.example.snestemplate.game.profile

import android.content.Context
import org.json.JSONObject

data class MemoryField(val id: String, val label: String, val offset: Int, val type: String, val access: String, val min: Int?, val max: Int?, val notes: String?)
data class GameProfile(val game: String, val fields: Map<String, MemoryField>)

object GameProfileLoader {
  fun load(context: Context, assetPath: String): GameProfile {
    val root = JSONObject(context.assets.open(assetPath).bufferedReader().use { it.readText() })
    val memory = root.getJSONObject("memory")
    val fields = buildMap {
      memory.keys().forEach { key ->
        val item = memory.getJSONObject(key)
        val id = item.optString("id", key)
        put(id, MemoryField(id, item.optString("label", id), item.getInt("offset"), item.getString("type"), item.getString("access"), if (item.has("min")) item.getInt("min") else null, if (item.has("max")) item.getInt("max") else null, if (item.has("notes")) item.getString("notes") else null))
      }
    }
    return GameProfile(root.getString("game"), fields)
  }
}
