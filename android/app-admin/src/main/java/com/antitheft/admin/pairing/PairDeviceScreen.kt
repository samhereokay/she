package com.antitheft.admin.pairing

import android.graphics.Bitmap
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.ui.components.SheButton
import com.antitheft.admin.ui.components.SheButtonVariant
import com.antitheft.admin.ui.components.SheCard
import com.antitheft.admin.ui.components.SheTextField
import com.antitheft.admin.ui.theme.SheTheme

@Composable
fun PairDeviceScreen(
    initialAdminPhone: String,
    initialServerUrl: String,
    qrBitmap: Bitmap?,
    pairingSecretHex: String,
    onGenerateQr: (phone: String, serverUrl: String) -> Unit,
    onConfirmCode: (code: String) -> Unit,
    onPairSameDevice: () -> Unit,
    showSameDeviceButton: Boolean,
    onBack: () -> Unit,
    isLoading: Boolean
) {
    var adminPhone by remember { mutableStateOf(initialAdminPhone) }
    var serverUrl by remember { mutableStateOf(initialServerUrl) }
    var confirmCode by remember { mutableStateOf("") }

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
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Pair Protected Handset",
                color = SheTheme.colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Cryptographic ECDSA & HMAC Handshake • Zero API Keys",
                color = SheTheme.colors.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
            )

            // Step 1 Card
            SheCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                backgroundColor = SheTheme.colors.surface
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "STEP 1: RECOVERY SMS & SERVER",
                        color = SheTheme.colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Guardian sends offline encrypted SMS beacons to your phone if the internet connection is severed.",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    SheTextField(
                        value = adminPhone,
                        onValueChange = { adminPhone = it },
                        label = "Your Phone Number (SMS Alerts)",
                        placeholder = "+91 9876543210",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    SheTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = "Backend URL (Embedded in QR)",
                        placeholder = "https://...",
                        helperText = "Self-hosted URL (Cloudflare Tunnel or Local IP)",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    SheButton(
                        text = "Generate Pairing QR Code",
                        onClick = { onGenerateQr(adminPhone, serverUrl) },
                        isLoading = isLoading,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 2 Card (QR Display)
            SheCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                backgroundColor = SheTheme.colors.surface
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "STEP 2: SCAN WITH GUARDIAN PHONE",
                        color = SheTheme.colors.accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Point the camera of your Guardian phone at this screen to exchange public keys.",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                    )

                    // White Container for QR
                    Box(
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Pairing QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = "Tap 'Generate' above\nto create QR code",
                                color = androidx.compose.ui.graphics.Color.Gray,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    if (pairingSecretHex.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Secret: ${pairingSecretHex.take(16)}...",
                            color = SheTheme.colors.textTertiary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    if (showSameDeviceButton) {
                        Spacer(modifier = Modifier.height(12.dp))
                        SheButton(
                            text = "Pair on This Handset (Single Device)",
                            onClick = onPairSameDevice,
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Smartphone,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            variant = SheButtonVariant.TONAL,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Step 3 Card (Confirm Code)
            SheCard(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 20.dp,
                backgroundColor = SheTheme.colors.surface
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "STEP 3: ENTER CONFIRMATION CODE",
                        color = SheTheme.colors.success,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Enter the 6-digit confirmation code shown on the Guardian handset screen:",
                        color = SheTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    SheTextField(
                        value = confirmCode,
                        onValueChange = { confirmCode = it },
                        placeholder = "e.g. 521-893",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    SheButton(
                        text = "Confirm & Activate Guardian",
                        onClick = { onConfirmCode(confirmCode) },
                        isLoading = isLoading,
                        variant = SheButtonVariant.PRIMARY,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SheButton(
                text = "Back to Devices",
                onClick = onBack,
                variant = SheButtonVariant.OUTLINED,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
