package de.gregoire.teamrowingremote.sync

import android.bluetooth.BluetoothSocket
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

// Kept identical in teamrowing-display and teamrowing-remote, which sync BPM entries over Bluetooth.

/** The BPM data a sync session exchanges with the other app. */
interface BpmSyncStore {
    /** Entries changed on this device, e.g. by the trainer; changes merged from the other app aren't emitted. */
    val localChanges: Flow<Map<String, BpmEntry>>

    suspend fun snapshot(): Map<String, BpmEntry>

    suspend fun merge(incoming: Map<String, BpmEntry>)
}

object BpmSyncProtocol {
    /** RFCOMM service of the display app, which the remote connects to. */
    val SERVICE_UUID: UUID = UUID.fromString("8f3c2a71-5b6e-4d2a-9c1e-7a4b3d2e1f60")
    const val SERVICE_NAME = "Teamrowing BPM sync"
    const val VERSION = 1

    /** One message per line: `{"version":1,"entries":{<track URI>:{…}}}`. */
    fun encode(entries: Map<String, BpmEntry>): String =
        JSONObject()
            .put("version", VERSION)
            .put("entries", bpmEntriesToJson(entries))
            .toString()

    /** The entries of a message, or null for an unreadable message or one of an unknown version. */
    fun decode(line: String): Map<String, BpmEntry>? = try {
        val json = JSONObject(line)
        if (json.optInt("version") == VERSION) bpmEntriesFromJson(json.getJSONObject("entries")) else null
    } catch (e: JSONException) {
        null
    }
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
 * Syncs BPM entries over a connected socket: both sides first send all their entries, then each
 * local change as it happens. Returns when the other side disconnects; throws IOException on errors.
 */
class BpmSyncSession(private val socket: BluetoothSocket, private val store: BpmSyncStore) {

    private val writeMutex = Mutex()

    suspend fun run() = coroutineScope {
        // Closing the socket is the only way to interrupt its blocking reads when cancelled
        val closer = launch { closeOnCancellation(socket) }
        val writer = socket.outputStream.bufferedWriter()

        // Collect local changes before taking the snapshot, so none are lost in between
        val sender = launch(start = CoroutineStart.UNDISPATCHED) {
            store.localChanges.collect { send(writer, it) }
        }
        send(writer, store.snapshot())

        withContext(Dispatchers.IO) {
            val reader = socket.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                BpmSyncProtocol.decode(line)?.let { store.merge(it) }
            }
        }
        // The other side disconnected
        sender.cancel()
        closer.cancel()
    }

    private suspend fun send(writer: BufferedWriter, entries: Map<String, BpmEntry>) {
        if (entries.isEmpty()) return
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                writer.write(BpmSyncProtocol.encode(entries))
                writer.newLine()
                writer.flush()
            }
        }
    }
}
