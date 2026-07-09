package com.mirko.glasstodo.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UrgencyTest {

    @Test fun mapsTheThreeLevels() {
        assertEquals(Urgency.NORMAL, Urgency.of(0))
        assertEquals(Urgency.IMPORTANT, Urgency.of(1))
        assertEquals(Urgency.URGENT, Urgency.of(2))
    }

    @Test fun unknownPrioritiesDegradeToNormal_ratherThanCrash() {
        // A row written by some other client (or the old schema default) must not blow up the widget.
        assertEquals(Urgency.NORMAL, Urgency.of(3))
        assertEquals(Urgency.NORMAL, Urgency.of(-1))
        assertEquals(Urgency.NORMAL, Urgency.of(99))
    }

    @Test fun prioritiesSortMostUrgentFirst() {
        assertEquals(
            listOf(Urgency.URGENT, Urgency.IMPORTANT, Urgency.NORMAL),
            Urgency.entries.sortedByDescending { it.priority }
        )
    }
}
