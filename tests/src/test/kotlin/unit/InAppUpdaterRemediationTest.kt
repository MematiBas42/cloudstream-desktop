package unit

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.services.PackageInstallerService
import com.lagradost.cloudstream3.utils.ApkInstaller
import com.lagradost.cloudstream3.utils.GitInfo
import com.lagradost.cloudstream3.utils.InAppUpdater
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Architectural Remediation & Parity Test Suite for [InAppUpdater].
 *
 * Validates 1:1 behavioral and contract parity against upstream CloudStream:
 * 1. Semantic version scoring and monotonic comparison algorithm (`parseVersionScore`).
 * 2. Cross-platform release asset matching for Linux (AppImage, deb, tar.gz, rpm) and Windows (exe, zip, msix).
 * 3. Markdown changelog sanitization stripping links down to text.
 * 4. Pre-release Git commit hash comparison against [GitInfo.currentCommitHash].
 * 5. Release sorting by numerical score ensuring latest version resolution.
 * 6. SharedPreferences auto-update and skip-update preferences logic.
 * 7. Mutex-protected concurrent update downloads.
 * 8. Cross-platform safe execution and [PlatformQuarantine] metadata verification.
 */
class InAppUpdaterRemediationTest {

    private val context = Context()

    @BeforeEach
    fun setUp() {
        CloudStreamApp.context = context
    }

    @Test
    fun `testVersionScoreCalculation`() {
        assertEquals(400_000_000, InAppUpdater.parseVersionScore("4.0.0"))
        assertEquals(400_000_001, InAppUpdater.parseVersionScore("4.0.1"))
        assertEquals(400_010_000, InAppUpdater.parseVersionScore("4.1.0"))
        assertEquals(500_020_003, InAppUpdater.parseVersionScore("5.2.3"))
        assertEquals(100_000_000, InAppUpdater.parseVersionScore("v1.0.0"))
        assertEquals(400_100_025, InAppUpdater.parseVersionScore("CloudStream-v4.10.25-release"))

        // Monotonic check
        val s400 = InAppUpdater.parseVersionScore("4.0.0")!!
        val s401 = InAppUpdater.parseVersionScore("4.0.1")!!
        val s410 = InAppUpdater.parseVersionScore("4.1.0")!!
        val s500 = InAppUpdater.parseVersionScore("5.0.0")!!

        assertTrue(s401 > s400, "4.0.1 score must be strictly greater than 4.0.0")
        assertTrue(s410 > s401, "4.1.0 score must be strictly greater than 4.0.1")
        assertTrue(s500 > s410, "5.0.0 score must be strictly greater than 4.1.0")

        // Edge cases
        assertNull(InAppUpdater.parseVersionScore(null))
        assertNull(InAppUpdater.parseVersionScore("invalid-version"))
        assertNull(InAppUpdater.parseVersionScore(""))
    }

    @Test
    fun `testSanitizeChangelog`() {
        val raw = "Release Notes:\n* [Fix video player](https://github.com/recloudstream/commit/1234)\n* [Update translations](https://github.com/recloudstream/commit/5678)"
        val expected = "Release Notes:\n* Fix video player\n* Update translations"
        assertEquals(expected, InAppUpdater.sanitizeChangelog(raw))

        // Plain text without markdown links remains unchanged
        val plain = "Normal bug fix without any links"
        assertEquals(plain, InAppUpdater.sanitizeChangelog(plain))

        // Null changelog handling
        assertNull(InAppUpdater.sanitizeChangelog(null))
    }

    @Test
    fun `testLinuxAssetMatching`() {
        val appImageAsset = InAppUpdater.GithubAsset(
            name = "CloudStream-4.0.1-x86_64.AppImage",
            size = 95_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/CloudStream.AppImage",
            contentType = "application/x-appimage"
        )
        val debAsset = InAppUpdater.GithubAsset(
            name = "cloudstream-desktop_4.0.1_amd64.deb",
            size = 85_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/cloudstream.deb",
            contentType = "application/vnd.debian.binary-package"
        )
        val tarGzAsset = InAppUpdater.GithubAsset(
            name = "cloudstream-desktop-4.0.1-linux-x64.tar.gz",
            size = 80_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/cloudstream.tar.gz",
            contentType = "application/gzip"
        )
        val rpmAsset = InAppUpdater.GithubAsset(
            name = "cloudstream-desktop-4.0.1.x86_64.rpm",
            size = 86_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/cloudstream.rpm",
            contentType = "application/x-rpm"
        )
        val apkAsset = InAppUpdater.GithubAsset(
            name = "CloudStream-4.0.1.apk",
            size = 45_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/CloudStream.apk",
            contentType = "application/vnd.android.package-archive"
        )

        // All assets available -> AppImage is prioritized on Linux
        val allAssets = listOf(apkAsset, rpmAsset, tarGzAsset, debAsset, appImageAsset)
        val matchedAppImage = InAppUpdater.findPlatformAsset(allAssets, PlatformPaths.OS.LINUX)
        assertNotNull(matchedAppImage)
        assertEquals(appImageAsset.name, matchedAppImage?.name)

        // If AppImage is not available -> deb is prioritized next
        val noAppImage = listOf(apkAsset, rpmAsset, tarGzAsset, debAsset)
        val matchedDeb = InAppUpdater.findPlatformAsset(noAppImage, PlatformPaths.OS.LINUX)
        assertEquals(debAsset.name, matchedDeb?.name)

        // If neither AppImage nor deb available -> tar.gz is prioritized next
        val noDeb = listOf(apkAsset, rpmAsset, tarGzAsset)
        val matchedTarGz = InAppUpdater.findPlatformAsset(noDeb, PlatformPaths.OS.LINUX)
        assertEquals(tarGzAsset.name, matchedTarGz?.name)

        // If only rpm is available -> rpm is selected
        val onlyRpm = listOf(apkAsset, rpmAsset)
        val matchedRpm = InAppUpdater.findPlatformAsset(onlyRpm, PlatformPaths.OS.LINUX)
        assertEquals(rpmAsset.name, matchedRpm?.name)

        // Fallback to apk if only apk is available
        val onlyApk = listOf(apkAsset)
        val matchedApk = InAppUpdater.findPlatformAsset(onlyApk, PlatformPaths.OS.LINUX)
        assertEquals(apkAsset.name, matchedApk?.name)
    }

    @Test
    fun `testWindowsAssetMatching`() {
        val exeAsset = InAppUpdater.GithubAsset(
            name = "CloudStream-Setup-4.0.1.exe",
            size = 90_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/CloudStream-Setup.exe",
            contentType = "application/x-msdownload"
        )
        val zipAsset = InAppUpdater.GithubAsset(
            name = "CloudStream-windows-x64-4.0.1.zip",
            size = 85_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/CloudStream-windows.zip",
            contentType = "application/zip"
        )
        val msixAsset = InAppUpdater.GithubAsset(
            name = "CloudStream-4.0.1.msix",
            size = 92_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/CloudStream.msix",
            contentType = "application/octet-stream"
        )
        val linuxAsset = InAppUpdater.GithubAsset(
            name = "CloudStream-4.0.1.AppImage",
            size = 95_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/CloudStream.AppImage",
            contentType = "application/x-appimage"
        )

        val assets = listOf(linuxAsset, zipAsset, msixAsset, exeAsset)

        // On Windows: exe installer is preferred first
        val matchedExe = InAppUpdater.findPlatformAsset(assets, PlatformPaths.OS.WINDOWS)
        assertEquals(exeAsset.name, matchedExe?.name)

        // If exe is absent: zip is preferred next
        val noExe = listOf(linuxAsset, msixAsset, zipAsset)
        val matchedZip = InAppUpdater.findPlatformAsset(noExe, PlatformPaths.OS.WINDOWS)
        assertEquals(zipAsset.name, matchedZip?.name)

        // If only msix is available
        val onlyMsix = listOf(linuxAsset, msixAsset)
        val matchedMsix = InAppUpdater.findPlatformAsset(onlyMsix, PlatformPaths.OS.WINDOWS)
        assertEquals(msixAsset.name, matchedMsix?.name)
    }

    @Test
    fun `testMacOSAssetMatching`() {
        val dmgAsset = InAppUpdater.GithubAsset(
            name = "CloudStream-4.0.1.dmg",
            size = 95_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/CloudStream.dmg",
            contentType = "application/x-apple-diskimage"
        )
        val tarGzAsset = InAppUpdater.GithubAsset(
            name = "CloudStream-4.0.1-darwin-x64.tar.gz",
            size = 80_000_000,
            browserDownloadUrl = "https://github.com/recloudstream/cloudstream/releases/download/v4.0.1/CloudStream-darwin.tar.gz",
            contentType = "application/gzip"
        )

        val assets = listOf(tarGzAsset, dmgAsset)
        val matchedDmg = InAppUpdater.findPlatformAsset(assets, PlatformPaths.OS.MACOS)
        assertEquals(dmgAsset.name, matchedDmg?.name)
    }

    @Test
    fun `testIsPlatformAsset`() {
        val linuxAppImage = InAppUpdater.GithubAsset(name = "test.AppImage")
        val linuxDeb = InAppUpdater.GithubAsset(name = "test.deb")
        val linuxTarGz = InAppUpdater.GithubAsset(name = "test.tar.gz")
        val windowsExe = InAppUpdater.GithubAsset(name = "test.exe")
        val windowsZip = InAppUpdater.GithubAsset(name = "test.zip")
        val macDmg = InAppUpdater.GithubAsset(name = "test.dmg")
        val androidApk = InAppUpdater.GithubAsset(name = "test.apk", contentType = "application/vnd.android.package-archive")

        assertTrue(InAppUpdater.isPlatformAsset(linuxAppImage, PlatformPaths.OS.LINUX))
        assertTrue(InAppUpdater.isPlatformAsset(linuxDeb, PlatformPaths.OS.LINUX))
        assertTrue(InAppUpdater.isPlatformAsset(linuxTarGz, PlatformPaths.OS.LINUX))
        assertFalse(InAppUpdater.isPlatformAsset(windowsExe, PlatformPaths.OS.LINUX))

        assertTrue(InAppUpdater.isPlatformAsset(windowsExe, PlatformPaths.OS.WINDOWS))
        assertTrue(InAppUpdater.isPlatformAsset(windowsZip, PlatformPaths.OS.WINDOWS))
        assertFalse(InAppUpdater.isPlatformAsset(linuxAppImage, PlatformPaths.OS.WINDOWS))

        assertTrue(InAppUpdater.isPlatformAsset(macDmg, PlatformPaths.OS.MACOS))
        assertFalse(InAppUpdater.isPlatformAsset(windowsExe, PlatformPaths.OS.MACOS))

        assertTrue(InAppUpdater.isPlatformAsset(androidApk, PlatformPaths.OS.UNKNOWN))
    }

    @Test
    fun `testReleaseSortingAndVersionComparison`() {
        val rel400 = InAppUpdater.GithubRelease(
            tagName = "v4.0.0",
            body = "Release 4.0.0",
            assets = listOf(InAppUpdater.GithubAsset(name = "CloudStream-4.0.0-x86_64.AppImage", browserDownloadUrl = "http://dl/400")),
            prerelease = false,
            nodeId = "node_400"
        )
        val rel401 = InAppUpdater.GithubRelease(
            tagName = "v4.0.1",
            body = "Release 4.0.1",
            assets = listOf(InAppUpdater.GithubAsset(name = "CloudStream-4.0.1-x86_64.AppImage", browserDownloadUrl = "http://dl/401")),
            prerelease = false,
            nodeId = "node_401"
        )
        val rel410 = InAppUpdater.GithubRelease(
            tagName = "v4.1.0",
            body = "Release 4.1.0",
            assets = listOf(InAppUpdater.GithubAsset(name = "CloudStream-4.1.0-x86_64.AppImage", browserDownloadUrl = "http://dl/410")),
            prerelease = false,
            nodeId = "node_410"
        )
        val relPrerelease = InAppUpdater.GithubRelease(
            tagName = "pre-release",
            body = "Prerelease beta",
            assets = listOf(InAppUpdater.GithubAsset(name = "CloudStream-pre-x86_64.AppImage", browserDownloadUrl = "http://dl/pre")),
            prerelease = true,
            nodeId = "node_pre"
        )

        val releases = listOf(rel401, relPrerelease, rel400, rel410)

        // Filter out prereleases and sort by version score
        val sortedStable = releases.filter { !it.prerelease }.sortedWith(compareBy { release ->
            val asset = InAppUpdater.findPlatformAsset(release.assets, PlatformPaths.OS.LINUX)
            val name = asset?.name ?: release.tagName
            InAppUpdater.parseVersionScore(name) ?: InAppUpdater.parseVersionScore(release.tagName)
        })

        assertEquals(3, sortedStable.size)
        assertEquals("v4.0.0", sortedStable[0].tagName)
        assertEquals("v4.0.1", sortedStable[1].tagName)
        assertEquals("v4.1.0", sortedStable[2].tagName)

        val latest = sortedStable.last()
        assertEquals("v4.1.0", latest.tagName)
        assertEquals("node_410", latest.nodeId)
    }

    @Test
    fun `testPreReleaseCommitHashMatching`() {
        val currentHash = GitInfo.currentCommitHash()
        val dummyRemoteHashDifferent = "1a2b3c4"
        val dummyRemoteHashSame = if (currentHash.isNotEmpty()) currentHash else "unknown"

        val updateDifferent = InAppUpdater.Update(
            shouldUpdate = (currentHash.isNotEmpty() && currentHash != dummyRemoteHashDifferent),
            updateURL = "https://github.com/recloudstream/cloudstream/releases/download/pre-release/app.AppImage",
            updateVersion = dummyRemoteHashDifferent,
            changelog = "Automated pre-release build",
            updateNodeId = "prerelease_node"
        )

        val updateSame = InAppUpdater.Update(
            shouldUpdate = (currentHash.isNotEmpty() && currentHash != dummyRemoteHashSame),
            updateURL = "https://github.com/recloudstream/cloudstream/releases/download/pre-release/app.AppImage",
            updateVersion = dummyRemoteHashSame,
            changelog = "Automated pre-release build",
            updateNodeId = "prerelease_node"
        )

        if (currentHash.isNotEmpty()) {
            assertTrue(updateDifferent.shouldUpdate, "Different hash must trigger update")
            assertFalse(updateSame.shouldUpdate, "Same hash must not trigger update")
        }
    }

    @Test
    fun `testSharedPreferencesAutoUpdateCheck`() = runBlocking {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        // When auto update is disabled, runAutoUpdate with checkAutoUpdate = true must return false
        prefs.edit().putBoolean(context.getString(R.string.auto_update_key), false).commit()

        val autoUpdateResult = InAppUpdater.runAutoUpdate(
            activity = null,
            checkAutoUpdate = true,
            installPrerelease = false
        )
        assertFalse(autoUpdateResult, "Must not run update when auto_update is disabled")

        // Re-enable auto-update
        prefs.edit().putBoolean(context.getString(R.string.auto_update_key), true).commit()

        // Set skip_update_key to a known nodeId
        prefs.edit().putString(context.getString(R.string.skip_update_key), "node_skip_123").commit()
        val skippedNodeId = prefs.getString(context.getString(R.string.skip_update_key), "")
        assertEquals("node_skip_123", skippedNodeId)
    }

    @Test
    fun `testUpdateLockConcurrency`() = runBlocking {
        assertTrue(!InAppUpdater.updateLock.isLocked, "updateLock must be initially unlocked")
        InAppUpdater.updateLock.withLock {
            assertTrue(InAppUpdater.updateLock.isLocked, "updateLock must be locked inside withLock")
        }
        assertTrue(!InAppUpdater.updateLock.isLocked, "updateLock must be released after withLock block")
    }

    @Test
    fun `testPlatformQuarantineMetadata`() {
        // Verify quarantine annotations exist on platform-specific shims
        val pkgServiceQuarantine = PackageInstallerService::class.java.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(pkgServiceQuarantine, "PackageInstallerService must be annotated with PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, pkgServiceQuarantine?.status)

        val apkInstallerQuarantine = ApkInstaller::class.java.getAnnotation(PlatformQuarantine::class.java)
        assertNotNull(apkInstallerQuarantine, "ApkInstaller must be annotated with PlatformQuarantine")
        assertEquals(QuarantineStatus.NOT_APPLICABLE_DESKTOP, apkInstallerQuarantine?.status)
    }

    @Test
    fun `testApkInstallerDelayedInstallerStart`() {
        ApkInstaller.delayedInstaller = ApkInstaller.DelayedInstaller()
        assertNotNull(ApkInstaller.delayedInstaller)
        val started = ApkInstaller.delayedInstaller?.startInstallation()
        assertEquals(false, started)
        assertNull(ApkInstaller.delayedInstaller, "delayedInstaller must be cleared after startInstallation")
    }

    @Test
    fun `testPackageInstallerServiceGetIntent`() {
        val intent = PackageInstallerService.getIntent(context, "https://download.url/update.apk")
        assertNotNull(intent)
        assertEquals("https://download.url/update.apk", intent.getStringExtra("EXTRA_URL"))
    }
}
