package com.voit.interpretation

import com.voit.domain.NoteBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FormattingParserTest {

    private val parser = FormattingParser()

    @Test
    fun headingDirectiveProducesHeadingBlock() {
        val doc = parser.parse("title: Shopping list")
        assertEquals(NoteBlock.Heading(1, "shopping list"), doc.documentBlocks.first())
    }

    @Test
    fun headingKeyword() {
        val doc = parser.parse("heading Weekly goals")
        assertEquals(NoteBlock.Heading(1, "weekly goals"), doc.documentBlocks.first())
    }

    @Test
    fun plainTextBecomesParagraph() {
        val doc = parser.parse("this is a simple note")
        assertEquals(listOf<NoteBlock>(NoteBlock.Paragraph("this is a simple note")), doc.documentBlocks)
    }

    @Test
    fun newParagraphSplitsBlocks() {
        val doc = parser.parse("first thought new paragraph second thought")
        assertEquals(
            listOf<NoteBlock>(
                NoteBlock.Paragraph("first thought"),
                NoteBlock.Paragraph("second thought"),
            ),
            doc.documentBlocks,
        )
    }

    @Test
    fun newLineKeepsSameParagraphWithBreak() {
        val doc = parser.parse("line one new line line two")
        assertEquals(listOf<NoteBlock>(NoteBlock.Paragraph("line one\nline two")), doc.documentBlocks)
    }

    @Test
    fun bulletPointProducesBullet() {
        val doc = parser.parse("bullet point milk bullet point eggs")
        assertEquals(
            listOf<NoteBlock>(NoteBlock.Bullet("milk"), NoteBlock.Bullet("eggs")),
            doc.documentBlocks,
        )
    }

    @Test
    fun numberedListAndNextItem() {
        val doc = parser.parse("numbered list first next item second")
        assertEquals(
            listOf<NoteBlock>(NoteBlock.Bullet("first"), NoteBlock.Bullet("second")),
            doc.documentBlocks,
        )
    }

    @Test
    fun checkboxProducesUncheckedTask() {
        val doc = parser.parse("checkbox buy milk")
        assertEquals(NoteBlock.Task("buy milk", completed = false), doc.documentBlocks.first())
    }

    @Test
    fun taskItemProducesUncheckedTask() {
        val doc = parser.parse("task item call the dentist")
        assertEquals(NoteBlock.Task("call the dentist", completed = false), doc.documentBlocks.first())
    }

    @Test
    fun quoteProducesQuote() {
        val doc = parser.parse("quote to be or not to be new paragraph done")
        assertTrue(doc.documentBlocks.contains(NoteBlock.Quote("to be or not to be")))
    }

    @Test
    fun codeBlockIsLiteralAndNotRewritten() {
        val doc = parser.parse("code block new paragraph should stay literal end code block")
        val code = doc.documentBlocks.filterIsInstance<NoteBlock.Code>().single()
        // "new paragraph" inside a code block must NOT be interpreted as a directive.
        assertEquals("new paragraph should stay literal", code.text)
        assertFalse(doc.reviewRequired)
    }

    @Test
    fun unclosedCodeBlockFlagsReview() {
        val doc = parser.parse("code block some unterminated code")
        assertTrue(doc.documentBlocks.any { it is NoteBlock.Code })
        assertTrue(doc.reviewRequired)
    }

    @Test
    fun tagGoesToTagsNotBody() {
        val doc = parser.parse("remember the plan tag work")
        assertEquals(listOf("work"), doc.tags)
        val bodyText = doc.documentBlocks.filterIsInstance<NoteBlock.Paragraph>().joinToString { it.text }
        assertFalse(bodyText.contains("work"))
        assertTrue(bodyText.contains("remember the plan"))
    }

    @Test
    fun matchedBoldWrapsText() {
        val doc = parser.parse("this is bold very important end bold text")
        val para = doc.documentBlocks.filterIsInstance<NoteBlock.Paragraph>().single()
        assertTrue(para.text.contains("**very important**"))
        assertFalse(doc.reviewRequired)
    }

    @Test
    fun unmatchedBoldPreservesContentAndFlagsReview() {
        val doc = parser.parse("start bold important but never closed")
        val para = doc.documentBlocks.filterIsInstance<NoteBlock.Paragraph>().single()
        // Content preserved, nothing discarded.
        assertTrue(para.text.contains("important but never closed"))
        assertTrue(doc.reviewRequired)
    }

    @Test
    fun italicMatched() {
        val doc = parser.parse("say italic hello end italic now")
        val para = doc.documentBlocks.filterIsInstance<NoteBlock.Paragraph>().single()
        assertTrue(para.text.contains("*hello*"))
    }

    @Test
    fun newNoteStartsFreshDraft() {
        val doc = parser.parse("old stuff new note fresh content")
        assertTrue(doc.startedNewNote)
        val text = doc.documentBlocks.filterIsInstance<NoteBlock.Paragraph>().joinToString { it.text }
        assertTrue(text.contains("fresh content"))
        assertFalse(text.contains("old stuff"))
    }

    @Test
    fun saveUndoCancelDirectives() {
        assertEquals(DocumentCommand.SAVE_NOTE, parser.parse("some note save note").command)
        assertEquals(DocumentCommand.UNDO_LAST_CHANGE, parser.parse("undo last change").command)
        assertEquals(DocumentCommand.CANCEL_NOTE, parser.parse("cancel note").command)
    }

    @Test
    fun literalModeDoesNotInterpretDirectives() {
        val doc = parser.parse("bullet point not a bullet", literalMode = true)
        assertEquals(listOf<NoteBlock>(NoteBlock.Paragraph("bullet point not a bullet")), doc.documentBlocks)
    }

    @Test
    fun allThreeRepresentationsRetained() {
        val doc = parser.parse("Title: Hello")
        assertEquals("Title: Hello", doc.rawTranscript)
        assertEquals("title: hello", doc.normalizedTranscript)
        assertTrue(doc.documentBlocks.isNotEmpty())
    }
}
