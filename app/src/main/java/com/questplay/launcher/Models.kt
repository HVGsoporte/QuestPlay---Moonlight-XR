package com.questplay.launcher

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * type: QUEST (app nativa instalada en las gafas) o WINDOWS (juego del PC, vía Sunshine + Moonlight XR).
 * sunshineApp: nombre exacto de la aplicación en Sunshine (solo WINDOWS).
 */
data class Game(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var type: String = "QUEST",
    val packageName: String? = null,
    var sunshineApp: String? = null,
    var coverFile: String? = null
)

class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("questplay", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun loadGames(): MutableList<Game> {
        val j = p.getString("games", null) ?: return mutableListOf()
        val list = try {
            gson.fromJson<MutableList<Game>>(j, object : TypeToken<MutableList<Game>>() {}.type)
                ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
        // Migración de versiones anteriores: PCVR/WINDOWS con server.exe pasan a WINDOWS (Sunshine).
        // Su nombre en Sunshine no se conocía, así que se parte del nombre mostrado; se corrige con ⚙.
        list.forEach {
            if (it.type != "QUEST") {
                it.type = "WINDOWS"
                if (it.sunshineApp.isNullOrBlank()) it.sunshineApp = it.name
            }
        }
        return list
    }

    fun saveGames(games: List<Game>) {
        p.edit().putString("games", gson.toJson(games)).apply()
    }

    /** Nombre del PC tal como aparece en Moonlight XR. */
    var pcName: String?
        get() = p.getString("pcName", null)
        set(v) { p.edit().putString("pcName", v).apply() }

    var pcUuid: String?
        get() = p.getString("pcUuid", null)
        set(v) { p.edit().putString("pcUuid", v).apply() }

    /** Vacío = detectar automáticamente entre los paquetes habituales. */
    var moonlightPackage: String?
        get() = p.getString("moonlightPackage", null)
        set(v) { p.edit().putString("moonlightPackage", v).apply() }

    var sort: String
        get() = p.getString("sort", "AZ") ?: "AZ"
        set(v) { p.edit().putString("sort", v).apply() }
}
