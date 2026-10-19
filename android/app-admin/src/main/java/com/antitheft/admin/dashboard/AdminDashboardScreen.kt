package com.antitheft.admin.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
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
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
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
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    devices: List<DeviceDto>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onOpenVault: () -> Unit,
    onLogout: () -> Unit,
    onPairDevice: () -> Unit,
    onSendCommand: (DeviceDto, String) -> Unit,
    onShowGuide: () -> Unit
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

    // Command Dispatch Bottom Sheet
    if (selectedDeviceForCommand != null) {
        val dev = selectedDeviceForCommand!!
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedDeviceForCommand = null },
            sheetState = sheetState,
            containerColor = SheTheme.colors.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Remote Command: ${dev.device_alias}",
                    color = SheTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Dispatches cryptographically signed ECDSA command to handset.",
                    color = SheTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                val commands = listOf(
                    Triple("LOCATE", "Ping real-time GPS coordinates", Icons.Outlined.MyLocation),
                    Triple("RING", "Play maximum-volume siren alarm", Icons.Outlined.NotificationsActive),
                    Triple("STOP_RING", "Silence siren alarm", Icons.Outlined.VolumeOff),
                    Triple("LOCK", "Enforce instant screen lock", Icons.Outlined.Lock),
                    Triple("TEST_MODE", "Run simulated recovery drill", Icons.Outlined.Science),
                    Triple("STOLEN_MODE", "Engage anti-theft lockdown & kiosk", Icons.Outlined.WarningAmber),
                    Triple("EXIT_KIOSK", "Release stolen mode kiosk lockdown", Icons.Outlined.LockOpen),
                    Triple("UNPAIR", "Revoke pairing keys and unbind", Icons.Outlined.LinkOff),
                    Triple("CLEAR_DEVICE_OWNER", "Revoke enterprise device owner", Icons.Outlined.DeleteForever)
                )

                commands.forEach { (cmd, desc, iconVector) ->
                    val isDestructive = cmd.contains("STOLEN") || cmd.contains("CLEAR") || cmd.contains("UNPAIR")
                    SheCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                selectedDeviceForCommand = null
                                onSendCommand(dev, cmd)
                            },
                        cornerRadius = 14.dp,
                        backgroundColor = SheTheme.colors.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDestructive) SheTheme.colors.dangerContainer else SheTheme.colors.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = iconVector,
                                        contentDescription = null,
                                        tint = if (isDestructive) SheTheme.colors.danger else SheTheme.colors.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = cmd,
                                        color = if (isDestructive) SheTheme.colors.danger else SheTheme.colors.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = desc,
                                        color = SheTheme.colors.textSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "SEND",
                                    color = SheTheme.colors.accent,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    tint = SheTheme.colors.accent,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    onThemeClick: () -> Unit,
    onOpenVault: () -> Unit,
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
