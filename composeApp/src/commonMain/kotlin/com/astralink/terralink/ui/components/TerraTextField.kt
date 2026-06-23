package com.astralink.terralink.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The single text input for the whole app -- a thin, opinionated wrapper over
 * OutlinedTextField so every field shares the same minimalist look: rounded
 * 14 dp corners, compact `bodyMedium` text, single line by default. Build forms
 * by stacking these inside a [TerraDialog] or a Column. Use [PasswordField] for
 * secrets (it adds the reveal toggle on top of this).
 */
@Composable
fun TerraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    isError: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        singleLine = singleLine,
        isError = isError,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    )
}
