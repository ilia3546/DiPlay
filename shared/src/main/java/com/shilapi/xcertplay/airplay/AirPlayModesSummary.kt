package com.shilapi.xcertplay.airplay

/**
 * The resource owners and app states of an iPhone modesChanged command on one log line, so the session log
 * shows who holds the screen and the main audio. Only numbers, booleans and short plain words are written.
 */
internal object AirPlayModesSummary {
    private val resourceNames = mapOf(1 to "screen", 2 to "mainAudio")
    private val plainWord = Regex("[A-Za-z0-9_.-]{1,24}")

    fun describe(params: Map<String, Any?>): String =
        "resources=${entries(params["resources"], "resourceID", resourceNames)} " +
            "appStates=${entries(params["appStates"], "appStateID", emptyMap())}"

    private fun entries(value: Any?, idKey: String, names: Map<Int, String>): String {
        val list = value as? List<*> ?: return "none"
        return list.joinToString(",", "[", "]") { item ->
            val entry = item as? Map<*, *> ?: return@joinToString "?"
            val id = (entry[idKey] as? Number)?.toInt()
            val label = id?.let { names[it] ?: it.toString() } ?: "?"
            val fields = entry.entries.filter { it.key != idKey }.sortedBy { it.key.toString() }
                .joinToString(",") { "${it.key}=${scalar(it.value)}" }
            "$label{$fields}"
        }
    }

    private fun scalar(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is String -> value.takeIf { plainWord.matches(it) } ?: "?"
        else -> "?"
    }
}
