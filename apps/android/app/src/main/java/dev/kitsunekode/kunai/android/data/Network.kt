package dev.kitsunekode.kunai.android.data

import android.net.Uri
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** No local server, Termux, desktop or user-configured relay; all calls use HTTPS. */
internal object Network {
    private const val AGENT = "KunaiAndroid/0.1 (Android native)"
    private const val LIMIT = 2_200_000
    fun text(
        url: String,
        jsonBody: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
        limit: Int = LIMIT,
    ): String {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            isPublicHost(uri.host.orEmpty())) {
            "Only HTTPS sources are supported"
        }
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 9000
            connection.readTimeout = 12500
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json, text/html, */*")
            connection.setRequestProperty("User-Agent", AGENT)
            headers.forEach { (key, value) ->
                if (key.lowercase() in setOf("referer", "origin", "user-agent", "accept")
                    && value.length <= 350 && !value.contains("\r") && !value.contains("\n")
                ) connection.setRequestProperty(key, value)
            }
            if (jsonBody != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(jsonBody.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) {
                "Source returned HTTP ${connection.responseCode}"
            }
            val destination = connection.url.toURI()
            check(destination.scheme == "https" && destination.host != null &&
                (destination.port == -1 || destination.port == 443) &&
                isPublicHost(destination.host)) {
                "Source redirected to an unsupported address"
            }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    check(total <= limit) { "Network response exceeds the size limit" }
                    out.write(chunk, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
    private fun isPublicHost(name: String): Boolean {
        val host = name.lowercase().trim('[', ']')
        if (host in setOf("localhost", "::1", "0.0.0.0") ||
            host.endsWith(".local") || host.startsWith("127.") ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("169.254.") ||
            host.matches(Regex("""172\.(1[6-9]|2[0-9]|3[01])\..*""")) ||
            host.startsWith("fc") || host.startsWith("fd") ||
            host.startsWith("fe80:")) return false
        return host.isNotBlank()
    }

    fun json(url: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()) =
        JSONObject(text(url, body, headers))
}
