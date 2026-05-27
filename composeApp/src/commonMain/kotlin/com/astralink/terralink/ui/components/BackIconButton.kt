package com.astralink.terralink.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

/**
 * Single back-arrow IconButton used in every TopAppBar navigationIcon.
 * Uses a Unicode "‹" glyph instead of material-icons-extended because
 * Compose Multiplatform 1.10 doesn't publish that artifact anymore --
 * the glyph + IconButton's circular ripple gives a clean, minimal look.
 */
@Composable
fun BackIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Text(
            text = "‹",
            fontSize = 30.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
