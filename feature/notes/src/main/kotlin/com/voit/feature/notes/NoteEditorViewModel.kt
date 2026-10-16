package com.voit.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voit.domain.Clock
import com.voit.domain.Note
import com.voit.domain.NoteBlock
import com.voit.domain.NoteExporter
import com.voit.domain.NoteRepository
import com.voit.domain.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Editor state for a single note.
 *
 * @param note the note being edited, or null while loading / if not found.
 * @param dirty whether unsaved edits are pending.
 * @param exportText a markdown export requested by the user (shown/shared by the UI).
 */
data class NoteEditorUiState(
    val note: Note? = null,
    val loading: Boolean = true,
    val dirty: Boolean = false,
    val exportText: String? = null,
)

/**
 * Edits a single note: title, block text, task-checkbox toggles, and tags. Persists via the
 * [NoteRepository]. [scope] is injectable for JVM unit tests.
 */
class NoteEditorViewModel(
    private val repository: NoteRepository,
    private val noteId: String,
    private val clock: Clock = SystemClock,
    scope: CoroutineScope? = null,
) : ViewModel() {

    private val workScope: CoroutineScope = scope ?: viewModelScope

    private val _uiState = MutableStateFlow(NoteEditorUiState())
    val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()

    init {
        workScope.launch {
            val note = repository.getNote(noteId)
            _uiState.value = NoteEditorUiState(note = note, loading = false)
        }
    }

    fun onTitleChange(title: String) = mutate { it.copy(title = title.ifBlank { null }) }

    fun onBlockTextChange(index: Int, text: String) = mutate { note ->
        val blocks = note.blocks.toMutableList()
        blocks.getOrNull(index)?.let { blocks[index] = withText(it, text) }
        note.copy(blocks = blocks)
    }

    /** Toggle a task-checkbox block's completed state. */
    fun toggleTask(index: Int) = mutate { note ->
        val blocks = note.blocks.toMutableList()
        (blocks.getOrNull(index) as? NoteBlock.Task)?.let {
            blocks[index] = it.copy(completed = !it.completed)
        }
        note.copy(blocks = blocks)
    }

    fun setTags(tags: List<String>) = mutate { it.copy(tags = tags.map(String::trim).filter(String::isNotEmpty)) }

    /** Append an empty paragraph block the user can type into. */
    fun addParagraph() = mutate { it.copy(blocks = it.blocks + NoteBlock.Paragraph("")) }

    fun save() {
        val note = _uiState.value.note ?: return
        workScope.launch {
            repository.update(note.copy(updatedAt = clock.nowEpochMillis()))
            _uiState.value = _uiState.value.copy(dirty = false)
        }
    }

    fun requestExport() {
        val note = _uiState.value.note ?: return
        _uiState.value = _uiState.value.copy(exportText = NoteExporter.toMarkdown(note))
    }

    fun clearExport() {
        _uiState.value = _uiState.value.copy(exportText = null)
    }

    private fun mutate(transform: (Note) -> Note) {
        val current = _uiState.value.note ?: return
        _uiState.value = _uiState.value.copy(note = transform(current), dirty = true)
    }

    private fun withText(block: NoteBlock, text: String): NoteBlock = when (block) {
        is NoteBlock.Paragraph -> block.copy(text = text)
        is NoteBlock.Heading -> block.copy(text = text)
        is NoteBlock.Bullet -> block.copy(text = text)
        is NoteBlock.Task -> block.copy(text = text)
        is NoteBlock.Code -> block.copy(text = text)
        is NoteBlock.Quote -> block.copy(text = text)
    }
}
