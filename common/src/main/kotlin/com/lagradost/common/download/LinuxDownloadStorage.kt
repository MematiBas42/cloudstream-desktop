package com.lagradost.common.download

import com.lagradost.common.logging.AppLogger
import com.lagradost.common.platform.FileNameSanitizer
import com.lagradost.common.platform.PlatformPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Storage foundations and path resolution adhering to the Freedesktop XDG Base Directory Specification.
 *
 * Downloads default to: $XDG_DATA_HOME/cloudstream/downloads/ (~/.local/share/cloudstream/downloads/).
 * Employs POSIX atomic moves (.part -> final) to prevent partially written files from polluting the library.
 */
object LinuxDownloadStorage {
    private const val PART_EXTENSION = ".part"

    @Volatile
    var customDownloadsDir: Path? = null

    val defaultDownloadsDir: Path
        get() = PlatformPaths.dataDir.resolve("downloads")

    fun getActiveDownloadsDir(): Path {
        return customDownloadsDir ?: defaultDownloadsDir
    }

    fun sanitizeFilename(name: String): String = FileNameSanitizer.sanitizeFileName(name)

    fun getFolderPrefix(tvType: String?): String {
        return when (tvType?.lowercase()) {
            "anime" -> "Anime"
            "animemovie", "movie" -> "Movies"
            "tvseries", "tv" -> "TVSeries"
            "cartoon", "cartoons" -> "Cartoons"
            "documentary", "documentaries" -> "Documentaries"
            "asiandrama", "asiandramas" -> "AsianDramas"
            "audio" -> "Audio"
            "audiobook" -> "AudioBooks"
            "music" -> "Music"
            "podcast", "podcasts" -> "Podcasts"
            else -> "Movies"
        }
    }

    fun isEpisodeBasedType(tvType: String?, season: Int?, episode: Int?): Boolean {
        if (season != null || episode != null) return true
        return when (tvType?.lowercase()) {
            "anime", "tvseries", "tv", "cartoon", "cartoons", "asiandrama", "asiandramas" -> true
            else -> false
        }
    }

    fun getRelativeFolder(tvType: String?, titleName: String, season: Int? = null, episode: Int? = null): String {
        val prefix = getFolderPrefix(tvType)
        return if (isEpisodeBasedType(tvType, season, episode)) {
            val sanitizedTitle = FileNameSanitizer.sanitizeDirectoryName(titleName)
            "$prefix/$sanitizedTitle"
        } else {
            prefix
        }
    }

    fun getDisplayName(
        titleName: String,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        isMovie: Boolean
    ): String {
        if (isMovie) return sanitizeFilename(titleName)

        val cleanEpName = episodeName?.let { sanitizeFilename(it) }?.ifBlank { null }
        return sanitizeFilename(
            when {
                cleanEpName == null -> {
                    if (season != null && episode != null) "Season $season Episode $episode"
                    else if (episode != null) "Episode $episode"
                    else titleName
                }
                episode != null -> {
                    if (season != null) "Season $season Episode $episode - $cleanEpName"
                    else "Episode $episode - $cleanEpName"
                }
                else -> cleanEpName
            }
        )
    }

    fun resolveTargetFile(relativeFolder: String, displayName: String, extension: String = "mp4"): File {
        val folder = getActiveDownloadsDir().resolve(relativeFolder)
        if (!Files.exists(folder)) {
            Files.createDirectories(folder)
        }
        return folder.resolve("$displayName.$extension").toFile()
    }

    fun resolvePartFile(targetFile: File): File {
        return File(targetFile.parentFile, targetFile.name + PART_EXTENSION)
    }

    fun commitPartFile(partFile: File, targetFile: File) {
        if (!partFile.exists()) return
        val targetParent = targetFile.parentFile
        if (targetParent != null && !targetParent.exists()) {
            targetParent.mkdirs()
        }

        try {
            Files.move(
                partFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(
                partFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    fun getAvailableStorageBytes(): Long {
        return try {
            val dir = getActiveDownloadsDir().toFile()
            if (!dir.exists()) dir.mkdirs()
            dir.usableSpace
        } catch (e: Exception) {
            AppLogger.e("LinuxDownloadStorage", "Failed to query storage space", e)
            0L
        }
    }

    fun getTotalStorageBytes(): Long {
        return try {
            val dir = getActiveDownloadsDir().toFile()
            if (!dir.exists()) dir.mkdirs()
            dir.totalSpace
        } catch (e: Exception) {
            0L
        }
    }
}
