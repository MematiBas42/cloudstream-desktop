// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/downloader/DownloadFileManagement.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.getFolderPrefix
import com.lagradost.cloudstream3.isEpisodeBased
import com.lagradost.common.platform.FileNameSanitizer
import com.lagradost.safefile.MediaFileContentType
import com.lagradost.safefile.SafeFile

object DownloadFileManagement {
    internal fun sanitizeFilename(name: String, removeSpaces: Boolean = false): String {
        val sanitized = FileNameSanitizer.sanitizeFileName(name)
        return if (removeSpaces) sanitized.replace(" ", "") else sanitized
    }

    /**
     * Used for getting video player subs.
     * @return List of pairs for the files in this format: <Name, Uri>
     * */
    internal fun getFolder(
        context: Context,
        relativePath: String,
        basePath: String?
    ): List<Pair<String, Uri>>? {
        val base = basePathToFile(context, basePath)
        val folder =
            base?.gotoDirectory(relativePath, createMissingDirectories = false) ?: return null

        return folder.listFiles()
            ?.mapNotNull { (it.name() ?: "") to (it.uri() ?: return@mapNotNull null) }
    }

    /**
     * Turns a string to an UniFile. Used for stored string paths such as settings.
     * Should only be used to get a download path.
     * */
    internal fun basePathToFile(context: Context, path: String?): SafeFile? {
        return when {
            path.isNullOrBlank() -> getDefaultDir(context)
            path.startsWith("content://") -> SafeFile.fromUri(context, path.toUri())
            else -> SafeFile.fromFilePath(context, path)
        }
    }

    /**
     * Base path where downloaded things should be stored, changes depending on settings.
     * Returns the file and a string to be stored for future file retrieval.
     * UniFile.filePath is not sufficient for storage.
     * */
    internal fun Context.getBasePath(): Pair<SafeFile?, String?> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val basePathSetting = settingsManager.getString(getString(R.string.download_path_key), null)
        return basePathToFile(this, basePathSetting) to basePathSetting
    }

    internal fun getFileName(
        context: Context,
        metadata: DownloadObjects.DownloadEpisodeMetadata
    ): String {
        return getFileName(context, metadata.name, metadata.episode, metadata.season)
    }

    internal fun getFileName(
        context: Context,
        epName: String?,
        episode: Int?,
        season: Int?
    ): String {
        return sanitizeFilename(
            if (epName == null) {
                if (season != null) {
                    "${context.getString(R.string.season)} $season ${context.getString(R.string.episode)} $episode"
                } else {
                    "${context.getString(R.string.episode)} $episode"
                }
            } else {
                if (episode != null) {
                    if (season != null) {
                        "${context.getString(R.string.season)} $season ${context.getString(R.string.episode)} $episode - $epName"
                    } else {
                        "${context.getString(R.string.episode)} $episode - $epName"
                    }
                } else {
                    epName
                }
            }
        )
    }

    internal fun DownloadObjects.DownloadedFileInfo.toFile(context: Context): SafeFile? {
        return basePathToFile(context, this.basePath)?.gotoDirectory(
            relativePath,
            createMissingDirectories = false
        )
            ?.findFile(displayName)
    }

    /**
     * Resolves the temporary partial file (.part) for this downloaded file info.
     */
    internal fun DownloadObjects.DownloadedFileInfo.toPartFile(context: Context): SafeFile? {
        return basePathToFile(context, this.basePath)?.gotoDirectory(
            relativePath,
            createMissingDirectories = false
        )?.findFile("${displayName}.part")
    }

    /**
     * Deletes orphaned partial (.part) files and temporary download artifacts for a given [DownloadedFileInfo].
     * @return true if a partial file was found and deleted, false otherwise.
     */
    fun deletePartial(context: Context, info: DownloadObjects.DownloadedFileInfo): Boolean {
        var deleted = false
        val partFile = info.toPartFile(context)
        if (partFile != null && partFile.exists()) {
            if (partFile.delete() == true || !partFile.exists()) {
                deleted = true
            }
        }
        return deleted
    }

    /**
     * Deletes orphaned partial (.part) files associated with a download [id].
     * Checks cached metadata, resume packages, and queue packages to locate the directory and files.
     * @return true if any partial file was successfully deleted, false otherwise.
     */
    fun deletePartial(context: Context, id: Int): Boolean {
        var deleted = false
        val info = com.lagradost.cloudstream3.utils.DataStore.getKey<DownloadObjects.DownloadedFileInfo>(
            VideoDownloadManager.KEY_DOWNLOAD_INFO,
            id.toString()
        )
        if (info != null) {
            deleted = deletePartial(context, info) || deleted
        }

        // Also check resume package or queue package metadata
        val resumePkg = VideoDownloadManager.getDownloadResumePackage(context, id)
        val queuePkg = VideoDownloadManager.getDownloadQueuePackage(context, id)
        val wrapper = queuePkg ?: resumePkg?.toWrapper()

        val downloadItem = wrapper?.downloadItem
        if (downloadItem != null) {
            val folder = getFolder(downloadItem.resultType, downloadItem.resultName)
            val fileName = getFileName(
                context,
                downloadItem.episode.name,
                downloadItem.episode.episode,
                downloadItem.episode.season
            )
            val (base, _) = context.getBasePath()
            val dir = base?.gotoDirectory(folder, createMissingDirectories = false)
            if (dir != null) {
                val directPart = dir.findFile("$fileName.part")
                if (directPart != null && directPart.exists()) {
                    if (directPart.delete() == true || !directPart.exists()) {
                        deleted = true
                    }
                }
                dir.listFiles()?.forEach { file ->
                    val name = file.name()
                    if (name != null && name.startsWith(fileName) && name.endsWith(".part")) {
                        if (file.delete() == true || !file.exists()) {
                            deleted = true
                        }
                    }
                }
            }
        }

        return deleted
    }

    /**
     * Deletes orphaned partial (.part) files for a queued download wrapper.
     */
    fun deletePartial(context: Context, wrapper: DownloadObjects.DownloadQueueWrapper): Boolean {
        return deletePartial(context, wrapper.id)
    }

    internal fun getFolder(currentType: TvType, titleName: String): String {
        return if (currentType.isEpisodeBased()) {
            val sanitizedFolderName = FileNameSanitizer.sanitizeDirectoryName(titleName)
            "${currentType.getFolderPrefix()}/$sanitizedFolderName"
        } else currentType.getFolderPrefix()
    }

    /**
     * Gets the default download path as an UniFile.
     * Vital for legacy downloads, be careful about changing anything here.
     * */
    fun getDefaultDir(context: Context): SafeFile? {
        return SafeFile.fromMedia(
            context, MediaFileContentType.Downloads
        )
    }
}
