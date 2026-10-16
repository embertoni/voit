package com.voit.domain

/**
 * Structured content block for a [Note].
 *
 * Voit stores notes as a list of typed blocks rather than raw markdown, so the
 * formatting parser can produce precise, loss-free structure and later phases can
 * render each block type deliberately in the monochrome design system.
 */
sealed interface NoteBlock {
    /** A paragraph of free-form prose. */
    data class Paragraph(val text: String) : NoteBlock

    /**
     * A heading. [level] is clamped to the inclusive range 1..6 at construction;
     * level 1 is the most prominent.
     */
    data class Heading(val level: Int, val text: String) : NoteBlock {
        init {
            require(level in MIN_LEVEL..MAX_LEVEL) {
                "Heading level must be in $MIN_LEVEL..$MAX_LEVEL but was $level"
            }
        }

        companion object {
            const val MIN_LEVEL = 1
            const val MAX_LEVEL = 6

            /** Builds a heading, clamping [level] into the valid range. */
            fun clamped(level: Int, text: String): Heading =
                Heading(level.coerceIn(MIN_LEVEL, MAX_LEVEL), text)
        }
    }

    /** An unordered (bullet) list item. */
    data class Bullet(val text: String) : NoteBlock

    /** A checkbox / task list item embedded in a note. */
    data class Task(val text: String, val completed: Boolean = false) : NoteBlock

    /** A verbatim code block. Content is preserved literally and never rewritten. */
    data class Code(val text: String) : NoteBlock

    /** A block quote. */
    data class Quote(val text: String) : NoteBlock
}
