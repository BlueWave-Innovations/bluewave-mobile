package com.example.bluewave_mobile.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.bluewave_mobile.R

/**
 * Shared compose-bar row used at the bottom of every chat surface.
 *
 * Hoisted into a top-level composable so [com.example.bluewave_mobile.ui.screens.ChatScreen]
 * and [com.example.bluewave_mobile.ui.screens.GroupChatScreen] can render
 * identical input affordances without duplicating the layout. Owns
 * no state of its own — the host passes [draft] and listens to
 * [onDraftChange] / [onSend].
 *
 * @param enabled Disables the field and the send button (e.g. while
 *                a peer is paused due to bond loss).
 */
@Composable
fun ChatInputRow(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val inputCd = stringResource(id = R.string.chat_input_cd)
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text(stringResource(id = R.string.chat_input_placeholder)) },
            singleLine = false,
            maxLines = 4,
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = inputCd },
        )
        SendButton(
            onClick = onSend,
            enabled = enabled && draft.isNotBlank(),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
