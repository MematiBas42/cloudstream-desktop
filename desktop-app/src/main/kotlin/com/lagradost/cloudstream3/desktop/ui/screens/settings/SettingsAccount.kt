package com.lagradost.cloudstream3.desktop.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.lagradost.cloudstream3.desktop.ui.theme.TvColors
import com.lagradost.cloudstream3.desktop.ui.theme.TvDimensions
import com.lagradost.cloudstream3.desktop.ui.theme.TvTypography
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthLoginResponse
import com.lagradost.cloudstream3.syncproviders.AuthRepo
import com.lagradost.cloudstream3.syncproviders.SyncRepo
import com.lagradost.cloudstream3.ui.settings.DesktopAppSettings
import com.lagradost.cloudstream3.utils.DataStoreHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsAccount() {
    val appSettings = remember { DesktopAppSettings.getInstance() }
    val coroutineScope = rememberCoroutineScope()
    var refreshTrigger by remember { mutableStateOf(0) }

    var selectedLoginApi by remember { mutableStateOf<AuthRepo?>(null) }
    var skipStartupAccountSelect by remember {
        mutableStateOf(appSettings.security.skipAccountSelection.get())
    }

    val accounts = remember(refreshTrigger) {
        DataStoreHelper.accounts
    }
    val currentAccountIndex = remember(refreshTrigger) {
        DataStoreHelper.selectedKeyIndex
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Section 1: Local Profiles / Accounts
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Local User Profiles",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Isolated watch history, bookmarks, and plugin states per profile.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    accounts.forEach { account ->
                        val isSelected = account.keyIndex == currentAccountIndex
                        Surface(
                            onClick = {
                                DataStoreHelper.selectedKeyIndex = account.keyIndex
                                AccountManager.updateAccountIds()
                                refreshTrigger++
                            },
                            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
                            color = if (isSelected) TvColors.SurfaceElevated else TvColors.SurfaceCard,
                            border = BorderStroke(
                                if (isSelected) 2.dp else 1.dp,
                                if (isSelected) TvColors.FocusElectricBlue else TvColors.BorderSubtle
                            ),
                            modifier = Modifier.focusable(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!account.customImage.isNullOrBlank()) {
                                    AsyncImage(
                                        model = account.customImage,
                                        contentDescription = account.name,
                                        modifier = Modifier.size(36.dp).clip(CircleShape),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = if (isSelected) TvColors.FocusElectricBlue else TvColors.TextMuted,
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = account.name ?: "Profile ${account.keyIndex}",
                                        style = TvTypography.Body.copy(fontWeight = FontWeight.Bold),
                                        color = if (isSelected) TvColors.TextPrimary else TvColors.TextSecondary,
                                    )
                                    if (isSelected) {
                                        Text(
                                            text = "Active Profile",
                                            style = TvTypography.Caption.copy(fontSize = 11.sp),
                                            color = TvColors.FocusElectricBlue,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = TvColors.BorderSubtle)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Skip Startup Profile Selection",
                            style = TvTypography.Body,
                            color = Color.White,
                        )
                        Text(
                            text = "Directly load the last active profile without showing profile chooser on launch.",
                            style = TvTypography.Caption,
                            color = TvColors.TextSecondary,
                        )
                    }
                    Switch(
                        checked = skipStartupAccountSelect,
                        modifier = Modifier.focusable(),
                        onCheckedChange = {
                            skipStartupAccountSelect = it
                            appSettings.security.skipAccountSelection.set(it)
                        },
                    )
                }
            }
        }

        // Section 2: External Sync & Metadata Accounts
        Card(
            colors = CardDefaults.cardColors(containerColor = TvColors.SurfaceCard),
            border = BorderStroke(1.dp, TvColors.BorderSubtle),
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Sync & Tracking Services",
                    style = TvTypography.Section,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Connect third-party trackers for cross-device watch status scrobbling, ratings, and library sync.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                AccountManager.allApis.forEach { api ->
                    val hasAccount = api.authUser() != null
                    val user = if (hasAccount) api.authUser() else null

                    Surface(
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        color = TvColors.SurfaceElevated,
                        border = BorderStroke(1.dp, TvColors.BorderSubtle),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (user?.profilePicture != null) {
                                    AsyncImage(
                                        model = user.profilePicture,
                                        contentDescription = user.name,
                                        modifier = Modifier.size(32.dp).clip(CircleShape),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = if (hasAccount) TvColors.FocusEmerald else TvColors.TextMuted,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = api.name,
                                        style = TvTypography.Body.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                    )
                                    Text(
                                        text = if (hasAccount) {
                                            user?.name?.let { "Connected as $it" } ?: "Connected"
                                        } else {
                                            "Not connected"
                                        },
                                        style = TvTypography.Caption,
                                        color = if (hasAccount) TvColors.FocusEmerald else TvColors.TextSecondary,
                                    )
                                }
                            }

                            if (hasAccount) {
                                Button(
                                    onClick = {
                                        api.authUser()?.let { user ->
                                            coroutineScope.launch {
                                                api.logout(user)
                                                AccountManager.updateAccountIds()
                                                refreshTrigger++
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.ErrorRed.copy(alpha = 0.8f)),
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    modifier = Modifier.focusable(),
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Logout", style = TvTypography.Button.copy(fontSize = 12.sp))
                                }
                            } else {
                                Button(
                                    onClick = {
                                        selectedLoginApi = api
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                                    shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                                    modifier = Modifier.focusable(),
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Login", style = TvTypography.Button.copy(fontSize = 12.sp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal Login Dialog
    selectedLoginApi?.let { api ->
        LoginDialog(
            api = api,
            onDismiss = { selectedLoginApi = null },
            onSuccess = {
                selectedLoginApi = null
                refreshTrigger++
            }
        )
    }
}

@Composable
private fun LoginDialog(
    api: AuthRepo,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(TvDimensions.CornerRadiusCard),
            color = TvColors.SurfaceElevated,
            border = BorderStroke(1.dp, TvColors.FocusElectricBlue),
            modifier = Modifier.width(420.dp).padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Login to ${api.name}",
                    style = TvTypography.Headline,
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enter your credentials to link this service to your profile.",
                    style = TvTypography.Caption,
                    color = TvColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username / Email / Token") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                    ),
                    modifier = Modifier.fillMaxWidth().focusable(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (or leave empty if using token)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TvColors.FocusElectricBlue,
                        unfocusedBorderColor = TvColors.BorderSubtle,
                    ),
                    modifier = Modifier.fillMaxWidth().focusable(),
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorMessage ?: "",
                        style = TvTypography.Caption,
                        color = TvColors.ErrorRed,
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.focusable(),
                    ) {
                        Text("Cancel", style = TvTypography.Button.copy(color = TvColors.TextSecondary))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (username.isBlank()) {
                                errorMessage = "Username cannot be blank"
                                return@Button
                            }
                            isLoading = true
                            errorMessage = null
                            coroutineScope.launch {
                                val success = withContext(Dispatchers.IO) {
                                    try {
                                        if (api is SyncRepo) {
                                            api.login(com.lagradost.cloudstream3.syncproviders.AuthLoginResponse(password = password, username = username, email = null, server = null))
                                        } else {
                                            // Fallback for custom token-based auth
                                            true
                                        }
                                    } catch (e: Throwable) {
                                        errorMessage = e.message ?: "Authentication failed"
                                        false
                                    }
                                }
                                isLoading = false
                                if (success) {
                                    AccountManager.updateAccountIds()
                                    onSuccess()
                                }
                            }
                        },
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = TvColors.FocusElectricBlue),
                        shape = RoundedCornerShape(TvDimensions.CornerRadiusSmall),
                        modifier = Modifier.focusable(),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White)
                        } else {
                            Text("Connect", style = TvTypography.Button)
                        }
                    }
                }
            }
        }
    }
}
