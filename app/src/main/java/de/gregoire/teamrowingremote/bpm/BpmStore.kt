package de.gregoire.teamrowingremote.bpm

import android.content.Context
import android.util.AtomicFile
import de.gregoire.teamrowingremote.sync.BpmSyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * BPM per track URI as synced from the display app, where sessions are prepared, stored as a JSON
 * file. The copy here is also sent back on each connection, so the display app can recover its data.
 */
class BpmStore(context: Context) : BpmSyncStore {

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val mutex = Mutex()
    private var loaded = false

    private val _entries = MutableStateFlow<Map<String, BpmEntry>>(emptyMap())
    val entries: StateFlow<Map<String, BpmEntry>> = _entries.asStateFlow()

    /** Entries aren't changed on this device yet. */
    override val localChanges: Flow<Map<String, BpmEntry>> = emptyFlow()

    /** Reads the stored entries, to show them before the display app is connected. */
    suspend fun load() = mutex.withLock { loadIfNeeded() }

    override suspend fun snapshot(): Map<String, BpmEntry> = mutex.withLock {
        loadIfNeeded()
        _entries.value
    }

    override suspend fun merge(incoming: Map<String, BpmEntry>) = mutex.withLock {
        loadIfNeeded()
        val previous = _entries.value
        val merged = previous + incoming.map { (uri, entry) -> uri to (previous[uri]?.mergedWith(entry) ?: entry) }
        if (merged != previous) {
            _entries.value = merged
            withContext(Dispatchers.IO) { save(merged) }
        }
    }

    /** Must be called with [mutex] held. */
    private suspend fun loadIfNeeded() {
        if (loaded) return
        _entries.value = withContext(Dispatchers.IO) {
            if (file.baseFile.exists()) bpmEntriesFromJson(JSONObject(file.readFully().decodeToString())) else emptyMap()
        }
        loaded = true
    }

    private fun save(entries: Map<String, BpmEntry>) {
        val stream = file.startWrite()
        try {
            stream.write(bpmEntriesToJson(entries).toString().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    private companion object {
        const val FILE_NAME = "bpm.json"
    }
}
