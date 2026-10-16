package com.voit.domain

/**
 * Where a note originated. Phase 1 produces notes from the text-command simulator and,
 * eventually, from live voice; [MANUAL] covers notes a user edits by hand.
 */
enum class NoteSource {
    VOICE,
    SIMULATOR,
    MANUAL,
    IMPORT,
}

/**
 * A structured note.
 *
 * @param id stable UUID string.
 * @param title optional display title (may be derived from a heading directive).
 * @param blocks ordered structured content; see [NoteBlock].
 * @param tags normalized tag list (tags are routed here, never into body text).
 * @param createdAt / updatedAt epoch-millis timestamps from an injected [Clock].
 * @param source how the note was captured.
 * @param transcriptLanguage BCP-47-ish language tag of the originating transcript, if known.
 * @param reviewRequired set true when parsing could not fully resolve the input
 *        (e.g. unmatched bold/italic boundaries) so content is preserved for user review.
 */
data class Note(
    val id: String,
    val title: String? = null,
    val blocks: List<NoteBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val source: NoteSource = NoteSource.MANUAL,
    val transcriptLanguage: String? = null,
    val reviewRequired: Boolean = false,
)
