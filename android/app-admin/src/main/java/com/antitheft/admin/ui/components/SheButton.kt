package com.antitheft.admin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.ui.theme.SheTheme

enum class SheButtonVariant {
    PRIMARY,
    SECONDARY,
    TONAL,
    OUTLINED,
    DANGER
}

@Composable
fun SheButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: SheButtonVariant = SheButtonVariant.PRIMARY,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    height: Dp = 50.dp,
    cornerRadius: Dp = 14.dp,
    icon: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(cornerRadius)

    when (variant) {
        SheButtonVariant.PRIMARY -> {
            Button(
                onClick = onClick,
                enabled = enabled && !isLoading,
                modifier = modifier.height(height),
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheTheme.colors.accent,
                    contentColor = if (SheTheme.colors.isDark) Color.Black else Color.White,
                    disabledContainerColor = SheTheme.colors.surfaceVariant,
                    disabledContentColor = SheTheme.colors.textTertiary
                ),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                ButtonInnerContent(text, isLoading, icon, if (SheTheme.colors.isDark) Color.Black else Color.White)
            }
        }
        SheButtonVariant.TONAL -> {
            Button(
                onClick = onClick,
                enabled = enabled && !isLoading,
                modifier = modifier.height(height),
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheTheme.colors.accentContainer,
                    contentColor = SheTheme.colors.accent,
                    disabledContainerColor = SheTheme.colors.surfaceVariant,
                    disabledContentColor = SheTheme.colors.textTertiary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                ButtonInnerContent(text, isLoading, icon, SheTheme.colors.accent)
            }
        }
        SheButtonVariant.SECONDARY -> {
            Button(
                onClick = onClick,
                enabled = enabled && !isLoading,
                modifier = modifier.height(height),
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheTheme.colors.surfaceVariant,
                    contentColor = SheTheme.colors.textPrimary,
                    disabledContainerColor = SheTheme.colors.surfaceVariant,
                    disabledContentColor = SheTheme.colors.textTertiary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                ButtonInnerContent(text, isLoading, icon, SheTheme.colors.textPrimary)
            }
        }
        SheButtonVariant.OUTLINED -> {
            OutlinedButton(
                onClick = onClick,
                enabled = enabled && !isLoading,
                modifier = modifier.height(height),
                shape = shape,
                border = BorderStroke(1.dp, SheTheme.colors.border),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = SheTheme.colors.textPrimary,
                    disabledContentColor = SheTheme.colors.textTertiary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp)
            ) {
                ButtonInnerContent(text, isLoading, icon, SheTheme.colors.textPrimary)
            }
        }
        SheButtonVariant.DANGER -> {
            Button(
                onClick = onClick,
                enabled = enabled && !isLoading,
                modifier = modifier.height(height),
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheTheme.colors.danger,
                    contentColor = Color.White,
                    disabledContainerColor = SheTheme.colors.surfaceVariant,
                    disabledContentColor = SheTheme.colors.textTertiary
                ),
                contentPadding = PaddingValues(horizontal = 20.dp)
            ) {
                ButtonInnerContent(text, isLoading, icon, Color.White)
            }
        }
    }
}

@Composable
private fun ButtonInnerContent(
    text: String,
    isLoading: Boolean,
    icon: (@Composable () -> Unit)?,
    contentColor: Color
) {
    if (isLoading) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = contentColor,
            strokeWidth = 2.dp
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
