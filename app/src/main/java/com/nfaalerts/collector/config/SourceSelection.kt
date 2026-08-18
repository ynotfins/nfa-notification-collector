package com.nfaalerts.collector.config

import com.nfaalerts.collector.capture.RawTextField

const val MAX_SELECTED_SOURCES = 10

data class SourceSelection(
    val packageName: String,
    val appLabel: String,
    val sourceId: String,
    val enabled: Boolean,
    val bnnMappingConfirmed: Boolean,
    val rawTextOrder: List<RawTextField>,
)

class AllowlistSnapshot private constructor(
    val selections: List<SourceSelection>,
    private val byPackage: Map<String, SourceSelection>,
) {
    val packageNames: Set<String> = selections.mapTo(linkedSetOf(), SourceSelection::packageName)

    fun sourceFor(packageName: String): SourceSelection? = byPackage[packageName]

    companion object {
        val EMPTY = from(emptyList())

        fun from(selections: List<SourceSelection>): AllowlistSnapshot {
            val immutable =
                selections
                    .map { it.copy(rawTextOrder = it.rawTextOrder.toList()) }
                    .sortedBy(SourceSelection::packageName)
            return AllowlistSnapshot(
                selections = immutable,
                byPackage = immutable.filter(SourceSelection::enabled).associateBy(SourceSelection::packageName),
            )
        }
    }
}
