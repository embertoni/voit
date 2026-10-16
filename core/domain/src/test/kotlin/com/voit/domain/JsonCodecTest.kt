package com.voit.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class JsonCodecTest {

    @Test
    fun stringsWithSpecialCharactersRoundTrip() {
        val tricky = "line1\nline2\t\"quoted\" \\slash\\ , comma: colon {brace}"
        val encoded = Json.write(mapOf("k" to tricky))
        val decoded = Json.parseObject(encoded)
        assertEquals(tricky, decoded["k"])
    }

    @Test
    fun nestedStructuresRoundTrip() {
        val value = mapOf(
            "num" to 42L,
            "flag" to true,
            "nothing" to null,
            "list" to listOf("a", "b", mapOf("c" to 3L)),
        )
        val decoded = Json.parseObject(Json.write(value))
        assertEquals("a", (decoded["list"] as List<*>)[0])
        assertEquals(42.0, decoded["num"])
        assertEquals(true, decoded["flag"])
        assertEquals(null, decoded["nothing"])
    }

    @Test
    fun integralNumbersSerializeWithoutDecimal() {
        assertEquals("""{"t":1700000000000}""", Json.write(mapOf("t" to 1_700_000_000_000L)))
    }

    @Test
    fun blockListRoundTripsLosslessly() {
        val blocks = listOf(
            NoteBlock.Heading(2, "Title with \"quotes\""),
            NoteBlock.Paragraph("A paragraph,\nwith a newline."),
            NoteBlock.Bullet("item 1"),
            NoteBlock.Task("do this", completed = true),
            NoteBlock.Code("fun main() {\n  println(\"hi\")\n}"),
            NoteBlock.Quote("quoted"),
        )
        val restored = NoteBlockCodec.decode(NoteBlockCodec.encode(blocks))
        assertEquals(blocks, restored)
    }

    @Test
    fun tagListRoundTripsWithTrickyTags() {
        val tags = listOf("work", "with,comma", "with\"quote")
        assertEquals(tags, TagListCodec.decode(TagListCodec.encode(tags)))
    }

    @Test
    fun emptyBlockAndTagStringsDecodeToEmpty() {
        assertEquals(emptyList(), NoteBlockCodec.decode(""))
        assertEquals(emptyList(), TagListCodec.decode(""))
    }

    @Test
    fun commandSchemaAndActionsRoundTrip() {
        val schema = ArgumentSchema(
            listOf(
                ArgumentSpec("title", ParamType.TEXT, required = true),
                ArgumentSpec("minutes", ParamType.DURATION),
            ),
        )
        assertEquals(schema, CommandCodec.decodeArgumentSchema(CommandCodec.encodeArgumentSchema(schema)))

        val actions = listOf(
            ActionRequest(
                ActionType.CREATE_NOTE,
                mapOf(
                    "title" to ParamValue.Text("Groceries"),
                    "count" to ParamValue.Number(3.5),
                    "flag" to ParamValue.Bool(true),
                    "dur" to ParamValue.Duration(300),
                    "ts" to ParamValue.Timestamp(1_700_000_000_000),
                    "tags" to ParamValue.Tags(listOf("a", "b")),
                ),
            ),
            ActionRequest(ActionType.SPEAK_RESPONSE),
        )
        assertEquals(actions, CommandCodec.decodeActions(CommandCodec.encodeActions(actions)))
    }
}
