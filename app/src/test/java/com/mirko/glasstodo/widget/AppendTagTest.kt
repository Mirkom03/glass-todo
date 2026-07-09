package com.mirko.glasstodo.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendTagTest {

    @Test fun appendsToAnEmptyField() {
        assertEquals("#casa ", appendTag("", "casa"))
        assertEquals("#casa ", appendTag("   ", "casa"))
    }

    @Test fun appendsAfterTheText() {
        assertEquals("Comprar pan #casa ", appendTag("Comprar pan", "casa"))
        assertEquals("Comprar pan #casa ", appendTag("Comprar pan   ", "casa"))
    }

    @Test fun neverAddsTheSameTagTwice() {
        assertEquals("Comprar pan #casa", appendTag("Comprar pan #casa", "casa"))
        assertEquals("#casa Comprar pan", appendTag("#casa Comprar pan", "casa"))
    }

    @Test fun doesNotMistakeAPrefixForTheWholeTag() {
        // "#casa" must still be appendable when the text only has "#casadas"
        assertEquals("mirar #casadas #casa ", appendTag("mirar #casadas", "casa"))
    }

    @Test fun tagsWithRegexCharactersAreTreatedLiterally() {
        assertEquals("x #a.b ", appendTag("x", "a.b"))
        assertEquals("x #a.b", appendTag("x #a.b", "a.b"))
        assertEquals("x #axb #a.b ", appendTag("x #axb", "a.b"))   // '.' must not match 'x'
    }
}
