package com.mirko.glasstodo.domain

/** Result of parsing a raw quick-add string into a title + optional #project. */
data class ParsedInput(val title: String, val project: String?)

private val PROJECT = Regex("#(\\S+)")

/**
 * Pure, unit-testable parser for the add-bar input.
 * "Comprar pan #casa" -> ParsedInput("Comprar pan", "casa"); blank -> null.
 */
fun parseInput(raw: String): ParsedInput? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val project = PROJECT.find(trimmed)?.groupValues?.get(1)
    val title = trimmed.replace(PROJECT, "").trim().ifBlank { trimmed }
    return ParsedInput(title, project)
}
