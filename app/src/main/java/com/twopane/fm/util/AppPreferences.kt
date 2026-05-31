package com.twopane.fm.util

import android.content.Context
import android.content.SharedPreferences
import com.twopane.fm.model.FilterType
import com.twopane.fm.model.SortOrder
import com.twopane.fm.model.ThemeMode
import com.twopane.fm.model.ViewMode

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("twopane_fm", Context.MODE_PRIVATE)

    var showHidden: Boolean
        get() = prefs.getBoolean("show_hidden", false)
        set(v) = prefs.edit().putBoolean("show_hidden", v).apply()

    var themeMode: ThemeMode
        get() = try { ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name) }
            catch (_: Exception) { ThemeMode.SYSTEM }
        set(v) = prefs.edit().putString("theme_mode", v.name).apply()

    var sortOrder: SortOrder
        get() = try { SortOrder.valueOf(prefs.getString("sort_order", SortOrder.NAME.name) ?: SortOrder.NAME.name) }
            catch (_: Exception) { SortOrder.NAME }
        set(v) = prefs.edit().putString("sort_order", v.name).apply()

    var viewMode: ViewMode
        get() = try { ViewMode.valueOf(prefs.getString("view_mode", ViewMode.LIST.name) ?: ViewMode.LIST.name) }
            catch (_: Exception) { ViewMode.LIST }
        set(v) = prefs.edit().putString("view_mode", v.name).apply()

    var activeFilter: FilterType
        get() = try { FilterType.valueOf(prefs.getString("active_filter", FilterType.ALL.name) ?: FilterType.ALL.name) }
            catch (_: Exception) { FilterType.ALL }
        set(v) = prefs.edit().putString("active_filter", v.name).apply()

    fun getBookmarks(): MutableList<String> {
        return prefs.getStringSet("bookmarks", setOf(
            "/storage/emulated/0",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Documents",
            "/storage/emulated/0/Pictures"
        ))?.toMutableList() ?: mutableListOf()
    }

    fun addBookmark(path: String) {
        val b = getBookmarks().toMutableSet()
        b.add(path)
        prefs.edit().putStringSet("bookmarks", b).apply()
    }

    fun removeBookmark(path: String) {
        val b = getBookmarks().toMutableSet()
        b.remove(path)
        prefs.edit().putStringSet("bookmarks", b).apply()
    }

    var smaliApiLevel: Int
        get() = prefs.getInt("smali_api_level", 35)
        set(v) = prefs.edit().putInt("smali_api_level", v).apply()

    var searchResultLimit: Int
        get() = prefs.getInt("search_result_limit", 200)
        set(v) = prefs.edit().putInt("search_result_limit", v).apply()
}
