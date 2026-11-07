package fr.hodr.aip

import android.content.Context
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertPathBuilder
import java.security.cert.CertPathValidator
import java.security.cert.CertStore
import java.security.cert.CertificateFactory
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.PKIXCertPathBuilderResult
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for PIA region endpoints.
 *
 * Region servers (port 1337) serve a leaf-only chain under a fresh
 * Let's Encrypt hierarchy (YE1 under Root YE under ISRG Root X2) that
 * older Android system stores do not know yet. The two intermediate
 * certificates ship in `assets/certs/`; validation still anchors on a
 * system root, so this adds no trust beyond chain completion.
 */
object TlsTrust {
    @Volatile
    private var socketFactory: javax.net.ssl.SSLSocketFactory? = null

    fun init(context: Context) {
        if (socketFactory != null) {
            return
        }
        val intermediates = loadBundled(context)
        val systemManagers = systemTrustManagers()
        val fallback = systemManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("No system X509TrustManager")
        val anchors = fallback.acceptedIssuers
            .filterIsInstance<X509Certificate>()
            .map { TrustAnchor(it, null) }
            .toSet()
        val trustManager = BundleCompletingTrustManager(fallback, anchors, intermediates)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)
        socketFactory = sslContext.socketFactory
    }

    fun applyTo(conn: HttpsURLConnection) {
        socketFactory?.let { conn.sslSocketFactory = it }
    }

    private fun loadBundled(context: Context): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val result = mutableListOf<X509Certificate>()
        val files = context.assets.list("certs").orEmpty()
        for (name in files) {
            context.assets.open("certs/$name").use { stream ->
                val bytes = stream.readBytes()
                val parsed = factory.generateCertificates(ByteArrayInputStream(bytes))
                for (cert in parsed) {
                    (cert as? X509Certificate)?.let { result.add(it) }
                }
            }
        }
        return result
    }

    private fun systemTrustManagers(): Array<javax.net.ssl.TrustManager> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers
    }

    private class BundleCompletingTrustManager(
        private val system: X509TrustManager,
        private val anchors: Set<TrustAnchor>,
        private val intermediates: List<X509Certificate>,
    ) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            system.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            try {
                system.checkServerTrusted(chain, authType)
                return
            } catch (systemError: java.security.cert.CertificateException) {
                completeWithBundled(chain, authType, systemError)
            }
        }

        private fun completeWithBundled(
            chain: Array<X509Certificate>,
            authType: String,
            systemError: java.security.cert.CertificateException,
        ) {
            if (chain.isEmpty() || intermediates.isEmpty() || anchors.isEmpty()) {
                throw systemError
            }
            val selector = X509CertSelector()
            selector.certificate = chain[0]
            val params = PKIXBuilderParameters(anchors, selector)
            params.isRevocationEnabled = false
            val storeContent = ArrayList<java.security.cert.Certificate>(chain.size + intermediates.size)
            storeContent.addAll(chain)
            storeContent.addAll(intermediates)
            val store: CertStore = CertStore.getInstance(
                "Collection",
                CollectionCertStoreParameters(storeContent),
            )
            params.addCertStore(store)
            val builder = CertPathBuilder.getInstance("PKIX")
            val built = builder.build(params) as PKIXCertPathBuilderResult
            val validator = CertPathValidator.getInstance("PKIX")
            val validatorParams = java.security.cert.PKIXParameters(anchors)
            validatorParams.isRevocationEnabled = false
            validator.validate(built.certPath, validatorParams)
        }
    }
}
