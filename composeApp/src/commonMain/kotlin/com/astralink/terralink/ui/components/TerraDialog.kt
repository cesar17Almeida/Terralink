package com.astralink.terralink.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The single modal for the whole app -- a thin wrapper over AlertDialog so every
 * dialog shares the same minimalist look: rounded 24 dp corners, a SemiBold
 * `titleMedium` heading, and a confirm/cancel button pair. Put the body in
 * [content] (a Column scope): a sentence for confirmations, or a stack of
 * [TerraTextField]/[PasswordField] for a form. Set [destructive] to tint the
 * confirm action as a warning (e.g. delete).
 */
@Composable
fun TerraDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
    dismissText: String? = "Cancelar",
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = { Column(modifier = Modifier.dismissKeyboardOnTap(), content = content) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(
                    text = confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
            }
        },
        dismissButton = if (dismissText != null) {
            { TextButton(onClick = onDismiss) { Text(dismissText) } }
        } else {
            null
        },
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    )
}
