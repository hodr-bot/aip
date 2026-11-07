package pia.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import pia.core.Clock
import pia.core.HttpPort

/** OkHttp-free [HttpPort] backed by java.net.HttpURLConnection. No secrets logged. */
class HttpUrlConnectionPort : HttpPort {
    override fun postForm(url: String, fields: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = fields.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }
            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
            }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    override fun get(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            for ((key, value) in headers) {
                conn.setRequestProperty(key, value)
            }
            return readResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            ?: throw java.io.IOException("HTTP $code with empty body")
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val body = reader.readText()
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code")
            }
            return body
        }
    }
}

class SystemClock : Clock {
    override fun nowEpochSec(): Long = System.currentTimeMillis() / 1000L
}
