package de.gregoire.teamrowingremote.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HttpResponse(
    val status: Int,
    val body: String,
    /** Seconds to wait before the next request, sent by servers when rate limiting (HTTP 429). */
    val retryAfterSeconds: Long?,
)

/** Performs a GET request on the IO dispatcher. Network failures are thrown as IOException. */
suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): HttpResponse =
    withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            HttpResponse(
                status = status,
                body = stream?.bufferedReader()?.use { it.readText() }.orEmpty(),
                retryAfterSeconds = connection.getHeaderField("Retry-After")?.toLongOrNull(),
            )
        } finally {
            connection.disconnect()
        }
    }

const val TIMEOUT_MS = 10_000
