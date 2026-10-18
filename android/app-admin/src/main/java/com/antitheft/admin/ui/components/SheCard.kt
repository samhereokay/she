package com.antitheft.admin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.ui.theme.SheTheme

@Composable
fun SheCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    backgroundColor: Color = SheTheme.colors.surface,
    borderColor: Color = SheTheme.colors.border,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        content()
    }
}

enum class StatusLevel {
    SUCCESS,
    WARNING,
    DANGER,
    INFO,
    NEUTRAL
}

@Composable
fun SheStatusChip(
    text: String,
    level: StatusLevel = StatusLevel.INFO,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, dotColor) = when (level) {
        StatusLevel.SUCCESS -> Triple(
            SheTheme.colors.successContainer,
            SheTheme.colors.success,
            SheTheme.colors.success
        )
        StatusLevel.WARNING -> Triple(
            SheTheme.colors.warningContainer,
            SheTheme.colors.warning,
            SheTheme.colors.warning
        )
        StatusLevel.DANGER -> Triple(
            SheTheme.colors.dangerContainer,
            SheTheme.colors.danger,
            SheTheme.colors.danger
        )
        StatusLevel.INFO -> Triple(
            SheTheme.colors.accentContainer,
            SheTheme.colors.accent,
            SheTheme.colors.accent
        )
        StatusLevel.NEUTRAL -> Triple(
            SheTheme.colors.surfaceVariant,
            SheTheme.colors.textSecondary,
            SheTheme.colors.textTertiary
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(bgColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text.uppercase(),
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}
