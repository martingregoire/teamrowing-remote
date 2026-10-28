package de.gregoire.teamrowingremote.prepare

import de.gregoire.teamrowingremote.spotify.Playlist
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

private val SESSION_NAME = Regex("""^TR (\d{2}\.\d{2}\.\d{4})\b""")
// Strict, so that e.g. 31.02. isn't taken as the end of February
private val SESSION_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

/** The date of a training session playlist named like `TR 28.10.2026`, or null for other playlists. */
fun sessionDate(name: String): LocalDate? {
    val date = SESSION_NAME.find(name)?.groupValues?.get(1) ?: return null
    return try {
        LocalDate.parse(date, SESSION_DATE)
    } catch (e: DateTimeParseException) {
        null
    }
}

/** The name of a new session playlist on [date], e.g. `TR 28.10.2026`. */
fun sessionName(date: LocalDate): String = "TR " + date.format(SESSION_DATE)

data class PastSession(
    val playlist: Playlist,
    val date: LocalDate,
    val trackUris: Set<String>,
)

/** The training sessions so far, to tell which tracks were used recently. */
class SessionHistory(sessions: List<PastSession>) {

    /** Newest first. */
    private val sessions = sessions.sortedByDescending { it.date }

    /** The session before the one prepared. */
    val lastSession: PastSession?
        get() = sessions.firstOrNull()

    /** The sessions before [date], i.e. before the session on that date; all for null. */
    fun before(date: LocalDate?): SessionHistory =
        if (date == null) this else SessionHistory(sessions.filter { it.date < date })

    fun isInLastSession(uri: String): Boolean = lastSession?.trackUris?.contains(uri) == true

    /** The newest session with the track, or null if it was never used. */
    fun lastUsed(uri: String): PastSession? = sessions.firstOrNull { uri in it.trackUris }
}
