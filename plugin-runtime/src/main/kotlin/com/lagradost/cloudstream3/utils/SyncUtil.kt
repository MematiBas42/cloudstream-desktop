// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/SyncUtil.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit

object SyncUtil {
    private val regexs = listOf(
        Regex("""(9anime)\.(?:to|center|id)/watch/.*?\.([^/?]*)"""),
        Regex("""(gogoanime|gogoanimes)\..*?/category/([^/?]*)"""),
        Regex("""(twist\.moe)/a/([^/?]*)"""),
    )

    private const val TAG = "SYNCUTIL"

    private const val GOGOANIME = "Gogoanime"
    private const val NINE_ANIME = "9anime"
    private const val TWIST_MOE = "Twistmoe"

    private val matchList = mapOf(
        "9anime" to NINE_ANIME,
        "gogoanime" to GOGOANIME,
        "gogoanimes" to GOGOANIME,
        "twist.moe" to TWIST_MOE,
    )

    suspend fun getIdsFromUrl(url: String?): Pair<String?, String?>? {
        if (url == null) return null
        Log.i(TAG, "getIdsFromUrl $url")
        for (regex in regexs) {
            regex.find(url)?.let { match ->
                if (match.groupValues.size == 3) {
                    val site = match.groupValues[1]
                    val slug = match.groupValues[2]
                    matchList[site]?.let { realSite ->
                        getIdsFromSlug(slug, realSite)?.let {
                            return it
                        } ?: kotlin.run {
                            if (slug.endsWith("-dub")) {
                                println("testing non -dub slug $slug")
                                getIdsFromSlug(slug.removeSuffix("-dub"), realSite)?.let {
                                    return it
                                }
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * first. Mal, second. Anilist,
     * Valid sites are: Gogoanime, Twistmoe and 9anime
     */
    suspend fun getIdsFromSlug(
        slug: String,
        site: String = "Gogoanime",
    ): Pair<String?, String?>? {
        Log.i(TAG, "getIdsFromSlug $slug $site")
        try {
            // Gogoanime, Twistmoe and 9anime
            val url = "https://raw.githubusercontent.com/MALSync/MAL-Sync-Backup/master/data/pages/$site/$slug.json"
            val response = app.get(url, cacheTime = 1, cacheUnit = TimeUnit.DAYS).text
            val mapped = tryParseJson<MalSyncPage>(response)

            val overrideMal = mapped?.malId ?: mapped?.mal?.id ?: mapped?.anilist?.malId
            val overrideAnilist = mapped?.aniId ?: mapped?.anilist?.id
            if (overrideMal != null) {
                return overrideMal.toString() to overrideAnilist?.toString()
            }

            return null
        } catch (e: Exception) {
            logError(e)
        }

        return null
    }

    /**
     * Resolves MAL and AniList IDs from an anime title by generating a slug.
     * Tries multiple slug formats against MAL-Sync-Backup.
     */
    suspend fun getIdsFromTitle(
        title: String,
        site: String = "Gogoanime"
    ): Pair<String?, String?>? {
        Log.i(TAG, "getIdsFromTitle $title $site")
        val slug = title.trim().lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-')
        if (slug.isBlank()) return null

        getIdsFromSlug(slug, site)?.let { return it }

        // Also try without special characters or stripping common words
        val alternateSlug = title.filter { it.isLetterOrDigit() || it.isWhitespace() }
            .trim()
            .replace("\\s+".toRegex(), "-")
            .lowercase()

        if (alternateSlug != slug && alternateSlug.isNotBlank()) {
            getIdsFromSlug(alternateSlug, site)?.let { return it }
        }

        // Try other sites in matchList
        for (otherSite in listOf(NINE_ANIME, TWIST_MOE)) {
            if (otherSite != site) {
                getIdsFromSlug(slug, otherSite)?.let { return it }
                if (alternateSlug != slug && alternateSlug.isNotBlank()) {
                    getIdsFromSlug(alternateSlug, otherSite)?.let { return it }
                }
            }
        }

        return null
    }

    suspend fun getUrlsFromId(id: String, type: String = "anilist"): List<String> {
        val url = "https://raw.githubusercontent.com/MALSync/MAL-Sync-Backup/master/data/$type/anime/$id.json"
        val response = app.get(url, cacheTime = 1, cacheUnit = TimeUnit.DAYS).parsed<SyncPage>()
        val pages = response.pages ?: return emptyList()
        val current = pages.gogoanime.values.union(pages.nineanime.values).union(pages.twistmoe.values)
            .mapNotNull { it.url }.toMutableList()

        if (type == "anilist") {
            apis.filter { it.name.contains("Aniflix", ignoreCase = true) }.forEach {
                current.add("${it.mainUrl}/anime/$id")
            }
        }

        return current
    }

    @Serializable
    data class SyncPage(
        @JsonProperty("Pages") @SerialName("Pages") val pages: SyncPages?,
    )

    @Serializable
    data class SyncPages(
        @JsonProperty("9anime") @SerialName("9anime") val nineanime: Map<String, ProviderPage> = emptyMap(),
        @JsonProperty("Gogoanime") @SerialName("Gogoanime") val gogoanime: Map<String, ProviderPage> = emptyMap(),
        @JsonProperty("Twistmoe") @SerialName("Twistmoe") val twistmoe: Map<String, ProviderPage> = emptyMap(),
    )

    @Serializable
    data class ProviderPage(
        @JsonProperty("url") @SerialName("url") val url: String?,
    )

    @Serializable
    data class MalSyncPage(
        @JsonProperty("identifier") @SerialName("identifier") val identifier: String?,
        @JsonProperty("type") @SerialName("type") val type: String?,
        @JsonProperty("page") @SerialName("page") val page: String?,
        @JsonProperty("title") @SerialName("title") val title: String?,
        @JsonProperty("url") @SerialName("url") val url: String?,
        @JsonProperty("image") @SerialName("image") val image: String?,
        @JsonProperty("hentai") @SerialName("hentai") val hentai: Boolean?,
        @JsonProperty("sticky") @SerialName("sticky") val sticky: Boolean?,
        @JsonProperty("active") @SerialName("active") val active: Boolean?,
        @JsonProperty("actor") @SerialName("actor") val actor: String?,
        @JsonProperty("malId") @SerialName("malId") val malId: Int?,
        @JsonProperty("aniId") @SerialName("aniId") val aniId: Int?,
        @JsonProperty("createdAt") @SerialName("createdAt") val createdAt: String?,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: String?,
        @JsonProperty("deletedAt") @SerialName("deletedAt") val deletedAt: String?,
        @JsonProperty("Mal") @SerialName("Mal") val mal: Mal?,
        @JsonProperty("Anilist") @SerialName("Anilist") val anilist: Anilist?,
        @JsonProperty("malUrl") @SerialName("malUrl") val malUrl: String?,
    )

    @Serializable
    data class Anilist(
        @JsonProperty("id") @SerialName("id") val id: Int?,
        @JsonProperty("malId") @SerialName("malId") val malId: Int?,
        @JsonProperty("type") @SerialName("type") val type: String?,
        @JsonProperty("title") @SerialName("title") val title: String?,
        @JsonProperty("url") @SerialName("url") val url: String?,
        @JsonProperty("image") @SerialName("image") val image: String?,
        @JsonProperty("category") @SerialName("category") val category: String?,
        @JsonProperty("hentai") @SerialName("hentai") val hentai: Boolean?,
        @JsonProperty("createdAt") @SerialName("createdAt") val createdAt: String?,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: String?,
        @JsonProperty("deletedAt") @SerialName("deletedAt") val deletedAt: String?,
    )

    @Serializable
    data class Mal(
        @JsonProperty("id") @SerialName("id") val id: Int?,
        @JsonProperty("type") @SerialName("type") val type: String?,
        @JsonProperty("title") @SerialName("title") val title: String?,
        @JsonProperty("url") @SerialName("url") val url: String?,
        @JsonProperty("image") @SerialName("image") val image: String?,
        @JsonProperty("category") @SerialName("category") val category: String?,
        @JsonProperty("hentai") @SerialName("hentai") val hentai: Boolean?,
        @JsonProperty("createdAt") @SerialName("createdAt") val createdAt: String?,
        @JsonProperty("updatedAt") @SerialName("updatedAt") val updatedAt: String?,
        @JsonProperty("deletedAt") @SerialName("deletedAt") val deletedAt: String?,
    )
}
