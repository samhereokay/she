package com.antitheft.admin.auth

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Usb
import com.antitheft.common.util.ApkShareHelper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.R
import com.antitheft.admin.ui.components.SheButton
import com.antitheft.admin.ui.components.SheButtonVariant
import com.antitheft.admin.ui.components.SheCard
import com.antitheft.admin.ui.components.SheTextField
import com.antitheft.admin.ui.components.SheThemeSelector
import com.antitheft.admin.ui.theme.SheTheme

data class TotpSetupState(
    val secret: String,
    val otpauthUri: String
)

@Composable
fun AdminLoginScreen(
    initialServerUrl: String,
    onLoginClick: (username: String, pass: String, serverUrl: String) -> Unit,
    onRegisterClick: (username: String, email: String, phone: String, pass: String, regToken: String, serverUrl: String) -> Unit,
    totpSetupState: TotpSetupState?,
    onVerifyTotpSetup: (code: String) -> Unit,
    recoveryKeysState: List<String>?,
    onDismissRecoveryKeys: () -> Unit,
    show2faLoginDialog: Boolean,
    onValidate2faLogin: (code: String) -> Unit,
    onDismiss2faLogin: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registrationToken by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var termsAccepted by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var termsError by remember { mutableStateOf<String?>(null) }

    // Dialog state
    var totpCodeInput by remember { mutableStateOf("") }
    var login2faCodeInput by remember { mutableStateOf("") }

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
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Trust & Privacy Info Card
            SheCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                backgroundColor = SheTheme.colors.surface,
                borderColor = SheTheme.colors.border
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Security,
                            contentDescription = null,
                            tint = SheTheme.colors.accent,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "100% Self-Hosted • Zero Commercial API Keys",
                            color = SheTheme.colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "No Google Cloud, Firebase, or external API keys required. All telemetry, encrypted vault items, and remote commands communicate directly with your private server.",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
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
                    letterSpacing = 0.8.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SheButton(
                        text = "Railway",
                        onClick = { serverUrl = "https://she.up.railway.app" },
                        variant = if (serverUrl == "https://she.up.railway.app") SheButtonVariant.PRIMARY else SheButtonVariant.TONAL,
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
                        variant = if (serverUrl.contains("127.0.0.1")) SheButtonVariant.PRIMARY else SheButtonVariant.TONAL,
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
                        variant = if (serverUrl.contains("10.0.2.2")) SheButtonVariant.PRIMARY else SheButtonVariant.TONAL,
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

            // Server URL Input
            SheTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = "Server Endpoint URL",
                placeholder = "https://...",
                helperText = "Connects to your self-hosted instance",
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Username
            SheTextField(
                value = username,
                onValueChange = { username = it },
                label = if (isRegisterMode) "Username" else "Username or Email",
                placeholder = if (isRegisterMode) "e.g. sam_owner" else "admin@example.com",
                modifier = Modifier.fillMaxWidth()
            )

            if (isRegisterMode) {
                Spacer(modifier = Modifier.height(14.dp))

                // Email
                SheTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email Address",
                    placeholder = "owner@example.com",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Owner Phone Number
                SheTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = "Owner Mobile Number",
                    placeholder = "+91 98765 43210",
                    helperText = "Emergency SMS recovery contact for offline beacons",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Password
            SheTextField(
                value = password,
                onValueChange = { password = it },
                label = "Master Password",
                placeholder = "••••••••••••",
                isPassword = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (isRegisterMode) {
                Spacer(modifier = Modifier.height(14.dp))

                // Registration Verification Token
                SheTextField(
                    value = registrationToken,
                    onValueChange = { registrationToken = it },
                    label = "Registration Verification Token",
                    placeholder = "Paste invitation / setup token",
                    helperText = "Required invitation token from your server administrator",
                    modifier = Modifier.fillMaxWidth()
                )
            }

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
                text = if (isRegisterMode) "Create Verified Owner Account" else "Sign In to Admin Center",
                onClick = {
                    if (!termsAccepted) {
                        termsError = "You must agree to the Terms of Service & Privacy Policy."
                        return@SheButton
                    }
                    termsError = null
                    if (isRegisterMode) {
                        onRegisterClick(username, email, phoneNumber, password, registrationToken, serverUrl)
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
                onClick = {
                    isRegisterMode = !isRegisterMode
                    termsError = null
                },
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

            Spacer(modifier = Modifier.height(20.dp))

            SheButton(
                text = "Share SHE App / APK (Bluetooth / Web)",
                onClick = {
                    ApkShareHelper.showShareOptions(
                        context,
                        "SHE Anti-Theft Admin",
                        "she-admin.apk"
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                variant = SheButtonVariant.OUTLINED,
                height = 42.dp,
                cornerRadius = 12.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // -------------------------------------------------------------
        // Step 1: Two-Factor Authentication Setup Modal
        // -------------------------------------------------------------
        if (totpSetupState != null) {
            AlertDialog(
                onDismissRequest = { /* Modal: Must complete setup to activate account */ },
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
                            text = "Link Authenticator (2FA)",
                            color = SheTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                },
                text = {
                    val qrBitmap = remember(totpSetupState.otpauthUri) {
                        try {
                            val barcodeEncoder = BarcodeEncoder()
                            barcodeEncoder.encodeBitmap(
                                totpSetupState.otpauthUri,
                                BarcodeFormat.QR_CODE,
                                600,
                                600
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Scan this QR code with Google Authenticator, Aegis, 2FAS, or Bitwarden to link 2FA, or enter the secret key manually below:",
                            color = SheTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // High-contrast QR Code Container
                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(androidx.compose.ui.graphics.Color.White)
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = qrBitmap.asImageBitmap(),
                                    contentDescription = "TOTP Setup QR Code",
                                    modifier = Modifier.size(190.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // Secret Key Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SheTheme.colors.surfaceVariant)
                                .border(1.dp, SheTheme.colors.border, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = "TOTP SECRET KEY",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SheTheme.colors.accent,
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = totpSetupState.secret,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = SheTheme.colors.textPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SheButton(
                                text = "Copy Secret",
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(totpSetupState.secret))
                                    Toast.makeText(context, "TOTP Secret copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                variant = SheButtonVariant.TONAL,
                                height = 36.dp,
                                cornerRadius = 8.dp,
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                            SheButton(
                                text = "Open App",
                                onClick = {
                                    try {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(totpSetupState.otpauthUri))
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "No authenticator app found. Copy the secret key instead.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                variant = SheButtonVariant.TONAL,
                                height = 36.dp,
                                cornerRadius = 8.dp,
                                icon = {
                                    Icon(
                                        imageVector = Icons.Outlined.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        SheTextField(
                            value = totpCodeInput,
                            onValueChange = { totpCodeInput = it },
                            label = "6-Digit Authenticator Code",
                            placeholder = "123456",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    SheButton(
                        text = "Verify & Activate 2FA",
                        onClick = {
                            if (totpCodeInput.isNotBlank()) {
                                onVerifyTotpSetup(totpCodeInput.trim())
                            } else {
                                Toast.makeText(context, "Enter the 6-digit code from your authenticator app", Toast.LENGTH_SHORT).show()
                            }
                        },
                        isLoading = isLoading,
                        height = 40.dp,
                        cornerRadius = 10.dp
                    )
                },
                containerColor = SheTheme.colors.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // -------------------------------------------------------------
        // Step 2: Emergency Recovery Keys Modal
        // -------------------------------------------------------------
        if (recoveryKeysState != null) {
            AlertDialog(
                onDismissRequest = { /* Must explicitly confirm saving keys */ },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null,
                            tint = SheTheme.colors.accent,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Emergency Recovery Keys",
                            color = SheTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        SheCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 12.dp,
                            backgroundColor = SheTheme.colors.surfaceVariant,
                            borderColor = SheTheme.colors.accent
                        ) {
                            Text(
                                text = "CRITICAL: If your phone is lost or stolen, your Google Authenticator is also on that stolen phone. Save these 8 keys in a safe place. You can use ANY single-use key to sign into the Emergency Web Console at https://she.up.railway.app/emergency from any borrowed phone or laptop.",
                                color = SheTheme.colors.textPrimary,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SheTheme.colors.surfaceVariant)
                                .border(1.dp, SheTheme.colors.border, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                recoveryKeysState.chunked(2).forEach { pair ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        pair.forEach { key ->
                                            Text(
                                                text = "• $key",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = SheTheme.colors.textPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        SheButton(
                            text = "Copy All Recovery Keys",
                            onClick = {
                                val keysFormatted = "SHE Anti-Theft Emergency Recovery Keys:\n" +
                                    recoveryKeysState.joinToString("\n") { "• $it" } +
                                    "\n\nLive Emergency Recovery Console: https://she.up.railway.app/emergency"
                                clipboardManager.setText(AnnotatedString(keysFormatted))
                                Toast.makeText(context, "All 8 recovery keys copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            variant = SheButtonVariant.TONAL,
                            height = 36.dp,
                            cornerRadius = 8.dp,
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    SheButton(
                        text = "I've Saved My Keys • Continue",
                        onClick = onDismissRecoveryKeys,
                        height = 40.dp,
                        cornerRadius = 10.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                containerColor = SheTheme.colors.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // -------------------------------------------------------------
        // Step 3: Two-Factor Login Verification Modal
        // -------------------------------------------------------------
        if (show2faLoginDialog) {
            AlertDialog(
                onDismissRequest = onDismiss2faLogin,
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
                            text = "Two-Factor Authentication",
                            color = SheTheme.colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Enter your 6-digit Authenticator code, or enter an 8-character emergency recovery key (XXXX-XXXX):",
                            color = SheTheme.colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        SheTextField(
                            value = login2faCodeInput,
                            onValueChange = { login2faCodeInput = it },
                            label = "TOTP Code or Recovery Key",
                            placeholder = "123456 or K4M9-7P2X",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    SheButton(
                        text = "Verify & Sign In",
                        onClick = {
                            if (login2faCodeInput.isNotBlank()) {
                                onValidate2faLogin(login2faCodeInput.trim())
                            }
                        },
                        isLoading = isLoading,
                        height = 40.dp,
                        cornerRadius = 10.dp
                    )
                },
                dismissButton = {
                    TextButton(onClick = onDismiss2faLogin) {
                        Text("Cancel", color = SheTheme.colors.textSecondary)
                    }
                },
                containerColor = SheTheme.colors.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // -------------------------------------------------------------
        // Terms of Service & Privacy Policy Dialog
        // -------------------------------------------------------------
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
                                "3. Data Protection Policy: SHE is designed to protect your data, never erase it. Remote commands lock down the screen, engage anti-theft kiosk mode, and track GPS to recover your device intact without data destruction.\n\n" +
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
