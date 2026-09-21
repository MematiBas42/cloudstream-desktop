export const meta = {
  name: "upstream-backend-parity-audit-v2",
  description: "106-agent deterministic line-by-line audit of CloudStream upstream backend parity with Windows compatibility assessment",
  phases: [
    { title: "Core Lifecycle & Network", detail: "Audit root, actions, network, mvvm (balanced workload)" },
    { title: "Plugins & Background Services", detail: "Audit plugin manager, repos, services, workers" },
    { title: "ResultViewModel2 & Details", detail: "Audit ResultViewModel2 parts 1-5, result helpers" },
    { title: "Player, Streaming & Subtitles", detail: "Audit GeneratorPlayer parts 1-5, IPlayer, CS3IPlayer, subtitles" },
    { title: "Sync Providers & Scrobblers", detail: "Audit AniList, Simkl, Kitsu, MAL, subtitle providers" },
    { title: "Search, Downloader & Utils", detail: "Audit search, library, home, downloader, datastore, utils" },
    { title: "Master Synthesis", detail: "Compile overall parity matrix, Windows assessment, and 00_MASTER_AUDIT_INDEX.md" }
  ]
};

const AUDIT_RESULT_SCHEMA = {
  type: "object",
  properties: {
    packageId: { type: "string" },
    upstreamFiles: { type: "array", items: { type: "string" } },
    desktopFiles: { type: "array", items: { type: "string" } },
    upstreamLines: { type: "number" },
    desktopLines: { type: "number" },
    parityPercentage: { type: "number" },
    missingFunctionsCount: { type: "number" },
    stubCount: { type: "number" },
    status: { type: "string", enum: ["VERIFIED_PARITY", "PARTIAL_PARITY", "DEFECTS_FOUND", "UNPORTED"] },
    criticalFindings: { type: "array", items: { type: "string" } },
    windowsCompatibilityIssues: { type: "array", items: { type: "string" } },
    reportPath: { type: "string" }
  },
  required: [
    "packageId", "upstreamFiles", "desktopFiles", "upstreamLines", "desktopLines",
    "parityPercentage", "missingFunctionsCount", "stubCount", "status", "criticalFindings",
    "windowsCompatibilityIssues", "reportPath"
  ]
};

const PACKAGES = [
  {
    "id": "01_root_cloudstream_app_grp1_grp1_grp1",
    "phase": "Core Lifecycle & Network",
    "title": "CloudStreamApp & Root Init (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/CloudStreamApp.kt",
      "com/lagradost/cloudstream3/AcraApplication.kt",
      "com/lagradost/cloudstream3/DownloaderTestImpl.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/CloudStreamApp.kt",
    "domainRequirements": [
      "Verify static getKey/setKey/setKeyRaw delegation to DataStore.",
      "Check Application lifecycle callbacks (onCreate, onTerminate) and context initialization.",
      "Check ACRA crash reporting adaptation or quarantine status on desktop.",
      "Inspect DownloaderTestImpl stubbing vs actual implementation."
    ]
  },
  {
    "id": "02_root_common_activity_commonactivity_p1_commonactivity_p1_commonactivity_p1",
    "phase": "Core Lifecycle & Network",
    "title": "CommonActivity Base & Global Event Bus: CommonActivity.kt (Part 1/1): CommonActivity.kt (Part 1/1): CommonActivity.kt (Part 1/1)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/CommonActivity.kt"
    ],
    "lineRange": "1-607",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/CommonActivity.kt",
    "domainRequirements": [
      "Verify dispatchKeyEvent handling (media keys, D-pad, keyboard shortcuts).",
      "Inspect Activity-level UI event bus listeners and onUserLeaveHint handling.",
      "Check picture-in-picture (PiP) and orientation listener desktop adaptations."
    ]
  },
  {
    "id": "03_root_main_activity_bootstrap_mainactivity_p1_mainactivity_p1_mainactivity_p1",
    "phase": "Core Lifecycle & Network",
    "title": "MainActivity Bootstrap & Intent Routing: MainActivity.kt (Part 1/4): MainActivity.kt (Part 1/4): MainActivity.kt (Part 1/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/MainActivity.kt"
    ],
    "lineRange": "1-533",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/MainActivity.kt",
    "domainRequirements": [
      "Verify intent handling (ACTION_VIEW, search intents, deep links, torrent links).",
      "Inspect plugin loader bootstrap sequence (initPlugins, reloadPlugins).",
      "Verify back button press dispatcher (onBackPressedDispatcher) and back-stack logic.",
      "Check notification permission and background service trigger parity."
    ]
  },
  {
    "id": "03_root_main_activity_bootstrap_mainactivity_p1_mainactivity_p1_mainactivity_p2",
    "phase": "Core Lifecycle & Network",
    "title": "MainActivity Bootstrap & Intent Routing: MainActivity.kt (Part 1/4): MainActivity.kt (Part 1/4): MainActivity.kt (Part 2/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/MainActivity.kt"
    ],
    "lineRange": "534-1033",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/MainActivity.kt",
    "domainRequirements": [
      "Verify intent handling (ACTION_VIEW, search intents, deep links, torrent links).",
      "Inspect plugin loader bootstrap sequence (initPlugins, reloadPlugins).",
      "Verify back button press dispatcher (onBackPressedDispatcher) and back-stack logic.",
      "Check notification permission and background service trigger parity."
    ]
  },
  {
    "id": "03_root_main_activity_bootstrap_mainactivity_p1_mainactivity_p1_mainactivity_p3",
    "phase": "Core Lifecycle & Network",
    "title": "MainActivity Bootstrap & Intent Routing: MainActivity.kt (Part 1/4): MainActivity.kt (Part 1/4): MainActivity.kt (Part 3/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/MainActivity.kt"
    ],
    "lineRange": "1034-1552",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/MainActivity.kt",
    "domainRequirements": [
      "Verify intent handling (ACTION_VIEW, search intents, deep links, torrent links).",
      "Inspect plugin loader bootstrap sequence (initPlugins, reloadPlugins).",
      "Verify back button press dispatcher (onBackPressedDispatcher) and back-stack logic.",
      "Check notification permission and background service trigger parity."
    ]
  },
  {
    "id": "03_root_main_activity_bootstrap_mainactivity_p1_mainactivity_p1_mainactivity_p4",
    "phase": "Core Lifecycle & Network",
    "title": "MainActivity Bootstrap & Intent Routing: MainActivity.kt (Part 1/4): MainActivity.kt (Part 1/4): MainActivity.kt (Part 4/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/MainActivity.kt"
    ],
    "lineRange": "1553-2077",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/MainActivity.kt",
    "domainRequirements": [
      "Verify intent handling (ACTION_VIEW, search intents, deep links, torrent links).",
      "Inspect plugin loader bootstrap sequence (initPlugins, reloadPlugins).",
      "Verify back button press dispatcher (onBackPressedDispatcher) and back-stack logic.",
      "Check notification permission and background service trigger parity."
    ]
  },
  {
    "id": "04_actions_video_click_grp7_grp7_grp7",
    "phase": "Core Lifecycle & Network",
    "title": "Video Click Actions & Intent Dispatchers (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/actions/VideoClickAction.kt",
      "com/lagradost/cloudstream3/actions/AlwaysAskAction.kt",
      "com/lagradost/cloudstream3/actions/OpenInAppAction.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/actions/",
    "domainRequirements": [
      "Audit VideoClickAction enum/interface and execution contracts.",
      "Verify AlwaysAskAction dialog event triggering.",
      "Check OpenInAppAction player launching and URL parameter encapsulation."
    ]
  },
  {
    "id": "05_actions_temp_external_players_grp8_grp8_grp8",
    "phase": "Core Lifecycle & Network",
    "title": "External Player Action Packages (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/actions/temp/MpvPackage.kt",
      "com/lagradost/cloudstream3/actions/temp/MpvKtPackage.kt",
      "com/lagradost/cloudstream3/actions/temp/MpvRxPackage.kt",
      "com/lagradost/cloudstream3/actions/temp/VlcPackage.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/actions/temp/",
    "domainRequirements": [
      "Verify intent extras (headers, subtitles, resume position) for external player packages.",
      "Inspect package name declarations and intent creation algorithms.",
      "Check desktop quarantine or ProcessBuilder alternatives for external players."
    ]
  },
  {
    "id": "05_actions_temp_external_players_grp9_grp9_grp9",
    "phase": "Core Lifecycle & Network",
    "title": "External Player Action Packages (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/actions/temp/JustPlayerPackage.kt",
      "com/lagradost/cloudstream3/actions/temp/NextPlayerPackage.kt",
      "com/lagradost/cloudstream3/actions/temp/OnlyPlayer.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/actions/temp/",
    "domainRequirements": [
      "Verify intent extras (headers, subtitles, resume position) for external player packages.",
      "Inspect package name declarations and intent creation algorithms.",
      "Check desktop quarantine or ProcessBuilder alternatives for external players."
    ]
  },
  {
    "id": "06_actions_temp_torrents_and_tools_grp10_grp10_grp10",
    "phase": "Core Lifecycle & Network",
    "title": "Torrent Packages & Action Helpers (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/actions/temp/BiglyBTPackage.kt",
      "com/lagradost/cloudstream3/actions/temp/LibreTorrentPackage.kt",
      "com/lagradost/cloudstream3/actions/temp/Aria2Package.kt",
      "com/lagradost/cloudstream3/actions/temp/CloudStreamPackage.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/actions/temp/",
    "domainRequirements": [
      "Audit torrent client intent builders (BiglyBT, LibreTorrent, Aria2).",
      "Verify clipboard copy action and browser invocation logic on Linux (xdg-open).",
      "Check M3U8 link viewing action."
    ]
  },
  {
    "id": "06_actions_temp_torrents_and_tools_grp11_grp11_grp11",
    "phase": "Core Lifecycle & Network",
    "title": "Torrent Packages & Action Helpers (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/actions/temp/WebVideoCastPackage.kt",
      "com/lagradost/cloudstream3/actions/temp/PlayMirrorAction.kt",
      "com/lagradost/cloudstream3/actions/temp/PlayInBrowserAction.kt",
      "com/lagradost/cloudstream3/actions/temp/ViewM3U8Action.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/actions/temp/",
    "domainRequirements": [
      "Audit torrent client intent builders (BiglyBT, LibreTorrent, Aria2).",
      "Verify clipboard copy action and browser invocation logic on Linux (xdg-open).",
      "Check M3U8 link viewing action."
    ]
  },
  {
    "id": "06_actions_temp_torrents_and_tools_grp12_grp12_grp12",
    "phase": "Core Lifecycle & Network",
    "title": "Torrent Packages & Action Helpers (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/actions/temp/CopyClipboardAction.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/actions/temp/",
    "domainRequirements": [
      "Audit torrent client intent builders (BiglyBT, LibreTorrent, Aria2).",
      "Verify clipboard copy action and browser invocation logic on Linux (xdg-open).",
      "Check M3U8 link viewing action."
    ]
  },
  {
    "id": "07_actions_temp_fcast_grp13_grp13_grp13",
    "phase": "Core Lifecycle & Network",
    "title": "FCast Protocol & Remote Casting (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/actions/temp/fcast/FcastManager.kt",
      "com/lagradost/cloudstream3/actions/temp/fcast/FcastAction.kt",
      "com/lagradost/cloudstream3/actions/temp/fcast/FcastSession.kt",
      "com/lagradost/cloudstream3/actions/temp/fcast/Packets.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/actions/temp/fcast/",
    "domainRequirements": [
      "Verify FCast binary/JSON packet serialization and opcode definitions.",
      "Inspect FcastSession state machine and WebSocket/socket connection management.",
      "Check FcastManager discovery, device pairing, and playback command forwarding."
    ]
  },
  {
    "id": "08_mvvm_lifecycle_grp14_grp14_grp14",
    "phase": "Core Lifecycle & Network",
    "title": "MVVM Lifecycle & Coroutine Scopes (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/mvvm/Lifecycle.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/DesktopViewModel.kt",
    "domainRequirements": [
      "Verify ArchComponent lifecycle mapping to desktop.",
      "Ensure DesktopViewModel provides structured coroutine scope (SupervisorJob + Dispatchers.Main).",
      "Verify deterministic onCleared() invocation and memory leak prevention."
    ]
  },
  {
    "id": "09_network_interceptors_grp15_grp15_grp15",
    "phase": "Core Lifecycle & Network",
    "title": "Network Interceptors & DoH (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/network/CloudflareKiller.kt",
      "com/lagradost/cloudstream3/network/DdosGuardKiller.kt",
      "com/lagradost/cloudstream3/network/DohProviders.kt",
      "com/lagradost/cloudstream3/network/RequestsHelper.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/network/ or common/src/main/kotlin/com/lagradost/common/network/",
    "domainRequirements": [
      "Verify DdosGuardKiller pure OkHttp interceptor parity (cookie extraction, challenge response).",
      "Inspect CloudflareKiller WebView vs headless TLS/BoringSSL implementation.",
      "Verify DohProviders DNS servers (Google, Cloudflare, Quad9, AdGuard).",
      "Audit RequestsHelper timeout adjustments and custom headers."
    ]
  },
  {
    "id": "10_plugins_plugin_manager_pluginmanager_p1_pluginmanager_p1_pluginmanager_p1",
    "phase": "Plugins & Background Services",
    "title": "PluginManager Lifecycle & Unloader: PluginManager.kt (Part 1/2): PluginManager.kt (Part 1/2): PluginManager.kt (Part 1/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/plugins/PluginManager.kt"
    ],
    "lineRange": "1-484",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/plugins/PluginManager.kt",
    "domainRequirements": [
      "Audit plugin download, extraction, SHA256 checksum verification and local cache storage.",
      "Verify enablePlugin/disablePlugin state persistence and reload triggers.",
      "Check 7-step Metaspace leak-free unload cycle (beforeUnload, APIHolder, static nulling, URLClassLoader.close).",
      "Verify DesktopPluginManager consolidation (no multi-headed loader conflicts)."
    ]
  },
  {
    "id": "10_plugins_plugin_manager_pluginmanager_p1_pluginmanager_p1_pluginmanager_p2",
    "phase": "Plugins & Background Services",
    "title": "PluginManager Lifecycle & Unloader: PluginManager.kt (Part 1/2): PluginManager.kt (Part 1/2): PluginManager.kt (Part 2/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/plugins/PluginManager.kt"
    ],
    "lineRange": "485-968",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/plugins/PluginManager.kt",
    "domainRequirements": [
      "Audit plugin download, extraction, SHA256 checksum verification and local cache storage.",
      "Verify enablePlugin/disablePlugin state persistence and reload triggers.",
      "Check 7-step Metaspace leak-free unload cycle (beforeUnload, APIHolder, static nulling, URLClassLoader.close).",
      "Verify DesktopPluginManager consolidation (no multi-headed loader conflicts)."
    ]
  },
  {
    "id": "11_plugins_repo_and_voting_grp18_grp18_grp18",
    "phase": "Plugins & Background Services",
    "title": "RepositoryManager & Voting API (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/plugins/RepositoryManager.kt",
      "com/lagradost/cloudstream3/plugins/VotingApi.kt",
      "com/lagradost/cloudstream3/plugins/Plugin.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/plugins/",
    "domainRequirements": [
      "Verify multi-repository synchronization (fetchPlugins, getPlugins, parseRepository).",
      "Audit jsDelivr CDN fallback proxy and raw GitHub URL transformation.",
      "Check VotingApi endpoint calls, vote registration, and upvote/downvote models."
    ]
  },
  {
    "id": "12_services_subscription_work_manager_grp19_grp19_grp19",
    "phase": "Plugins & Background Services",
    "title": "SubscriptionWorkManager Episode Scanner (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/services/SubscriptionWorkManager.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/services/SubscriptionWorkManager.kt",
    "domainRequirements": [
      "Verify periodic episode scanning algorithm over subscribed items.",
      "Check Android WorkManager conversion to Linux coroutine timer daemon.",
      "Verify Freedesktop D-Bus notification publishing (org.freedesktop.Notifications) with poster and action payload."
    ]
  },
  {
    "id": "13_services_backup_work_manager_grp20_grp20_grp20",
    "phase": "Plugins & Background Services",
    "title": "BackupWorkManager Atomic Scheduler (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/services/BackupWorkManager.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/services/BackupWorkManager.kt",
    "domainRequirements": [
      "Verify periodic backup scheduler timing and trigger conditions.",
      "Check atomic ZIP backup archive creation and automatic old backup pruning.",
      "Ensure clean cancellation when disabled in settings."
    ]
  },
  {
    "id": "14_services_download_services_grp21_grp21_grp21",
    "phase": "Plugins & Background Services",
    "title": "Video Download & Queue Services (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/services/VideoDownloadService.kt",
      "com/lagradost/cloudstream3/services/DownloadQueueService.kt",
      "com/lagradost/cloudstream3/receivers/VideoDownloadRestartReceiver.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/services/",
    "domainRequirements": [
      "Verify VideoDownloadService foreground service conversion to POSIX daemon.",
      "Audit DownloadQueueService queue persistence, pause/resume, and crash recovery.",
      "Check systemd-inhibit sleep inhibition while downloads are in flight."
    ]
  },
  {
    "id": "15_services_package_installer_grp22_grp22_grp22",
    "phase": "Plugins & Background Services",
    "title": "PackageInstallerService (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/services/PackageInstallerService.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/services/",
    "domainRequirements": [
      "Check Android PackageInstaller session handling.",
      "Verify desktop equivalent or @PlatformQuarantine annotation with status NOT_APPLICABLE_DESKTOP."
    ]
  },
  {
    "id": "16_subtitles_abstract_providers_grp23_grp23_grp23",
    "phase": "Plugins & Background Services",
    "title": "Abstract Subtitle Provider & Entities (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/subtitles/AbstractSubProvider.kt",
      "com/lagradost/cloudstream3/subtitles/AbstractSubtitleEntities.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/subtitles/",
    "domainRequirements": [
      "Audit AbstractSubProvider contracts (search, load, getLanguage, id).",
      "Verify SubtitleSearchResponse, SubtitleResource, and SubtitleData data classes."
    ]
  },
  {
    "id": "17_sync_account_manager_grp24_grp24_grp24",
    "phase": "Plugins & Background Services",
    "title": "Sync AccountManager (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/AccountManager.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/AccountManager.kt",
    "domainRequirements": [
      "Verify account token storage hierarchy (auth_tokens/${prefix}/${account}).",
      "Check multi-profile isolation, active account switching, and token revocation.",
      "Verify accounts listener dispatching."
    ]
  },
  {
    "id": "18_sync_auth_and_sync_api_grp25_grp25_grp25",
    "phase": "Plugins & Background Services",
    "title": "AuthAPI & SyncAPI Framework Core (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/AuthAPI.kt",
      "com/lagradost/cloudstream3/syncproviders/AuthRepo.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/",
    "domainRequirements": [
      "Audit AuthAPI contracts (loginUrl, handleRedirect, logOut, getPersonalLibrary).",
      "Verify SyncRepo.freshAuth() single-flight mutex protection (refreshMutexes).",
      "Check SyncAPI score, status, and progress sync models."
    ]
  },
  {
    "id": "18_sync_auth_and_sync_api_grp26_grp26_grp26",
    "phase": "Plugins & Background Services",
    "title": "AuthAPI & SyncAPI Framework Core (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/SyncAPI.kt",
      "com/lagradost/cloudstream3/syncproviders/SyncRepo.kt",
      "com/lagradost/cloudstream3/syncproviders/BackupAPI.kt",
      "com/lagradost/cloudstream3/syncproviders/SubtitleAPI.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/",
    "domainRequirements": [
      "Audit AuthAPI contracts (loginUrl, handleRedirect, logOut, getPersonalLibrary).",
      "Verify SyncRepo.freshAuth() single-flight mutex protection (refreshMutexes).",
      "Check SyncAPI score, status, and progress sync models."
    ]
  },
  {
    "id": "18_sync_auth_and_sync_api_grp27_grp27_grp27",
    "phase": "Plugins & Background Services",
    "title": "AuthAPI & SyncAPI Framework Core (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/SubtitleRepo.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/",
    "domainRequirements": [
      "Audit AuthAPI contracts (loginUrl, handleRedirect, logOut, getPersonalLibrary).",
      "Verify SyncRepo.freshAuth() single-flight mutex protection (refreshMutexes).",
      "Check SyncAPI score, status, and progress sync models."
    ]
  },
  {
    "id": "19_result_vm2_episodes_resultviewmodel2_p1_resultviewmodel2_p1_resultviewmodel2_p1",
    "phase": "ResultViewModel2 & Details",
    "title": "ResultVM2: Content Types, Seasons & Dub/Sub Logic: ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 1/5)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt"
    ],
    "lineRange": "1-540",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt",
    "domainRequirements": [
      "Verify 5 content types parsing: AnimeLoadResponse, TvSeriesLoadResponse, MovieLoadResponse, LiveStream, Torrent.",
      "Audit Dub/Sub range tolerance algorithm: minByOrNull { abs(it.startEpisode - range.startEpisode) }.",
      "Check cumulative totalIndex calculation and season/episode singleMap generation.",
      "Ensure ResultEpisode ID generation formula exactly matches upstream."
    ]
  },
  {
    "id": "19_result_vm2_episodes_resultviewmodel2_p1_resultviewmodel2_p1_resultviewmodel2_p2",
    "phase": "ResultViewModel2 & Details",
    "title": "ResultVM2: Content Types, Seasons & Dub/Sub Logic: ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 2/5)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt"
    ],
    "lineRange": "541-1080",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt",
    "domainRequirements": [
      "Verify 5 content types parsing: AnimeLoadResponse, TvSeriesLoadResponse, MovieLoadResponse, LiveStream, Torrent.",
      "Audit Dub/Sub range tolerance algorithm: minByOrNull { abs(it.startEpisode - range.startEpisode) }.",
      "Check cumulative totalIndex calculation and season/episode singleMap generation.",
      "Ensure ResultEpisode ID generation formula exactly matches upstream."
    ]
  },
  {
    "id": "19_result_vm2_episodes_resultviewmodel2_p1_resultviewmodel2_p1_resultviewmodel2_p3",
    "phase": "ResultViewModel2 & Details",
    "title": "ResultVM2: Content Types, Seasons & Dub/Sub Logic: ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 3/5)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt"
    ],
    "lineRange": "1081-1612",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt",
    "domainRequirements": [
      "Verify 5 content types parsing: AnimeLoadResponse, TvSeriesLoadResponse, MovieLoadResponse, LiveStream, Torrent.",
      "Audit Dub/Sub range tolerance algorithm: minByOrNull { abs(it.startEpisode - range.startEpisode) }.",
      "Check cumulative totalIndex calculation and season/episode singleMap generation.",
      "Ensure ResultEpisode ID generation formula exactly matches upstream."
    ]
  },
  {
    "id": "19_result_vm2_episodes_resultviewmodel2_p1_resultviewmodel2_p1_resultviewmodel2_p4",
    "phase": "ResultViewModel2 & Details",
    "title": "ResultVM2: Content Types, Seasons & Dub/Sub Logic: ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 4/5)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt"
    ],
    "lineRange": "1613-2166",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt",
    "domainRequirements": [
      "Verify 5 content types parsing: AnimeLoadResponse, TvSeriesLoadResponse, MovieLoadResponse, LiveStream, Torrent.",
      "Audit Dub/Sub range tolerance algorithm: minByOrNull { abs(it.startEpisode - range.startEpisode) }.",
      "Check cumulative totalIndex calculation and season/episode singleMap generation.",
      "Ensure ResultEpisode ID generation formula exactly matches upstream."
    ]
  },
  {
    "id": "19_result_vm2_episodes_resultviewmodel2_p1_resultviewmodel2_p1_resultviewmodel2_p5",
    "phase": "ResultViewModel2 & Details",
    "title": "ResultVM2: Content Types, Seasons & Dub/Sub Logic: ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 1/5): ResultViewModel2.kt (Part 5/5)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt"
    ],
    "lineRange": "2167-2710",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt",
    "domainRequirements": [
      "Verify 5 content types parsing: AnimeLoadResponse, TvSeriesLoadResponse, MovieLoadResponse, LiveStream, Torrent.",
      "Audit Dub/Sub range tolerance algorithm: minByOrNull { abs(it.startEpisode - range.startEpisode) }.",
      "Check cumulative totalIndex calculation and season/episode singleMap generation.",
      "Ensure ResultEpisode ID generation formula exactly matches upstream."
    ]
  },
  {
    "id": "22_result_helpers_grp33_grp33_grp33",
    "phase": "ResultViewModel2 & Details",
    "title": "Result ViewModel Helpers & Dialog Contracts (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/result/SyncViewModel.kt",
      "com/lagradost/cloudstream3/ui/result/ResultTrailerPlayer.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/result/",
    "domainRequirements": [
      "Audit SyncViewModel modifyMaxEpisode and sync state mutations.",
      "Verify ResultTrailerPlayer extractor link resolution and trailer playback.",
      "Check DetailsDialogEvent (DuplicateWarning, SelectMirror, SelectSubtitle, EpisodeActionMenu) event flow."
    ]
  },
  {
    "id": "22_result_helpers_grp34_grp34_grp34",
    "phase": "ResultViewModel2 & Details",
    "title": "Result ViewModel Helpers & Dialog Contracts (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/result/LinearListLayout.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/result/",
    "domainRequirements": [
      "Audit SyncViewModel modifyMaxEpisode and sync state mutations.",
      "Verify ResultTrailerPlayer extractor link resolution and trailer playback.",
      "Check DetailsDialogEvent (DuplicateWarning, SelectMirror, SelectSubtitle, EpisodeActionMenu) event flow."
    ]
  },
  {
    "id": "23_player_iplayer_cs3iplayer_cs3iplayer_p1_cs3iplayer_p1_cs3iplayer_p1",
    "phase": "Player, Streaming & Subtitles",
    "title": "IPlayer Contract & CS3IPlayer State Engine: CS3IPlayer.kt (Part 1/4): CS3IPlayer.kt (Part 1/4): CS3IPlayer.kt (Part 1/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt"
    ],
    "lineRange": "1-501",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Audit player-agnostic IPlayer interface methods, events, and state flows.",
      "Verify CS3IPlayer event mapping: time-pos -> PositionEvent, pause -> IsPlaying/IsPaused, eof -> VideoEndedEvent.",
      "Verify track-list parsing -> EmbeddedSubtitlesFetchedEvent."
    ]
  },
  {
    "id": "23_player_iplayer_cs3iplayer_cs3iplayer_p1_cs3iplayer_p1_cs3iplayer_p2",
    "phase": "Player, Streaming & Subtitles",
    "title": "IPlayer Contract & CS3IPlayer State Engine: CS3IPlayer.kt (Part 1/4): CS3IPlayer.kt (Part 1/4): CS3IPlayer.kt (Part 2/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt"
    ],
    "lineRange": "502-1038",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Audit player-agnostic IPlayer interface methods, events, and state flows.",
      "Verify CS3IPlayer event mapping: time-pos -> PositionEvent, pause -> IsPlaying/IsPaused, eof -> VideoEndedEvent.",
      "Verify track-list parsing -> EmbeddedSubtitlesFetchedEvent."
    ]
  },
  {
    "id": "23_player_iplayer_cs3iplayer_cs3iplayer_p1_cs3iplayer_p1_cs3iplayer_p3",
    "phase": "Player, Streaming & Subtitles",
    "title": "IPlayer Contract & CS3IPlayer State Engine: CS3IPlayer.kt (Part 1/4): CS3IPlayer.kt (Part 1/4): CS3IPlayer.kt (Part 3/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt"
    ],
    "lineRange": "1039-1499",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Audit player-agnostic IPlayer interface methods, events, and state flows.",
      "Verify CS3IPlayer event mapping: time-pos -> PositionEvent, pause -> IsPlaying/IsPaused, eof -> VideoEndedEvent.",
      "Verify track-list parsing -> EmbeddedSubtitlesFetchedEvent."
    ]
  },
  {
    "id": "23_player_iplayer_cs3iplayer_cs3iplayer_p1_cs3iplayer_p1_cs3iplayer_p4",
    "phase": "Player, Streaming & Subtitles",
    "title": "IPlayer Contract & CS3IPlayer State Engine: CS3IPlayer.kt (Part 1/4): CS3IPlayer.kt (Part 1/4): CS3IPlayer.kt (Part 4/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/CS3IPlayer.kt"
    ],
    "lineRange": "1500-2032",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Audit player-agnostic IPlayer interface methods, events, and state flows.",
      "Verify CS3IPlayer event mapping: time-pos -> PositionEvent, pause -> IsPlaying/IsPaused, eof -> VideoEndedEvent.",
      "Verify track-list parsing -> EmbeddedSubtitlesFetchedEvent."
    ]
  },
  {
    "id": "23_player_iplayer_cs3iplayer_grp39_grp39_grp39",
    "phase": "Player, Streaming & Subtitles",
    "title": "IPlayer Contract & CS3IPlayer State Engine (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/IPlayer.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Audit player-agnostic IPlayer interface methods, events, and state flows.",
      "Verify CS3IPlayer event mapping: time-pos -> PositionEvent, pause -> IsPlaying/IsPaused, eof -> VideoEndedEvent.",
      "Verify track-list parsing -> EmbeddedSubtitlesFetchedEvent."
    ]
  },
  {
    "id": "24_player_generator_player_resolution_generatorplayer_p1_generatorplayer_p1_generatorplayer_p1",
    "phase": "Player, Streaming & Subtitles",
    "title": "GeneratorPlayer: Extractor Resolution & Mirrors: GeneratorPlayer.kt (Part 1/4): GeneratorPlayer.kt (Part 1/4): GeneratorPlayer.kt (Part 1/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt"
    ],
    "lineRange": "1-602",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt",
    "domainRequirements": [
      "Audit link resolution flow: loadExtractor, mirror selection, error fallback.",
      "Verify headers (Referer, User-Agent, Cookie) injection into playback stream.",
      "Check subtitle URL attachment to stream."
    ]
  },
  {
    "id": "24_player_generator_player_resolution_generatorplayer_p1_generatorplayer_p1_generatorplayer_p2",
    "phase": "Player, Streaming & Subtitles",
    "title": "GeneratorPlayer: Extractor Resolution & Mirrors: GeneratorPlayer.kt (Part 1/4): GeneratorPlayer.kt (Part 1/4): GeneratorPlayer.kt (Part 2/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt"
    ],
    "lineRange": "603-1202",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt",
    "domainRequirements": [
      "Audit link resolution flow: loadExtractor, mirror selection, error fallback.",
      "Verify headers (Referer, User-Agent, Cookie) injection into playback stream.",
      "Check subtitle URL attachment to stream."
    ]
  },
  {
    "id": "24_player_generator_player_resolution_generatorplayer_p1_generatorplayer_p1_generatorplayer_p3",
    "phase": "Player, Streaming & Subtitles",
    "title": "GeneratorPlayer: Extractor Resolution & Mirrors: GeneratorPlayer.kt (Part 1/4): GeneratorPlayer.kt (Part 1/4): GeneratorPlayer.kt (Part 3/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt"
    ],
    "lineRange": "1203-1800",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt",
    "domainRequirements": [
      "Audit link resolution flow: loadExtractor, mirror selection, error fallback.",
      "Verify headers (Referer, User-Agent, Cookie) injection into playback stream.",
      "Check subtitle URL attachment to stream."
    ]
  },
  {
    "id": "24_player_generator_player_resolution_generatorplayer_p1_generatorplayer_p1_generatorplayer_p4",
    "phase": "Player, Streaming & Subtitles",
    "title": "GeneratorPlayer: Extractor Resolution & Mirrors: GeneratorPlayer.kt (Part 1/4): GeneratorPlayer.kt (Part 1/4): GeneratorPlayer.kt (Part 4/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt"
    ],
    "lineRange": "1801-2404",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/GeneratorPlayer.kt",
    "domainRequirements": [
      "Audit link resolution flow: loadExtractor, mirror selection, error fallback.",
      "Verify headers (Referer, User-Agent, Cookie) injection into playback stream.",
      "Check subtitle URL attachment to stream."
    ]
  },
  {
    "id": "26_player_generators_playergeneratorviewmodel_playergeneratorviewmodel_playergeneratorviewmodel",
    "phase": "Player, Streaming & Subtitles",
    "title": "Link Generators & Generator ViewModel: PlayerGeneratorViewModel.kt: PlayerGeneratorViewModel.kt: PlayerGeneratorViewModel.kt",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/PlayerGeneratorViewModel.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify 20-minute cache TTL on RepoLinkGenerator.",
      "Audit ExtractorLinkGenerator pre-resolved link handling.",
      "Check PlayerGeneratorViewModel state management and coroutine scope."
    ]
  },
  {
    "id": "26_player_generators_grp45_grp45_grp45",
    "phase": "Player, Streaming & Subtitles",
    "title": "Link Generators & Generator ViewModel (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/LinkGenerator.kt",
      "com/lagradost/cloudstream3/ui/player/RepoLinkGenerator.kt",
      "com/lagradost/cloudstream3/ui/player/ExtractorLinkGenerator.kt",
      "com/lagradost/cloudstream3/ui/player/IGenerator.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify 20-minute cache TTL on RepoLinkGenerator.",
      "Audit ExtractorLinkGenerator pre-resolved link handling.",
      "Check PlayerGeneratorViewModel state management and coroutine scope."
    ]
  },
  {
    "id": "27_player_subtitles_and_decoders_grp46_grp46_grp46",
    "phase": "Player, Streaming & Subtitles",
    "title": "Subtitle Parsers, Decoders & Sanitizer (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/CustomSubripParser.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/subtitles/SubtitleSanitizer.kt",
    "domainRequirements": [
      "Verify SubtitleSanitizer malformed timestamp regex (00:01:23,45 -> 00:01:23.450).",
      "Audit HTML tag cleaner and character encoding detector (CP1254 vs UTF-8).",
      "Check subtitle delay offset conversion (milliseconds to seconds for MPV sub-delay)."
    ]
  },
  {
    "id": "27_player_subtitles_and_decoders_grp47_grp47_grp47",
    "phase": "Player, Streaming & Subtitles",
    "title": "Subtitle Parsers, Decoders & Sanitizer (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/CustomSubtitleDecoderFactory.kt",
      "com/lagradost/cloudstream3/ui/player/PlayerSubtitleHelper.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/subtitles/SubtitleSanitizer.kt",
    "domainRequirements": [
      "Verify SubtitleSanitizer malformed timestamp regex (00:01:23,45 -> 00:01:23.450).",
      "Audit HTML tag cleaner and character encoding detector (CP1254 vs UTF-8).",
      "Check subtitle delay offset conversion (milliseconds to seconds for MPV sub-delay)."
    ]
  },
  {
    "id": "28_player_offline_and_torrent_grp48_grp48_grp48",
    "phase": "Player, Streaming & Subtitles",
    "title": "Offline Playback Helper & Torrent Streaming (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/DownloadedPlayerActivity.kt",
      "com/lagradost/cloudstream3/ui/player/OfflinePlaybackHelper.kt",
      "com/lagradost/cloudstream3/ui/player/DownloadFileGenerator.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify DownloadFileGenerator local file resolution and metadata loading.",
      "Audit Torrent.kt magnet URI parsing, peer management, and streaming server bridge.",
      "Check offline subtitle file matching."
    ]
  },
  {
    "id": "28_player_offline_and_torrent_grp49_grp49_grp49",
    "phase": "Player, Streaming & Subtitles",
    "title": "Offline Playback Helper & Torrent Streaming (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/Torrent.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify DownloadFileGenerator local file resolution and metadata loading.",
      "Audit Torrent.kt magnet URI parsing, peer management, and streaming server bridge.",
      "Check offline subtitle file matching."
    ]
  },
  {
    "id": "29_player_helpers_and_preview_previewgenerator_previewgenerator_previewgenerator",
    "phase": "Player, Streaming & Subtitles",
    "title": "Preview Thumbnail Generator & State Helpers: PreviewGenerator.kt: PreviewGenerator.kt: PreviewGenerator.kt",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/PreviewGenerator.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Verify PreviewGenerator WebVTT sprite sheet and m3u8 thumbnail tile parsing.",
      "Audit PlayerGestureHelper state calculations adapted to mouse wheel/drag.",
      "Check PiP helper quarantine or desktop window floating state."
    ]
  },
  {
    "id": "29_player_helpers_and_preview_playergesturehelper_p1_playergesturehelper_p1_playergesturehelper_p1",
    "phase": "Player, Streaming & Subtitles",
    "title": "Preview Thumbnail Generator & State Helpers: PlayerGestureHelper.kt (Part 1/2): PlayerGestureHelper.kt (Part 1/2): PlayerGestureHelper.kt (Part 1/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/PlayerGestureHelper.kt"
    ],
    "lineRange": "1-610",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Verify PreviewGenerator WebVTT sprite sheet and m3u8 thumbnail tile parsing.",
      "Audit PlayerGestureHelper state calculations adapted to mouse wheel/drag.",
      "Check PiP helper quarantine or desktop window floating state."
    ]
  },
  {
    "id": "29_player_helpers_and_preview_playergesturehelper_p1_playergesturehelper_p1_playergesturehelper_p2",
    "phase": "Player, Streaming & Subtitles",
    "title": "Preview Thumbnail Generator & State Helpers: PlayerGestureHelper.kt (Part 1/2): PlayerGestureHelper.kt (Part 1/2): PlayerGestureHelper.kt (Part 2/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/PlayerGestureHelper.kt"
    ],
    "lineRange": "611-1221",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Verify PreviewGenerator WebVTT sprite sheet and m3u8 thumbnail tile parsing.",
      "Audit PlayerGestureHelper state calculations adapted to mouse wheel/drag.",
      "Check PiP helper quarantine or desktop window floating state."
    ]
  },
  {
    "id": "29_player_helpers_and_preview_grp53_grp53_grp53",
    "phase": "Player, Streaming & Subtitles",
    "title": "Preview Thumbnail Generator & State Helpers (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/PlayerPipHelper.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/ or player-mpv/",
    "domainRequirements": [
      "Verify PreviewGenerator WebVTT sprite sheet and m3u8 thumbnail tile parsing.",
      "Audit PlayerGestureHelper state calculations adapted to mouse wheel/drag.",
      "Check PiP helper quarantine or desktop window floating state."
    ]
  },
  {
    "id": "30_player_live_and_source_priority_grp54_grp54_grp54",
    "phase": "Player, Streaming & Subtitles",
    "title": "Live Stream Manager & Source Priority (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/live/LiveManager.kt",
      "com/lagradost/cloudstream3/ui/player/live/LiveHelper.kt",
      "com/lagradost/cloudstream3/ui/player/source_priority/QualityDataHelper.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify LiveManager time-window calculation and sliding buffer.",
      "Audit QualityDataHelper source quality rules, automatic resolution selection.",
      "Check source priority persistence in DataStore."
    ]
  },
  {
    "id": "30_player_live_and_source_priority_grp55_grp55_grp55",
    "phase": "Player, Streaming & Subtitles",
    "title": "Live Stream Manager & Source Priority (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/source_priority/QualityProfileDialog.kt",
      "com/lagradost/cloudstream3/ui/player/source_priority/SourcePriorityDialog.kt",
      "com/lagradost/cloudstream3/ui/player/source_priority/SourceProfileSettingsDialog.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify LiveManager time-window calculation and sliding buffer.",
      "Audit QualityDataHelper source quality rules, automatic resolution selection.",
      "Check source priority persistence in DataStore."
    ]
  },
  {
    "id": "31_player_demuxers_and_renderers_updatedmatroskaextractor_p1_updatedmatroskaextractor_p1_updatedmatroskaextractor_p1",
    "phase": "Player, Streaming & Subtitles",
    "title": "Demuxers, Renderers & SSL Trust: UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 1/6)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt"
    ],
    "lineRange": "1-533",
    "desktopHint": "player-mpv/ or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify ExoPlayer demuxer patches are superseded by native C FFmpeg/MPV demuxers.",
      "Check SSLTrustManager trust-all SSL socket factory for rogue media servers.",
      "Ensure proper @PlatformQuarantine annotation on obsolete Android ExoPlayer classes."
    ]
  },
  {
    "id": "31_player_demuxers_and_renderers_updatedmatroskaextractor_p1_updatedmatroskaextractor_p1_updatedmatroskaextractor_p2",
    "phase": "Player, Streaming & Subtitles",
    "title": "Demuxers, Renderers & SSL Trust: UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 2/6)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt"
    ],
    "lineRange": "534-1084",
    "desktopHint": "player-mpv/ or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify ExoPlayer demuxer patches are superseded by native C FFmpeg/MPV demuxers.",
      "Check SSLTrustManager trust-all SSL socket factory for rogue media servers.",
      "Ensure proper @PlatformQuarantine annotation on obsolete Android ExoPlayer classes."
    ]
  },
  {
    "id": "31_player_demuxers_and_renderers_updatedmatroskaextractor_p1_updatedmatroskaextractor_p1_updatedmatroskaextractor_p3",
    "phase": "Player, Streaming & Subtitles",
    "title": "Demuxers, Renderers & SSL Trust: UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 3/6)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt"
    ],
    "lineRange": "1085-1619",
    "desktopHint": "player-mpv/ or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify ExoPlayer demuxer patches are superseded by native C FFmpeg/MPV demuxers.",
      "Check SSLTrustManager trust-all SSL socket factory for rogue media servers.",
      "Ensure proper @PlatformQuarantine annotation on obsolete Android ExoPlayer classes."
    ]
  },
  {
    "id": "31_player_demuxers_and_renderers_updatedmatroskaextractor_p1_updatedmatroskaextractor_p1_updatedmatroskaextractor_p4",
    "phase": "Player, Streaming & Subtitles",
    "title": "Demuxers, Renderers & SSL Trust: UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 4/6)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt"
    ],
    "lineRange": "1620-2153",
    "desktopHint": "player-mpv/ or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify ExoPlayer demuxer patches are superseded by native C FFmpeg/MPV demuxers.",
      "Check SSLTrustManager trust-all SSL socket factory for rogue media servers.",
      "Ensure proper @PlatformQuarantine annotation on obsolete Android ExoPlayer classes."
    ]
  },
  {
    "id": "31_player_demuxers_and_renderers_updatedmatroskaextractor_p1_updatedmatroskaextractor_p1_updatedmatroskaextractor_p5",
    "phase": "Player, Streaming & Subtitles",
    "title": "Demuxers, Renderers & SSL Trust: UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 5/6)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt"
    ],
    "lineRange": "2154-2777",
    "desktopHint": "player-mpv/ or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify ExoPlayer demuxer patches are superseded by native C FFmpeg/MPV demuxers.",
      "Check SSLTrustManager trust-all SSL socket factory for rogue media servers.",
      "Ensure proper @PlatformQuarantine annotation on obsolete Android ExoPlayer classes."
    ]
  },
  {
    "id": "31_player_demuxers_and_renderers_updatedmatroskaextractor_p1_updatedmatroskaextractor_p1_updatedmatroskaextractor_p6",
    "phase": "Player, Streaming & Subtitles",
    "title": "Demuxers, Renderers & SSL Trust: UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 1/6): UpdatedMatroskaExtractor.kt (Part 6/6)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/UpdatedMatroskaExtractor.kt"
    ],
    "lineRange": "2778-3243",
    "desktopHint": "player-mpv/ or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify ExoPlayer demuxer patches are superseded by native C FFmpeg/MPV demuxers.",
      "Check SSLTrustManager trust-all SSL socket factory for rogue media servers.",
      "Ensure proper @PlatformQuarantine annotation on obsolete Android ExoPlayer classes."
    ]
  },
  {
    "id": "31_player_demuxers_and_renderers_updateddefaultextractorsfactory_p1_updateddefaultextractorsfactory_p1_updateddefaultextractorsfactory_p1",
    "phase": "Player, Streaming & Subtitles",
    "title": "Demuxers, Renderers & SSL Trust: UpdatedDefaultExtractorsFactory.kt (Part 1/1): UpdatedDefaultExtractorsFactory.kt (Part 1/1): UpdatedDefaultExtractorsFactory.kt (Part 1/1)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/UpdatedDefaultExtractorsFactory.kt"
    ],
    "lineRange": "1-679",
    "desktopHint": "player-mpv/ or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify ExoPlayer demuxer patches are superseded by native C FFmpeg/MPV demuxers.",
      "Check SSLTrustManager trust-all SSL socket factory for rogue media servers.",
      "Ensure proper @PlatformQuarantine annotation on obsolete Android ExoPlayer classes."
    ]
  },
  {
    "id": "31_player_demuxers_and_renderers_grp63_grp63_grp63",
    "phase": "Player, Streaming & Subtitles",
    "title": "Demuxers, Renderers & SSL Trust (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/player/FixedNextRenderersFactory.kt",
      "com/lagradost/cloudstream3/ui/player/SSLTrustManager.kt"
    ],
    "lineRange": null,
    "desktopHint": "player-mpv/ or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/player/",
    "domainRequirements": [
      "Verify ExoPlayer demuxer patches are superseded by native C FFmpeg/MPV demuxers.",
      "Check SSLTrustManager trust-all SSL socket factory for rogue media servers.",
      "Ensure proper @PlatformQuarantine annotation on obsolete Android ExoPlayer classes."
    ]
  },
  {
    "id": "32_sync_provider_anilist_anilistapi_p1_anilistapi_p1_anilistapi_p1",
    "phase": "Sync Providers & Scrobblers",
    "title": "AniList API (GraphQL v2 & OAuth2): AniListApi.kt (Part 1/2): AniListApi.kt (Part 1/2): AniListApi.kt (Part 1/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/AniListApi.kt"
    ],
    "lineRange": "1-600",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/AniListApi.kt",
    "domainRequirements": [
      "Verify GraphQL v2 query templates (Viewer, MediaListCollection, SaveMediaListEntry).",
      "Audit OAuth2 Implicit Grant token extraction (access_token in redirect URL).",
      "Check AniList season and character parsing models.",
      "Verify fake RFC 8628 stub removal (100% authentic upstream parity)."
    ]
  },
  {
    "id": "32_sync_provider_anilist_anilistapi_p1_anilistapi_p1_anilistapi_p2",
    "phase": "Sync Providers & Scrobblers",
    "title": "AniList API (GraphQL v2 & OAuth2): AniListApi.kt (Part 1/2): AniListApi.kt (Part 1/2): AniListApi.kt (Part 2/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/AniListApi.kt"
    ],
    "lineRange": "601-1217",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/AniListApi.kt",
    "domainRequirements": [
      "Verify GraphQL v2 query templates (Viewer, MediaListCollection, SaveMediaListEntry).",
      "Audit OAuth2 Implicit Grant token extraction (access_token in redirect URL).",
      "Check AniList season and character parsing models.",
      "Verify fake RFC 8628 stub removal (100% authentic upstream parity)."
    ]
  },
  {
    "id": "33_sync_provider_simkl_simklapi_p1_simklapi_p1_simklapi_p1",
    "phase": "Sync Providers & Scrobblers",
    "title": "Simkl API (REST v2 & Device PIN): SimklApi.kt (Part 1/2): SimklApi.kt (Part 1/2): SimklApi.kt (Part 1/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/SimklApi.kt"
    ],
    "lineRange": "1-567",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/SimklApi.kt",
    "domainRequirements": [
      "Verify Simkl REST v2 endpoints and JSON models.",
      "Audit Device PIN flow (/oauth/pin, polling, verification URL simkl.com/pin).",
      "Check movie, TV show, and anime unified sync logic."
    ]
  },
  {
    "id": "33_sync_provider_simkl_simklapi_p1_simklapi_p1_simklapi_p2",
    "phase": "Sync Providers & Scrobblers",
    "title": "Simkl API (REST v2 & Device PIN): SimklApi.kt (Part 1/2): SimklApi.kt (Part 1/2): SimklApi.kt (Part 2/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/SimklApi.kt"
    ],
    "lineRange": "568-1142",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/SimklApi.kt",
    "domainRequirements": [
      "Verify Simkl REST v2 endpoints and JSON models.",
      "Audit Device PIN flow (/oauth/pin, polling, verification URL simkl.com/pin).",
      "Check movie, TV show, and anime unified sync logic."
    ]
  },
  {
    "id": "34_sync_provider_kitsu_kitsuapi_p1_kitsuapi_p1_kitsuapi_p1",
    "phase": "Sync Providers & Scrobblers",
    "title": "Kitsu API (JSON:API 1.0 & In-App Auth): KitsuApi.kt (Part 1/2): KitsuApi.kt (Part 1/2): KitsuApi.kt (Part 1/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/KitsuApi.kt"
    ],
    "lineRange": "1-404",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/KitsuApi.kt",
    "domainRequirements": [
      "Verify JSON:API 1.0 resource relationship parsing (libraryEntries, anime, manga).",
      "Audit grant_type=password in-app authentication and refresh token exchange.",
      "Check user library mutation and episode progress updates."
    ]
  },
  {
    "id": "34_sync_provider_kitsu_kitsuapi_p1_kitsuapi_p1_kitsuapi_p2",
    "phase": "Sync Providers & Scrobblers",
    "title": "Kitsu API (JSON:API 1.0 & In-App Auth): KitsuApi.kt (Part 1/2): KitsuApi.kt (Part 1/2): KitsuApi.kt (Part 2/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/KitsuApi.kt"
    ],
    "lineRange": "405-804",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/KitsuApi.kt",
    "domainRequirements": [
      "Verify JSON:API 1.0 resource relationship parsing (libraryEntries, anime, manga).",
      "Audit grant_type=password in-app authentication and refresh token exchange.",
      "Check user library mutation and episode progress updates."
    ]
  },
  {
    "id": "35_sync_provider_mal_malapi_p1_malapi_p1_malapi_p1",
    "phase": "Sync Providers & Scrobblers",
    "title": "MyAnimeList API (PKCE OAuth2 & Scrobbler): MALApi.kt (Part 1/1): MALApi.kt (Part 1/1): MALApi.kt (Part 1/1)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/MALApi.kt"
    ],
    "lineRange": "1-689",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/MALApi.kt",
    "domainRequirements": [
      "Audit OAuth2 PKCE implementation (code_verifier, code_challenge generation).",
      "Verify MAL REST v2 endpoints (animelist, user status update).",
      "Check deep-link redirect handling (cloudstreamapp://mallogin)."
    ]
  },
  {
    "id": "36_sync_provider_subtitles_rest_grp71_grp71_grp71",
    "phase": "Sync Providers & Scrobblers",
    "title": "OpenSubtitles & Subdl REST APIs (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/OpenSubtitlesApi.kt",
      "com/lagradost/cloudstream3/syncproviders/providers/Subdl.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/",
    "domainRequirements": [
      "Verify OpenSubtitles REST API v1/v2 login, JWT caching, and IMDB subtitle query.",
      "Audit Subdl API key query, subtitle format extraction, and zip/file download URL resolution."
    ]
  },
  {
    "id": "37_sync_provider_subtitles_scraping_grp72_grp72_grp72",
    "phase": "Sync Providers & Scrobblers",
    "title": "Addic7ed, SubSource & LocalList (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/syncproviders/providers/Addic7ed.kt",
      "com/lagradost/cloudstream3/syncproviders/providers/SubSource.kt",
      "com/lagradost/cloudstream3/syncproviders/providers/LocalList.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/syncproviders/providers/",
    "domainRequirements": [
      "Verify Addic7ed and SubSource HTML scraping parsers and session cookies.",
      "Audit LocalList local JSON metadata sync and watch status storage."
    ]
  },
  {
    "id": "38_sync_utils_and_mapping_grp73_grp73_grp73",
    "phase": "Sync Providers & Scrobblers",
    "title": "SyncUtil & MAL-Sync-Backup ID Cross-Mapping (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/SyncUtil.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/SyncUtil.kt",
    "domainRequirements": [
      "Audit MAL-Sync-Backup database lookup logic (cross-referencing MAL ID to AniList and Kitsu IDs).",
      "Verify getSyncId, getSyncIds, and external ID resolver algorithms."
    ]
  },
  {
    "id": "39_search_viewmodel_bundle_search_grp74_grp74_grp74",
    "phase": "Search, Downloader & Utils",
    "title": "SearchViewModel & bundleSearch Multi-Provider Harvester (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/search/SearchViewModel.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/search/",
    "domainRequirements": [
      "Audit round-robin bundleSearch algorithm: interleaving 1st, then 2nd results from all providers.",
      "Verify currentSearchIndex counter cancellation to eliminate fast-typing race conditions.",
      "Check 300 ms debounced TMDB multi-search suggestions."
    ]
  },
  {
    "id": "39_search_viewmodel_bundle_search_grp75_grp75_grp75",
    "phase": "Search, Downloader & Utils",
    "title": "SearchViewModel & bundleSearch Multi-Provider Harvester (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/search/SearchResultBuilder.kt",
      "com/lagradost/cloudstream3/ui/search/SearchSuggestionApi.kt",
      "com/lagradost/cloudstream3/ui/search/SearchHelper.kt",
      "com/lagradost/cloudstream3/ui/search/SyncSearchViewModel.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/search/",
    "domainRequirements": [
      "Audit round-robin bundleSearch algorithm: interleaving 1st, then 2nd results from all providers.",
      "Verify currentSearchIndex counter cancellation to eliminate fast-typing race conditions.",
      "Check 300 ms debounced TMDB multi-search suggestions."
    ]
  },
  {
    "id": "40_search_quicksearch_grp76_grp76_grp76",
    "phase": "Search, Downloader & Utils",
    "title": "QuickSearch & Search History Engine (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/quicksearch/QuickSearchFragment.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/quicksearch/",
    "domainRequirements": [
      "Verify $currentAccount/search_history chronological persistence.",
      "Audit quicksearch real-time provider querying and debounced search triggers."
    ]
  },
  {
    "id": "41_library_viewmodel_sorting_grp77_grp77_grp77",
    "phase": "Search, Downloader & Utils",
    "title": "LibraryViewModel & Watch Status Sorting (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/library/LibraryViewModel.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/library/LibraryViewModel.kt",
    "domainRequirements": [
      "Verify 5 library watch states: WATCHING, COMPLETED, ONHOLD, DROPPED, PLANTOWATCH.",
      "Audit 9 sorting modes and in-library search using Levenshtein distance (partialRatio).",
      "Check local library reload triggers on sync events."
    ]
  },
  {
    "id": "42_home_viewmodel_continuous_homeviewmodel_homeviewmodel_homeviewmodel",
    "phase": "Search, Downloader & Utils",
    "title": "HomeViewModel & Resume Watching Rows: HomeViewModel.kt: HomeViewModel.kt: HomeViewModel.kt",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/home/HomeViewModel.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/home/HomeViewModel.kt",
    "domainRequirements": [
      "Audit async category rows loading and pagination.",
      "Verify resume watching row state and chronological order.",
      "Check random movie/series picker algorithm."
    ]
  },
  {
    "id": "42_home_viewmodel_continuous_grp79_grp79_grp79",
    "phase": "Search, Downloader & Utils",
    "title": "HomeViewModel & Resume Watching Rows (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/WatchType.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/home/HomeViewModel.kt",
    "domainRequirements": [
      "Audit async category rows loading and pagination.",
      "Verify resume watching row state and chronological order.",
      "Check random movie/series picker algorithm."
    ]
  },
  {
    "id": "43_settings_viewmodels_logcat_grp80_grp80_grp80",
    "phase": "Search, Downloader & Utils",
    "title": "Settings ViewModels & Logcat Parser (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/settings/extensions/PluginsViewModel.kt",
      "com/lagradost/cloudstream3/ui/settings/extensions/ExtensionsViewModel.kt",
      "com/lagradost/cloudstream3/ui/settings/testing/TestViewModel.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/settings/",
    "domainRequirements": [
      "Verify settings preference key mapping to DesktopAppSettings / DataStore.",
      "Audit TestViewModel provider automated testing runner.",
      "Check LogcatParser log line regex parsing and log level filtering."
    ]
  },
  {
    "id": "43_settings_viewmodels_logcat_settingsgeneral_settingsgeneral_settingsgeneral",
    "phase": "Search, Downloader & Utils",
    "title": "Settings ViewModels & Logcat Parser: SettingsGeneral.kt: SettingsGeneral.kt: SettingsGeneral.kt",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/settings/",
    "domainRequirements": [
      "Verify settings preference key mapping to DesktopAppSettings / DataStore.",
      "Audit TestViewModel provider automated testing runner.",
      "Check LogcatParser log line regex parsing and log level filtering."
    ]
  },
  {
    "id": "43_settings_viewmodels_logcat_grp82_grp82_grp82",
    "phase": "Search, Downloader & Utils",
    "title": "Settings ViewModels & Logcat Parser (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/settings/logcat/LogcatParser.kt",
      "com/lagradost/cloudstream3/ui/settings/Globals.kt",
      "com/lagradost/cloudstream3/ui/settings/SettingsProviders.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/settings/",
    "domainRequirements": [
      "Verify settings preference key mapping to DesktopAppSettings / DataStore.",
      "Audit TestViewModel provider automated testing runner.",
      "Check LogcatParser log line regex parsing and log level filtering."
    ]
  },
  {
    "id": "43_settings_viewmodels_logcat_grp83_grp83_grp83",
    "phase": "Search, Downloader & Utils",
    "title": "Settings ViewModels & Logcat Parser (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/settings/SettingsUI.kt",
      "com/lagradost/cloudstream3/ui/settings/SettingsUpdates.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/ui/settings/",
    "domainRequirements": [
      "Verify settings preference key mapping to DesktopAppSettings / DataStore.",
      "Audit TestViewModel provider automated testing runner.",
      "Check LogcatParser log line regex parsing and log level filtering."
    ]
  },
  {
    "id": "44_downloader_download_manager_downloadmanager_p1_downloadmanager_p1_downloadmanager_p1",
    "phase": "Search, Downloader & Utils",
    "title": "DownloadManager Core (10 MiB Range & Backpressure): DownloadManager.kt (Part 1/4): DownloadManager.kt (Part 1/4): DownloadManager.kt (Part 1/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt"
    ],
    "lineRange": "1-516",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt",
    "domainRequirements": [
      "Audit 10 MiB HTTP Range segment chunking algorithm.",
      "Verify RAM reassembly queue (pendingData) and 50 MB backpressure throttling.",
      "Check atomic file move on completion (Files.move with ATOMIC_MOVE from .part to .mp4).",
      "Verify download resumption and chunk verification."
    ]
  },
  {
    "id": "44_downloader_download_manager_downloadmanager_p1_downloadmanager_p1_downloadmanager_p2",
    "phase": "Search, Downloader & Utils",
    "title": "DownloadManager Core (10 MiB Range & Backpressure): DownloadManager.kt (Part 1/4): DownloadManager.kt (Part 1/4): DownloadManager.kt (Part 2/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt"
    ],
    "lineRange": "517-1036",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt",
    "domainRequirements": [
      "Audit 10 MiB HTTP Range segment chunking algorithm.",
      "Verify RAM reassembly queue (pendingData) and 50 MB backpressure throttling.",
      "Check atomic file move on completion (Files.move with ATOMIC_MOVE from .part to .mp4).",
      "Verify download resumption and chunk verification."
    ]
  },
  {
    "id": "44_downloader_download_manager_downloadmanager_p1_downloadmanager_p1_downloadmanager_p3",
    "phase": "Search, Downloader & Utils",
    "title": "DownloadManager Core (10 MiB Range & Backpressure): DownloadManager.kt (Part 1/4): DownloadManager.kt (Part 1/4): DownloadManager.kt (Part 3/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt"
    ],
    "lineRange": "1037-1569",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt",
    "domainRequirements": [
      "Audit 10 MiB HTTP Range segment chunking algorithm.",
      "Verify RAM reassembly queue (pendingData) and 50 MB backpressure throttling.",
      "Check atomic file move on completion (Files.move with ATOMIC_MOVE from .part to .mp4).",
      "Verify download resumption and chunk verification."
    ]
  },
  {
    "id": "44_downloader_download_manager_downloadmanager_p1_downloadmanager_p1_downloadmanager_p4",
    "phase": "Search, Downloader & Utils",
    "title": "DownloadManager Core (10 MiB Range & Backpressure): DownloadManager.kt (Part 1/4): DownloadManager.kt (Part 1/4): DownloadManager.kt (Part 4/4)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt"
    ],
    "lineRange": "1570-2096",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/downloader/DownloadManager.kt",
    "domainRequirements": [
      "Audit 10 MiB HTTP Range segment chunking algorithm.",
      "Verify RAM reassembly queue (pendingData) and 50 MB backpressure throttling.",
      "Check atomic file move on completion (Files.move with ATOMIC_MOVE from .part to .mp4).",
      "Verify download resumption and chunk verification."
    ]
  },
  {
    "id": "45_downloader_queue_and_objects_grp88_grp88_grp88",
    "phase": "Search, Downloader & Utils",
    "title": "Download Queue & File Management (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/downloader/DownloadObjects.kt",
      "com/lagradost/cloudstream3/utils/downloader/DownloadQueueManager.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/downloader/ or ui/download/",
    "domainRequirements": [
      "Audit DownloadItem, DownloadHeader, and DownloadEpisode models.",
      "Verify DownloadQueueManager active queue ordering and concurrency limits.",
      "Check disk storage availability checks and download folder path resolution."
    ]
  },
  {
    "id": "45_downloader_queue_and_objects_downloadviewmodel_p1_downloadviewmodel_p1_downloadviewmodel_p1",
    "phase": "Search, Downloader & Utils",
    "title": "Download Queue & File Management: DownloadViewModel.kt (Part 1/1): DownloadViewModel.kt (Part 1/1): DownloadViewModel.kt (Part 1/1)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/ui/download/DownloadViewModel.kt"
    ],
    "lineRange": "1-613",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/downloader/ or ui/download/",
    "domainRequirements": [
      "Audit DownloadItem, DownloadHeader, and DownloadEpisode models.",
      "Verify DownloadQueueManager active queue ordering and concurrency limits.",
      "Check disk storage availability checks and download folder path resolution."
    ]
  },
  {
    "id": "45_downloader_queue_and_objects_grp90_grp90_grp90",
    "phase": "Search, Downloader & Utils",
    "title": "Download Queue & File Management (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/downloader/DownloadUtils.kt",
      "com/lagradost/cloudstream3/utils/downloader/DownloadFileManagement.kt",
      "com/lagradost/cloudstream3/ui/download/DownloadButtonSetup.kt",
      "com/lagradost/cloudstream3/ui/download/queue/DownloadQueueViewModel.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/downloader/ or ui/download/",
    "domainRequirements": [
      "Audit DownloadItem, DownloadHeader, and DownloadEpisode models.",
      "Verify DownloadQueueManager active queue ordering and concurrency limits.",
      "Check disk storage availability checks and download folder path resolution."
    ]
  },
  {
    "id": "46_videoskip_engine_grp91_grp91_grp91",
    "phase": "Search, Downloader & Utils",
    "title": "VideoSkip Engine (AnimeSkip & IntroDB) (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/videoskip/AnimeSkip.kt",
      "com/lagradost/cloudstream3/utils/videoskip/SkipAPI.kt",
      "com/lagradost/cloudstream3/utils/videoskip/IntroDbSkip.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/videoskip/",
    "domainRequirements": [
      "Audit AnimeSkip and AniSkip API query parameters (malId, episode, duration).",
      "Verify SkipStamp time intervals (startMs, endMs, type).",
      "Check UriSerializer custom Jackson/JSON serializer."
    ]
  },
  {
    "id": "46_videoskip_engine_grp92_grp92_grp92",
    "phase": "Search, Downloader & Utils",
    "title": "VideoSkip Engine (AnimeSkip & IntroDB) (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/videoskip/AniSkip.kt",
      "com/lagradost/cloudstream3/utils/videoskip/TheIntroDBSkip.kt",
      "com/lagradost/cloudstream3/utils/serializers/UriSerializer.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/videoskip/",
    "domainRequirements": [
      "Audit AnimeSkip and AniSkip API query parameters (malId, episode, duration).",
      "Verify SkipStamp time intervals (startMs, endMs, type).",
      "Check UriSerializer custom Jackson/JSON serializer."
    ]
  },
  {
    "id": "47_storage_datastore_core_datastorehelper_p1_datastorehelper_p1_datastorehelper_p1",
    "phase": "Search, Downloader & Utils",
    "title": "DataStore & DataStoreHelper: DataStoreHelper.kt (Part 1/2): DataStoreHelper.kt (Part 1/2): DataStoreHelper.kt (Part 1/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/DataStoreHelper.kt"
    ],
    "lineRange": "1-415",
    "desktopHint": "common/src/main/kotlin/com/lagradost/common/storage/DesktopDataStore.kt or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Verify LoadResponse.getId() hash algorithm: url.replace(mainUrl, \"\").replace(\"/\", \"\").hashCode().",
      "Check VIDEO_POS_DUR, RESULT_WATCH_STATE, RESULT_RESUME_WATCHING keys.",
      "Audit PosDur.fixVisual() visual percentage normalization (<1% -> 0, <5% -> 5%, >95% -> 100%)."
    ]
  },
  {
    "id": "47_storage_datastore_core_datastorehelper_p1_datastorehelper_p1_datastorehelper_p2",
    "phase": "Search, Downloader & Utils",
    "title": "DataStore & DataStoreHelper: DataStoreHelper.kt (Part 1/2): DataStoreHelper.kt (Part 1/2): DataStoreHelper.kt (Part 2/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/DataStoreHelper.kt"
    ],
    "lineRange": "416-838",
    "desktopHint": "common/src/main/kotlin/com/lagradost/common/storage/DesktopDataStore.kt or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Verify LoadResponse.getId() hash algorithm: url.replace(mainUrl, \"\").replace(\"/\", \"\").hashCode().",
      "Check VIDEO_POS_DUR, RESULT_WATCH_STATE, RESULT_RESUME_WATCHING keys.",
      "Audit PosDur.fixVisual() visual percentage normalization (<1% -> 0, <5% -> 5%, >95% -> 100%)."
    ]
  },
  {
    "id": "47_storage_datastore_core_grp95_grp95_grp95",
    "phase": "Search, Downloader & Utils",
    "title": "DataStore & DataStoreHelper (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/DataStore.kt"
    ],
    "lineRange": null,
    "desktopHint": "common/src/main/kotlin/com/lagradost/common/storage/DesktopDataStore.kt or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Verify LoadResponse.getId() hash algorithm: url.replace(mainUrl, \"\").replace(\"/\", \"\").hashCode().",
      "Check VIDEO_POS_DUR, RESULT_WATCH_STATE, RESULT_RESUME_WATCHING keys.",
      "Audit PosDur.fixVisual() visual percentage normalization (<1% -> 0, <5% -> 5%, >95% -> 100%)."
    ]
  },
  {
    "id": "48_storage_backup_and_filler_grp96_grp96_grp96",
    "phase": "Search, Downloader & Utils",
    "title": "BackupUtils & FillerEpisodeCheck (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/BackupUtils.kt",
      "com/lagradost/cloudstream3/utils/FillerEpisodeCheck.kt"
    ],
    "lineRange": null,
    "desktopHint": "common/src/main/kotlin/com/lagradost/common/storage/BackupRestoreManager.kt or plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Verify backup JSON serialization/deserialization and nonTransferableKeys filtering.",
      "Audit AnimeDB filler episode check API queries and caching."
    ]
  },
  {
    "id": "49_utils_app_context_appcontextutils_p1_appcontextutils_p1_appcontextutils_p1",
    "phase": "Search, Downloader & Utils",
    "title": "AppContextUtils (Media & Language Filters): AppContextUtils.kt (Part 1/2): AppContextUtils.kt (Part 1/2): AppContextUtils.kt (Part 1/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/AppContextUtils.kt"
    ],
    "lineRange": "1-451",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/AppContextUtils.kt",
    "domainRequirements": [
      "Audit media and language filtering algorithms.",
      "Verify Turkish/English abbreviation canonical search normalization.",
      "Check video click action dispatching."
    ]
  },
  {
    "id": "49_utils_app_context_appcontextutils_p1_appcontextutils_p1_appcontextutils_p2",
    "phase": "Search, Downloader & Utils",
    "title": "AppContextUtils (Media & Language Filters): AppContextUtils.kt (Part 1/2): AppContextUtils.kt (Part 1/2): AppContextUtils.kt (Part 2/2)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/AppContextUtils.kt"
    ],
    "lineRange": "452-905",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/AppContextUtils.kt",
    "domainRequirements": [
      "Audit media and language filtering algorithms.",
      "Verify Turkish/English abbreviation canonical search normalization.",
      "Check video click action dispatching."
    ]
  },
  {
    "id": "50_utils_in_app_updater_and_testing_grp99_grp99_grp99",
    "phase": "Search, Downloader & Utils",
    "title": "InAppUpdater & TestingUtils (Group of 1 files) (Group of 1 files) (Group of 1 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/InAppUpdater.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Verify InAppUpdater GitHub release asset matching (detecting Linux tar.gz / deb / AppImage).",
      "Audit TestingUtils provider test assertions (search, load, loadLinks).",
      "Check GitInfo commit hash embedding."
    ]
  },
  {
    "id": "50_utils_in_app_updater_and_testing_grp100_grp100_grp100",
    "phase": "Search, Downloader & Utils",
    "title": "InAppUpdater & TestingUtils (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/TestingUtils.kt",
      "com/lagradost/cloudstream3/utils/GitInfo.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Verify InAppUpdater GitHub release asset matching (detecting Linux tar.gz / deb / AppImage).",
      "Audit TestingUtils provider test assertions (search, load, loadLinks).",
      "Check GitInfo commit hash embedding."
    ]
  },
  {
    "id": "51_utils_helpers_and_events_uihelper_p1_uihelper_p1_uihelper_p1",
    "phase": "Search, Downloader & Utils",
    "title": "UIHelper, Selection & Common Utilities: UIHelper.kt (Part 1/1): UIHelper.kt (Part 1/1): UIHelper.kt (Part 1/1)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/UIHelper.kt"
    ],
    "lineRange": "1-720",
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Audit string formatting, time parsing, and formatting helpers in UIHelper and TextUtil.",
      "Verify Event bus pattern and ConsistentLiveData.",
      "Check dialog selection helpers in SingleSelectionHelper."
    ]
  },
  {
    "id": "51_utils_helpers_and_events_grp102_grp102_grp102",
    "phase": "Search, Downloader & Utils",
    "title": "UIHelper, Selection & Common Utilities (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/SingleSelectionHelper.kt",
      "com/lagradost/cloudstream3/utils/TextUtil.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Audit string formatting, time parsing, and formatting helpers in UIHelper and TextUtil.",
      "Verify Event bus pattern and ConsistentLiveData.",
      "Check dialog selection helpers in SingleSelectionHelper."
    ]
  },
  {
    "id": "51_utils_helpers_and_events_grp103_grp103_grp103",
    "phase": "Search, Downloader & Utils",
    "title": "UIHelper, Selection & Common Utilities (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/Event.kt",
      "com/lagradost/cloudstream3/utils/ConsistentLiveData.kt",
      "com/lagradost/cloudstream3/utils/SubtitleUtils.kt",
      "com/lagradost/cloudstream3/utils/ImageUtil.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Audit string formatting, time parsing, and formatting helpers in UIHelper and TextUtil.",
      "Verify Event bus pattern and ConsistentLiveData.",
      "Check dialog selection helpers in SingleSelectionHelper."
    ]
  },
  {
    "id": "51_utils_helpers_and_events_grp104_grp104_grp104",
    "phase": "Search, Downloader & Utils",
    "title": "UIHelper, Selection & Common Utilities (Group of 3 files) (Group of 3 files) (Group of 3 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/IntentHelpers.kt",
      "com/lagradost/cloudstream3/utils/Vector2.kt",
      "com/lagradost/cloudstream3/utils/IDisposable.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/",
    "domainRequirements": [
      "Audit string formatting, time parsing, and formatting helpers in UIHelper and TextUtil.",
      "Verify Event bus pattern and ConsistentLiveData.",
      "Check dialog selection helpers in SingleSelectionHelper."
    ]
  },
  {
    "id": "52_utils_hardware_and_platform_quarantine_grp105_grp105_grp105",
    "phase": "Search, Downloader & Utils",
    "title": "Platform Quarantine & Mobile Hardware Checks (Group of 4 files) (Group of 4 files) (Group of 4 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/BiometricAuthenticator.kt",
      "com/lagradost/cloudstream3/utils/PowerManagerAPI.kt",
      "com/lagradost/cloudstream3/utils/CastHelper.kt",
      "com/lagradost/cloudstream3/utils/CastOptionsProvider.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/ or android-shims/",
    "domainRequirements": [
      "Audit @PlatformQuarantine annotations for mobile-only hardware constraints.",
      "Verify QuarantineStatus classification (NOT_APPLICABLE_DESKTOP vs NEEDS_DESKTOP_ALTERNATIVE).",
      "Ensure no silent stubbing or missing quarantine tags."
    ]
  },
  {
    "id": "52_utils_hardware_and_platform_quarantine_grp106_grp106_grp106",
    "phase": "Search, Downloader & Utils",
    "title": "Platform Quarantine & Mobile Hardware Checks (Group of 2 files) (Group of 2 files) (Group of 2 files)",
    "upstreamFiles": [
      "com/lagradost/cloudstream3/utils/TvChannelUtils.kt",
      "com/lagradost/cloudstream3/utils/PackageInstaller.kt"
    ],
    "lineRange": null,
    "desktopHint": "plugin-runtime/src/main/kotlin/com/lagradost/cloudstream3/utils/ or android-shims/",
    "domainRequirements": [
      "Audit @PlatformQuarantine annotations for mobile-only hardware constraints.",
      "Verify QuarantineStatus classification (NOT_APPLICABLE_DESKTOP vs NEEDS_DESKTOP_ALTERNATIVE).",
      "Ensure no silent stubbing or missing quarantine tags."
    ]
  }
];

log("Starting 106-agent deterministic precision audit across 7 phases (Output: docs/auditv2/)...");

const auditResults = await pipeline(
  PACKAGES,
  async (pkg) => {
    phase(pkg.phase);
    log("[" + pkg.id + "] Starting precision audit for: " + pkg.title);

    const upstreamList = pkg.upstreamFiles.map(f => "  - upstream/app/src/main/java/" + f).join("\n");
    const domainReqs = pkg.domainRequirements.map(r => "  - " + r).join("\n");
    const lineRangeText = pkg.lineRange
      ? "\n### 🎯 CERRAHİ SATIR ODAĞI: Satır " + pkg.lineRange + "\nBu dosyanın YALNIZCA " + pkg.lineRange + " satır aralığındaki fonksiyonlarını, sınıflarını ve mantığını mikroskobik olarak denetle.\nDosyanın geri kalanı komşu ajanlar tarafından denetlenmektedir. Kapsam dışına çıkıp çıktıyı şişirme.\n"
      : "\n### 🎯 CERRAHİ SATIR ODAĞI: Tam Dosya (Tüm Fonksiyonlar)\n";

    const prompt = `# CLOUDSTREAM DESKTOP ARCHITECTURAL AUDIT MISSION (V2)
YOU ARE ASSIGNED TO PACKAGE: ${pkg.id} (${pkg.title})

## ⛔ KESİNLİKLE DERLEME VE TEST KOŞMAYIN (STRICT STATIC ANALYSIS ONLY - CLAUDE.MD OVERRIDE)
- BU GÖREV SALT STATİK KOD İNCELEMESİ VE DOKÜMANTASYON GÖREVİDİR (READ-ONLY AUDIT).
- Proje veya Global CLAUDE.md dosyalarındaki "Her değişiklikte Gradle testi koşulmalıdır" kuralı BU GÖREV İÇİN KESİNLİKLE GEÇERSİZDİR (OVERRIDDEN).
- Kod tabanında HİÇBİR DOSYA DEĞİŞTİRİLMEMEKTEDİR. Bu nedenle derleme, derleme denemesi veya test koşmaya KESİNLİKLE GEREK YOKTUR.
- KESİNLİKLE './gradlew', 'compileKotlin', 'test', 'agent-exec.sh' veya herhangi bir derleme/terminal komutu ÇALIŞTIRMAYIN.
- Terminalde derleme veya test çalıştırmak, kilit kuyruklarında (/tmp/cloudstream_build.lock) dakikalarca kilitlenmenize ve bağlamınızın çöp terminal çıktılarıyla şişmesine sebep olur.
- YALNIZCA kaynak dosyaları açıp satır satır okuyun (Read), arayın (Grep) ve bulgularınızı docs/auditv2/${pkg.id}.md dosyasına yazın.

## 1. MAKRO VİZYON VE RESMİ PORT STANDARDI
Bu görev sıradan bir kod taraması değildir. Bu görev, resmi CloudStream Android uygulamasının (upstream/app) masaüstü platformuna resmi nitelikte ve 1:1 mimari eşdeğerlikte (1:1 architectural parity) taşınmasının cerrahi kanıtıdır.
- Upstream (upstream/app) TEK VE MUTLAK OTORİTEDİR.
- "Benzerini yazma" veya yüzeysel yeniden yazım kesinlikle yasaktır. 4 yıllık üretim tecrübesi, yüzlerce edge-case ve istisnai durum satır satır korunmuş olmalıdır.
- Sıfır-Shim / Sıfır-Fallback İlkesi: Çalışmayan bir mantığı sahte bir sarmalayıcı veya mock arkasına saklayamazsın. Tespit ettiğin her sahte stub veya eksik doğrudan raporda ifşa edilmelidir.

## 2. SOMUT KANIT VE SIFIR VARSAYIM İLKESİ (EVIDENCE-BASED AUDIT)
- Asla "genel olarak uyumlu görünüyor", "implement edilmiş" gibi içi boş genellemeler yapma.
- Her iddianı dosya yolu, sınıf adı, fonksiyon adı ve kesin satır numarasıyla (file_path:line_number) ispatlamak zorundasın.
- READ-ONLY AUDIT: Kod tabanında HİÇBİR DOSYAYI DEĞİŞTİRME. Yalnızca oku, ara ve bulgularını docs/auditv2/${pkg.id}.md dosyasına yaz.

## 3. GÖREVLENDİRİLDİĞİNİZ UPSTREAM DOSYALARI VE SATIR ODAĞI:
${upstreamList}
${lineRangeText}

### MASAÜSTÜ HEDEF DİZİN İPUÇLARI:
  - Birincil hedef: ${pkg.desktopHint}
  - İlgili diğer modüller: plugin-runtime/, common/, player-mpv/, android-shims/, desktop-app/

### BU PAKETE ÖZEL TEKNİK KRİTİK DENETİM MADDELERİ:
${domainReqs}

## 4. MİKROSKOBİK DENETİM METODOLOJİSİ (HER FONKSİYON, HER SATIR)
Upstream kaynak dosyalarını açarak şu 6 aşamalı denetim protokolünü harfiyen işlet:
1. İmza ve Varlık Paritesi: Kapsamındaki HER sınıfı, arayüzü, enum'ı, fonksiyonu, parametre listesini, dönüş tipini, görünürlük belirleyicisini (public, internal, private) ve suspend/inline durumunu masaüstü karşılığı ile tek tek eşleştir.
2. Satır Hacmi ve Koruma Oranı: Upstream satır sayısı ile masaüstü satır sayısını karşılaştır. Saf iş mantığı satır koruma oranı >= %85 olmalıdır. %85 altında kalan her durum için gerekçe sun.
3. Anti-Stub / Sıfır Sahte Kod Taraması: Kodda şu şüpheli yapıları tara:
   - TODO veya FIXME yorumları
   - NotImplementedError veya throw UnsupportedOperationException
   - İçi boş fonksiyon gövdeleri { } veya sahte dönüşler (return null, return true, return emptyList())
   - Sabit mock veriler veya uydurma shim'ler
4. İş Mantığı, Algoritmalar ve İstisnalar: Upstream'deki tüm döngüleri, filtreleri, regex desenlerini, null güvenlik kontrollerini (?. , ?:), hata yakalama bloklarını (try/catch/finally) satır satır incele.
5. Platform Adaptasyon Kalitesi: Android Context, SharedPreferences, WorkManager, ExoPlayer bağımlılıklarının Linux POSIX / DesktopDataStore / D-Bus / MPV karşılıklarına işlev kaybı olmadan uyarlanıp uyarlanmadığını değerlendir.
6. Windows Uyumluluk Denetimi: Bu bölümdeki kodların Windows ortamında (Win32, NTFS, Windows Media, Registry, Dosya Kilitleri) çalışabilirliğini incele.

## 5. ÖZEL KRİTİK GÖREV: WINDOWS MASAÜSTÜ UYUMLULUĞU İNCELEMESİ (WINDOWS AUDIT)
Bu pakette denetlediğiniz kodların Windows (Windows 10/11 x64, Win32 API, NTFS dosya sistemi) ortamında çalıştırılması durumunda:
1. POSIX ve Linux-Spesifik Yollar & XDG: / vs \\ ayracı, ~/.config, ~/.cache, MAX_PATH (260 karakter) sınırı, dosya adı büyük/küçük harf duyarlılığı (NTFS case-insensitivity) açısından değiştirilmesi gereken bir yer var mı?
2. Süreç ve Terminal Yönetimi: ProcessBuilder("sh", "-c", ...), xdg-open, D-Bus, chmod, flock gibi Linux-native çağrıların Windows karşılığı (cmd.exe, powershell, explorer.exe, Win32 Named Pipes vb.) gerekiyor mu?
3. Multimedya ve Yüzey: libmpv.so yerine mpv-2.dll, Wayland/X11 EGL yüzeyleri yerine Windows DirectX/ANGLE/DirectComposition Skiko yüzeyi için bu dosyalarda özel bir köprü/adaptasyon gerekiyor mu?
4. Sistem Entegrasyonları: Linux D-Bus MPRIS (Medya Tuşları), systemd-inhibit (Uyku Engelleme), org.freedesktop.Notifications yerine Windows SMTC (System Media Transport Controls), SetThreadExecutionState ve Windows Toast bildirimleri için gereken adaptasyonlar nelerdir?
5. Windows Dosya Kilitleri (File Locking): Windows'ta açık olan .jar / .dex dosyalarının URLClassLoader tarafından kilitlenip silinememesi (Metaspace/Plugin unloading sorunu) bu dosyalarda risk oluşturuyor mu?
Bulgularınızı rapordaki "## 8. Windows Platform Compatibility & Porting Assessment" başlığı altına somut satır referanslarıyla yazın.

## 6. ZORUNLU ÇIKTI RAPORU (docs/auditv2/${pkg.id}.md)
docs/auditv2/${pkg.id}.md dosyasını Write aracı ile oluştur. Raporunda şu başlıklar ZORUNLU ve EKSİKSİZ olacaktır:
# Audit Report: ${pkg.title} (${pkg.id})

## 1. Executive Summary & Verdict
- Parity Score: (%0 - %100)
- Verdict: (VERIFIED_PARITY | PARTIAL_PARITY | DEFECTS_FOUND | UNPORTED)
- Özet Değerlendirme

## 2. File & Line Count Parity Table
| Upstream File | Upstream Lines | Desktop Port File | Desktop Lines | Parity Ratio (%) | Provenance Header (@PortSource) |
|---|---|---|---|---|---|

## 3. Function-by-Function Parity Matrix
| Upstream Function / Property | Upstream Line | Desktop Port Equivalent | Desktop Line | Status (EXACT / MODIFIED / MISSING / STUB) | Notes / Discrepancies |
|---|---|---|---|---|---|

## 4. Business Logic, Algorithms & Edge Cases Deep Dive
(Kritik algoritmaların, regex'lerin, hata yakalama bloklarının satır satır detaylı analizi)

## 5. Anti-Stub & Fake-Code Findings
(Tespit edilen her stub, sahte dönüş veya boş fonksiyonun tam dosya ve satır referansıyla listesi. Yoksa açıkça: "VERIFIED: Zero stubs detected")

## 6. Linux & Desktop Adaptation Assessment
(Android bağımlılıklarının Linux masaüstüne uyarlanma başarısı)

## 7. Actionable Remediation Checklist
(%100 parite için doğrudan yapılması gereken somut maddeler)

## 8. Windows Platform Compatibility & Porting Assessment
(Windows 10/11, NTFS, Win32, mpv-2.dll, SMTC, dosya yolları ve dosya kilitleme uyumluluğu için somut riskler, gereken adaptasyonlar veya "VERIFIED: No Windows-specific issues detected")

Raporu yazdıktan sonra şemaya uygun JSON nesnesini döndür.`;

    return await agent(prompt, {
      label: "auditv2:" + pkg.id,
      phase: pkg.phase,
      schema: AUDIT_RESULT_SCHEMA
    });
  }
);

// Master Synthesis
phase("Master Synthesis");
log("All 106 audit agents completed. Synthesizing Master Parity Report...");

await agent(
  `You are the Master Audit Synthesizer on the CloudStream Linux project.
Read all 106 audit reports located in docs/auditv2/*.md.
Compile a comprehensive, publication-grade Master Parity Report at docs/auditv2/00_MASTER_AUDIT_INDEX.md containing:
1. Executive Summary and Overall Project Parity Score (calculated across all 106 packages).
2. Master Status Table covering all 106 packages (Package ID, Title, Upstream Lines, Desktop Lines, Parity %, Stub Count, Verdict).
3. Systemic Architecture Analysis: Core Strengths vs Systematic Blind Spots.
4. Critical Missing Components & Incomplete Port Hotspots.
5. Anti-Stub & Fake-Code Catalog (exact locations requiring immediate root-cause fixes).
6. Windows Platform Porting & Compatibility Roadmap:
   - File system & Path normalization (XDG vs %APPDATA%, separator issues).
   - Multimedia & Render surface bridging (mpv-2.dll, DirectX/Skiko).
   - System integrations (SMTC, sleep inhibition, notifications).
   - Windows file lock mitigations (URLClassLoader JAR locks).
7. Prioritized Remediation Roadmap (Wave 1: Critical Core, Wave 2: Streaming & Player, Wave 3: Sync & Services, Wave 4: Windows Portability).
Write the final synthesized report to docs/auditv2/00_MASTER_AUDIT_INDEX.md using the Write tool.`,
  { label: "master-synthesis-v2", phase: "Master Synthesis" }
);

return { totalAudited: auditResults.filter(Boolean).length };
