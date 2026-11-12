package fr.hodr.pia

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import pia.core.Clock
import pia.core.HttpPort

/**
 * OkHttp-free [HttpPort] backed by java.net.HttpURLConnection.
 *
 * Small-hello TLS for constrained paths (see docs/CONSTRAINED-NETWORK.md):
 * TLS 1.2 with a short cipher list keeps the ClientHello under 1000 bytes.
 * Trust comes from the platform store, which carries the current
 * Let's Encrypt hierarchy. No secrets logged.
 */
class LinuxHttpPort : HttpPort {
    override fun postForm(url: String, fields: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        (conn as? HttpsURLConnection)?.sslSocketFactory = tlsFactory()
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
        (conn as? HttpsURLConnection)?.sslSocketFactory = tlsFactory()
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

    companion object {
        private val factoryRef = AtomicReference<SSLSocketFactory?>()

        private val WANTED_SUITES = arrayOf(
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        )

        fun tlsFactory(): SSLSocketFactory {
            factoryRef.get()?.let { return it }
            val context = SSLContext.getInstance("TLSv1.2")
            context.init(null, null, null)
            val supported = context.socketFactory.supportedCipherSuites.toSet()
            val picked = WANTED_SUITES.filter { it in supported }.toTypedArray()
            val factory = if (picked.isNotEmpty()) {
                ConstrainingFactory(context.socketFactory, arrayOf("TLSv1.2"), picked)
            } else {
                context.socketFactory
            }
            factoryRef.compareAndSet(null, factory)
            return factoryRef.get()!!
        }

        private class ConstrainingFactory(
            private val inner: SSLSocketFactory,
            private val protocols: Array<String>,
            private val suites: Array<String>,
        ) : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = suites
            override fun getSupportedCipherSuites(): Array<String> = suites
            private fun constrain(s: java.net.Socket): java.net.Socket {
                (s as? javax.net.ssl.SSLSocket)?.let {
                    try { it.enabledProtocols = protocols } catch (ignored: Exception) { }
                    try { it.enabledCipherSuites = suites } catch (ignored: Exception) { }
                }
                return s
            }
            override fun createSocket(s: java.net.Socket, h: String, p: Int, a: Boolean) =
                constrain(inner.createSocket(s, h, p, a))
            override fun createSocket(h: String, p: Int) = constrain(inner.createSocket(h, p))
            override fun createSocket(h: String, p: Int, l: java.net.InetAddress, lp: Int) =
                constrain(inner.createSocket(h, p, l, lp))
            override fun createSocket(h: java.net.InetAddress, p: Int) =
                constrain(inner.createSocket(h, p))
            override fun createSocket(h: java.net.InetAddress, p: Int, l: java.net.InetAddress, lp: Int) =
                constrain(inner.createSocket(h, p, l, lp))
        }
    }
}

class SystemClock : Clock {
    override fun nowEpochSec(): Long = System.currentTimeMillis() / 1000L
}
