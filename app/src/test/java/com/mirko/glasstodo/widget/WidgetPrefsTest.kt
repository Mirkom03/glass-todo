package com.mirko.glasstodo.widget

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPrefsTest {

    @Test fun aFreshWidgetShowsEverythingAsAList() {
        val prefs = mutablePreferencesOf()
        assertNull(WidgetPrefs.filterOf(prefs))
        assertFalse(WidgetPrefs.showTagsOf(prefs))
    }

    @Test fun anEmptyFilterMeansAllTags() {
        // SelectTagAction writes "" to clear, rather than removing the key.
        val prefs = mutablePreferencesOf(WidgetPrefs.FILTER to "")
        assertNull(WidgetPrefs.filterOf(prefs))
        assertNull(WidgetPrefs.filterOf(mutablePreferencesOf(WidgetPrefs.FILTER to "   ")))
    }

    @Test fun aStoredTagIsTheFilter() {
        assertEquals("aide", WidgetPrefs.filterOf(mutablePreferencesOf(WidgetPrefs.FILTER to "aide")))
    }

    @Test fun theTagsFaceIsRemembered() {
        assertTrue(WidgetPrefs.showTagsOf(mutablePreferencesOf(WidgetPrefs.SHOW_TAGS to true)))
        assertFalse(WidgetPrefs.showTagsOf(mutablePreferencesOf(WidgetPrefs.SHOW_TAGS to false)))
    }
}
