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

    @Test
    fun tagOnlyKeepsRawAsTitle() {
        // "#casa" alone -> title falls back to the raw (never empty)
        val r = parseInput("#casa")!!
        assertEquals("casa", r.project)
        assertEquals("#casa", r.title)
    }
}
