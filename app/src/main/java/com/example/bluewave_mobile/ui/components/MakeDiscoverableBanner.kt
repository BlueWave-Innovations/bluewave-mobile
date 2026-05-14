package com.example.bluewave_mobile.ui.components

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.preferences.BluetoothVisibility
import com.example.bluewave_mobile.ui.theme.BrandBlue
import com.example.bluewave_mobile.ui.viewmodel.SettingsViewModel

/**
 * Slim banner shown on the chats / device list screen whenever the
 * user's Bluetooth discoverable window is OFF.
 *
 * Tapping the CTA persists a 30-minute window through
 * [SettingsViewModel.setBluetoothVisibility] and immediately fires
 * the system `ACTION_REQUEST_DISCOVERABLE` dialog — the user accepts
 * once, no detour through system Settings required. The banner
 * disappears with a tasteful slide-up animation as soon as the
 * persisted value flips away from `OFF`.
 *
 * Hidden entirely (no slot consumed) when visibility is already on,
 * so the host can drop this composable in unconditionally.
 */
@Composable
fun MakeDiscoverableBanner(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val visibility: BluetoothVisibility by viewModel.bluetoothVisibility.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) {
            viewModel.setBluetoothVisibility(BluetoothVisibility.OFF)
        }
    }

    AnimatedVisibility(
        visible = visibility == BluetoothVisibility.OFF,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(
                    color = BrandBlue.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = BrandBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(id = R.string.discoverable_banner_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(id = R.string.discoverable_banner_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        viewModel.setBluetoothVisibility(BluetoothVisibility.MIN_30)
                        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(
                                BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                                BluetoothVisibility.MIN_30.durationSeconds,
                            )
                        runCatching { discoverableLauncher.launch(intent) }
                    },
                ) {
                    Text(
                        text = stringResource(id = R.string.discoverable_banner_cta),
                        color = BrandBlue,
                    )
                }
            }
        }
    }
}
