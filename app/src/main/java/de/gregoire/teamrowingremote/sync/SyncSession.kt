package de.gregoire.teamrowingremote.sync

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import de.gregoire.teamrowingremote.bpm.BpmEntry
import de.gregoire.teamrowingremote.bpm.bpmEntriesFromJson
import de.gregoire.teamrowingremote.bpm.bpmEntriesToJson
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.util.UUID

// Kept identical in teamrowing-display and teamrowing-remote, which sync over Bluetooth.

/** The BPM data a sync session exchanges with the other app. */
interface BpmSyncStore {
    /** Entries changed on this device, e.g. by the trainer; changes merged from the other app aren't emitted. */
    val localChanges: Flow<Map<String, BpmEntry>>

    suspend fun snapshot(): Map<String, BpmEntry>

    suspend fun merge(incoming: Map<String, BpmEntry>)
}

/** The playback state the remote app sends to the display app on every change. */
data class PlaybackUpdate(
    /** The current track's URI, or null if nothing is playing. */
    val trackUri: String?,
    val title: String,
    val artists: String,
    val durationMs: Long,
    val positionMs: Long,
    val isPaused: Boolean,
    /** What is being played, e.g. `spotify:playlist:…`, or null if unknown. */
    val contextUri: String?,
    /** [SystemClock.elapsedRealtime] when [positionMs] was current; only meaningful on this device. */
    val positionAt: Long = SystemClock.elapsedRealtime(),
) {
    /** The position now, assuming playback continued since [positionAt]. */
    fun currentPositionMs(): Long =
        if (isPaused) positionMs else (positionMs + SystemClock.elapsedRealtime() - positionAt).coerceAtMost(durationMs)
}

/** Playback updates a sync session sends to or receives from the other app. */
interface PlaybackSyncPeer {
    /** Updates to send; only the remote app has any. */
    val outgoing: Flow<PlaybackUpdate>

    fun received(update: PlaybackUpdate)
}

sealed interface SyncMessage {
    data class Entries(val entries: Map<String, BpmEntry>) : SyncMessage
    data class Playback(val update: PlaybackUpdate) : SyncMessage
}

object SyncProtocol {
    /** RFCOMM service of the display app, which the remote connects to. */
    val SERVICE_UUID: UUID = UUID.fromString("8f3c2a71-5b6e-4d2a-9c1e-7a4b3d2e1f60")
    const val SERVICE_NAME = "Teamrowing BPM sync"
    const val VERSION = 1

    /**
     * One message per line, either `{"version":1,"entries":{<track URI>:{…}}}` or
     * `{"version":1,"playback":{…}}`. Apps that don't know a message type ignore it.
     */
    fun encode(message: SyncMessage): String {
        val json = JSONObject().put("version", VERSION)
        when (message) {
            is SyncMessage.Entries -> json.put("entries", bpmEntriesToJson(message.entries))
            is SyncMessage.Playback -> json.put("playback", playbackToJson(message.update))
        }
        return json.toString()
    }

    /** The message, or null for an unreadable or unknown one. */
    fun decode(line: String): SyncMessage? = try {
        val json = JSONObject(line)
        when {
            json.optInt("version") != VERSION -> null
            json.has("entries") -> SyncMessage.Entries(bpmEntriesFromJson(json.getJSONObject("entries")))
            json.has("playback") -> SyncMessage.Playback(playbackFromJson(json.getJSONObject("playback")))
            else -> null
        }
    } catch (e: JSONException) {
        null
    }

    private fun playbackToJson(update: PlaybackUpdate): JSONObject =
        JSONObject()
            .putOpt("trackUri", update.trackUri)
            .put("title", update.title)
            .put("artists", update.artists)
            .put("durationMs", update.durationMs)
            // Sent as of now, as the receiver can't use this device's clock
            .put("positionMs", update.currentPositionMs())
            .put("isPaused", update.isPaused)
            .putOpt("contextUri", update.contextUri)

    private fun playbackFromJson(json: JSONObject) = PlaybackUpdate(
        trackUri = json.optString("trackUri").takeIf { it.isNotEmpty() },
        title = json.optString("title"),
        artists = json.optString("artists"),
        durationMs = json.optLong("durationMs"),
        positionMs = json.optLong("positionMs"),
        isPaused = json.optBoolean("isPaused", true),
        contextUri = json.optString("contextUri").takeIf { it.isNotEmpty() },
    )
}

/** Suspends until cancelled, then closes [closeable], e.g. to interrupt a blocking socket call elsewhere. */
suspend fun closeOnCancellation(closeable: Closeable) {
    try {
        awaitCancellation()
    } finally {
        closeable.close()
    }
}

/**
 * Syncs over a connected socket: both sides first send all their BPM entries, then each local
 * change as it happens; the remote app also sends each playback change. Returns when the other side
 * disconnects; throws IOException on errors.
 */
class SyncSession(
    private val socket: BluetoothSocket,
    private val store: BpmSyncStore,
    private val playback: PlaybackSyncPeer,
) {

    private val writeMutex = Mutex()

    suspend fun run() = coroutineScope {
        // Closing the socket is the only way to interrupt its blocking reads when cancelled
        val closer = launch { closeOnCancellation(socket) }
        val writer = socket.outputStream.bufferedWriter()

        // Collect local changes before taking the snapshot, so none are lost in between
        val entriesSender = launch(start = CoroutineStart.UNDISPATCHED) {
            store.localChanges.collect { send(writer, SyncMessage.Entries(it)) }
        }
        val playbackSender = launch {
            playback.outgoing.collect { send(writer, SyncMessage.Playback(it)) }
        }
        send(writer, SyncMessage.Entries(store.snapshot()))

        withContext(Dispatchers.IO) {
            val reader = socket.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                when (val message = SyncProtocol.decode(line)) {
                    is SyncMessage.Entries -> store.merge(message.entries)
                    is SyncMessage.Playback -> playback.received(message.update)
                    null -> Unit
                }
            }
        }
        // The other side disconnected
        entriesSender.cancel()
        playbackSender.cancel()
        closer.cancel()
    }

    private suspend fun send(writer: BufferedWriter, message: SyncMessage) {
        if (message is SyncMessage.Entries && message.entries.isEmpty()) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                writer.write(SyncProtocol.encode(message))
                writer.newLine()
                writer.flush()
            }
        }
    }
}
