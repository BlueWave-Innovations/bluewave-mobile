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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.example.bluewave_mobile.ui.components.InitialAvatar
import com.example.bluewave_mobile.ui.components.SectionHeader
import com.example.bluewave_mobile.ui.components.SettingsCard
import com.example.bluewave_mobile.ui.components.SettingsRow
import com.example.bluewave_mobile.ui.components.SettingsRowDivider
import com.example.bluewave_mobile.ui.components.pressScale
import com.example.bluewave_mobile.ui.theme.AccentCyan
import com.example.bluewave_mobile.ui.theme.AccentIndigo
import com.example.bluewave_mobile.ui.theme.BrandBlue
import com.example.bluewave_mobile.ui.theme.SuccessGreen
import com.example.bluewave_mobile.utils.BlueWaveLogger
import com.example.bluewave_mobile.utils.LogExporter
import com.example.bluewave_mobile.ui.viewmodel.SettingsViewModel

/**
 * Settings tab — modern redesign.
 *
 * Layout (top → bottom):
 *  1. Branded profile hero card — gradient backdrop, initial-based
 *     avatar, name, online-via-Bluetooth status pill, optional QR
 *     button on the right.
 *  2. Section "ВНЕШНИЙ ВИД" / "Appearance":
 *      * Theme row → bottom-sheet picker
 *      * Chat-folders row → navigates to `FoldersManagementScreen`
 *  3. Section "СИСТЕМА" / "System":
 *      * Language row → bottom-sheet picker
 *      * Bluetooth-visibility row → switch
 *      * Inline explanatory caption
 *
 * The picker dialogs preserve the existing `SettingsViewModel`
 * surface contract (one write per pick) and the platform
 * `ACTION_REQUEST_DISCOVERABLE` is still launched on every
 * positive Bluetooth-visibility transition, exactly as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenProfile: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val profile: LocalProfile by viewModel.profile.collectAsStateWithLifecycle()
    val themeMode: ThemeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val appLanguage: AppLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val bluetoothVisibility: BluetoothVisibility by viewModel.bluetoothVisibility.collectAsStateWithLifecycle()

    var themePickerOpen by rememberSaveable { mutableStateOf(false) }
    var languagePickerOpen by rememberSaveable { mutableStateOf(false) }

    // ACTION_REQUEST_DISCOVERABLE returns the duration the platform
    // actually granted (or `RESULT_CANCELED` = -1 if the user
    // declined). We persist whatever the user picked, so the next
    // cold launch can re-issue the dialog if discoverability has
    // since lapsed.
    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_CANCELED) {
            viewModel.setBluetoothVisibility(BluetoothVisibility.OFF)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProfileHeroCard(
                profile = profile,
                online = bluetoothVisibility != BluetoothVisibility.OFF,
                onClick = onOpenProfile,
            )

            Spacer(modifier = Modifier.height(8.dp))

            SectionHeader(title = stringResource(id = R.string.settings_section_appearance_caps))
            SettingsCard {
                SettingsRow(
                    icon = Icons.Filled.Palette,
                    title = stringResource(id = R.string.settings_theme_short),
                    iconTint = AccentIndigo,
                    onClick = { themePickerOpen = true },
                    trailing = {
                        TrailingValue(text = themeLabel(themeMode))
                    },
                )
            }

            SectionHeader(title = stringResource(id = R.string.settings_section_system_caps))
            SettingsCard {
                SettingsRow(
                    icon = Icons.Filled.Language,
                    title = stringResource(id = R.string.settings_language_short),
                    iconTint = SuccessGreen,
                    onClick = { languagePickerOpen = true },
                    trailing = {
                        TrailingValue(text = languageLabel(appLanguage))
                    },
                )
                SettingsRowDivider()
                BluetoothVisibilityRow(
                    visibility = bluetoothVisibility,
                    onToggle = { enabled ->
                        if (enabled) {
                            // Default to a comfortable 30-minute
                            // window when the switch flips on —
                            // the user can pick a different duration
                            // from the picker (kept for advanced
                            // flows, hidden in this card).
                            viewModel.setBluetoothVisibility(BluetoothVisibility.MIN_30)
                            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                .putExtra(
                                    BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                                    BluetoothVisibility.MIN_30.durationSeconds,
                                )
                            runCatching { discoverableLauncher.launch(intent) }
                        } else {
                            viewModel.setBluetoothVisibility(BluetoothVisibility.OFF)
                        }
                    },
                )
                SettingsRowDivider()
                val context = androidx.compose.ui.platform.LocalContext.current
                var exportSnackbar by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(exportSnackbar) {
                    exportSnackbar?.let { msg ->
                        // In a real app we'd use a SnackbarHost, but a
                        // simple Toast via a helper keeps the diff small.
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                        exportSnackbar = null
                    }
                }
                SettingsRow(
                    icon = Icons.Filled.Description,
                    title = stringResource(id = R.string.settings_export_logs),
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {
                        BlueWaveLogger.i("Settings", "User requested log export")
                        val exported = LogExporter.exportToDownloads(context)
                        exportSnackbar = if (exported != null) {
                            context.getString(R.string.settings_export_logs_success)
                        } else {
                            context.getString(R.string.settings_export_logs_failed)
                        }
                    },
                )
            }
            Text(
                text = stringResource(id = R.string.settings_bt_visibility_short_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }

    if (themePickerOpen) {
        ThemePickerDialog(
            selected = themeMode,
            onSelect = {
                viewModel.setThemeMode(it)
                themePickerOpen = false
            },
            onDismiss = { themePickerOpen = false },
        )
    }
    if (languagePickerOpen) {
        LanguagePickerDialog(
            selected = appLanguage,
            onSelect = {
                viewModel.setAppLanguage(it)
                languagePickerOpen = false
            },
            onDismiss = { languagePickerOpen = false },
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Profile hero card
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileHeroCard(
    profile: LocalProfile,
    online: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroAvatar(profile = profile)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                val displayName = profile.displayName.ifBlank {
                    stringResource(id = R.string.profile_default_name)
                }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(4.dp))
                BluetoothStatusPill(online = online)
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCode2,
                    contentDescription = stringResource(id = R.string.settings_qr_share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HeroAvatar(profile: LocalProfile) {
    val displayName = profile.displayName.ifBlank { "JD" }
    val avatarUri = profile.avatarUri
    if (!avatarUri.isNullOrBlank()) {
        AsyncImage(
            model = avatarUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape),
        )
    } else {
        InitialAvatar(name = displayName, size = 54)
    }
}

@Composable
private fun BluetoothStatusPill(online: Boolean) {
    val statusText = if (online) {
        stringResource(id = R.string.settings_profile_card_status)
    } else {
        stringResource(id = R.string.settings_profile_card_status_offline)
    }
    val accent = if (online) BrandBlue else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Bluetooth visibility row (switch flavour)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun BluetoothVisibilityRow(
    visibility: BluetoothVisibility,
    onToggle: (Boolean) -> Unit,
) {
    SettingsRow(
        icon = Icons.Filled.Bluetooth,
        title = stringResource(id = R.string.settings_bt_visibility_short),
        iconTint = BrandBlue,
        trailing = {
            Switch(
                checked = visibility != BluetoothVisibility.OFF,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = BrandBlue,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                    uncheckedBorderColor = Color.Transparent,
                ),
            )
        },
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Picker dialogs
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ThemePickerDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    PickerDialog(
        titleResId = R.string.settings_theme_picker_title,
        options = listOf(
            ThemeMode.SYSTEM to R.string.settings_theme_system,
            ThemeMode.LIGHT to R.string.settings_theme_light,
            ThemeMode.DARK to R.string.settings_theme_dark,
        ),
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

@Composable
private fun LanguagePickerDialog(
    selected: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    PickerDialog(
        titleResId = R.string.settings_language_picker_title,
        options = listOf(
            AppLanguage.SYSTEM to R.string.settings_language_system,
            AppLanguage.ENGLISH to R.string.settings_language_english,
            AppLanguage.RUSSIAN to R.string.settings_language_russian,
        ),
        selected = selected,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

@Composable
private fun <T> PickerDialog(
    titleResId: Int,
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = titleResId),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column {
                options.forEach { (value, labelResId) ->
                    val isSelected = value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.RadioButton) { onSelect(value) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = labelResId),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.common_done))
            }
        },
        shape = RoundedCornerShape(24.dp),
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// Small helpers
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrailingValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(id = R.string.settings_theme_system)
    ThemeMode.LIGHT -> stringResource(id = R.string.settings_theme_light)
    ThemeMode.DARK -> stringResource(id = R.string.settings_theme_dark)
}

@Composable
private fun languageLabel(lang: AppLanguage): String = when (lang) {
    AppLanguage.SYSTEM -> stringResource(id = R.string.settings_language_system)
    AppLanguage.ENGLISH -> stringResource(id = R.string.settings_language_english)
    AppLanguage.RUSSIAN -> stringResource(id = R.string.settings_language_russian)
}

