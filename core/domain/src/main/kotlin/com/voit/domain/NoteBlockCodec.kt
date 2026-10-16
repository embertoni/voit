package com.voit.domain

/**
 * Serializes a note's structured [NoteBlock] list into a single JSON string column and
 * back, losslessly. Room persists this string in one column per the spec's block model;
 * export/backup reuses the same codec so there is a single source of truth for the format.
 *
 * The format is a JSON array of objects, each tagged with a stable `type` discriminator.
 * Round-trip is total: [decode] of an [encode] result always equals the input list.
 */
object NoteBlockCodec {

    private const val TYPE = "type"
    private const val TEXT = "text"
    private const val LEVEL = "level"
    private const val COMPLETED = "completed"

    private const val T_PARAGRAPH = "paragraph"
    private const val T_HEADING = "heading"
    private const val T_BULLET = "bullet"
    private const val T_TASK = "task"
    private const val T_CODE = "code"
    private const val T_QUOTE = "quote"

    fun encode(blocks: List<NoteBlock>): String =
        Json.write(blocks.map { toMap(it) })

    fun decode(json: String): List<NoteBlock> {
        if (json.isBlank()) return emptyList()
        return Json.parseArray(json).map { element ->
            @Suppress("UNCHECKED_CAST")
            fromMap(element as Map<String, Any?>)
        }
    }

    private fun toMap(block: NoteBlock): Map<String, Any?> = when (block) {
        is NoteBlock.Paragraph -> mapOf(TYPE to T_PARAGRAPH, TEXT to block.text)
        is NoteBlock.Heading -> mapOf(TYPE to T_HEADING, LEVEL to block.level, TEXT to block.text)
        is NoteBlock.Bullet -> mapOf(TYPE to T_BULLET, TEXT to block.text)
        is NoteBlock.Task -> mapOf(TYPE to T_TASK, TEXT to block.text, COMPLETED to block.completed)
        is NoteBlock.Code -> mapOf(TYPE to T_CODE, TEXT to block.text)
        is NoteBlock.Quote -> mapOf(TYPE to T_QUOTE, TEXT to block.text)
    }

    private fun fromMap(map: Map<String, Any?>): NoteBlock {
        val type = map[TYPE] as? String ?: error("Missing block type")
        val text = map[TEXT] as? String ?: ""
        return when (type) {
            T_PARAGRAPH -> NoteBlock.Paragraph(text)
            T_HEADING -> NoteBlock.Heading((map[LEVEL] as? Double)?.toInt() ?: 1, text)
            T_BULLET -> NoteBlock.Bullet(text)
            T_TASK -> NoteBlock.Task(text, (map[COMPLETED] as? Boolean) ?: false)
            T_CODE -> NoteBlock.Code(text)
            T_QUOTE -> NoteBlock.Quote(text)
            else -> error("Unknown block type: $type")
        }
    }
}

/**
 * Serializes a note/command tag list to and from a single stored string, losslessly.
 * Uses the shared [Json] codec so tags containing commas/quotes survive the round-trip.
 */
object TagListCodec {
    fun encode(tags: List<String>): String = Json.write(tags)

    fun decode(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return Json.parseArray(json).map { it as String }
    }
}
