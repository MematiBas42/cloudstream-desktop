// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/SSLTrustManager.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.network

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Trust-all X509TrustManager and SSLSocketFactory implementation.
 * Ported from upstream [com.lagradost.cloudstream3.ui.player.SSLTrustManager] to provide
 * unconditional certificate acceptance for rogue streaming servers, self-signed CDNs,
 * and media extractors without throwing SSLHandshakeException or CertificateException.
 */
class SSLTrustManager : X509TrustManager {
    override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {
        // Trust all client certificates unconditionally
    }

    override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {
        // Trust all server certificates unconditionally
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf()
    }

    companion object {
        /**
         * Singleton array of trust-all X509TrustManager instances for SSLContext initialization.
         */
        val trustAllCerts: Array<X509TrustManager> = arrayOf(SSLTrustManager())

        /**
         * HostnameVerifier that unconditionally accepts any hostname / SSLSession pairing.
         */
        val trustAllHostnameVerifier: HostnameVerifier = HostnameVerifier { _: String, _: SSLSession -> true }

        /**
         * Generates an unverified, trust-all SSLSocketFactory using a standard TLS SSLContext.
         */
        fun getUnsafeSslSocketFactory(): SSLSocketFactory {
            val sslContext = getUnsafeSslContext()
            return sslContext.socketFactory
        }

        /**
         * Generates an initialized TLS SSLContext configured with [SSLTrustManager] and [SecureRandom].
         */
        fun getUnsafeSslContext(): SSLContext {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            return sslContext
        }

        /**
         * Enables trust-all SSL globally for standard Java [HttpsURLConnection].
         * Mirrors exact upstream CS3IPlayer ignoreSSL behavior.
         */
        fun enableTrustAllSSL() {
            val sslContext = getUnsafeSslContext()
            sslContext.createSSLEngine()
            HttpsURLConnection.setDefaultHostnameVerifier(trustAllHostnameVerifier)
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
        }

        /**
         * Configures an [OkHttpClient.Builder] to bypass SSL/TLS certificate verification
         * and hostname verification for rogue video hosts and CDNs.
         */
        fun OkHttpClient.Builder.ignoreAllSSLErrors(): OkHttpClient.Builder {
            val trustManager = SSLTrustManager()
            val sslSocketFactory = getUnsafeSslSocketFactory()
            return sslSocketFactory(sslSocketFactory, trustManager)
                .hostnameVerifier(trustAllHostnameVerifier)
        }
    }
}
