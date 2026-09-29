package com.aura.companion.data.db

/**
 * Turns free text ("what did I decide about the flat?") into an FTS4 MATCH expression
 * (`decide* OR flat*`), so a search matches any meaningful word instead of the whole phrase.
 */
object FtsQuery {

    private val STOP_WORDS = setOf(
        "the", "and", "for", "are", "was", "were", "what", "when", "where", "who", "why", "how",
        "did", "does", "do", "you", "your", "about", "that", "this", "with", "have", "has", "had",
        "can", "could", "would", "should", "tell", "remember", "said", "say", "any", "there",
        "from", "into", "they", "them", "then", "than", "been", "will", "just", "also", "some",
        "our", "out", "not", "but", "all", "its", "aura", "please", "me", "my"
    )

    /** Returns null when the text has no searchable words. */
    fun from(text: String, maxTerms: Int = 8): String? {
        // Letters and digits only, so the result can't contain FTS syntax (quotes, -, *, :)
        val terms = Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
            .take(maxTerms)
            .toList()
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { "$it*" }
    }
}
