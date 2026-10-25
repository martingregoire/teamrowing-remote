package de.gregoire.teamrowingremote.bpm

import org.json.JSONObject
import java.text.DecimalFormat

// Kept identical in teamrowing-display and teamrowing-remote, which sync these entries over Bluetooth.

enum class BpmSource(val label: String) {
    RECCOBEATS("ReccoBeats"),
    DEEZER("Deezer"),
}

data class BpmEntry(
    /** BPM from a lookup service, or null if no service knows the track. */
    val detectedBpm: Double? = null,
    val source: BpmSource? = null,
    /** The trainer's value, which replaces the detected one. */
    val manualBpm: Double? = null,
    /** Set by the trainer for tracks shown without tempo, e.g. for stretching. */
    val hidden: Boolean = false,
    /** When the trainer last changed [manualBpm] or [hidden], in epoch milliseconds; 0 if never. */
    val editedAt: Long = 0,
) {
    val bpm: Double?
        get() = manualBpm ?: detectedBpm

    /**
     * Combines this entry with the same track's entry from another device: a detected BPM is taken
     * from whichever has one, and the trainer's changes from the more recently edited one.
     */
    fun mergedWith(other: BpmEntry): BpmEntry {
        val detected = if (detectedBpm != null) this else other
        val edited = if (other.editedAt > editedAt) other else this
        return BpmEntry(
            detectedBpm = detected.detectedBpm,
            source = detected.source,
            manualBpm = edited.manualBpm,
            hidden = edited.hidden,
            editedAt = edited.editedAt,
        )
    }
}

/** Formats a BPM value with at most one decimal, using the device's decimal separator. */
fun formatBpm(bpm: Double): String = DecimalFormat("0.#").format(bpm)

/** BPM entries by track URI as JSON, as stored in a file and sent to the other app. */
fun bpmEntriesToJson(entries: Map<String, BpmEntry>): JSONObject {
    val json = JSONObject()
    entries.forEach { (uri, entry) ->
        json.put(
            uri,
            JSONObject()
                .putOpt(KEY_DETECTED, entry.detectedBpm)
                .putOpt(KEY_SOURCE, entry.source?.name)
                .putOpt(KEY_MANUAL, entry.manualBpm)
                .putOpt(KEY_HIDDEN, entry.hidden.takeIf { it })
                .putOpt(KEY_EDITED_AT, entry.editedAt.takeIf { it > 0 })
        )
    }
    return json
}

fun bpmEntriesFromJson(json: JSONObject): Map<String, BpmEntry> =
    json.keys().asSequence().associateWith { uri ->
        val entry = json.getJSONObject(uri)
        BpmEntry(
            detectedBpm = if (entry.has(KEY_DETECTED)) entry.getDouble(KEY_DETECTED) else null,
            source = entry.optString(KEY_SOURCE).let { name -> BpmSource.entries.firstOrNull { it.name == name } },
            manualBpm = if (entry.has(KEY_MANUAL)) entry.getDouble(KEY_MANUAL) else null,
            hidden = entry.optBoolean(KEY_HIDDEN),
            editedAt = entry.optLong(KEY_EDITED_AT),
        )
    }

private const val KEY_DETECTED = "detected"
private const val KEY_SOURCE = "source"
private const val KEY_MANUAL = "manual"
private const val KEY_HIDDEN = "hidden"
private const val KEY_EDITED_AT = "editedAt"
