package de.gregoire.teamrowingremote.bpm

import android.content.Context
import android.util.AtomicFile
import de.gregoire.teamrowingremote.spotify.Track
import de.gregoire.teamrowingremote.sync.BpmSyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * BPM per track URI, synced with the display app and stored as a JSON file. The trainer can correct
 * them here too. The copy here is sent on each connection, so the display app gets edits made while
 * disconnected, and can recover its data. Tracks of sessions being prepared are looked up here, the
 * others in the display app.
 */
class BpmStore(context: Context, private val lookup: BpmLookup = BpmLookup()) : BpmSyncStore {

    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val mutex = Mutex()
    private val lookupMutex = Mutex()
    private var loaded = false
    private var lastFailedLookupAt = 0L

    private val _entries = MutableStateFlow<Map<String, BpmEntry>>(emptyMap())
    val entries: StateFlow<Map<String, BpmEntry>> = _entries.asStateFlow()

    private val _localChanges = MutableSharedFlow<Map<String, BpmEntry>>(extraBufferCapacity = 64)
    override val localChanges: SharedFlow<Map<String, BpmEntry>> = _localChanges.asSharedFlow()

    /** Reads the stored entries, to show them before the display app is connected. */
    suspend fun load() = mutex.withLock { loadIfNeeded() }

    /**
     * Saves the trainer's tempo and "No tempo" for a track in one edit; a null tempo, or the
     * detected one, removes the correction.
     */
    suspend fun saveEdit(uri: String, manualBpm: Double?, hidden: Boolean) = update { entries ->
        val entry = entries[uri] ?: BpmEntry()
        val changed = entry.withManualBpm(manualBpm).copy(hidden = hidden)
        // Stamped with the time for merging with the display app
        if (changed == entry) entries else entries + (uri to changed.copy(editedAt = System.currentTimeMillis()))
    }

    /**
     * Looks up the tracks that have no entry yet. Returns false if a service failed;
     * the lookup is then retried at the earliest after [RETRY_DELAY_MS].
     */
    suspend fun lookUpMissing(tracks: List<Track>): Boolean = lookupMutex.withLock {
        mutex.withLock { loadIfNeeded() }
        val missing = tracks.filter { it.uri !in _entries.value }.distinctBy { it.uri }
        if (missing.isEmpty()) return true
        if (System.currentTimeMillis() < lastFailedLookupAt + RETRY_DELAY_MS) return false

        val result = lookup.lookUp(missing)
        if (!result.complete) lastFailedLookupAt = System.currentTimeMillis()
        val newEntries = missing.mapNotNull { track ->
            val detected = result.found[track.uri]
            when {
                detected != null -> track.uri to BpmEntry(detected.bpm, detected.source)
                result.complete -> track.uri to BpmEntry()
                else -> null
            }
        }
        // Merge, as the display app may have sent entries for these tracks during the lookup
        update { entries -> entries + newEntries.map { (uri, entry) -> uri to (entries[uri]?.mergedWith(entry) ?: entry) } }
        result.complete
    }

    override suspend fun snapshot(): Map<String, BpmEntry> = mutex.withLock {
        loadIfNeeded()
        _entries.value
    }

    override suspend fun merge(incoming: Map<String, BpmEntry>) = update(fromSync = true) { entries ->
        entries + incoming.map { (uri, entry) -> uri to (entries[uri]?.mergedWith(entry) ?: entry) }
    }

    private suspend fun update(
        fromSync: Boolean = false,
        transform: (Map<String, BpmEntry>) -> Map<String, BpmEntry>,
    ) = mutex.withLock {
        loadIfNeeded()
        val previous = _entries.value
        val updated = transform(previous)
        if (updated == previous) return@withLock
        _entries.value = updated
        withContext(Dispatchers.IO) { save(updated) }
        if (!fromSync) {
            _localChanges.tryEmit(updated.filter { (uri, entry) -> previous[uri] != entry })
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
        const val RETRY_DELAY_MS = 60_000L
    }
}
