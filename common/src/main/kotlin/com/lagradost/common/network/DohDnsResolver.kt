// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/network/DohProviders.kt", upstreamCommit = "caeec18")
package com.lagradost.common.network

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Upstream extension functions for OkHttpClient.Builder for 1:1 parity with DohProviders.kt.
 */
fun OkHttpClient.Builder.addGenericDns(url: String, ips: List<String>): OkHttpClient.Builder = dns(
    DnsOverHttps.Builder()
        .client(build())
        .url(url.toHttpUrl())
        .includeIPv6(true)
        .systemDns(Dns.SYSTEM)
        .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
        .build()
)

fun OkHttpClient.Builder.addGoogleDns(): OkHttpClient.Builder = addGenericDns(
    DohDnsResolver.GOOGLE_URL,
    listOf(
        "8.8.4.4",
        "8.8.8.8",
        "2001:4860:4860::8888",
        "2001:4860:4860::8844"
    )
)

fun OkHttpClient.Builder.addCloudFlareDns(): OkHttpClient.Builder = addGenericDns(
    DohDnsResolver.CLOUDFLARE_URL,
    listOf(
        "1.1.1.1",
        "1.0.0.1",
        "2606:4700:4700::1111",
        "2606:4700:4700::1001"
    )
)


fun OkHttpClient.Builder.addOpenDns(): OkHttpClient.Builder = addGenericDns(
    "https://doh.opendns.com/dns-query",
    listOf(
        "208.67.222.222",
        "208.67.220.220",
        "2620:119:35::35",
        "2620:119:53::53"
    )
)

fun OkHttpClient.Builder.addAdGuardDns(): OkHttpClient.Builder = addGenericDns(
    DohDnsResolver.ADGUARD_URL,
    listOf(
        "94.140.14.140",
        "94.140.14.141"
    )
)

fun OkHttpClient.Builder.addDNSWatchDns(): OkHttpClient.Builder = addGenericDns(
    DohDnsResolver.DNS_WATCH_URL,
    listOf(
        "84.200.69.80",
        "84.200.70.40"
    )
)

fun OkHttpClient.Builder.addQuad9Dns(): OkHttpClient.Builder = addGenericDns(
    DohDnsResolver.QUAD9_URL,
    listOf(
        "9.9.9.9",
        "149.112.112.112"
    )
)

fun OkHttpClient.Builder.addDnsSbDns(): OkHttpClient.Builder = addGenericDns(
    DohDnsResolver.DNS_SB_URL,
    listOf(
        "185.222.222.222",
        "45.11.45.11"
    )
)

fun OkHttpClient.Builder.addCanadianShieldDns(): OkHttpClient.Builder = addGenericDns(
    DohDnsResolver.CANADIAN_SHIELD_URL,
    listOf(
        "149.112.121.10",
        "149.112.122.10"
    )
)

/**
 * DNS-over-HTTPS (DoH) resolver implementing okhttp3.Dns.
 *
 * Designed to bypass ISP-level DNS censorship and domain hijacking.
 * Supports all 7 canonical upstream DoH endpoints with automatic,
 * fully-logged fallback to system DNS on network failures.
 */
class DohDnsResolver(
    var provider: DohProvider = DohProvider.CLOUDFLARE
) : Dns {

    enum class DohProvider(
        val prefValue: Int,
        val displayName: String,
        val url: String,
        val ips: List<String> = emptyList()
    ) {
        NONE(
            prefValue = 0,
            displayName = "Disabled (System DNS)",
            url = "",
            ips = emptyList()
        ),
        GOOGLE(
            prefValue = 1,
            displayName = "Google DoH",
            url = GOOGLE_URL,
            ips = listOf(
                "8.8.8.8",
                "8.8.4.4",
                "2001:4860:4860::8888",
                "2001:4860:4860::8844"
            )
        ),
        CLOUDFLARE(
            prefValue = 2,
            displayName = "Cloudflare DoH",
            url = CLOUDFLARE_URL,
            ips = listOf(
                "1.1.1.1",
                "1.0.0.1",
                "2606:4700:4700::1111",
                "2606:4700:4700::1001"
            )
        ),
        ADGUARD(
            prefValue = 4,
            displayName = "AdGuard DoH",
            url = ADGUARD_URL,
            ips = listOf(
                "94.140.14.140",
                "94.140.14.141"
            )
        ),
        DNS_WATCH(
            prefValue = 5,
            displayName = "DNS.WATCH DoH",
            url = DNS_WATCH_URL,
            ips = listOf(
                "84.200.69.80",
                "84.200.70.40"
            )
        ),
        QUAD9(
            prefValue = 6,
            displayName = "Quad9 DoH",
            url = QUAD9_URL,
            ips = listOf(
                "9.9.9.9",
                "149.112.112.112"
            )
        ),
        DNS_SB(
            prefValue = 7,
            displayName = "DNS.SB DoH",
            url = DNS_SB_URL,
            ips = listOf(
                "185.222.222.222",
                "45.11.45.11"
            )
        ),
        CANADIAN_SHIELD(
            prefValue = 8,
            displayName = "Canadian Shield DoH",
            url = CANADIAN_SHIELD_URL,
            ips = listOf(
                "149.112.121.10",
                "149.112.122.10"
            )
        );

        companion object {
            fun fromPrefValue(prefValue: Int): DohProvider {
                return entries.firstOrNull { it.prefValue == prefValue } ?: run {
                    AppLogger.w("DohDnsResolver", "Unknown DoH prefValue: $prefValue, falling back to NONE (System DNS)")
                    NONE
                }
            }

            fun fromUrl(url: String?): DohProvider = fromValue(url)

            fun fromValue(value: String?): DohProvider {
                if (value.isNullOrBlank()) return NONE
                val trimmed = value.trim()
                trimmed.toIntOrNull()?.let { intVal ->
                    fromPrefValue(intVal).let { if (it != NONE || intVal == 0) return it }
                }
                entries.firstOrNull { it.url.equals(trimmed, ignoreCase = true) }?.let { return it }
                entries.firstOrNull {
                    it.name.equals(trimmed, ignoreCase = true) ||
                    it.name.replace("_", "").equals(trimmed.replace("_", "").replace(".", "").replace("-", ""), ignoreCase = true)
                }?.let { return it }
                val lower = trimmed.lowercase()
                return when {
                    lower.contains("google") -> GOOGLE
                    lower.contains("cloudflare") -> CLOUDFLARE
                    lower.contains("adguard") -> ADGUARD
                    lower.contains("watch") -> DNS_WATCH
                    lower.contains("quad9") -> QUAD9
                    lower.contains("dns.sb") || lower.contains("dnssb") -> DNS_SB
                    lower.contains("canadian") || lower.contains("cira") -> CANADIAN_SHIELD
                    lower == "none" || lower == "disabled" || lower == "system" -> NONE
                    else -> {
                        AppLogger.w("DohDnsResolver", "Unknown DoH provider or URL: '$value', falling back to NONE (System DNS)")
                        NONE
                    }
                }
            }
        }
    }

    private val bootstrapClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .dns(Dns.SYSTEM) // Crucial: bootstrap client must use standard system DNS to avoid lookup loops
            .build()
    }

    private fun buildDnsOverHttps(dohProvider: DohProvider): DnsOverHttps {
        return DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(dohProvider.url.toHttpUrl())
            .includeIPv6(true)
            .systemDns(Dns.SYSTEM)
            .bootstrapDnsHosts(dohProvider.ips.map { InetAddress.getByName(it) })
            .build()
    }

    private val dohInstances: Map<DohProvider, DnsOverHttps> by lazy {
        DohProvider.entries
            .filter { it != DohProvider.NONE }
            .associateWith { buildDnsOverHttps(it) }
    }

    private fun getDohInstance(targetProvider: DohProvider): DnsOverHttps {
        if (targetProvider == DohProvider.NONE) {
            throw IllegalStateException("Cannot obtain DoH instance for NONE provider")
        }
        return dohInstances[targetProvider]
            ?: throw IllegalStateException("No DoH instance available for provider: $targetProvider")
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (provider == DohProvider.NONE) {
            return Dns.SYSTEM.lookup(hostname)
        }

        return try {
            val doh = getDohInstance(provider)
            val addresses = doh.lookup(hostname)
            if (addresses.isNotEmpty()) {
                addresses
            } else {
                AppLogger.w("DohDnsResolver", "Empty result from DoH ($provider) for '$hostname'; falling back to system DNS")
                InetAddress.getAllByName(hostname).toList()
            }
        } catch (e: Exception) {
            AppLogger.w(
                "DohDnsResolver",
                "DoH lookup failed for '$hostname' via $provider, falling back to system DNS: ${e.message}"
            )
            try {
                InetAddress.getAllByName(hostname).toList()
            } catch (systemEx: Exception) {
                AppLogger.e(
                    "DohDnsResolver",
                    "DNS resolution failed completely for '$hostname' (both DoH and system DNS failed)",
                    systemEx
                )
                if (systemEx is UnknownHostException) {
                    throw systemEx
                } else {
                    throw UnknownHostException("Unable to resolve host '$hostname': ${systemEx.message}").apply {
                        initCause(systemEx)
                    }
                }
            }
        }
    }

    companion object : Dns {
        const val CLOUDFLARE_URL = "https://cloudflare-dns.com/dns-query"
        const val GOOGLE_URL = "https://dns.google/dns-query"
        const val ADGUARD_URL = "https://dns.adguard.com/dns-query"
        const val DNS_WATCH_URL = "https://resolver2.dns.watch/dns-query"
        const val QUAD9_URL = "https://dns.quad9.net/dns-query"
        const val DNS_SB_URL = "https://doh.dns.sb/dns-query"
        const val CANADIAN_SHIELD_URL = "https://private.canadianshield.cira.ca/dns-query"

        val CLOUDFLARE = DohDnsResolver(DohProvider.CLOUDFLARE)
        val GOOGLE = DohDnsResolver(DohProvider.GOOGLE)
        val ADGUARD = DohDnsResolver(DohProvider.ADGUARD)
        val DNS_WATCH = DohDnsResolver(DohProvider.DNS_WATCH)
        val QUAD9 = DohDnsResolver(DohProvider.QUAD9)
        val DNS_SB = DohDnsResolver(DohProvider.DNS_SB)
        val CANADIAN_SHIELD = DohDnsResolver(DohProvider.CANADIAN_SHIELD)
        val SYSTEM = DohDnsResolver(DohProvider.NONE)

        private val _currentProvider = MutableStateFlow(DohProvider.NONE)
        val currentProviderFlow: StateFlow<DohProvider> = _currentProvider.asStateFlow()

        val INSTANCE: DohDnsResolver = DohDnsResolver(DohProvider.NONE)

        var activeProvider: DohProvider
            get() = INSTANCE.provider
            set(value) {
                setProvider(value)
            }

        fun setProvider(newProvider: DohProvider) {
            _currentProvider.value = newProvider
            INSTANCE.provider = newProvider
            AppLogger.i("DohDnsResolver", "Active DoH provider switched to: ${newProvider.displayName} (prefValue=${newProvider.prefValue})")
        }

        val DEFAULT: DohDnsResolver get() = INSTANCE

        init {
            runCatching {
                val savedDns = DesktopDataStore.getKey<Int>("dns_key")
                    ?: DesktopDataStore.getKey<Int>("doh_provider")
                if (savedDns != null) {
                    val provider = DohProvider.fromPrefValue(savedDns)
                    setProvider(provider)
                }
            }
        }

        override fun lookup(hostname: String): List<InetAddress> = INSTANCE.lookup(hostname)
    }
}
