package com.mirko.glasstodo.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseInputTest {

    @Test
    fun extractsProjectTag() {
        val r = parseInput("Comprar pan #casa")!!
        assertEquals("Comprar pan", r.title)
        assertEquals("casa", r.project)
    }

    @Test
    fun noTagLeavesProjectNull() {
        val r = parseInput("Llamar a Jose")!!
        assertEquals("Llamar a Jose", r.title)
        assertNull(r.project)
    }

    @Test
    fun blankReturnsNull() {
        assertNull(parseInput("   "))
        assertNull(parseInput(""))
    }

    /**
     * Only the FIRST #tag becomes the project. `String.replace(Regex, …)` strips every match, so the
     * second tag was silently deleted from what the user typed — the text vanished without becoming
     * anything. Extra tags stay in the title.
     */
    @Test
    fun keepsTheExtraTagsInTheTitle() {
        val r = parseInput("Llamar a Ana #trabajo #urgente")!!
        assertEquals("trabajo", r.project)
        assertEquals("Llamar a Ana #urgente", r.title)
    }

    @Test
    fun tagOnlyKeepsRawAsTitle() {
        // "#casa" alone -> title falls back to the raw (never empty)
        val r = parseInput("#casa")!!
        assertEquals("casa", r.project)
        assertEquals("#casa", r.title)
    }
}
