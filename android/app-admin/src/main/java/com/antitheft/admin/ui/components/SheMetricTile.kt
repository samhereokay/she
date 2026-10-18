package com.antitheft.admin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.ui.theme.SheTheme

@Composable
fun SheMetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    subtext: String? = null,
    icon: (@Composable () -> Unit)? = null,
    highlightColor: Color = SheTheme.colors.accent
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = SheTheme.colors.surface,
        border = BorderStroke(1.dp, SheTheme.colors.border)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label.uppercase(),
                    color = SheTheme.colors.textSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(SheTheme.colors.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        icon()
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = value,
                color = highlightColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            if (!subtext.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtext,
                    color = SheTheme.colors.textTertiary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun SheSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            color = SheTheme.colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )

        if (!actionText.isNullOrBlank() && onActionClick != null) {
            Text(
                text = actionText,
                color = SheTheme.colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
