package com.voit.domain

/**
 * Serializes a command's [ArgumentSchema] and ordered [ActionRequest] list to/from single
 * JSON strings, losslessly. Shared by the Room entity converters and backup serializer so
 * the on-disk and in-backup representations agree.
 */
object CommandCodec {

    // ---- argument schema --------------------------------------------------------

    fun encodeArgumentSchema(schema: ArgumentSchema): String =
        Json.write(
            schema.specs.map {
                mapOf("name" to it.name, "type" to it.type.name, "required" to it.required)
            },
        )

    fun decodeArgumentSchema(json: String): ArgumentSchema {
        if (json.isBlank()) return ArgumentSchema()
        val specs = Json.parseArray(json).map { raw ->
            val m = raw.asStringMap()
            ArgumentSpec(
                name = m["name"] as String,
                type = ParamType.valueOf(m["type"] as String),
                required = (m["required"] as? Boolean) ?: false,
            )
        }
        return ArgumentSchema(specs)
    }

    // ---- action sequence --------------------------------------------------------

    fun encodeActions(actions: List<ActionRequest>): String =
        Json.write(
            actions.map { req ->
                mapOf(
                    "type" to req.type.name,
                    "args" to req.args.mapValues { (_, v) -> paramToMap(v) },
                )
            },
        )

    fun decodeActions(json: String): List<ActionRequest> {
        if (json.isBlank()) return emptyList()
        return Json.parseArray(json).map { raw ->
            val m = raw.asStringMap()
            val argsMap = (m["args"] as? Map<*, *>).orEmpty()
            ActionRequest(
                type = ActionType.valueOf(m["type"] as String),
                args = argsMap.entries.associate { (k, v) ->
                    k.toString() to paramFromMap(v.asStringMap())
                },
            )
        }
    }

    // ---- ParamValue -------------------------------------------------------------

    private fun paramToMap(value: ParamValue): Map<String, Any?> = when (value) {
        is ParamValue.Text -> mapOf("kind" to "text", "value" to value.value)
        is ParamValue.Number -> mapOf("kind" to "number", "value" to value.value)
        is ParamValue.Bool -> mapOf("kind" to "bool", "value" to value.value)
        is ParamValue.Duration -> mapOf("kind" to "duration", "value" to value.seconds)
        is ParamValue.Timestamp -> mapOf("kind" to "timestamp", "value" to value.epochMillis)
        is ParamValue.Tags -> mapOf("kind" to "tags", "value" to value.values)
    }

    private fun paramFromMap(m: Map<String, Any?>): ParamValue = when (m["kind"] as String) {
        "text" -> ParamValue.Text(m["value"] as String)
        "number" -> ParamValue.Number(m["value"] as Double)
        "bool" -> ParamValue.Bool(m["value"] as Boolean)
        "duration" -> ParamValue.Duration((m["value"] as Double).toLong())
        "timestamp" -> ParamValue.Timestamp((m["value"] as Double).toLong())
        "tags" -> ParamValue.Tags((m["value"] as List<*>).map { it as String })
        else -> error("Unknown ParamValue kind: ${m["kind"]}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asStringMap(): Map<String, Any?> =
        (this as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
            ?: error("Expected a JSON object")

    private fun Map<*, *>?.orEmpty(): Map<*, *> = this ?: emptyMap<Any?, Any?>()
}
