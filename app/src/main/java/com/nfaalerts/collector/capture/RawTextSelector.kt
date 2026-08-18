package com.nfaalerts.collector.capture

enum class RawTextField(
    val configValue: String,
) {
    BIG_TEXT("bigText"),
    TEXT("text"),
    TEXT_LINES("textLines"),
    TICKER("ticker"),
    ;

    companion object {
        val DEFAULT_ORDER = listOf(BIG_TEXT, TEXT, TEXT_LINES, TICKER)
    }
}

data class RawTextSelection(
    val rawText: String?,
    val selectedField: RawTextField?,
    val candidates: Map<RawTextField, List<String>>,
)

object RawTextSelector {
    fun select(
        orderedFields: List<RawTextField>,
        candidates: Map<RawTextField, List<String>>,
    ): RawTextSelection {
        val preserved = candidates.mapValues { (_, values) -> values.toList() }
        val selected = orderedFields.firstOrNull { preserved[it]?.isNotEmpty() == true }
        return RawTextSelection(
            rawText = selected?.let { preserved.getValue(it).joinToString("\n") },
            selectedField = selected,
            candidates = preserved,
        )
    }
}
