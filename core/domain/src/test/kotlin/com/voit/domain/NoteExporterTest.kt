package com.voit.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteExporterTest {

    private val note = Note(
        id = "n1",
        title = "Groceries",
        blocks = listOf(
            NoteBlock.Heading(2, "Produce"),
            NoteBlock.Bullet("Apples"),
            NoteBlock.Task("Buy milk", completed = false),
            NoteBlock.Task("Buy eggs", completed = true),
            NoteBlock.Paragraph("Remember coupons."),
            NoteBlock.Code("print('x')"),
            NoteBlock.Quote("stay frugal"),
        ),
        tags = listOf("shopping", "home"),
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Test
    fun markdownRendersBlocksAndTags() {
        val md = NoteExporter.toMarkdown(note)
        assertTrue(md.contains("# Groceries"))
        assertTrue(md.contains("## Produce"))
        assertTrue(md.contains("- Apples"))
        assertTrue(md.contains("- [ ] Buy milk"))
        assertTrue(md.contains("- [x] Buy eggs"))
        assertTrue(md.contains("```\nprint('x')\n```"))
        assertTrue(md.contains("> stay frugal"))
        assertTrue(md.contains("#shopping #home"))
    }

    @Test
    fun plainTextStripsMarkup() {
        val txt = NoteExporter.toPlainText(note)
        assertTrue(txt.startsWith("Groceries"))
        assertTrue(txt.contains("Produce"))
        assertFalse(txt.contains("## Produce"))
        assertTrue(txt.contains("[x] Buy eggs"))
        assertTrue(txt.contains("Tags: shopping, home"))
    }

    @Test
    fun untitledNoteOmitsHeadingLine() {
        val n = note.copy(title = null)
        val md = NoteExporter.toMarkdown(n)
        assertFalse(md.startsWith("# "))
    }

    private fun assertFalse(condition: Boolean) = assertEquals(false, condition)
}
