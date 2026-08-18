package com.nfaalerts.collector.config

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

interface SourceSelectionStore {
    suspend fun load(): List<SourceSelection>

    suspend fun save(selections: List<SourceSelection>)
}

sealed interface SelectionUpdate {
    data object Accepted : SelectionUpdate

    data object BnnConfirmationRequired : SelectionUpdate

    data class MaximumReached(
        val maximum: Int,
    ) : SelectionUpdate
}

class SourceSelectionRepository(
    private val store: SourceSelectionStore,
) {
    private val mutationMutex = Mutex()
    private val current = AtomicReference(AllowlistSnapshot.EMPTY)

    fun snapshot(): AllowlistSnapshot = current.get()

    suspend fun load() =
        mutationMutex.withLock {
            current.set(AllowlistSnapshot.from(store.load().take(MAX_SELECTED_SOURCES)))
        }

    suspend fun upsert(selection: SourceSelection): SelectionUpdate =
        mutationMutex.withLock {
            if (selection.sourceId == BNN_SOURCE_ID && !selection.bnnMappingConfirmed) {
                return@withLock SelectionUpdate.BnnConfirmationRequired
            }
            val existing =
                current
                    .get()
                    .selections
                    .associateBy(SourceSelection::packageName)
                    .toMutableMap()
            if (selection.packageName !in existing && existing.size >= MAX_SELECTED_SOURCES) {
                return@withLock SelectionUpdate.MaximumReached(MAX_SELECTED_SOURCES)
            }
            existing[selection.packageName] = selection.copy(rawTextOrder = selection.rawTextOrder.toList())
            val updated = existing.values.sortedBy(SourceSelection::packageName)
            store.save(updated)
            current.set(AllowlistSnapshot.from(updated))
            SelectionUpdate.Accepted
        }

    suspend fun remove(packageName: String) =
        mutationMutex.withLock {
            val updated = current.get().selections.filterNot { it.packageName == packageName }
            store.save(updated)
            current.set(AllowlistSnapshot.from(updated))
        }

    private companion object {
        const val BNN_SOURCE_ID = "bnn"
    }
}
