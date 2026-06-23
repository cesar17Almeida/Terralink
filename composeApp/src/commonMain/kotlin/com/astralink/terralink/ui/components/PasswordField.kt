package com.astralink.terralink.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Password input: the shared [TerraTextField] plus a trailing eye icon to
 * reveal/hide what's typed. Same style as every other field in the app.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    TerraTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        isError = isError,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) TerraIcons.VisibilityOff else TerraIcons.Visibility,
                    contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        supportingText = supportingText,
        modifier = modifier,
    )
}
