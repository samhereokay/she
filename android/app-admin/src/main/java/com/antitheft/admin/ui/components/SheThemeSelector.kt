package com.antitheft.admin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.ui.theme.SheTheme
import com.antitheft.admin.ui.theme.ThemeMode
import com.antitheft.admin.ui.theme.ThemePreferences

@Composable
fun SheThemeSelector(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val themePrefs = ThemePreferences.getInstance(context)
    val currentMode by themePrefs.themeMode.collectAsState()

    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SheTheme.colors.surfaceVariant)
            .border(1.dp, SheTheme.colors.border, shape)
            .padding(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            ThemeOption(
                icon = Icons.Outlined.LightMode,
                label = "Light",
                isSelected = currentMode == ThemeMode.LIGHT,
                onClick = { themePrefs.setThemeMode(ThemeMode.LIGHT) },
                modifier = Modifier.weight(1f)
            )

            ThemeOption(
                icon = Icons.Outlined.DarkMode,
                label = "Dark",
                isSelected = currentMode == ThemeMode.DARK,
                onClick = { themePrefs.setThemeMode(ThemeMode.DARK) },
                modifier = Modifier.weight(1f)
            )

            ThemeOption(
                icon = Icons.Outlined.SettingsSuggest,
                label = "System",
                isSelected = currentMode == ThemeMode.SYSTEM,
                onClick = { themePrefs.setThemeMode(ThemeMode.SYSTEM) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ThemeOption(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val bgColor = if (isSelected) SheTheme.colors.surface else Color.Transparent
    val textColor = if (isSelected) SheTheme.colors.textPrimary else SheTheme.colors.textSecondary

    Box(
        modifier = modifier
            .clip(shape)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = textColor,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
