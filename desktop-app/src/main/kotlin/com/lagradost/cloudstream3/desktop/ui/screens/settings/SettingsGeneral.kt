package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.desktop.ui.models.CustomSite
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.ui.settings.DesktopAppSettings
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.common.network.DohDnsResolver
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import kotlin.math.roundToInt

const val PREF_APP_LANGUAGE = "app_language"
const val PREF_LOCALE_AUDIO = "locale_audio"
const val PREF_LOCALE_SUB = "locale_sub"
const val PREF_DOWNLOAD_PATH = "download_path_key"
const val PREF_DOWNLOAD_PARALLEL = "download_parallel_key"
const val PREF_DOWNLOAD_CONCURRENT = "download_concurrent_key"
const val PREF_DNS = "dns_key"
const val PREF_OVERRIDE_SITE = "override_site_key"
const val PREF_JSDELIVR_PROXY = "jsdelivr_proxy_key"

@Composable
fun SettingsGeneral() {
    val appSettings = remember { DesktopAppSettings.getInstance() }
    val coroutineScope = rememberCoroutineScope()

    // 1. Language States
    var appLanguage by remember {
        mutableStateOf(DesktopDataStore.getKey<String>(PREF_APP_LANGUAGE) ?: "")
    }
    var localeAudio by remember {
        mutableStateOf(DesktopDataStore.getKey<String>(PREF_LOCALE_AUDIO) ?: "")
    }
    var localeSub by remember {
        mutableStateOf(DesktopDataStore.getKey<String>(PREF_LOCALE_SUB) ?: "")
    }

    // 2. Download States
    val defaultDownloadPath = remember { PlatformPaths.downloadsDir.toAbsolutePath().toString() }
    var downloadPath by remember {
        mutableStateOf(DesktopDataStore.getKey<String>(PREF_DOWNLOAD_PATH)?.ifBlank { null } ?: defaultDownloadPath)
    }
    var parallelDownloads by remember {
        mutableStateOf((DesktopDataStore.getKey<Int>(PREF_DOWNLOAD_PARALLEL) ?: 3).coerceIn(1, 10))
    }
    var concurrentConnections by remember {
        mutableStateOf((DesktopDataStore.getKey<Int>(PREF_DOWNLOAD_CONCURRENT) ?: 3).coerceIn(1, 10))
    }

    // 3. Network / DNS / Proxy States
    var dnsKey by remember {
        val savedValue = DesktopDataStore.getKey<Int>(PREF_DNS)
            ?: DesktopDataStore.getKey<Int>("doh_provider")
            ?: 0
        mutableStateOf(savedValue)
    }
    var jsdelivrProxy by remember {
        mutableStateOf(DesktopDataStore.getKey<Boolean>(PREF_JSDELIVR_PROXY) ?: false)
    }

    // 4. Custom Site Domain Overrides
    var overrideSiteKey by remember {
        mutableStateOf(DesktopDataStore.getKey<String>(PREF_OVERRIDE_SITE) ?: "")
    }
    var customSites by remember {
        mutableStateOf(
            DesktopDataStore.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toList() ?: emptyList()
        )
    }
    var showAddSiteDialog by remember { mutableStateOf(false) }

    // Languages list
    val allLanguages = remember {
        SubtitleHelper.languages
            .map { it.IETF_tag to it.nameNextToFlagEmoji() }
            .distinctBy { it.first }
            .sortedBy { it.second }
    }

    val languageOptions = remember(allLanguages) {
        listOf("" to "Default (System Language)") + allLanguages
    }

    val audioLanguageOptions = remember(allLanguages) {
        listOf("" to "None / Provider Default") + allLanguages
    }

    val subLanguageOptions = remember(allLanguages) {
        listOf("" to "None / Provider Default") + allLanguages
    }

    // DNS Options
    val dnsOptions = remember {
        DohDnsResolver.DohProvider.entries.map { it.prefValue to it.displayName }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        // Section 1: Language & Audio / Subtitle Preferences
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Language & Locale Preferences",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select application UI language and preferred dubbing/subtitling defaults.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // App Language
                GeneralDropdownSetting(
                    label = "Application Interface Language",
                    description = "Sets the localization for CloudStream Desktop.",
                    options = languageOptions,
                    currentValue = appLanguage,
                    onSelectionChanged = { code ->
                        appLanguage = code
                        DesktopDataStore.setKey(PREF_APP_LANGUAGE, code)
                        DesktopDataStore.setKey("app_locale", code)
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Preferred Audio / Dub Language
                GeneralDropdownSetting(
                    label = "Preferred Audio / Dub Language",
                    description = "Automatically prioritizes streams with matching audio/dub track.",
                    options = audioLanguageOptions,
                    currentValue = localeAudio,
                    onSelectionChanged = { code ->
                        localeAudio = code
                        DesktopDataStore.setKey(PREF_LOCALE_AUDIO, code)
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Preferred Subtitle Language
                GeneralDropdownSetting(
                    label = "Preferred Subtitle Language",
                    description = "Automatically selects subtitles in this language if available.",
                    options = subLanguageOptions,
                    currentValue = localeSub,
                    onSelectionChanged = { code ->
                        localeSub = code
                        DesktopDataStore.setKey(PREF_LOCALE_SUB, code)
                    },
                )
            }
        }

        // Section 2: Downloads Configuration
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Download Engine & Storage",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Configure filesystem destination, concurrency throttling, and parallel jobs.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Download Directory Selection
                Text(
                    text = "Download Directory",
                    style = TvTypography.Body,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = TvColors.SurfaceElevated,
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        ) {
                            Text(
                                text = downloadPath,
                                style = TvTypography.Caption,
                                color = TvColors.TextPrimary,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            coroutineScope.launch(Dispatchers.IO) {
                                val chooser = JFileChooser(downloadPath).apply {
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    dialogTitle = "Select Download Directory"
                                }
                                val result = chooser.showOpenDialog(null)
                                if (result == JFileChooser.APPROVE_OPTION) {
                                    val selected = chooser.selectedFile?.absolutePath
                                    if (!selected.isNullOrBlank()) {
                                        withContext(Dispatchers.Main) {
                                            downloadPath = selected
                                            DesktopDataStore.setKey(PREF_DOWNLOAD_PATH, selected)
                                            DesktopDataStore.setKey("download_path_key_visual", selected)
                                        }
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.height(48.dp).focusable(),
                    ) {
                        Text("Browse...")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            downloadPath = defaultDownloadPath
                            DesktopDataStore.setKey(PREF_DOWNLOAD_PATH, defaultDownloadPath)
                            DesktopDataStore.setKey("download_path_key_visual", defaultDownloadPath)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TvColors.TextSecondary),
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.height(48.dp).focusable(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Default")
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(20.dp))

                // Parallel Downloads Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Parallel Downloads",
                            style = TvTypography.Body,
                            color = Color.White,
                        )
                        Text(
                            text = "Maximum number of simultaneous active video downloads.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    Text(
                        text = "$parallelDownloads concurrent jobs",
                        style = TvTypography.Button.copy(color = TvColors.FocusElectricBlue),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = parallelDownloads.toFloat(),
                    onValueChange = { parallelDownloads = it.roundToInt() },
                    onValueChangeFinished = {
                        DesktopDataStore.setKey(PREF_DOWNLOAD_PARALLEL, parallelDownloads)
                        DownloadQueueManager.forceRefreshQueue()
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = TvColors.FocusElectricBlue,
                        activeTrackColor = TvColors.FocusElectricBlue,
                        inactiveTrackColor = TvColors.BorderSubtle,
                    ),
                    modifier = Modifier.fillMaxWidth().focusable(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Concurrent Connections Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Connections per Download",
                            style = TvTypography.Body,
                            color = Color.White,
                        )
                        Text(
                            text = "Chunked HTTP range streams allocated per individual video file.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    Text(
                        text = "$concurrentConnections chunks/file",
                        style = TvTypography.Button.copy(color = TvColors.FocusElectricBlue),
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = concurrentConnections.toFloat(),
                    onValueChange = { concurrentConnections = it.roundToInt() },
                    onValueChangeFinished = {
                        DesktopDataStore.setKey(PREF_DOWNLOAD_CONCURRENT, concurrentConnections)
                    },
                    valueRange = 1f..10f,
                    steps = 8,
                    colors = SliderDefaults.colors(
                        thumbColor = TvColors.FocusElectricBlue,
                        activeTrackColor = TvColors.FocusElectricBlue,
                        inactiveTrackColor = TvColors.BorderSubtle,
                    ),
                    modifier = Modifier.fillMaxWidth().focusable(),
                )
            }
        }

        // Section 3: Network & DNS Configuration
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Network & DNS Routing",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Bypass ISP blocks and configure content delivery proxies.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                // DNS Over HTTPS Selector
                GeneralDropdownSetting(
                    label = "DNS over HTTPS (DoH)",
                    description = "Encrypts domain resolution requests to bypass ISP filtering.",
                    options = dnsOptions.map { it.first.toString() to it.second },
                    currentValue = dnsKey.toString(),
                    onSelectionChanged = { value ->
                        val intVal = value.toIntOrNull() ?: 0
                        dnsKey = intVal
                        DesktopDataStore.setKey(PREF_DNS, intVal)
                        DesktopDataStore.removeKey("doh_provider")
                        val provider = DohDnsResolver.DohProvider.fromPrefValue(intVal)
                        DohDnsResolver.setProvider(provider)

                        // Real application to shared OkHttpClient instances (Zero-Shim compliance)
                        runCatching {
                            app.baseClient = app.baseClient.newBuilder()
                                .dns(DohDnsResolver.INSTANCE)
                                .build()
                            insecureApp.baseClient = insecureApp.baseClient.newBuilder()
                                .dns(DohDnsResolver.INSTANCE)
                                .build()
                        }
                    },
                )

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(20.dp))

                // jsDelivr Proxy Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "jsDelivr Proxy",
                            style = TvTypography.Body,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Route plugin repositories and assets through jsDelivr CDN to circumvent GitHub blocks.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    Switch(
                        checked = jsdelivrProxy,
                        modifier = Modifier.focusable(),
                        onCheckedChange = {
                            jsdelivrProxy = it
                            DesktopDataStore.setKey(PREF_JSDELIVR_PROXY, it)
                        },
                    )
                }
            }
        }

        // Section 4: Custom Site Domain Overrides
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Custom Site Domain Overrides",
                            style = TvTypography.Section,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Replace blocked provider root domains with functional mirrors.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    Button(
                        onClick = { showAddSiteDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.focusable(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Mirror")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Global Domain Override input
                Text(
                    text = "Global Override URL",
                    style = TvTypography.Body,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = overrideSiteKey,
                    onValueChange = {
                        overrideSiteKey = it
                        DesktopDataStore.setKey(PREF_OVERRIDE_SITE, it)
                    },
                    placeholder = { Text("https://mirror.example.com", color = TvColors.TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = TvColors.TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth().focusable(),
                )

                if (customSites.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Configured Provider Mirrors (${customSites.size})",
                        style = TvTypography.Body,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    customSites.forEachIndexed { index, site ->
                        Surface(
                            color = TvColors.SurfaceElevated,
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                            border = BorderStroke(1.dp, TvColors.BorderSubtle),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${site.name} (${site.parentJavaClass})",
                                        style = TvTypography.Button,
                                        color = Color.White,
                                    )
                                    Text(
                                        text = "${site.url} [${site.lang}]",
                                        style = TvTypography.Caption,
                                        color = TvColors.FocusElectricBlue,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val updated = customSites.toMutableList().apply { removeAt(index) }
                                        customSites = updated
                                        DesktopDataStore.setKey(USER_PROVIDER_API, updated.toTypedArray())
                                        CloudStreamApp.setKey(USER_PROVIDER_API, updated.toTypedArray())
                                        MainActivity.afterPluginsLoadedEvent.invoke(false)
                                    },
                                    modifier = Modifier.focusable(),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove",
                                        tint = TvColors.ErrorRed,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSiteDialog) {
        AddCustomSiteDialog(
            onDismissRequest = { showAddSiteDialog = false },
            onSiteAdded = { newSite ->
                val updated = customSites + newSite
                customSites = updated
                DesktopDataStore.setKey(USER_PROVIDER_API, updated.toTypedArray())
                CloudStreamApp.setKey(USER_PROVIDER_API, updated.toTypedArray())
                MainActivity.afterPluginsLoadedEvent.invoke(false)
                showAddSiteDialog = false
            },
        )
    }
}

@Composable
private fun AddCustomSiteDialog(
    onDismissRequest: () -> Unit,
    onSiteAdded: (CustomSite) -> Unit,
) {
    var providerName by remember { mutableStateOf("") }
    var parentClass by remember { mutableStateOf("") }
    var mirrorUrl by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("en") }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        containerColor = TvColors.SurfaceCard,
        title = {
            Text("Add Custom Provider Mirror", style = TvTypography.Headline, color = Color.White)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                OutlinedTextField(
                    value = providerName,
                    onValueChange = { providerName = it },
                    label = { Text("Display Name") },
                    placeholder = { Text("e.g. My Flixer Mirror") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                        focusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = parentClass,
                    onValueChange = { parentClass = it },
                    label = { Text("Provider Class / Identifier") },
                    placeholder = { Text("e.g. SuperStream") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                        focusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = mirrorUrl,
                    onValueChange = { mirrorUrl = it },
                    label = { Text("Mirror URL") },
                    placeholder = { Text("https://mirror-url.to") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                        focusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text("Language Tag (e.g. en, tr)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                        focusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (providerName.isNotBlank() && mirrorUrl.isNotBlank() && parentClass.isNotBlank()) {
                        onSiteAdded(
                            CustomSite(
                                parentClassName = parentClass.trim(),
                                name = providerName.trim(),
                                url = mirrorUrl.trim().removeSuffix("/"),
                                lang = language.trim().ifBlank { "en" },
                            )
                        )
                    }
                },
                enabled = providerName.isNotBlank() && mirrorUrl.isNotBlank() && parentClass.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
            ) {
                Text("Save Mirror")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest,
                colors = ButtonDefaults.textButtonColors(contentColor = TvColors.TextSecondary),
            ) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun GeneralDropdownSetting(
    label: String,
    description: String? = null,
    options: List<Pair<String, String>>,
    currentValue: String,
    onSelectionChanged: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayValue = options.firstOrNull { it.first == currentValue }?.second ?: currentValue.ifBlank { "Default" }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = TvTypography.Body, color = Color.White)
        if (!description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = description, style = TvTypography.Caption, color = TvColors.TextSecondary)
        }
        Spacer(modifier = Modifier.height(6.dp))

        Box {
            Button(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(48.dp).focusable(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = TvColors.SurfaceElevated,
                    contentColor = TvColors.TextPrimary,
                ),
                shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                border = BorderStroke(1.dp, TvColors.BorderSubtle),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = displayValue,
                        style = TvTypography.Button,
                        maxLines = 1,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(TvColors.SurfaceElevated)
                    .widthIn(min = 280.dp, max = 500.dp)
                    .heightIn(max = 400.dp),
            ) {
                options.forEach { (value, title) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = title,
                                style = TvTypography.Body.copy(
                                    color = if (value == currentValue) TvColors.FocusElectricBlue else TvColors.TextPrimary,
                                ),
                            )
                        },
                        onClick = {
                            onSelectionChanged(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
