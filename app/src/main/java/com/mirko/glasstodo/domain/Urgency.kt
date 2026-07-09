package com.mirko.glasstodo.domain

/**
 * Maps the `priority` column (a smallint that already existed) onto the three levels the UI offers.
 * Ordering is done in SQL (`ORDER BY done ASC, priority DESC, createdAt DESC`), so this type only
 * exists to keep the UI from passing raw magic numbers around.
 */
enum class Urgency(val priority: Int, val label: String) {
    NORMAL(0, "Normal"),
    IMPORTANT(1, "Importante"),
    URGENT(2, "Urgente");

    companion object {
        /** Unknown / out-of-range values (a row written by another client) degrade to NORMAL. */
        fun of(priority: Int): Urgency = entries.firstOrNull { it.priority == priority } ?: NORMAL
    }
}
