package com.example.bluewave_mobile.ui.screens

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.preferences.AppLanguage
import com.example.bluewave_mobile.preferences.BluetoothVisibility
import com.example.bluewave_mobile.preferences.LocalProfile
import com.example.bluewave_mobile.preferences.ThemeMode
import com.example.bluewave_mobile.ui.viewmodel.SettingsViewModel

/**
 * Full implementation of the Settings tab.
 *
 * Top to bottom:
 *  1. Profile card (avatar + display name + @handle) — tapping
 *     navigates to the Profile tab.
 *  2. Appearance section: theme picker, language picker.
 *  3. Connectivity section: Bluetooth-visibility picker — picking
 *     anything other than [BluetoothVisibility.OFF] also fires the
 *     system `ACTION_REQUEST_DISCOVERABLE` dialog so the platform
 *     advertises us for the chosen window.
 *  4. Organisation section: pushes
 *     [com.example.bluewave_mobile.ui.navigation.FoldersManagementRoute].
 *
 * The screen is intentionally radio-button heavy (no spinners /
 * dropdowns) because each pick triggers a write — there is no
 * "Save" button. This matches the mockup the user signed off on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenFolders: () -> Unit,
    onOpenProfile: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val profile: LocalProfile by viewModel.profile.collectAsStateWithLifecycle()
    val themeMode: ThemeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage: AppLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val bluetoothVisibility: BluetoothVisibility by viewModel.bluetoothVisibility.collectAsStateWithLifecycle()

    // ACTION_REQUEST_DISCOVERABLE returns the duration the platform
    // actually granted (or `RESULT_CANCELED` = -1 if the user
    // declined). We persist whatever the user picked, so the next
    // cold launch can re-issue the dialog if discoverability has
    // since lapsed.
    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) {
            // User refused — fall back to OFF so the toggle reflects
            // reality.
            viewModel.setBluetoothVisibility(BluetoothVisibility.OFF)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.settings_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfileSummaryCard(
                    profile = profile,
                    onClick = onOpenProfile,
                )

                SettingsSection(titleResId = R.string.settings_section_appearance) {
                    ThemeModePicker(
                        selected = themeMode,
                        onSelect = viewModel::setThemeMode,
                    )
                    HorizontalDivider()
                    LanguagePicker(
                        selected = appLanguage,
                        onSelect = viewModel::setAppLanguage,
                    )
                }

                SettingsSection(titleResId = R.string.settings_section_connectivity) {
                    BluetoothVisibilityPicker(
                        selected = bluetoothVisibility,
                        onSelect = { value ->
                            viewModel.setBluetoothVisibility(value)
                            if (value != BluetoothVisibility.OFF) {
                                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                    .putExtra(
                                        BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                                        value.durationSeconds,
                                    )
                                runCatching { discoverableLauncher.launch(intent) }
                            }
                        },
                    )
                    Text(
                        text = stringResource(id = R.string.settings_bt_visibility_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                SettingsSection(titleResId = R.string.settings_section_organization) {
                    NavigationRow(
                        icon = Icons.Filled.Folder,
                        title = stringResource(id = R.string.settings_open_folders),
                        subtitle = stringResource(id = R.string.settings_open_folders_summary),
                        onClick = onOpenFolders,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileSummaryCard(
    profile: LocalProfile,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatarThumbnail(uri = profile.avatarUri)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val displayName = profile.displayName.ifBlank {
                    stringResource(id = R.string.profile_default_name)
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
                if (profile.handle.isNotBlank()) {
                    Text(
                        text = "@" + profile.handle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProfileAvatarThumbnail(uri: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                color = MaterialTheme.colorScheme.primary,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (uri.isNotBlank()) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun SettingsSection(
    titleResId: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = stringResource(id = titleResId),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun ThemeModePicker(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    SettingHeader(titleResId = R.string.settings_theme)
    RadioRow(
        labelResId = R.string.settings_theme_system,
        selected = selected == ThemeMode.SYSTEM,
        onClick = { onSelect(ThemeMode.SYSTEM) },
    )
    RadioRow(
        labelResId = R.string.settings_theme_light,
        selected = selected == ThemeMode.LIGHT,
        onClick = { onSelect(ThemeMode.LIGHT) },
    )
    RadioRow(
        labelResId = R.string.settings_theme_dark,
        selected = selected == ThemeMode.DARK,
        onClick = { onSelect(ThemeMode.DARK) },
    )
}

@Composable
private fun LanguagePicker(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
) {
    SettingHeader(titleResId = R.string.settings_language)
    RadioRow(
        labelResId = R.string.settings_language_system,
        selected = selected == AppLanguage.SYSTEM,
        onClick = { onSelect(AppLanguage.SYSTEM) },
    )
    RadioRow(
        labelResId = R.string.settings_language_english,
        selected = selected == AppLanguage.ENGLISH,
        onClick = { onSelect(AppLanguage.ENGLISH) },
    )
    RadioRow(
        labelResId = R.string.settings_language_russian,
        selected = selected == AppLanguage.RUSSIAN,
        onClick = { onSelect(AppLanguage.RUSSIAN) },
    )
}

@Composable
private fun BluetoothVisibilityPicker(
    selected: BluetoothVisibility,
    onSelect: (BluetoothVisibility) -> Unit,
) {
    SettingHeader(titleResId = R.string.settings_bt_visibility)
    RadioRow(
        labelResId = R.string.settings_bt_visibility_off,
        selected = selected == BluetoothVisibility.OFF,
        onClick = { onSelect(BluetoothVisibility.OFF) },
    )
    RadioRow(
        labelResId = R.string.settings_bt_visibility_5min,
        selected = selected == BluetoothVisibility.MIN_5,
        onClick = { onSelect(BluetoothVisibility.MIN_5) },
    )
    RadioRow(
        labelResId = R.string.settings_bt_visibility_30min,
        selected = selected == BluetoothVisibility.MIN_30,
        onClick = { onSelect(BluetoothVisibility.MIN_30) },
    )
    RadioRow(
        labelResId = R.string.settings_bt_visibility_120min,
        selected = selected == BluetoothVisibility.MIN_120,
        onClick = { onSelect(BluetoothVisibility.MIN_120) },
    )
}

@Composable
private fun SettingHeader(titleResId: Int) {
    Text(
        text = stringResource(id = titleResId),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun RadioRow(
    labelResId: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = stringResource(id = labelResId),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun NavigationRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
