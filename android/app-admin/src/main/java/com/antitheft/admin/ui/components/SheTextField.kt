package com.antitheft.admin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antitheft.admin.ui.theme.SheTheme

@Composable
fun SheTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    helperText: String? = null,
    isError: Boolean = false,
    errorMessage: String? = null,
    isPassword: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                color = if (isError) SheTheme.colors.danger else SheTheme.colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        val shape = RoundedCornerShape(14.dp)
        val borderColor = when {
            isError -> SheTheme.colors.danger
            else -> SheTheme.colors.border
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(SheTheme.colors.surface)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(modifier = Modifier.width(10.dp))
                }

                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = SheTheme.colors.textTertiary,
                            fontSize = 14.sp
                        )
                    }

                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = SheTheme.colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        cursorBrush = SolidColor(SheTheme.colors.accent),
                        singleLine = singleLine,
                        keyboardOptions = keyboardOptions,
                        keyboardActions = keyboardActions,
                        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None
                    )
                }

                if (isPassword) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (passwordVisible) "HIDE" else "SHOW",
                        color = SheTheme.colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(4.dp)
                            .then(
                                Modifier.padding(horizontal = 4.dp)
                            )
                    )
                }
            }
        }

        val displayHelp = if (isError && !errorMessage.isNullOrBlank()) errorMessage else helperText
        if (!displayHelp.isNullOrBlank()) {
            Text(
                text = displayHelp,
                color = if (isError) SheTheme.colors.danger else SheTheme.colors.textTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}
