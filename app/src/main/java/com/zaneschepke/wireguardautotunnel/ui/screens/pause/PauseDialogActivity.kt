package com.zaneschepke.wireguardautotunnel.ui.screens.pause

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.PauseCoordinator
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.ui.theme.Theme
import com.zaneschepke.wireguardautotunnel.ui.theme.WireguardAutoTunnelTheme
import org.koin.android.ext.android.inject

/**
 * Asks how long to pause the VPN for. Shown over the current app when opened from the tunnel
 * notification.
 */
class PauseDialogActivity : AppCompatActivity() {

    private val pauseCoordinator: PauseCoordinator by inject()
    private val settingsRepository: GeneralSettingRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsRepository.flow.collectAsState(initial = null)
            WireguardAutoTunnelTheme(theme = settings?.theme ?: Theme.AUTOMATIC) {
                PauseDialog(
                    onDismiss = { finish() },
                    onPause = { durationMs ->
                        pauseCoordinator.launchPause(durationMs)
                        finish()
                    },
                )
            }
        }
    }
}

private data class Preset(val labelRes: Int, val minutes: Long)

private val presets =
    listOf(
        Preset(R.string.pause_preset_5_min, 5),
        Preset(R.string.pause_preset_15_min, 15),
        Preset(R.string.pause_preset_30_min, 30),
        Preset(R.string.pause_preset_1_hour, 60),
        Preset(R.string.pause_preset_2_hours, 120),
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PauseDialog(onDismiss: () -> Unit, onPause: (durationMs: Long?) -> Unit) {
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    val customMinutes = (hours.toLongOrNull() ?: 0) * 60 + (minutes.toLongOrNull() ?: 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pause_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.pause_dialog_message))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { preset ->
                        AssistChip(
                            onClick = { onPause(preset.minutes * PauseCoordinator.MINUTE_MS) },
                            label = { Text(stringResource(preset.labelRes)) },
                        )
                    }
                    AssistChip(
                        onClick = { onPause(null) },
                        label = { Text(stringResource(R.string.pause_indefinite)) },
                    )
                }
                Text(stringResource(R.string.pause_custom))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { hours = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.pause_hours)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.pause_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = customMinutes > 0,
                onClick = { onPause(customMinutes * PauseCoordinator.MINUTE_MS) },
            ) {
                Text(stringResource(R.string.pause))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
