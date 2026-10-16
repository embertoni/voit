package com.voit.domain

/**
 * Renders a [Note] to human-readable formats for sharing/export.
 *
 * Pure, deterministic, and dependency-free. Markdown mirrors the structured block model;
 * plain text strips markup while keeping the structure legible.
 */
object NoteExporter {

    fun toMarkdown(note: Note): String {
        val sb = StringBuilder()
        note.title?.takeIf { it.isNotBlank() }?.let {
            sb.append("# ").append(it).append("\n\n")
        }
        note.blocks.forEachIndexed { index, block ->
            if (index > 0) sb.append('\n')
            sb.append(blockToMarkdown(block)).append('\n')
        }
        if (note.tags.isNotEmpty()) {
            sb.append('\n')
            sb.append(note.tags.joinToString(" ") { "#$it" }).append('\n')
        }
        return sb.toString().trimEnd('\n') + "\n"
    }

    fun toPlainText(note: Note): String {
        val sb = StringBuilder()
        note.title?.takeIf { it.isNotBlank() }?.let {
            sb.append(it).append("\n\n")
        }
        note.blocks.forEachIndexed { index, block ->
            if (index > 0) sb.append('\n')
            sb.append(blockToPlainText(block)).append('\n')
        }
        if (note.tags.isNotEmpty()) {
            sb.append('\n')
            sb.append("Tags: ").append(note.tags.joinToString(", ")).append('\n')
        }
        return sb.toString().trimEnd('\n') + "\n"
    }

    private fun blockToMarkdown(block: NoteBlock): String = when (block) {
        is NoteBlock.Paragraph -> block.text
        is NoteBlock.Heading -> "#".repeat(block.level) + " " + block.text
        is NoteBlock.Bullet -> "- " + block.text
        is NoteBlock.Task -> (if (block.completed) "- [x] " else "- [ ] ") + block.text
        is NoteBlock.Code -> "```\n" + block.text + "\n```"
        is NoteBlock.Quote -> "> " + block.text
    }

    private fun blockToPlainText(block: NoteBlock): String = when (block) {
        is NoteBlock.Paragraph -> block.text
        is NoteBlock.Heading -> block.text
        is NoteBlock.Bullet -> "- " + block.text
        is NoteBlock.Task -> (if (block.completed) "[x] " else "[ ] ") + block.text
        is NoteBlock.Code -> block.text
        is NoteBlock.Quote -> "\"" + block.text + "\""
    }
}
