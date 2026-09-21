package com.lagradost.cloudstream3.desktop.ui.screens.details

import com.lagradost.cloudstream3.*
import com.lagradost.common.logging.AppLogger

/**
 * Desktop-only title metadata module.
 *
 * The interface deliberately accepts the extension-compatible [LoadResponse]. The implementation
 * fills presentation metadata only; provider URLs, episode data payloads, and playback behavior
 * remain owned by the extension.
 */
object TitleMetadataEnricher {
    suspend fun enrich(target: LoadResponse): LoadResponse {
        target.tags = normalizeTags(target.tags, target.type)
        if (target.type == TvType.Live) {
            return target
        }

        return runCatching {
            val tmdb = object : com.lagradost.cloudstream3.metaproviders.TmdbProvider() {
                override val useMetaLoadResponse = true
            }
            val query = normalizedSearchTitle(target.name)
            if (query.isBlank()) return target
            val match = tmdb.search(query, 1)?.items.orEmpty()
                .filter { candidateMatches(target, it, query) }
                .maxByOrNull { matchScore(target, it, query) }
                ?: return target
            val source = tmdb.load(match.url) ?: return target
            merge(target, source)
            target
        }.onFailure {
            AppLogger.w("TitleMetadataEnricher", "Title metadata enrichment failed for ${target.name}: ${it.message}")
        }.getOrDefault(target)
    }

    internal fun normalizedSearchTitle(name: String): String = name
        .replace(Regex("""\s*[\[(](?:19|20)\d{2}[\])].*"""), "")
        .replace(
            Regex(
                """(?i)\b(dual audio|multi audio|dubbed|subbed|4k|2160p|1080p|720p|480p|webrip|web[- .]?dl|hdtv|bluray|hdrip|x26[45]|hevc)\b.*""",
            ),
            "",
        )
        .replace(Regex("""\s*[\[{].*"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', ':')

    private fun canonical(value: String): String = value
        .lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), "")

    private fun candidateMatches(target: LoadResponse, candidate: SearchResponse, query: String): Boolean {
        if (canonical(candidate.name) != canonical(query)) return false
        val candidateYear = when (candidate) {
            is MovieSearchResponse -> candidate.year
            is TvSeriesSearchResponse -> candidate.year
            else -> null
        }
        return target.year == null || candidateYear == null || target.year == candidateYear
    }

    private fun matchScore(target: LoadResponse, candidate: SearchResponse, query: String): Int {
        var score = if (candidate.name.equals(query, true)) 100 else 80
        val candidateYear = when (candidate) {
            is MovieSearchResponse -> candidate.year
            is TvSeriesSearchResponse -> candidate.year
            else -> null
        }
        if (target.year != null && target.year == candidateYear) score += 30
        if (target.type.isEpisodeBased() == candidate.type.isEpisodeBased()) score += 10
        return score
    }

    private fun merge(target: LoadResponse, source: LoadResponse) {
        target.posterUrl = bestImage(target.posterUrl, source.posterUrl)
        target.backgroundPosterUrl = bestImage(target.backgroundPosterUrl, source.backgroundPosterUrl, original = true)
            ?: bestImage(target.backgroundPosterUrl, source.posterUrl, original = true)
        if (target.logoUrl.isNullOrBlank()) target.logoUrl = source.logoUrl
        if (target.plot.isNullOrBlank()) target.plot = source.plot
        if (target.year == null) target.year = source.year
        if (target.duration == null || target.duration == 0) target.duration = source.duration
        if (target.score == null) target.score = source.score
        if (target.contentRating.isNullOrBlank()) target.contentRating = source.contentRating
        if (target.recommendations.isNullOrEmpty()) target.recommendations = source.recommendations
        target.tags = normalizeTags(target.tags.orEmpty() + source.tags.orEmpty(), target.type)
        target.actors = mergeCast(target.actors, source.actors)
        mergeEpisodes(target, source)
    }

    private fun bestImage(existing: String?, fallback: String?, original: Boolean = false): String? {
        val selected = existing?.takeIf { it.isNotBlank() } ?: fallback
        return if (original) selected?.replace("/w500/", "/original/") else selected
    }

    internal fun normalizeTags(tags: List<String>?, type: TvType): List<String> {
        val noise = Regex("""(?i)^(\d{3,4}p|4k|x26[45]|hevc|web[- .]?dl|webrip|bluray|hdrip)$""")
        val normalized = tags.orEmpty()
            .flatMap { it.split(',', '/', '|') }
            .map { it.trim().replace(Regex("""\s+"""), " ") }
            .filter { it.length in 2..40 && !noise.matches(it) }
            .distinctBy { it.lowercase() }
            .toMutableList()
        val typeTag = when {
            type == TvType.Live -> "Live"
            type.isAnimeOp() -> "Anime"
            type.isEpisodeBased() -> "Series"
            type == TvType.Movie || type == TvType.AnimeMovie -> "Movie"
            else -> null
        }
        if (typeTag != null && normalized.none { it.equals(typeTag, true) }) normalized.add(0, typeTag)
        return normalized.take(16)
    }

    private fun mergeCast(existing: List<ActorData>?, enriched: List<ActorData>?): List<ActorData>? {
        if (enriched.isNullOrEmpty()) return existing
        if (existing.isNullOrEmpty()) return enriched.distinctBy { canonical(it.actor.name) }.take(30)
        val enrichedByName = enriched.associateBy { canonical(it.actor.name) }
        val merged = existing.map { credit ->
            val extra = enrichedByName[canonical(credit.actor.name)] ?: return@map credit
            ActorData(
                actor = Actor(
                    name = credit.actor.name,
                    image = credit.actor.image?.takeIf { it.isNotBlank() } ?: extra.actor.image,
                ),
                role = credit.role ?: extra.role,
                roleString = credit.roleString?.takeIf { it.isNotBlank() } ?: extra.roleString,
                voiceActor = credit.voiceActor ?: extra.voiceActor,
            )
        }.toMutableList()
        val known = merged.mapTo(mutableSetOf()) { canonical(it.actor.name) }
        enriched.filter { known.add(canonical(it.actor.name)) }.forEach(merged::add)
        return merged.take(30)
    }

    private fun episodes(response: LoadResponse): List<Episode> = when (response) {
        is TvSeriesLoadResponse -> response.episodes
        is AnimeLoadResponse -> response.episodes.values.flatten()
        else -> emptyList()
    }

    private fun mergeEpisodes(target: LoadResponse, source: LoadResponse) {
        val sourceEpisodes = episodes(source)
        if (sourceEpisodes.isEmpty()) return
        val byIndex = sourceEpisodes.associateBy { (it.season ?: 1) to it.episode }
        episodes(target).forEach { episode ->
            val match = byIndex[(episode.season ?: 1) to episode.episode] ?: return@forEach
            if (episode.posterUrl.isNullOrBlank()) episode.posterUrl = match.posterUrl
            if (episode.name.isNullOrBlank()) episode.name = match.name
            if (episode.description.isNullOrBlank()) episode.description = match.description
            if (episode.score == null) episode.score = match.score
            if (episode.date == null) episode.date = match.date
            if (episode.runTime == null || episode.runTime == 0) episode.runTime = match.runTime
        }
    }
}
