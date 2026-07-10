package com.mirko.glasstodo.domain

/** Result of parsing a raw quick-add string into a title + optional #project. */
data class ParsedInput(val title: String, val project: String?)

private val PROJECT = Regex("#(\\S+)")
private val INNER_GAP = Regex("\\s{2,}")

/**
 * Pure, unit-testable parser for the add-bar input.
 * "Comprar pan #casa" -> ParsedInput("Comprar pan", "casa"); blank -> null.
 *
 * Only the FIRST tag becomes the project, so only the first is cut from the title: `replace` strips
 * every match, which silently deleted "#urgente" from "Llamar a Ana #trabajo #urgente" — the text
 * disappeared without becoming anything. Extra tags stay where the user typed them.
 */
fun parseInput(raw: String): ParsedInput? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val project = PROJECT.find(trimmed)?.groupValues?.get(1)
    val title = PROJECT.replaceFirst(trimmed, "")
        .replace(INNER_GAP, " ")            // the cut tag leaves a double space behind
        .trim()
        .ifBlank { trimmed }
    return ParsedInput(title, project)
}
