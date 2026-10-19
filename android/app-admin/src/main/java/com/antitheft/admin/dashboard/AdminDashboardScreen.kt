package com.antitheft.admin.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Smartphone
import com.antitheft.common.util.ApkShareHelper
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.network.DeviceDto
import com.antitheft.admin.tracking.LiveTrackingActivity
import com.antitheft.admin.ui.components.SheButton
import com.antitheft.admin.ui.components.SheButtonVariant
import com.antitheft.admin.ui.components.SheCard
import com.antitheft.admin.ui.components.SheMetricTile
import com.antitheft.admin.ui.components.SheSectionHeader
import com.antitheft.admin.ui.components.SheStatusChip
import com.antitheft.admin.ui.components.SheThemeSelector
import com.antitheft.admin.ui.components.StatusLevel
import com.antitheft.admin.ui.theme.SheTheme
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.AlertDialog
import com.antitheft.admin.network.UserProfileDto
import com.antitheft.admin.network.UserSessionDto
import com.antitheft.admin.ui.components.SheTextField
import java.util.Locale

data class CommandDispatchDialogState(
    val device: DeviceDto,
    val commandType: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    userProfile: UserProfileDto?,
    devices: List<DeviceDto>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenVault: () -> Unit,
    onLogout: () -> Unit,
    onPairDevice: () -> Unit,
    onSendCommand: (DeviceDto, String) -> Unit,
    onShowGuide: () -> Unit,
    onStartEmailVerification: () -> Unit,
    onStartPhoneVerification: () -> Unit,
    showEmailVerifyDialog: Boolean,
    onDismissEmailVerifyDialog: () -> Unit,
    onSendEmailCode: () -> Unit,
    onConfirmEmailCode: (String) -> Unit,
    isEmailVerifying: Boolean,
    emailVerifyMessage: String?,
    showPhoneVerifyDialog: Boolean,
    onDismissPhoneVerifyDialog: () -> Unit,
    onSendPhoneCode: (String) -> Unit,
    onConfirmPhoneCode: (String) -> Unit,
    isPhoneVerifying: Boolean,
    phoneVerifyMessage: String?,
    commandDispatchState: CommandDispatchDialogState? = null,
    onDismissCommandDispatch: () -> Unit = {},
    onConfirmCommandDispatch: (totpCode: String, friendPhone: String) -> Unit = { _, _ -> },
    showGuideDialog: Boolean = false,
    onDismissGuideDialog: () -> Unit = {},
    showSessionsDialog: Boolean = false,
    onOpenSessions: () -> Unit = {},
    onDismissSessionsDialog: () -> Unit = {},
    sessionsList: List<UserSessionDto> = emptyList(),
    isSessionsLoading: Boolean = false,
    onRevokeOtherSessions: () -> Unit = {},
    onRevokeSession: (String, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var showThemeSheet by remember { mutableStateOf(false) }
    var selectedDeviceForCommand by remember { mutableStateOf<DeviceDto?>(null) }

    Scaffold(
        containerColor = SheTheme.colors.background,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onPairDevice,
                containerColor = SheTheme.colors.accent,
                contentColor = if (SheTheme.colors.isDark) Color.Black else Color.White,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(bottom = 12.dp, end = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pair Handset",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top App Bar
            item {
                DashboardHeader(
                    onThemeClick = { showThemeSheet = true },
                    onOpenVault = onOpenVault,
                    onOpenSessions = onOpenSessions,
                    onShareApp = {
                        ApkShareHelper.showShareOptions(
                            context,
                            "SHE Anti-Theft Admin",
                            "she-admin.apk"
                        )
                    },
                    onLogout = onLogout
                )
            }

            // Quick Metrics Overview
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SheMetricTile(
                        label = "Monitored",
                        value = "${devices.size}",
                        subtext = "Enrolled phones",
                        modifier = Modifier.weight(1f)
                    )
                    SheMetricTile(
                        label = "Active Guard",
                        value = "${devices.count { it.status == "ACTIVE" }}",
                        subtext = "Direct boot ready",
                        highlightColor = SheTheme.colors.success,
                        modifier = Modifier.weight(1f)
                    )
                    SheMetricTile(
                        label = "Security",
                        value = "RLS Safe",
                        subtext = "Zero Knowledge",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Ownership Verification Status Banner
            item {
                VerificationStatusSection(
                    userProfile = userProfile,
                    onVerifyEmailClick = onStartEmailVerification,
                    onVerifyPhoneClick = onStartPhoneVerification
                )
            }

            // Devices Section Header
            item {
                SheSectionHeader(
                    title = "PROTECTED HANDSETS (${devices.size})",
                    actionText = if (isLoading) "Refreshing..." else "Sync",
                    onActionClick = onRefresh
                )
            }

            // Device Items
            if (devices.isEmpty()) {
                item {
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = SheTheme.colors.accent)
                        }
                    } else {
                        EmptyDevicesCard(onPairDevice = onPairDevice)
                    }
                }
            } else {
                items(devices) { device ->
                    DeviceCardItem(
                        device = device,
                        onOpenRadar = {
                            val intent = Intent(context, LiveTrackingActivity::class.java).apply {
                                putExtra(LiveTrackingActivity.EXTRA_DEVICE_ID, device.id)
                                putExtra(LiveTrackingActivity.EXTRA_DEVICE_ALIAS, device.device_alias)
                                if (device.last_location != null) {
                                    putExtra(LiveTrackingActivity.EXTRA_INITIAL_LAT, device.last_location.latitude)
                                    putExtra(LiveTrackingActivity.EXTRA_INITIAL_LNG, device.last_location.longitude)
                                    putExtra(LiveTrackingActivity.EXTRA_INITIAL_ACC, device.last_location.accuracy)
                                }
                            }
                            context.startActivity(intent)
                        },
                        onOpenMap = {
                            openMapForDevice(context, device)
                        },
                        onOpenGuide = onShowGuide,
                        onDispatchCommand = {
                            selectedDeviceForCommand = device
                        }
                    )
                }
            }
        }
    }

    // Theme Selection Bottom Sheet
    if (showThemeSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showThemeSheet = false },
            sheetState = sheetState,
            containerColor = SheTheme.colors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Display & Theme Preference",
                    color = SheTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choose light, dark, or follow Android system mode. Preference persists across sessions.",
                    color = SheTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                SheThemeSelector(modifier = Modifier.fillMaxWidth())

                Spacer(modifier = Modifier.height(24.dp))

                SheButton(
                    text = "Done",
                    onClick = { showThemeSheet = false },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // iOS-Style Command Dispatch Bottom Sheet
    if (selectedDeviceForCommand != null) {
        val dev = selectedDeviceForCommand!!
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedDeviceForCommand = null },
            sheetState = sheetState,
            containerColor = SheTheme.colors.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // iOS Handle & Header
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(SheTheme.colors.border)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = dev.device_alias,
                        color = SheTheme.colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Hardware Keystore ECDSA Command Center",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }

                // Group 1: SIMULATION & DIAGNOSTICS
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SIMULATION & DIAGNOSTICS",
                        color = SheTheme.colors.textTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    IosGroupedCard {
                        IosActionSheetRow(
                            title = "Simulated Anti-Theft Drill",
                            subtitle = "Safe 10s alert. Configurable friend's phone for drill SMS.",
                            icon = Icons.Outlined.Science,
                            iconBg = Color(0xFF8B5CF6),
                            badge = "DRILL",
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "TEST_MODE")
                            }
                        )
                        IosDivider()
                        IosActionSheetRow(
                            title = "Satellite GPS Telemetry",
                            subtitle = "Pings hardware GPS for fresh live satellite coordinates.",
                            icon = Icons.Outlined.MyLocation,
                            iconBg = Color(0xFF0284C7),
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "LOCATE")
                            }
                        )
                    }
                }

                // Group 2: ACOUSTIC & HARDWARE CONTROLS
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "ACOUSTIC & DISPLAY LOCK",
                        color = SheTheme.colors.textTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    IosGroupedCard {
                        IosActionSheetRow(
                            title = "Maximum Siren Alarm",
                            subtitle = "Loud acoustic emergency alarm overriding silent mode.",
                            icon = Icons.Outlined.NotificationsActive,
                            iconBg = Color(0xFF10B981),
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "RING")
                            }
                        )
                        IosDivider()
                        IosActionSheetRow(
                            title = "Silence Siren Alarm",
                            subtitle = "Mutes active acoustic alarm on the handset.",
                            icon = Icons.AutoMirrored.Outlined.VolumeOff,
                            iconBg = Color(0xFF64748B),
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "STOP_RING")
                            }
                        )
                        IosDivider()
                        IosActionSheetRow(
                            title = "Force Screen Lock",
                            subtitle = "Instantly locks display and requires enterprise PIN.",
                            icon = Icons.Outlined.Lock,
                            iconBg = Color(0xFF6366F1),
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "LOCK")
                            }
                        )
                        IosDivider()
                        IosActionSheetRow(
                            title = "Release Kiosk Launcher",
                            subtitle = "Releases stolen kiosk lock and restores default launcher.",
                            icon = Icons.Outlined.LockOpen,
                            iconBg = Color(0xFF0D9488),
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "EXIT_KIOSK")
                            }
                        )
                    }
                }

                // Group 3: THEFT EMERGENCY & RECOVERY
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "THEFT EMERGENCY & RECOVERY",
                        color = SheTheme.colors.textTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    IosGroupedCard {
                        IosActionSheetRow(
                            title = "Real Stolen Lockdown",
                            subtitle = "Full lockdown: continuous siren, kiosk, & SMS beacon.",
                            icon = Icons.Outlined.WarningAmber,
                            iconBg = Color(0xFFEF4444),
                            isDestructive = true,
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "STOLEN_MODE")
                            }
                        )
                        IosDivider()
                        IosActionSheetRow(
                            title = "Revoke Pairing Session",
                            subtitle = "Invalidates mutual ECDSA pairing keys.",
                            icon = Icons.Outlined.LinkOff,
                            iconBg = Color(0xFFF43F5E),
                            isDestructive = true,
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "UNPAIR")
                            }
                        )
                        IosDivider()
                        IosActionSheetRow(
                            title = "Deprovision Device Owner",
                            subtitle = "Permanent administrative reset (requires TOTP).",
                            icon = Icons.Outlined.DeleteForever,
                            iconBg = Color(0xFFDC2626),
                            isDestructive = true,
                            onClick = {
                                val d = dev
                                selectedDeviceForCommand = null
                                onSendCommand(d, "CLEAR_DEVICE_OWNER")
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (showEmailVerifyDialog) {
        EmailVerificationDialog(
            email = userProfile?.email ?: "",
            onDismiss = onDismissEmailVerifyDialog,
            onSendCode = onSendEmailCode,
            onConfirmCode = onConfirmEmailCode,
            isLoading = isEmailVerifying,
            errorMessage = emailVerifyMessage
        )
    }

    if (showPhoneVerifyDialog) {
        PhoneVerificationDialog(
            initialPhone = userProfile?.fallback_phone_e164 ?: userProfile?.phone_number ?: "",
            onDismiss = onDismissPhoneVerifyDialog,
            onSendCode = onSendPhoneCode,
            onConfirmCode = onConfirmPhoneCode,
            isLoading = isPhoneVerifying,
            errorMessage = phoneVerifyMessage
        )
    }

    if (commandDispatchState != null) {
        IosCommandDispatchDialog(
            state = commandDispatchState,
            onDismiss = onDismissCommandDispatch,
            onConfirm = onConfirmCommandDispatch
        )
    }

    if (showGuideDialog) {
        IosCommandGuideDialog(
            onDismiss = onDismissGuideDialog
        )
    }

    if (showSessionsDialog) {
        ActiveSessionsSheet(
            sessions = sessionsList,
            isLoading = isSessionsLoading,
            onDismiss = onDismissSessionsDialog,
            onRevokeOtherSessions = onRevokeOtherSessions,
            onRevokeSession = onRevokeSession
        )
    }
}

@Composable
private fun DashboardHeader(
    onThemeClick: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenSessions: () -> Unit,
    onShareApp: () -> Unit,
    onLogout: () -> Unit
) {
    SheCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        backgroundColor = SheTheme.colors.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SHE COMMAND",
                    color = SheTheme.colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Sentinel Network • Row-Level Security",
                    color = SheTheme.colors.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderIconButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Palette,
                            contentDescription = "Display & Theme",
                            tint = SheTheme.colors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = onThemeClick
                )
                HeaderIconButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = "Encrypted Vault",
                            tint = SheTheme.colors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = onOpenVault
                )
                HeaderIconButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = "Active Sessions",
                            tint = SheTheme.colors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = onOpenSessions
                )
                HeaderIconButton(
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = "Share App",
                            tint = SheTheme.colors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = onShareApp
                )
                HeaderIconButton(
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ExitToApp,
                            contentDescription = "Sign Out",
                            tint = SheTheme.colors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    onClick = onLogout
                )
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SheTheme.colors.surfaceVariant)
            .border(1.dp, SheTheme.colors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Composable
private fun DeviceCardItem(
    device: DeviceDto,
    onOpenRadar: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenGuide: () -> Unit,
    onDispatchCommand: () -> Unit
) {
    val statusLevel = when (device.calculated_status) {
        "LIVE", "RECENT" -> StatusLevel.SUCCESS
        "DELAYED" -> StatusLevel.WARNING
        else -> StatusLevel.DANGER
    }

    SheCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        backgroundColor = SheTheme.colors.surface
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.device_alias,
                        color = SheTheme.colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ID: ${device.id.take(16)}...",
                        color = SheTheme.colors.textTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                SheStatusChip(
                    text = device.calculated_status,
                    level = statusLevel
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Telemetry Box
            val loc = device.last_location
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SheTheme.colors.surfaceVariant)
                    .border(1.dp, SheTheme.colors.border, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                if (loc != null) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude),
                                color = SheTheme.colors.accent,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "±${loc.accuracy}m",
                                color = SheTheme.colors.textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Captured: ${loc.capture_time ?: "N/A"}",
                            color = SheTheme.colors.textTertiary,
                            fontSize = 11.sp
                        )
                    }
                } else {
                    Text(
                        text = "No location fix recorded yet",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Actions Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SheButton(
                    text = "Live Radar",
                    onClick = onOpenRadar,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Radar,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    height = 42.dp,
                    cornerRadius = 12.dp,
                    modifier = Modifier.weight(1.5f)
                )

                SheButton(
                    text = "Maps",
                    onClick = onOpenMap,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Map,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    variant = SheButtonVariant.TONAL,
                    height = 42.dp,
                    cornerRadius = 12.dp,
                    modifier = Modifier.weight(1f)
                )

                SheButton(
                    text = "",
                    onClick = onOpenGuide,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "Device Command Guide",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    variant = SheButtonVariant.OUTLINED,
                    height = 42.dp,
                    cornerRadius = 12.dp,
                    modifier = Modifier.width(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Actions Row 2: Send Command
            SheButton(
                text = "Dispatch Remote Command",
                onClick = onDispatchCommand,
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                variant = SheButtonVariant.SECONDARY,
                height = 42.dp,
                cornerRadius = 12.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EmptyDevicesCard(onPairDevice: () -> Unit) {
    SheCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        backgroundColor = SheTheme.colors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(SheTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Smartphone,
                    contentDescription = null,
                    tint = SheTheme.colors.textSecondary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No Protected Devices Yet",
                color = SheTheme.colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Pair your Android phone running SHE Guardian using cryptographic QR pairing.",
                color = SheTheme.colors.textSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
            )

            SheButton(
                text = "Pair New Handset",
                onClick = onPairDevice
            )
        }
    }
}

private fun openMapForDevice(context: Context, device: DeviceDto) {
    val loc = device.last_location
    if (loc == null) {
        android.widget.Toast.makeText(context, "No location fix recorded yet", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val label = Uri.encode(device.device_alias)
        val geoUri = Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}($label)")
        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapIntent)
        } else {
            val webUri = Uri.parse("https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    } catch (_: Exception) {
        val webUri = Uri.parse("https://maps.google.com/?q=${loc.latitude},${loc.longitude}")
        context.startActivity(Intent(Intent.ACTION_VIEW, webUri))
    }
}

@Composable
private fun VerificationStatusSection(
    userProfile: UserProfileDto?,
    onVerifyEmailClick: () -> Unit,
    onVerifyPhoneClick: () -> Unit
) {
    if (userProfile == null) return

    val emailVerified = !userProfile.email_verified_at.isNullOrBlank()
    val phoneVerified = !userProfile.phone_verified_at.isNullOrBlank()

    if (!emailVerified) {
        // High-priority Warning Banner for Unverified Email
        SheCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = SheTheme.colors.warningContainer.copy(alpha = 0.25f),
            borderColor = SheTheme.colors.warning
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = SheTheme.colors.warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "ACTION REQUIRED: EMAIL UNVERIFIED",
                        color = SheTheme.colors.warning,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Your account email (${userProfile.email}) is unverified. Zero-trust policy blocks remote commands, GPS tracking, and vault decryption until ownership is proven.",
                    color = SheTheme.colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                SheButton(
                    text = "Verify Email Now",
                    onClick = onVerifyEmailClick,
                    variant = SheButtonVariant.PRIMARY,
                    height = 42.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } else if (!phoneVerified) {
        // Warning Banner for Unverified Emergency Phone
        SheCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = SheTheme.colors.warningContainer.copy(alpha = 0.15f),
            borderColor = SheTheme.colors.warning.copy(alpha = 0.6f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SimCard,
                        contentDescription = null,
                        tint = SheTheme.colors.warning,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "EMERGENCY SMS PHONE UNVERIFIED",
                        color = SheTheme.colors.warning,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Offline SIM swap beacons and emergency SMS alerts will only transmit to a cryptographically verified mobile number.",
                    color = SheTheme.colors.textPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                SheButton(
                    text = "Verify Fallback Phone",
                    onClick = onVerifyPhoneClick,
                    variant = SheButtonVariant.TONAL,
                    height = 42.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } else {
        // Both Verified Banner
        SheCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = SheTheme.colors.surfaceVariant,
            borderColor = SheTheme.colors.border
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(SheTheme.colors.success)
                        )
                        Text(
                            text = "Ownership Verified",
                            color = SheTheme.colors.success,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Email: ${userProfile.email} • SMS: ${userProfile.fallback_phone_e164 ?: userProfile.phone_number ?: "Armed"}",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                SheButton(
                    text = "Update",
                    onClick = onVerifyPhoneClick,
                    variant = SheButtonVariant.OUTLINED,
                    height = 34.dp
                )
            }
        }
    }
}

@Composable
private fun EmailVerificationDialog(
    email: String,
    onDismiss: () -> Unit,
    onSendCode: () -> Unit,
    onConfirmCode: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheTheme.colors.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = SheTheme.colors.accent,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Verify Account Email",
                    color = SheTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "To prevent unauthorized takeovers, remote device administration and tracking require cryptographic proof of email ownership.",
                    color = SheTheme.colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                SheCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = SheTheme.colors.surfaceVariant,
                    borderColor = SheTheme.colors.border
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "TARGET ADDRESS",
                            color = SheTheme.colors.textTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = email.ifBlank { "Registered Account Email" },
                            color = SheTheme.colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                SheButton(
                    text = "Dispatch / Resend OTP",
                    onClick = onSendCode,
                    variant = SheButtonVariant.TONAL,
                    modifier = Modifier.fillMaxWidth(),
                    isLoading = isLoading
                )

                SheTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6 && it.all { char -> char.isDigit() }) code = it },
                    label = "6-DIGIT VERIFICATION CODE",
                    placeholder = "123456",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = if (errorMessage.contains("dispatched", ignoreCase = true) || errorMessage.contains("sent", ignoreCase = true))
                            SheTheme.colors.success else SheTheme.colors.danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            SheButton(
                text = "Confirm Verification",
                onClick = { onConfirmCode(code) },
                enabled = code.length == 6 && !isLoading,
                isLoading = isLoading,
                variant = SheButtonVariant.PRIMARY
            )
        },
        dismissButton = {
            SheButton(
                text = "Close",
                onClick = onDismiss,
                variant = SheButtonVariant.OUTLINED
            )
        }
    )
}

@Composable
private fun PhoneVerificationDialog(
    initialPhone: String,
    onDismiss: () -> Unit,
    onSendCode: (String) -> Unit,
    onConfirmCode: (String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?
) {
    var phoneInput by remember { mutableStateOf(initialPhone) }
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheTheme.colors.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Smartphone,
                    contentDescription = null,
                    tint = SheTheme.colors.accent,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Verify Emergency SMS Phone",
                    color = SheTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Offline emergency beacons and SIM swap alerts only transmit to verified phone numbers. The OTP challenge is sent directly from your paired Guardian handset.",
                    color = SheTheme.colors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )

                SheTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it },
                    label = "EMERGENCY MOBILE NUMBER (E.164)",
                    placeholder = "+919876543210 or +1234567890",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                    )
                )

                SheButton(
                    text = "Dispatch SMS via Guardian",
                    onClick = { onSendCode(phoneInput) },
                    variant = SheButtonVariant.TONAL,
                    modifier = Modifier.fillMaxWidth(),
                    isLoading = isLoading
                )

                SheTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6 && it.all { char -> char.isDigit() }) code = it },
                    label = "6-DIGIT SMS CODE",
                    placeholder = "123456",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = if (errorMessage.contains("dispatched", ignoreCase = true) || errorMessage.contains("sent", ignoreCase = true))
                            SheTheme.colors.success else SheTheme.colors.danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            SheButton(
                text = "Verify & Arm Beacons",
                onClick = { onConfirmCode(code) },
                enabled = code.length == 6 && !isLoading,
                isLoading = isLoading,
                variant = SheButtonVariant.PRIMARY
            )
        },
        dismissButton = {
            SheButton(
                text = "Close",
                onClick = onDismiss,
                variant = SheButtonVariant.OUTLINED
            )
        }
    )
}

@Composable
private fun IosGroupedCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = SheTheme.colors.surfaceVariant.copy(alpha = 0.7f),
        border = BorderStroke(0.75.dp, SheTheme.colors.border.copy(alpha = 0.5f))
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun IosDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 58.dp)
            .height(0.5.dp)
            .background(SheTheme.colors.border.copy(alpha = 0.5f))
    )
}

@Composable
private fun IosActionSheetRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconBg: Color,
    badge: String? = null,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = if (isDestructive) SheTheme.colors.danger else SheTheme.colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (badge != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(iconBg.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badge,
                                color = iconBg,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Text(
                    text = subtitle,
                    color = SheTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
            contentDescription = null,
            tint = SheTheme.colors.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun IosCommandDispatchDialog(
    state: CommandDispatchDialogState,
    onDismiss: () -> Unit,
    onConfirm: (totpCode: String, friendPhone: String) -> Unit
) {
    var totpCode by remember { mutableStateOf("") }
    var friendPhone by remember { mutableStateOf("") }

    val cmd = state.commandType
    val isTestMode = cmd == "TEST_MODE"
    val isStolenMode = cmd == "STOLEN_MODE"
    val isClearOwner = cmd == "CLEAR_DEVICE_OWNER"
    val isDestructive = isStolenMode || isClearOwner || cmd == "UNPAIR"

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheTheme.colors.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when {
                                isTestMode -> Color(0xFF8B5CF6).copy(alpha = 0.2f)
                                isDestructive -> SheTheme.colors.dangerContainer
                                else -> SheTheme.colors.accentContainer
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (cmd) {
                            "TEST_MODE" -> Icons.Outlined.Science
                            "STOLEN_MODE" -> Icons.Outlined.WarningAmber
                            "CLEAR_DEVICE_OWNER" -> Icons.Outlined.DeleteForever
                            "LOCATE" -> Icons.Outlined.MyLocation
                            "RING" -> Icons.Outlined.NotificationsActive
                            "STOP_RING" -> Icons.AutoMirrored.Outlined.VolumeOff
                            "LOCK" -> Icons.Outlined.Lock
                            "EXIT_KIOSK" -> Icons.Outlined.LockOpen
                            "UNPAIR" -> Icons.Outlined.LinkOff
                            else -> Icons.Outlined.Security
                        },
                        contentDescription = null,
                        tint = when {
                            isTestMode -> Color(0xFF8B5CF6)
                            isDestructive -> SheTheme.colors.danger
                            else -> SheTheme.colors.accent
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = when (cmd) {
                            "TEST_MODE" -> "Simulated Drill (Test Mode)"
                            "STOLEN_MODE" -> "Real Stolen Lockdown"
                            "CLEAR_DEVICE_OWNER" -> "Clear Device Owner"
                            else -> "Confirm: $cmd"
                        },
                        color = if (isDestructive) SheTheme.colors.danger else SheTheme.colors.textPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Target: ${state.device.device_alias}",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    isTestMode -> {
                        SheCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = Color(0xFF8B5CF6).copy(alpha = 0.12f),
                            borderColor = Color(0xFF8B5CF6).copy(alpha = 0.4f),
                            cornerRadius = 14.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "NON-DESTRUCTIVE SECURITY DRILL",
                                    color = Color(0xFF8B5CF6),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Sounds handset siren for 10 seconds only. It will NOT broadcast emergency alerts to police or official authorities.",
                                    color = SheTheme.colors.textPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }

                        SheTextField(
                            value = friendPhone,
                            onValueChange = { friendPhone = it },
                            label = "FRIEND'S MOBILE FOR DRILL SMS (OPTIONAL)",
                            placeholder = "+1234567890 or +91...",
                            helperText = "Enter friend's number to test offline SMS beacon reception safely without irritating officers. Leave empty for sound-only drill.",
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                            )
                        )
                    }
                    isStolenMode -> {
                        SheCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = SheTheme.colors.dangerContainer.copy(alpha = 0.3f),
                            borderColor = SheTheme.colors.danger,
                            cornerRadius = 14.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "LIVE THEFT RECOVERY MODE",
                                    color = SheTheme.colors.danger,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Escalates to maximum threat level: continuous acoustic siren, forces enterprise kiosk lock, suppresses notifications, and broadcasts emergency SMS beacon to your verified contacts.",
                                    color = SheTheme.colors.textPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                    isClearOwner -> {
                        SheCard(
                            modifier = Modifier.fillMaxWidth(),
                            backgroundColor = SheTheme.colors.dangerContainer.copy(alpha = 0.3f),
                            borderColor = SheTheme.colors.danger,
                            cornerRadius = 14.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "PERMANENT DEPROVISIONING",
                                    color = SheTheme.colors.danger,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Permanently deprovisions Device Owner administrative control, revokes enterprise lockdown, and clears factory reset protection.",
                                    color = SheTheme.colors.textPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = when (cmd) {
                                "LOCATE" -> "Pings the handset's hardware GPS to capture and transmit fresh satellite coordinates immediately."
                                "RING" -> "Triggers loud acoustic emergency siren at 100% maximum volume (overrides silent/DND mode)."
                                "STOP_RING" -> "Silences the active acoustic siren alert."
                                "LOCK" -> "Instantly turns off the screen and forces enterprise kiosk/PIN lockdown."
                                "EXIT_KIOSK" -> "Releases kiosk lock and restores default Android home screen."
                                "UNPAIR" -> "Revokes mutual pairing keys between this companion and the target handset."
                                else -> "Dispatches signed cryptographic command to target device."
                            },
                            color = SheTheme.colors.textSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }

                SheTextField(
                    value = totpCode,
                    onValueChange = { totpCode = it },
                    label = "6-DIGIT TOTP OR RECOVERY CODE",
                    placeholder = "Enter 6-digit TOTP or recovery key",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Text
                    )
                )
            }
        },
        confirmButton = {
            SheButton(
                text = when {
                    isTestMode -> "Run Drill Simulation"
                    isStolenMode -> "Arm Stolen Lockdown"
                    isClearOwner -> "Deprovision Device"
                    else -> "Dispatch Command"
                },
                onClick = { onConfirm(totpCode.trim(), friendPhone.trim()) },
                enabled = totpCode.isNotBlank(),
                variant = if (isDestructive) SheButtonVariant.DANGER else SheButtonVariant.PRIMARY
            )
        },
        dismissButton = {
            SheButton(
                text = "Cancel",
                onClick = onDismiss,
                variant = SheButtonVariant.OUTLINED
            )
        }
    )
}

@Composable
private fun IosCommandGuideDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SheTheme.colors.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = SheTheme.colors.accent,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Recovery Command Center",
                    color = SheTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Test Mode vs Real Mode Comparison Card
                SheCard(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = SheTheme.colors.surfaceVariant,
                    borderColor = SheTheme.colors.accent.copy(alpha = 0.5f),
                    cornerRadius = 14.dp
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "TEST MODE VS REAL MODE",
                            color = SheTheme.colors.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "• TEST MODE (Drill):\n" +
                                   "  Runs a non-destructive simulation. The siren sounds for 10 seconds only. You can input a friend's mobile number to test offline SMS beacons without notifying police or emergency authorities.\n\n" +
                                   "• REAL MODE (STOLEN_MODE):\n" +
                                   "  Full production theft lockdown. Sounds continuous acoustic siren + TTS alert, forces enterprise kiosk, hides notifications, and transmits emergency SMS beacons with live GPS coordinates to verified emergency contacts.",
                            color = SheTheme.colors.textPrimary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                }

                Text(
                    text = "COMMAND REFERENCE",
                    color = SheTheme.colors.textTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )

                val guideItems = listOf(
                    Triple("LOCATE", "Captures fresh GPS fix via satellite hardware and transmits over WebSocket.", Icons.Outlined.MyLocation),
                    Triple("RING", "Plays maximum volume siren alert to pinpoint phone nearby (overrides Silent/DND).", Icons.Outlined.NotificationsActive),
                    Triple("STOP_RING", "Silences the acoustic emergency siren alert.", Icons.AutoMirrored.Outlined.VolumeOff),
                    Triple("LOCK", "Forces immediate screen sleep and enterprise PIN/kiosk lockdown.", Icons.Outlined.Lock),
                    Triple("EXIT_KIOSK", "Releases stolen kiosk lock and restores default Android home launcher.", Icons.Outlined.LockOpen),
                    Triple("UNPAIR", "Revokes mutual pairing handshake keys between companion and handset.", Icons.Outlined.LinkOff),
                    Triple("CLEAR_DEVICE_OWNER", "Deprovisions enterprise Device Owner administration (requires TOTP confirmation).", Icons.Outlined.DeleteForever)
                )

                guideItems.forEach { (cmd, desc, icon) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SheTheme.colors.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = SheTheme.colors.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = cmd,
                                color = SheTheme.colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = desc,
                                color = SheTheme.colors.textSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            SheButton(
                text = "Got It",
                onClick = onDismiss,
                variant = SheButtonVariant.PRIMARY
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveSessionsSheet(
    sessions: List<UserSessionDto>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onRevokeOtherSessions: () -> Unit,
    onRevokeSession: (String, Boolean) -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheTheme.colors.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // iOS Handle & Title
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(SheTheme.colors.border)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Security,
                        contentDescription = null,
                        tint = SheTheme.colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Active Login Sessions",
                        color = SheTheme.colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Manage logged-in devices. Registered phone is protected.",
                    color = SheTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }

            // Quick Revoke Others Card
            SheCard(
                modifier = Modifier.fillMaxWidth(),
                backgroundColor = SheTheme.colors.surfaceVariant,
                cornerRadius = 16.dp
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = SheTheme.colors.accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "PRIMARY PHONE PROTECTION",
                            color = SheTheme.colors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Text(
                        text = "Terminating other sessions logs out web emergency consoles and borrowed devices immediately, but will NEVER log out your primary registered phone.",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    SheButton(
                        text = if (isLoading) "Processing..." else "Log Out All Other Sessions",
                        onClick = onRevokeOtherSessions,
                        variant = SheButtonVariant.DANGER,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )
                }
            }

            // Sessions List
            Text(
                text = "CURRENT SESSIONS (${sessions.size})",
                color = SheTheme.colors.textTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            if (isLoading && sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SheTheme.colors.accent)
                }
            } else if (sessions.isEmpty()) {
                Text(
                    text = "No active sessions found.",
                    color = SheTheme.colors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                sessions.forEach { s ->
                    SessionRowCard(
                        session = s,
                        onRevoke = { onRevokeSession(s.id, false) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SheButton(
                text = "Close",
                onClick = onDismiss,
                variant = SheButtonVariant.PRIMARY,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SessionRowCard(
    session: UserSessionDto,
    onRevoke: () -> Unit
) {
    SheCard(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = if (session.is_current) SheTheme.colors.surfaceVariant.copy(alpha = 0.8f) else SheTheme.colors.surface,
        borderColor = if (session.is_current) SheTheme.colors.accent.copy(alpha = 0.5f) else SheTheme.colors.border,
        cornerRadius = 14.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (session.is_registered_device) Icons.Outlined.Smartphone else Icons.Outlined.Security,
                        contentDescription = null,
                        tint = if (session.is_registered_device) SheTheme.colors.success else SheTheme.colors.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = when {
                            session.is_registered_device -> "Registered Phone"
                            session.user_agent?.contains("SHE-Admin", ignoreCase = true) == true -> "Admin Handset"
                            else -> "Web / Emergency Console"
                        },
                        color = SheTheme.colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (session.is_registered_device) {
                        SheStatusChip(text = "REGISTERED", level = StatusLevel.SUCCESS)
                    }
                    if (session.is_current) {
                        SheStatusChip(text = "THIS PHONE", level = StatusLevel.NEUTRAL)
                    }
                }
            }

            if (!session.device_info.isNullOrBlank()) {
                Text(
                    text = session.device_info,
                    color = SheTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "IP: ${session.ip_address ?: "Unknown"}",
                    color = SheTheme.colors.textTertiary,
                    fontSize = 11.sp
                )
                Text(
                    text = "Active: ${session.last_active_at.take(19).replace('T', ' ')}",
                    color = SheTheme.colors.textTertiary,
                    fontSize = 11.sp
                )
            }

            if (!session.is_current) {
                Spacer(modifier = Modifier.height(4.dp))
                SheButton(
                    text = if (session.is_registered_device) "Revoke (Registered Phone)" else "Log Out This Session",
                    onClick = onRevoke,
                    variant = SheButtonVariant.OUTLINED,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


