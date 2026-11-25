package com.nfaalerts.collector.config

import com.nfaalerts.collector.capture.RawTextField

/**
 * BNN is the first-class source for this collector. The operator's standing
 * instruction is that BNN capture returns automatically if the source list is
 * reset by a test, a reinstall, or a fresh config. Other sources are never
 * removed by this restore; they are only added or edited by the operator.
 */
object DefaultSources {
    const val BNN_PACKAGE = "us.bnn.newsapp"
    const val BNN_LABEL = "BNN"
    const val BNN_SOURCE_ID = "bnn"

    fun bnn(): SourceSelection =
        SourceSelection(
            packageName = BNN_PACKAGE,
            appLabel = BNN_LABEL,
            sourceId = BNN_SOURCE_ID,
            enabled = true,
            bnnMappingConfirmed = true,
            rawTextOrder = RawTextField.DEFAULT_ORDER,
        )

    fun missingBnn(
        selections: List<SourceSelection>,
        bnnInstalled: Boolean,
    ): SourceSelection? =
        if (bnnInstalled && selections.none { it.packageName == BNN_PACKAGE }) {
            bnn()
        } else {
            null
        }
}
