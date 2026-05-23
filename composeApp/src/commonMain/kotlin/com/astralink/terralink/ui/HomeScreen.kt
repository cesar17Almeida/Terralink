package com.astralink.terralink.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralink.terralink.model.Device
import com.astralink.terralink.ui.components.DeviceInfoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    device: Device,
    onUpdateFirmware: () -> Unit,
    onSyncData: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "TerraLink",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Configuración de estaciones",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = "Estación detectada",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            DeviceInfoCard(device = device)

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onUpdateFirmware,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Actualizar firmware")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onSyncData,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Sincronizar datos")
            }
        }
    }
}
