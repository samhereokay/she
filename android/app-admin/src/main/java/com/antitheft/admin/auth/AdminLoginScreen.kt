package com.antitheft.admin.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.R
import com.antitheft.admin.ui.components.SheButton
import com.antitheft.admin.ui.components.SheButtonVariant
import com.antitheft.admin.ui.components.SheCard
import com.antitheft.admin.ui.components.SheTextField
import com.antitheft.admin.ui.components.SheThemeSelector
import com.antitheft.admin.ui.theme.SheTheme

@Composable
fun AdminLoginScreen(
    initialServerUrl: String,
    onLoginClick: (username: String, pass: String, serverUrl: String) -> Unit,
    onRegisterClick: (username: String, pass: String, serverUrl: String) -> Unit,
    isLoading: Boolean,
    errorMessage: String?
) {
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var termsAccepted by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var termsError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SheTheme.colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Brand Logo & Title
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(SheTheme.colors.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_she_logo),
                    contentDescription = "SHE Logo",
                    modifier = Modifier.size(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "SHE ADMIN",
                color = SheTheme.colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Text(
                text = "Sentinel for Handheld Equipment • Command Center",
                color = SheTheme.colors.textSecondary,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Zero API Key Reassurance Card
            SheCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                backgroundColor = SheTheme.colors.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = SheTheme.colors.accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "100% Self-Hosted • Zero Commercial API Keys",
                            color = SheTheme.colors.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "No Google Cloud, Firebase, or external API keys required. All telemetry, encrypted vault items, and remote commands communicate directly with your private server.",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Server Presets
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "SERVER PRESETS",
                    color = SheTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SheButton(
                        text = "Railway",
                        onClick = { serverUrl = "https://she.up.railway.app" },
                        variant = SheButtonVariant.TONAL,
                        height = 36.dp,
                        cornerRadius = 10.dp,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.CloudQueue,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    SheButton(
                        text = "USB",
                        onClick = { serverUrl = "http://127.0.0.1:8000" },
                        variant = SheButtonVariant.TONAL,
                        height = 36.dp,
                        cornerRadius = 10.dp,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Usb,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                    SheButton(
                        text = "Emulator",
                        onClick = { serverUrl = "http://10.0.2.2:8000" },
                        variant = SheButtonVariant.TONAL,
                        height = 36.dp,
                        cornerRadius = 10.dp,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Computer,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Inputs
            SheTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = "Server Endpoint URL",
                placeholder = "https://...",
                helperText = "Connects to your self-hosted instance",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            SheTextField(
                value = username,
                onValueChange = { username = it },
                label = "Username or Email",
                placeholder = "admin@example.com",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            SheTextField(
                value = password,
                onValueChange = { password = it },
                label = "Master Password",
                placeholder = "••••••••••••",
                isPassword = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                SheCard(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 12.dp,
                    backgroundColor = SheTheme.colors.dangerContainer,
                    borderColor = SheTheme.colors.danger
                ) {
                    Text(
                        text = errorMessage,
                        color = SheTheme.colors.danger,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Terms of Service & Privacy Agreement Checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        termsAccepted = !termsAccepted
                        termsError = null
                    }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = termsAccepted,
                    onCheckedChange = {
                        termsAccepted = it
                        termsError = null
                    },
                    colors = CheckboxDefaults.colors(
                        checkedColor = SheTheme.colors.accent,
                        uncheckedColor = SheTheme.colors.textSecondary,
                        checkmarkColor = SheTheme.colors.background
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = buildAnnotatedString {
                        append("I agree to the ")
                        withStyle(SpanStyle(color = SheTheme.colors.accent, fontWeight = FontWeight.Bold)) {
                            append("Terms of Service")
                        }
                        append(" & ")
                        withStyle(SpanStyle(color = SheTheme.colors.accent, fontWeight = FontWeight.Bold)) {
                            append("Privacy Policy")
                        }
                    },
                    fontSize = 12.sp,
                    color = SheTheme.colors.textSecondary,
                    modifier = Modifier.clickable { showTermsDialog = true }
                )
            }

            if (termsError != null) {
                Text(
                    text = termsError!!,
                    color = SheTheme.colors.danger,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 2.dp, bottom = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons
            SheButton(
                text = if (isRegisterMode) "Create Owner Account" else "Sign In to Admin Center",
                onClick = {
                    if (!termsAccepted) {
                        termsError = "You must agree to the Terms of Service & Privacy Policy."
                        return@SheButton
                    }
                    termsError = null
                    if (isRegisterMode) {
                        onRegisterClick(username, password, serverUrl)
                    } else {
                        onLoginClick(username, password, serverUrl)
                    }
                },
                isLoading = isLoading,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(10.dp))

            SheButton(
                text = if (isRegisterMode) "Already have an account? Sign In" else "Create New Owner Account",
                onClick = { isRegisterMode = !isRegisterMode },
                variant = SheButtonVariant.OUTLINED,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Multi-Tenant Isolation: Every owner account is protected by strict Row-Level Security (RLS). You only see and manage your own paired devices.",
                color = SheTheme.colors.textTertiary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Theme Selector
            Text(
                text = "THEME MODE",
                color = SheTheme.colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            SheThemeSelector(modifier = Modifier.fillMaxWidth())
        }

        // Terms of Service & Privacy Policy Dialog
        if (showTermsDialog) {
            AlertDialog(
                onDismissRequest = { showTermsDialog = false },
                title = {
                    Text(
                        text = "Terms of Service & Privacy Policy",
                        color = SheTheme.colors.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "SHE Sentinel Anti-Theft Handset Agreement:\n\n" +
                                "1. Authorized Personal Use: SHE is licensed exclusively for personal anti-theft defense, emergency device tracking, and data protection on handsets you own or legally manage. Stalking or unauthorized surveillance on third parties is strictly prohibited.\n\n" +
                                "2. Single-Device Limit: Each account is strictly restricted to 1 active protected phone to maintain cryptographic chain-of-custody.\n\n" +
                                "3. Permanent Remote Commands: Executing WIPE permanently erases device storage. The owner bears sole responsibility for dispatching remote commands.\n\n" +
                                "4. Zero Commercial Telemetry & 30-Day Purge: Location records older than 30 days are automatically purged. Vault secrets are protected with zero-knowledge AES-256-GCM encryption.\n\n" +
                                "5. Open-Source Software: Distributed under the Apache 2.0 License without warranties of any kind.\n\n" +
                                "Full policies available at:\n" +
                                "• Terms: https://she.up.railway.app/terms\n" +
                                "• Privacy: https://she.up.railway.app/privacy",
                            color = SheTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            termsAccepted = true
                            termsError = null
                            showTermsDialog = false
                        }
                    ) {
                        Text("Accept & Close", color = SheTheme.colors.accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTermsDialog = false }) {
                        Text("Close", color = SheTheme.colors.textSecondary)
                    }
                },
                containerColor = SheTheme.colors.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}
