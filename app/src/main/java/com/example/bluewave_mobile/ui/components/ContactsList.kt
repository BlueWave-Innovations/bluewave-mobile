package com.example.bluewave_mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.model.ContactRow
import com.example.bluewave_mobile.ui.preview.PreviewLightDark
import com.example.bluewave_mobile.ui.theme.BlueWaveTheme
import java.text.DateFormat
import java.util.Date

/**
 * Sectioned contact list rendered by `DeviceListScreen` once the
 * runtime permission gate has cleared.
 *
 * The composable consumes a flat `List<ContactRow>` produced by
 * [com.example.bluewave_mobile.ui.viewmodel.DeviceListViewModel] —
 * row order in the list determines render order. Sections are
 * separated by a sticky-ish header drawn from the row's runtime
 * subtype (`ExistingChat`, `StartChatCandidate`, `InstallSuggestion`)
 * — when consecutive rows share a subtype the header is suppressed.
 *
 * Each individual row is its own composable so previews can target
 * one section at a time without spinning up the entire screen.
 *
 * @param onRowClick Invoked with the tapped row's MAC address when
 *                   the user picks a chat or a "can start" candidate.
 *                   Suppressed for [ContactRow.InstallSuggestion]
 *                   rows — those route to [onSuggestInstall] instead.
 * @param onSuggestInstall Invoked with the tapped row's MAC address
 *                         when the user taps the "share APK" CTA on
 *                         a no-app row.
 */
@Composable
fun ContactsList(
    rows: List<ContactRow>,
    onRowClick: (String) -> Unit,
    onSuggestInstall: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 8.dp),
    onGroupClick: (String) -> Unit = onRowClick,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        var lastKey: String? = null
        for ((index, row) in rows.withIndex()) {
            val sectionKey = sectionKeyOf(row)
            if (sectionKey != lastKey) {
                item(key = "header_$sectionKey") { SectionHeader(sectionKey) }
                lastKey = sectionKey
            }
            item(key = "row_${row.macAddress}_$index") {
                when (row) {
                    is ContactRow.ExistingChat -> ExistingChatRow(
                        row = row,
                        onClick = { onRowClick(row.macAddress) },
                    )
                    is ContactRow.StartChatCandidate -> StartChatRow(
                        row = row,
                        onClick = { onRowClick(row.macAddress) },
                    )
                    is ContactRow.InstallSuggestion -> InstallSuggestionRow(
                        row = row,
                        onSuggestInstall = { onSuggestInstall(row.macAddress) },
                    )
                    is ContactRow.GroupChat -> GroupChatRow(
                        row = row,
                        onClick = { onGroupClick(row.groupId) },
                    )
                }
            }
        }
    }
}

/**
 * Stable per-section identifier used both for `LazyColumn` keys and
 * for picking the localized header label.
 */
private fun sectionKeyOf(row: ContactRow): String = when (row) {
    is ContactRow.GroupChat -> "groups"
    is ContactRow.ExistingChat -> "chats"
    is ContactRow.StartChatCandidate -> "candidates"
    is ContactRow.InstallSuggestion -> "installs"
}

@Composable
private fun SectionHeader(sectionKey: String) {
    val label = when (sectionKey) {
        "groups" -> stringResource(id = R.string.contacts_groups_section)
        "chats" -> stringResource(id = R.string.contacts_section_chats)
        "candidates" -> stringResource(id = R.string.contacts_section_can_chat)
        "installs" -> stringResource(id = R.string.contacts_section_install_suggest)
        else -> ""
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ExistingChatRow(
    row: ContactRow.ExistingChat,
    onClick: () -> Unit,
) {
    val timestampLabel: String = remember(row.lastMessageTimestamp) {
        DateFormat.getTimeInstance(DateFormat.SHORT)
            .format(Date(row.lastMessageTimestamp))
    }
    val unreadDescription = if (row.unreadCount > 0) {
        stringResource(id = R.string.contacts_chat_unread_badge_cd, row.unreadCount)
    } else {
        ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = listOf(row.displayName, unreadDescription)
                    .filter(String::isNotEmpty)
                    .joinToString(separator = ", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            online = row.isOnline,
            badgeText = row.displayName.firstOrNull()?.uppercase() ?: "?",
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = timestampLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.lastMessagePreview.ifEmpty {
                        stringResource(id = R.string.contacts_chat_empty_preview)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.unreadCount > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (row.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = row.unreadCount.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartChatRow(
    row: ContactRow.StartChatCandidate,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = row.displayName
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (row.isBonded) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (row.isBonded) {
                    stringResource(id = R.string.contacts_paired_label)
                } else {
                    row.macAddress
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InstallSuggestionRow(
    row: ContactRow.InstallSuggestion,
    onSuggestInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(id = R.string.contacts_install_suggest_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onSuggestInstall) {
            Text(text = stringResource(id = R.string.contacts_install_suggest_action))
        }
    }
}

@Composable
private fun GroupChatRow(
    row: ContactRow.GroupChat,
    onClick: () -> Unit,
) {
    val timestampLabel: String = if (row.lastMessageTimestamp > 0L) {
        remember(row.lastMessageTimestamp) {
            DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(Date(row.lastMessageTimestamp))
        }
    } else {
        ""
    }
    val unreadDescription = if (row.unreadCount > 0) {
        stringResource(id = R.string.contacts_chat_unread_badge_cd, row.unreadCount)
    } else {
        ""
    }
    val memberCountLabel = stringResource(id = R.string.group_chat_member_count, row.memberCount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = listOf(row.displayName, memberCountLabel, unreadDescription)
                    .filter(String::isNotEmpty)
                    .joinToString(separator = ", ")
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupAvatarTile()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (timestampLabel.isNotEmpty()) {
                    Text(
                        text = timestampLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.lastMessagePreview.ifEmpty { memberCountLabel },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.unreadCount > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (row.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(
                            text = row.unreadCount.coerceAtMost(99).toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupAvatarTile() {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Group,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun Avatar(online: Boolean, badgeText: String) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badgeText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        if (online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Inlined `remember` shim — pulled into a private local helper so
 * `ContactsList` can stay self-contained and previews don't need to
 * pull in `androidx.compose.runtime.remember` explicitly.
 */
@Composable
private fun <T> remember(key: Any?, block: () -> T): T =
    androidx.compose.runtime.remember(key) { block() }

@PreviewLightDark
@Composable
private fun ContactsListPreview() {
    BlueWaveTheme {
        ContactsList(
            rows = listOf(
                ContactRow.GroupChat(
                    displayName = "Дизайн-команда",
                    groupId = "group:design",
                    memberCount = 4,
                    lastMessagePreview = "Алекс: ок, заливаю",
                    lastMessageTimestamp = System.currentTimeMillis() - 2_000L,
                    unreadCount = 5,
                ),
                ContactRow.ExistingChat(
                    displayName = "Aleksandr",
                    macAddress = "AA:BB:CC:11:22:33",
                    lastMessagePreview = "Готово, забрал. Встречаемся в 19?",
                    lastMessageTimestamp = System.currentTimeMillis() - 5_000L,
                    unreadCount = 2,
                    isOnline = true,
                ),
                ContactRow.ExistingChat(
                    displayName = "Mama",
                    macAddress = "BB:CC:DD:22:33:44",
                    lastMessagePreview = "Ужин на столе.",
                    lastMessageTimestamp = System.currentTimeMillis() - 60_000L,
                    unreadCount = 0,
                    isOnline = false,
                ),
                ContactRow.StartChatCandidate(
                    displayName = "Pixel 9 Pro",
                    macAddress = "CC:DD:EE:33:44:55",
                    isBonded = true,
                ),
                ContactRow.InstallSuggestion(
                    displayName = "OnePlus 13",
                    macAddress = "DD:EE:FF:44:55:66",
                ),
            ),
            onRowClick = {},
            onSuggestInstall = {},
        )
    }
}
