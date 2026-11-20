package de.streamview.tv

import android.content.Context
import android.content.SharedPreferences
import de.streamview.tv.core.PopupPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

enum class NavMode { CURSOR, FOCUS }

/** Globale Einstellungen. Gespeichert app-privat (MODE_PRIVATE), kein Backup (allowBackup=false). */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("streamview", Context.MODE_PRIVATE)

    var adblockEnabled: Boolean
        get() = sp.getBoolean("adblock", true)
        set(v) = sp.edit().putBoolean("adblock", v).apply()

    /** Pop-up- und Weiterleitungsschutz (unabhängig vom Werbefilter). */
    var popupProtection: Boolean
        get() = sp.getBoolean("popup_protection", true)
        set(v) = sp.edit().putBoolean("popup_protection", v).apply()

    /** 0 = aus, 1 = nur seitenspezifisch (Standard), 2 = auch allgemeine (mehr RAM) */
    var cosmeticMode: Int
        get() = sp.getInt("cosmetic", 1)
        set(v) = sp.edit().putInt("cosmetic", v).apply()

    var navMode: NavMode
        get() = if (sp.getString("nav", "CURSOR") == "FOCUS") NavMode.FOCUS else NavMode.CURSOR
        set(v) = sp.edit().putString("nav", v.name).apply()

    /** 0 langsam, 1 normal, 2 schnell */
    var cursorSpeed: Int
        get() = sp.getInt("cursor_speed", 1)
        set(v) = sp.edit().putInt("cursor_speed", v).apply()

    var desktopDefault: Boolean
        get() = sp.getBoolean("desktop", false)
        set(v) = sp.edit().putBoolean("desktop", v).apply()

    var thirdPartyCookies: Boolean
        get() = sp.getBoolean("third_party_cookies", true)
        set(v) = sp.edit().putBoolean("third_party_cookies", v).apply()

    var rememberLastPage: Boolean
        get() = sp.getBoolean("remember_last", true)
        set(v) {
            sp.edit().putBoolean("remember_last", v).apply()
            if (!v) lastUrl = null
        }

    var lastUrl: String?
        get() = sp.getString("last_url", null)
        set(v) = sp.edit().putString("last_url", v).apply()

    var enabledLists: Set<String>
        get() = sp.getStringSet("lists", null) ?: FilterManager.LISTS.filter { it.defaultOn }.map { it.id }.toSet()
        set(v) = sp.edit().putStringSet("lists", HashSet(v)).apply()

    fun listUpdated(id: String): Long = sp.getLong("list_updated_$id", 0L)
    fun setListUpdated(id: String, t: Long) = sp.edit().putLong("list_updated_$id", t).apply()
    var lastUpdateAttempt: Long
        get() = sp.getLong("last_update_attempt", 0L)
        set(v) = sp.edit().putLong("last_update_attempt", v).apply()
    var lastUpdateError: String?
        get() = sp.getString("last_update_error", null)
        set(v) = sp.edit().putString("last_update_error", v).apply()

    /** Zufälliger Name des JavaScript-Kommunikationsobjekts (pro Installation). */
    val bridgeName: String
        get() {
            sp.getString("bridge_name", null)?.let { return it }
            val r = SecureRandom()
            val name = "__sv" + (1..10).map { "abcdefghijklmnopqrstuvwxyz"[r.nextInt(26)] }.joinToString("")
            sp.edit().putString("bridge_name", name).apply()
            return name
        }

    // --- Favoriten ---
    data class Favorite(val name: String, val url: String)

    fun favorites(): List<Favorite> {
        val json = sp.getString("favorites", null)
        if (json == null) {
            val defaults = listOf(
                Favorite("ZDF Mediathek", "https://www.zdf.de/"),
                Favorite("ARD Mediathek", "https://www.ardmediathek.de/"),
                Favorite("arte", "https://www.arte.tv/de/"),
                Favorite("YouTube", "https://www.youtube.com/"),
            )
            saveFavorites(defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Favorite(o.getString("n"), o.getString("u"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveFavorites(list: List<Favorite>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("u", it.url)) }
        sp.edit().putString("favorites", arr.toString()).apply()
    }

    fun addFavorite(name: String, url: String) {
        val list = favorites().filter { it.url != url }.toMutableList()
        list.add(Favorite(name.take(60), url))
        saveFavorites(list)
    }

    fun clearBrowsingTraces() {
        sp.edit().remove("last_url").apply()
    }
}

/** Einstellungen pro Website (Schlüssel = registrierbare Domain, z. B. "zdf.de"). */
data class SiteConfig(
    /** null = Standard (an) */
    val adblock: Boolean? = null,
    val popups: PopupPolicy = PopupPolicy.ASK,
    /** null = globale Einstellung */
    val desktop: Boolean? = null,
    /** DRM (geschützte Inhalte): null = jedes Mal fragen */
    val drm: Boolean? = null,
) {
    val isDefault get() = adblock == null && popups == PopupPolicy.ASK && desktop == null && drm == null
}

class SiteStore(context: Context) {
    private val sp = context.getSharedPreferences("streamview_sites", Context.MODE_PRIVATE)
    private val map = ConcurrentHashMap<String, SiteConfig>()

    init {
        try {
            val o = JSONObject(sp.getString("sites", "{}") ?: "{}")
            for (k in o.keys()) {
                val s = o.getJSONObject(k)
                map[k] = SiteConfig(
                    adblock = if (s.has("ab")) s.getBoolean("ab") else null,
                    popups = runCatching { PopupPolicy.valueOf(s.optString("pp", "ASK")) }.getOrDefault(PopupPolicy.ASK),
                    desktop = if (s.has("dt")) s.getBoolean("dt") else null,
                    drm = if (s.has("drm")) s.getBoolean("drm") else null,
                )
            }
        } catch (_: Exception) {
        }
    }

    operator fun get(site: String): SiteConfig = map[site] ?: SiteConfig()

    fun update(site: String, change: (SiteConfig) -> SiteConfig) {
        if (site.isEmpty()) return
        val n = change(get(site))
        if (n.isDefault) map.remove(site) else map[site] = n
        save()
    }

    fun all(): Map<String, SiteConfig> = map.toSortedMap()

    fun reset(site: String) { map.remove(site); save() }

    fun resetAll() { map.clear(); save() }

    private fun save() {
        val o = JSONObject()
        for ((k, v) in map) {
            val s = JSONObject()
            v.adblock?.let { s.put("ab", it) }
            if (v.popups != PopupPolicy.ASK) s.put("pp", v.popups.name)
            v.desktop?.let { s.put("dt", it) }
            v.drm?.let { s.put("drm", it) }
            o.put(k, s)
        }
        sp.edit().putString("sites", o.toString()).apply()
    }
}
