package com.voit.interpretation

import com.voit.domain.NoteBlock

/**
 * The structured result of running a transcript through [FormattingParser].
 *
 * All three representations are retained so later phases can show the user what was heard
 * ([rawTranscript]), what the parser worked from ([normalizedTranscript]), and the final
 * structure ([documentBlocks]).
 *
 * @param command a terminal control directive the parser encountered (save/undo/cancel),
 *        or null if none. These do not become content.
 * @param reviewRequired true when the parser could not fully resolve the input
 *        (e.g. an unmatched bold/italic boundary). Content is preserved, never discarded.
 */
data class ParsedDocument(
    val rawTranscript: String,
    val normalizedTranscript: String,
    val documentBlocks: List<NoteBlock>,
    val tags: List<String> = emptyList(),
    val startedNewNote: Boolean = false,
    val command: DocumentCommand? = null,
    val reviewRequired: Boolean = false,
)

/** Terminal control directives that act on the draft rather than adding content. */
enum class DocumentCommand {
    SAVE_NOTE,
    UNDO_LAST_CHANGE,
    CANCEL_NOTE,
}

/**
 * Deterministic transcript -> structured-blocks parser. No LLM, no network.
 *
 * Honors the spoken formatting directives:
 * - `new note` / `start a note`      -> begins a fresh draft (flag + clears content)
 * - `title:` / `heading:`            -> heading block (level 1)
 * - `new paragraph`                  -> start a new paragraph block
 * - `new line`                       -> line break within the current paragraph
 * - `bullet point`                   -> bullet list item
 * - `numbered list` / `next item`    -> ordered list item (modeled as a bullet with marker)
 * - `checkbox` / `task item`         -> unchecked [NoteBlock.Task]
 * - `bold ... end bold`              -> inline emphasis markers in the text
 * - `italic ... end italic`          -> inline emphasis markers in the text
 * - `code block` ... `end code block`-> verbatim [NoteBlock.Code] (literal, not rewritten)
 * - `quote`                          -> quote block
 * - `tag ...`                        -> adds a tag (never body text)
 * - `save note` / `undo last change` / `cancel note` -> terminal [DocumentCommand]
 *
 * Unmatched `bold`/`italic` boundaries are preserved verbatim and set reviewRequired=true.
 */
class FormattingParser {

    /** Builder state while walking the token stream. */
    private class Builder {
        val blocks = mutableListOf<NoteBlock>()
        val tags = mutableListOf<String>()
        var startedNewNote = false
        var command: DocumentCommand? = null
        var reviewRequired = false

        // Current paragraph accumulation (joined with "\n" for new-line directives).
        private val paragraphLines = mutableListOf<String>()
        private var currentLine = StringBuilder()

        fun appendText(text: String) {
            if (currentLine.isNotEmpty() && !currentLine.endsWith(" ")) {
                currentLine.append(' ')
            }
            currentLine.append(text)
        }

        fun newLine() {
            paragraphLines.add(currentLine.toString().trim())
            currentLine = StringBuilder()
        }

        fun flushParagraph() {
            if (currentLine.isNotBlank()) {
                paragraphLines.add(currentLine.toString().trim())
            }
            currentLine = StringBuilder()
            val joined = paragraphLines.joinToString("\n").trim()
            paragraphLines.clear()
            if (joined.isNotEmpty()) {
                blocks.add(NoteBlock.Paragraph(joined))
            }
        }

        fun addBlock(block: NoteBlock) {
            flushParagraph()
            blocks.add(block)
        }

        fun hasPendingText(): Boolean =
            currentLine.isNotBlank() || paragraphLines.any { it.isNotBlank() }
    }

    fun parse(transcript: String, literalMode: Boolean = false): ParsedDocument {
        val raw = transcript
        val normalized = normalize(transcript)

        if (literalMode) {
            val blocks = if (normalized.isEmpty()) emptyList() else listOf(NoteBlock.Paragraph(normalized))
            return ParsedDocument(
                rawTranscript = raw,
                normalizedTranscript = normalized,
                documentBlocks = blocks,
            )
        }

        val tokens = normalized.split(" ").filter { it.isNotEmpty() }
        val b = Builder()
        var i = 0

        while (i < tokens.size) {
            val consumed = tryDirective(tokens, i, b)
            if (consumed > 0) {
                i += consumed
            } else {
                b.appendText(tokens[i])
                i++
            }
        }
        b.flushParagraph()

        return ParsedDocument(
            rawTranscript = raw,
            normalizedTranscript = normalized,
            documentBlocks = b.blocks.toList(),
            tags = b.tags.toList(),
            startedNewNote = b.startedNewNote,
            command = b.command,
            reviewRequired = b.reviewRequired,
        )
    }

    /**
     * Attempts to match a directive starting at [start]. Returns the number of tokens
     * consumed, or 0 if no directive matched (caller treats the token as content).
     */
    private fun tryDirective(tokens: List<String>, start: Int, b: Builder): Int {
        // Multi-word directives, longest first.
        matchPhrase(tokens, start, "start", "a", "note")?.let {
            b.flushParagraph(); b.blocks.clear(); b.tags.clear(); b.startedNewNote = true
            return it
        }
        matchPhrase(tokens, start, "new", "note")?.let {
            b.flushParagraph(); b.blocks.clear(); b.tags.clear(); b.startedNewNote = true
            return it
        }
        matchPhrase(tokens, start, "new", "paragraph")?.let { b.flushParagraph(); return it }
        matchPhrase(tokens, start, "new", "line")?.let { b.newLine(); return it }
        matchPhrase(tokens, start, "bullet", "point")?.let {
            return collectInlineBlock(tokens, start + it, b) { text -> NoteBlock.Bullet(text) } + it
        }
        matchPhrase(tokens, start, "numbered", "list")?.let {
            return collectInlineBlock(tokens, start + it, b) { text -> NoteBlock.Bullet(text) } + it
        }
        matchPhrase(tokens, start, "next", "item")?.let {
            return collectInlineBlock(tokens, start + it, b) { text -> NoteBlock.Bullet(text) } + it
        }
        matchPhrase(tokens, start, "task", "item")?.let {
            return collectInlineBlock(tokens, start + it, b) { text -> NoteBlock.Task(text, completed = false) } + it
        }
        matchPhrase(tokens, start, "code", "block")?.let {
            return consumeCodeBlock(tokens, start + it, b) + it
        }
        matchPhrase(tokens, start, "end", "bold")?.let {
            // Unmatched end-bold: preserve literally, flag for review.
            b.appendText("**"); b.reviewRequired = true; return it
        }
        matchPhrase(tokens, start, "end", "italic")?.let {
            b.appendText("*"); b.reviewRequired = true; return it
        }
        matchPhrase(tokens, start, "undo", "last", "change")?.let { b.command = DocumentCommand.UNDO_LAST_CHANGE; return it }
        matchPhrase(tokens, start, "save", "note")?.let { b.command = DocumentCommand.SAVE_NOTE; return it }
        matchPhrase(tokens, start, "cancel", "note")?.let { b.command = DocumentCommand.CANCEL_NOTE; return it }

        // Single-word / prefix directives.
        val word = tokens[start]
        when (word) {
            "checkbox" -> return collectInlineBlock(tokens, start + 1, b) { text -> NoteBlock.Task(text, completed = false) } + 1
            "quote" -> return collectInlineBlock(tokens, start + 1, b) { text -> NoteBlock.Quote(text) } + 1
            "bold" -> return consumeEmphasis(tokens, start + 1, b, "end", "bold", "**") + 1
            "italic" -> return consumeEmphasis(tokens, start + 1, b, "end", "italic", "*") + 1
            "tag" -> return consumeTag(tokens, start + 1, b) + 1
            "title", "heading" -> return consumeHeading(tokens, start + 1, b) + 1
        }

        // Prefix forms like "title:" / "heading:" where the colon is attached.
        if (word == "title:" || word == "heading:") {
            return consumeHeading(tokens, start + 1, b) + 1
        }

        return 0
    }

    /**
     * Collects the rest of the current clause (until the next directive) into a single
     * block built by [factory]. Returns tokens consumed (excluding the directive itself).
     */
    private fun collectInlineBlock(
        tokens: List<String>,
        start: Int,
        b: Builder,
        factory: (String) -> NoteBlock,
    ): Int {
        val sb = StringBuilder()
        var i = start
        while (i < tokens.size && peekDirective(tokens, i) == 0) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(tokens[i])
            i++
        }
        b.addBlock(factory(sb.toString().trim()))
        return i - start
    }

    private fun consumeHeading(tokens: List<String>, start: Int, b: Builder): Int {
        val sb = StringBuilder()
        var i = start
        while (i < tokens.size && peekDirective(tokens, i) == 0) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(tokens[i])
            i++
        }
        var text = sb.toString().trim()
        // Drop a leading colon if "title :" split or ":text".
        text = text.removePrefix(":").trim()
        b.addBlock(NoteBlock.Heading(1, text))
        return i - start
    }

    private fun consumeTag(tokens: List<String>, start: Int, b: Builder): Int {
        // A tag is a single following token (hyphenated/underscored allowed), never body.
        if (start >= tokens.size) return 0
        val tag = tokens[start].removePrefix("#").trim()
        if (tag.isNotEmpty()) b.tags.add(tag)
        return 1
    }

    /** Emphasis: wrap text up to the matching end marker; preserve + flag if unmatched. */
    private fun consumeEmphasis(
        tokens: List<String>,
        start: Int,
        b: Builder,
        endWord1: String,
        endWord2: String,
        marker: String,
    ): Int {
        val sb = StringBuilder()
        var i = start
        var matched = false
        while (i < tokens.size) {
            if (matchPhrase(tokens, i, endWord1, endWord2) != null) {
                i += 2
                matched = true
                break
            }
            // A new structural directive terminates an unmatched emphasis span.
            if (peekDirective(tokens, i) != 0) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(tokens[i])
            i++
        }
        val inner = sb.toString().trim()
        if (matched) {
            b.appendText("$marker$inner$marker")
        } else {
            // Unresolvable boundary: preserve the content, mark for review.
            if (inner.isNotEmpty()) b.appendText("$marker$inner")
            b.reviewRequired = true
        }
        return i - start
    }

    /** Code block: verbatim content until `end code block`. Literal, never rewritten. */
    private fun consumeCodeBlock(tokens: List<String>, start: Int, b: Builder): Int {
        val sb = StringBuilder()
        var i = start
        var closed = false
        while (i < tokens.size) {
            if (matchPhrase(tokens, i, "end", "code", "block") != null) {
                i += 3
                closed = true
                break
            }
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(tokens[i])
            i++
        }
        b.addBlock(NoteBlock.Code(sb.toString()))
        if (!closed) b.reviewRequired = true
        return i - start
    }

    /** Returns consumed-token count for a directive at [i] WITHOUT mutating state. */
    private fun peekDirective(tokens: List<String>, i: Int): Int {
        matchPhrase(tokens, i, "start", "a", "note")?.let { return it }
        matchPhrase(tokens, i, "new", "note")?.let { return it }
        matchPhrase(tokens, i, "new", "paragraph")?.let { return it }
        matchPhrase(tokens, i, "new", "line")?.let { return it }
        matchPhrase(tokens, i, "bullet", "point")?.let { return it }
        matchPhrase(tokens, i, "numbered", "list")?.let { return it }
        matchPhrase(tokens, i, "next", "item")?.let { return it }
        matchPhrase(tokens, i, "task", "item")?.let { return it }
        matchPhrase(tokens, i, "code", "block")?.let { return it }
        matchPhrase(tokens, i, "undo", "last", "change")?.let { return it }
        matchPhrase(tokens, i, "save", "note")?.let { return it }
        matchPhrase(tokens, i, "cancel", "note")?.let { return it }
        return when (tokens.getOrNull(i)) {
            "checkbox", "quote", "bold", "italic", "tag", "title", "heading", "title:", "heading:" -> 1
            else -> 0
        }
    }

    /** Returns the phrase length if [words] match at [start], else null. */
    private fun matchPhrase(tokens: List<String>, start: Int, vararg words: String): Int? {
        if (start + words.size > tokens.size) return null
        for (k in words.indices) {
            if (tokens[start + k] != words[k]) return null
        }
        return words.size
    }

    /** Lower-cases, strips most punctuation (keeps colon/hash/hyphen/underscore), collapses spaces. */
    private fun normalize(input: String): String {
        val sb = StringBuilder()
        for (ch in input.lowercase()) {
            when {
                ch.isLetterOrDigit() || ch == ':' || ch == '#' || ch == '-' || ch == '_' -> sb.append(ch)
                ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }
}
