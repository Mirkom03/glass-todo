package com.mirko.glasstodo.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Per-widget state, persisted by Glance's default PreferencesGlanceStateDefinition. Two widgets can
 * therefore sit on the same screen showing different tags — one pinned to #aide, one showing all.
 *
 * The filter is stored as "" for "no filter" rather than absent, so clearing it is a plain write.
 */
object WidgetPrefs {
    val FILTER = stringPreferencesKey("filter_tag")
    val SHOW_TAGS = booleanPreferencesKey("show_tags")

    /** Empty string means "all tags"; null-safe for a widget that has never been touched. */
    fun filterOf(prefs: Preferences): String? = prefs[FILTER]?.takeIf { it.isNotBlank() }

    fun showTagsOf(prefs: Preferences): Boolean = prefs[SHOW_TAGS] ?: false
}
