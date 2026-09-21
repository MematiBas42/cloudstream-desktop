export const meta = {
  name: "cloudstream-missing-double-checks",
  description: "12-agent targeted double-check for clusters skipped due to 429 rate limit + master synthesis",
  phases: [
    { title: "Double-Check Adversarial Verification", detail: "Independent static audit of Stage 1 fixes for 12 remaining clusters" },
    { title: "Master Synthesis", detail: "Compile final Master Remediation Index across all 40 clusters" }
  ]
};

const VERIFICATION_RESULT_SCHEMA = {
  type: "object",
  properties: {
    clusterId: { type: "string" },
    reportPath: { type: "string" },
    verdict: { type: "string", enum: ["DOUBLE_CHECKED_VERIFIED", "POLISH_APPLIED", "DEFECT_REMAINING"] },
    parityMatchRatio: { type: "number" },
    stubCount: { type: "number" },
    testValidationNotes: { type: "string" },
    verificationSummary: { type: "string" }
  },
  required: ["clusterId", "reportPath", "verdict", "parityMatchRatio", "stubCount", "testValidationNotes", "verificationSummary"]
};

const MISSING_CLUSTERS = [
  {
    id: "C18_CS3IPlayer",
    title: "CS3IPlayer: Track-List & Torrent Metrics Event Wiring",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt",
    testFile: "tests/src/test/kotlin/unit/CS3IPlayerRemediationTest.kt",
    reportFile: "docs/fix/C18_CS3IPlayer.md",
    defects: [
      "Wire track-list parsing into EmbeddedSubtitlesFetchedEvent for embedded audio and subtitle tracks.",
      "Wire TorrServerClient speed and peer metrics into torrentEventLooper."
    ]
  },
  {
    id: "C25_PlayerGestureHelper",
    title: "PlayerGestureHelper: Mouse Scroll Scrub & Volume Drag Parity",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/PlayerGestureHelper.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/PlayerGestureHelper.kt",
    testFile: "tests/src/test/kotlin/unit/PlayerGestureHelperRemediationTest.kt",
    reportFile: "docs/fix/C25_PlayerGestureHelper.md",
    defects: [
      "Map mouse scroll-wheel scrubbing and volume drag cleanly to DesktopPlayerView."
    ]
  },
  {
    id: "C30_LogcatParser",
    title: "LogcatParser: Desktop File Log Reading Fallback",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/logcat/LogcatParser.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/settings/logcat/LogcatParser.kt",
    testFile: "tests/src/test/kotlin/unit/LogcatParserRemediationTest.kt",
    reportFile: "docs/fix/C30_LogcatParser.md",
    defects: [
      "L42: Replace Android logcat -d shell execution with reading PlatformPaths.logDir/app.log."
    ]
  },
  {
    id: "C31_ExtensionsViewModel",
    title: "ExtensionsViewModel: Desktop Plugin Update Channels Parity",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsViewModel.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsViewModel.kt",
    testFile: "tests/src/test/kotlin/unit/ExtensionsViewModelRemediationTest.kt",
    reportFile: "docs/fix/C31_ExtensionsViewModel.md",
    defects: [
      "Align repository synchronization with DesktopPluginManager update channels."
    ]
  },
  {
    id: "C32_SettingsGeneral",
    title: "SettingsGeneral: CustomSite Wire Contract Parity",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt",
    targetFile: "desktop-app/src/main/kotlin/com/lagradost/cloudstream3/desktop/settings/SettingsGeneral.kt",
    testFile: "tests/src/test/kotlin/unit/SettingsGeneralRemediationTest.kt",
    reportFile: "docs/fix/C32_SettingsGeneral.md",
    defects: [
      "Realign CustomSite JSON model serialization properties with upstream."
    ]
  },
  {
    id: "C33_DownloadQueueViewModel",
    title: "DownloadQueueViewModel: Queue Item Partial File Cleanup",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/download/queue/DownloadQueueViewModel.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/download/queue/DownloadQueueViewModel.kt",
    testFile: "tests/src/test/kotlin/unit/DownloadQueueRemediationTest.kt",
    reportFile: "docs/fix/C33_DownloadQueueViewModel.md",
    defects: [
      "L60: Trigger DownloadFileManagement.deletePartial() when deleting a queue item to avoid orphaned part files."
    ]
  },
  {
    id: "C34_BackupUtils",
    title: "BackupUtils: Download Header Cache Whitelist Parity",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/BackupUtils.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/BackupUtils.kt",
    testFile: "tests/src/test/kotlin/unit/BackupUtilsRemediationTest.kt",
    reportFile: "docs/fix/C34_BackupUtils.md",
    defects: [
      "L95: Remove download_header_cache from prohibited keys so offline download headers are retained during backup/restore."
    ]
  },
  {
    id: "C36_AppContextUtils",
    title: "AppContextUtils: Search Pagination startValue Parameter",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/AppContextUtils.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/AppContextUtils.kt",
    testFile: "tests/src/test/kotlin/unit/AppContextUtilsRemediationTest.kt",
    reportFile: "docs/fix/C36_AppContextUtils.md",
    defects: [
      "L145: Forward startValue parameter to SearchViewModel in loadSearchResult to preserve pagination index."
    ]
  },
  {
    id: "C37_InAppUpdater",
    title: "InAppUpdater: Porting Missing Desktop GitHub Releases Updater",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/InAppUpdater.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/InAppUpdater.kt",
    testFile: "tests/src/test/kotlin/unit/InAppUpdaterRemediationTest.kt",
    reportFile: "docs/fix/C37_InAppUpdater.md",
    defects: [
      "Port missing InAppUpdater.kt from upstream (372 lines) with exact class structure.",
      "Adapt release asset parser to match Linux (AppImage, deb, tar.gz) and Windows (zip, exe) assets from GitHub Releases API."
    ]
  },
  {
    id: "C38_UiImage",
    title: "UiImage: Skia Image & BufferedImage Conversion Parity",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/ImageUtil.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/UiImage.kt",
    testFile: "tests/src/test/kotlin/unit/UiImageRemediationTest.kt",
    reportFile: "docs/fix/C38_UiImage.md",
    defects: [
      "L25: Port missing bitmap conversion functions using Skia Image and BufferedImage."
    ]
  },
  {
    id: "C39_UIHelper",
    title: "UIHelper: Clipboard Safe Access & Dialog Helpers",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/UIHelper.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/UIHelper.kt",
    testFile: "tests/src/test/kotlin/unit/UIHelperRemediationTest.kt",
    reportFile: "docs/fix/C39_UIHelper.md",
    defects: [
      "L110: Guard clipboard access with java.awt.datatransfer.Clipboard retry mechanism.",
      "Verify SingleSelectionHelper and TextUtil parity."
    ]
  },
  {
    id: "C40_CastHelper",
    title: "CastHelper: DLNA Media Info Metadata Payload Parity",
    upstreamFile: "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/CastHelper.kt",
    targetFile: "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/CastHelper.kt",
    testFile: "tests/src/test/kotlin/unit/CastHelperRemediationTest.kt",
    reportFile: "docs/fix/C40_CastHelper.md",
    defects: [
      "L37: Populate media info metadata payload for DLNA / smart TV streaming.",
      "Ensure BiometricAuthenticator and PowerManagerAPI carry explicit @PlatformQuarantine(NOT_APPLICABLE_DESKTOP) annotations."
    ]
  }
];

log("Starting 12-agent targeted double-check for missing clusters (C18, C25, C30-C34, C36-C40)...");

phase("Double-Check Adversarial Verification");

const verificationResults = await parallel(
  MISSING_CLUSTERS.map(cluster => async () => {
    const defectsList = cluster.defects.map(d => "  - " + d).join("\n");

    const prompt = `# CLOUDSTREAM INDEPENDENT PARITY VERIFIER (STAGE 2: DOUBLE-CHECK REVIEW)
YOU ARE ASSIGNED TO DOUBLE-CHECK ATOMIC CLUSTER: ${cluster.id} (${cluster.title})

## ⛔ KESİNLİKLE DERLEME VE TEST KOŞMAYIN (STRICT STATIC AUDIT ONLY)
- Terminalde derleme veya test KOŞMAYIN. Yalnızca Read, Grep, Edit araçlarıyla satır satır denetleyin.

## GÖREV TANIMI:
Stage 1 ajanı bu küme için aşağıdaki dosyaları düzenledi ve raporu oluşturdu:
- Düzenlenen Masaüstü Dosyası: ${cluster.targetFile}
- Yazılan Test Dosyası: ${cluster.testFile}
- Fix Raporu: ${cluster.reportFile}

### GİDERİLMESİ GEREKEN KUSURLAR:
${defectsList}

## 4 AŞAMALI BAĞIMSIZ DENETİM PROTOKOLÜ:
1. **Satır Satır Parite Denetimi:** ${cluster.targetFile} dosyasını açın ve ${cluster.upstreamFile} ile satır satır karşılaştırın. Fonksiyon isimleri, tipler ve upstream davranışı 1:1 korunmuş mu?
2. **Anti-Stub Taraması:** Düzenlenen dosyada TODO, NotImplementedError, sahte mock veya boş gövde ({ }) var mı? Varsa cerrahi olarak düzeltin.
3. **Test Kalitesi Denetimi:** ${cluster.testFile} dosyasını inceleyin. Testler gerçek mantığı ve sınır durumlarını test ediyor mu, yoksa mock tiyatrosu mu?
4. **Rapor Güncelleme:** ${cluster.reportFile} dosyasının sonuna "## 7. Independent Double-Check Verdict" başlığı altında denetim sonucunuzu ekleyin. Gerekirse koda cerrahi rötuş uygulayın.

Denetim sonucunda şemaya uygun JSON nesnesini döndürün.`;

    return await agent(prompt, {
      label: "double-check:" + cluster.id,
      phase: "Double-Check Adversarial Verification",
      schema: VERIFICATION_RESULT_SCHEMA
    });
  })
);

// Master Synthesis
phase("Master Synthesis");
log("All 12 missing double-checks completed. Running master synthesis...");

await agent(
  `You are the Master Remediation Synthesizer on CloudStream Native Desktop.
Read ALL 40 cluster fix reports located in docs/fix/C*.md (C01 through C40).
Compile an exhaustive, publication-grade Master Remediation Index at docs/fix/00_MASTER_FIX_INDEX.md containing:
1. Executive Summary: Total atomic clusters fixed (40/40), total defects eliminated across all packages.
2. Master Fix Parity Matrix: Table of all 40 clusters with target files, tests created, double-check verdicts, and final 1:1 official parity status.
3. Deep-Dive of Key Critical Architectural Fixes:
   - MpvPlayer local file / offline media targetLink routing and absolute seek.
   - CommonActivity startup temporary torrent file cleanup.
   - SSLTrustManager porting and MPV --tls-verify=no integration.
   - InAppUpdater desktop porting.
   - LinearListLayout porting.
   - VideoClickAction and notification cross-platform abstractions.
4. Comprehensive Test Suite Catalog: List of all 40 newly authored JUnit 5 test classes and coverage areas.
5. Final Updated Project Parity Score and Master Verdict.
Write the report to docs/fix/00_MASTER_FIX_INDEX.md using the Write tool.`,
  { label: "master-remediation-synthesis", phase: "Master Synthesis" }
);

return { missingClustersVerified: verificationResults.filter(Boolean).length };
