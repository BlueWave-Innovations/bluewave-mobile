package com.example.bluewave_mobile.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.bluewave_mobile.R
import com.example.bluewave_mobile.ui.state.QrShareUiState
import com.example.bluewave_mobile.ui.viewmodel.QrShareViewModel
import kotlinx.coroutines.launch

/**
 * Screen that renders the local profile as a `bluewave://contact`
 * QR code and lets the user share or copy the deep link.
 *
 * Three sections stacked inside a [LazyColumn]:
 *
 *  1. **QR card** — square 1:1 surface that shows the rendered
 *     bitmap. Falls back to the empty-state copy from
 *     [QrShareUiState.NoMac] if the local Bluetooth MAC is missing.
 *  2. **Share row** — Material 3 [Button] / [OutlinedButton] pair
 *     that copies the deep link to the clipboard or opens the system
 *     share sheet via [Intent.ACTION_SEND].
 *  3. **Paste-to-scan card** — text field that accepts a
 *     `bluewave://contact?...` URI and forwards the parsed MAC to
 *     [onContactScanned]. This is the simplest scan flow that does
 *     not require a camera permission and works offline; a camera
 *     scanner is tracked as future work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrShareScreen(
    onClose: () -> Unit,
    onContactScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: QrShareViewModel = viewModel(factory = QrShareViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val copiedMessage = stringResource(id = R.string.qr_share_copied)
    val invalidMessage = stringResource(id = R.string.qr_share_invalid)
    var pasted: String by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(id = R.string.qr_share_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(id = R.string.action_close),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "qr") { QrSurface(uiState = uiState) }
            item(key = "share") {
                ShareRow(
                    uiState = uiState,
                    onCopy = { deepLink ->
                        copyToClipboard(context, deepLink)
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(copiedMessage)
                        }
                    },
                    onShare = { deepLink ->
                        launchShareIntent(context, deepLink)
                    },
                )
            }
            item(key = "scan_paste") {
                ScanCard(
                    pasted = pasted,
                    onPastedChange = { pasted = it },
                    onParse = { input ->
                        val payload = viewModel.parsePastedUri(input)
                        if (payload == null) {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(invalidMessage)
                            }
                        } else {
                            onContactScanned(payload.macAddress)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun QrSurface(uiState: QrShareUiState) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(16.dp),
        ) {
            when (uiState) {
                is QrShareUiState.Loading -> CircularProgressIndicator()
                is QrShareUiState.NoMac -> {
                    Text(
                        text = stringResource(id = R.string.qr_share_no_profile),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                is QrShareUiState.Ready -> {
                    Image(
                        bitmap = uiState.bitmap.asImageBitmap(),
                        contentDescription = stringResource(id = R.string.qr_share_explainer),
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareRow(
    uiState: QrShareUiState,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    val link = (uiState as? QrShareUiState.Ready)?.deepLink
    val enabled = link != null
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(id = R.string.qr_share_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            arrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { link?.let(onShare) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(text = stringResource(id = R.string.qr_share_share_button))
            }
            OutlinedButton(
                onClick = { link?.let(onCopy) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(text = stringResource(id = R.string.qr_share_copy_link))
            }
        }
    }
}

@Composable
private fun ScanCard(
    pasted: String,
    onPastedChange: (String) -> Unit,
    onParse: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(id = R.string.qr_share_scan_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = pasted,
                onValueChange = onPastedChange,
                placeholder = {
                    Text(text = "bluewave://contact?mac=…")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onParse(pasted) },
                enabled = pasted.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(text = stringResource(id = R.string.qr_share_scan_action))
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("bluewave_contact", text)
    clipboard?.setPrimaryClip(clip)
}

private fun launchShareIntent(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val chooser = Intent.createChooser(intent, null)
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(chooser)
}

@Composable
private fun Row(
    verticalAlignment: Alignment.Vertical,
    arrangement: Arrangement.Horizontal,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = verticalAlignment,
        horizontalArrangement = arrangement,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        content = content,
    )
}
