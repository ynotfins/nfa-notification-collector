package com.nfaalerts.collector.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

sealed interface SelectionLoadState {
    data object NotLoaded : SelectionLoadState

    data object Loaded : SelectionLoadState

    data class Invalid(
        val code: String,
    ) : SelectionLoadState
}

class InvalidSelectionConfigException(
    val code: String,
) : IllegalArgumentException(code)

class SourceSelectionRepository(
    private val store: SourceSelectionStore,
) {
    private val mutationMutex = Mutex()
    private val current = AtomicReference(AllowlistSnapshot.EMPTY)
    private val mutableSelections = MutableStateFlow(AllowlistSnapshot.EMPTY)
    private val mutableLoadState = MutableStateFlow<SelectionLoadState>(SelectionLoadState.NotLoaded)
    val selections: StateFlow<AllowlistSnapshot> = mutableSelections.asStateFlow()
    val loadState: StateFlow<SelectionLoadState> = mutableLoadState.asStateFlow()

    fun snapshot(): AllowlistSnapshot = current.get()

    suspend fun load() =
        mutationMutex.withLock {
            try {
                val loaded = store.load()
                validateStoredSelections(loaded)
                publish(AllowlistSnapshot.from(loaded))
                mutableLoadState.value = SelectionLoadState.Loaded
            } catch (failure: InvalidSelectionConfigException) {
                publish(AllowlistSnapshot.EMPTY)
                mutableLoadState.value = SelectionLoadState.Invalid(failure.code)
            } catch (_: Exception) {
                publish(AllowlistSnapshot.EMPTY)
                mutableLoadState.value = SelectionLoadState.Invalid("INVALID_CONFIG")
            }
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
            publish(AllowlistSnapshot.from(updated))
            mutableLoadState.value = SelectionLoadState.Loaded
            SelectionUpdate.Accepted
        }

    suspend fun remove(packageName: String) =
        mutationMutex.withLock {
            val updated = current.get().selections.filterNot { it.packageName == packageName }
            store.save(updated)
            publish(AllowlistSnapshot.from(updated))
            mutableLoadState.value = SelectionLoadState.Loaded
        }

    private fun publish(snapshot: AllowlistSnapshot) {
        current.set(snapshot)
        mutableSelections.value = snapshot
    }

    private fun validateStoredSelections(selections: List<SourceSelection>) {
        if (selections.size > MAX_SELECTED_SOURCES) {
            throw InvalidSelectionConfigException("TOO_MANY_SOURCES")
        }
        if (selections.map(SourceSelection::packageName).toSet().size != selections.size) {
            throw InvalidSelectionConfigException("DUPLICATE_SOURCE_PACKAGE")
        }
        if (selections.any { it.packageName.isBlank() || it.sourceId.isBlank() || it.rawTextOrder.isEmpty() }) {
            throw InvalidSelectionConfigException("INVALID_SOURCE")
        }
        if (selections.any { it.sourceId == BNN_SOURCE_ID && !it.bnnMappingConfirmed }) {
            throw InvalidSelectionConfigException("BNN_CONFIRMATION_REQUIRED")
        }
    }

    private companion object {
        const val BNN_SOURCE_ID = "bnn"
    }
}
