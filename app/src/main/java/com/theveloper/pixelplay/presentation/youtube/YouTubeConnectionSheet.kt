package com.theveloper.pixelplay.presentation.youtube

import android.app.Activity
import android.content.ContextWrapper
import android.view.WindowManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YouTubeConnectionSheet(viewModel: OnlineHomeViewModel, onDismiss: () -> Unit, onOtherServices: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(context) {
        var base = context
        while (base is ContextWrapper && base !is Activity) base = base.baseContext
        val window = (base as? Activity)?.window
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val account by viewModel.account.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val listeningSync by viewModel.listeningSyncState.collectAsStateWithLifecycle()
    // Sensitive input deliberately never enters rememberSaveable or a ViewModel.
    var session by remember { mutableStateOf("") }
    var accountIndex by remember { mutableStateOf("0") }
    var showInstructions by remember { mutableStateOf(false) }
    var showManualImport by remember { mutableStateOf(false) }
    var fileLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val handoff = com.theveloper.pixelplay.data.youtube.YouTubeSessionStore(context, YouTubeLoginActivity.PENDING_SESSION)
            val captured = handoff.read()
            handoff.clear()
            if (captured != null) viewModel.connectAccount(captured.cookie, captured.authUser)
            else viewModel.connectionInputError()
        } else if (result.resultCode == YouTubeLoginActivity.RESULT_IMPORT) showManualImport = true
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            fileLoading = true
            try {
                session = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(out.size() + count <= 65_536)
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray().decodeToString()
                    } ?: error("Missing file")
                }
                viewModel.clearConnectionError()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { session = ""; viewModel.connectionInputError() }
            finally { fileLoading = false }
        }
    }
    LaunchedEffect(Unit) { viewModel.clearConnectionError() }
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = { session = ""; onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .navigationBarsPadding().imePadding().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Text("YouTube Music", style = MaterialTheme.typography.headlineSmall)
            if (account != null) {
                Text(account!!.name, style = MaterialTheme.typography.titleMedium)
                Text(if (account!!.needsReconnect) "Your session expired. Sign in again to reconnect."
                    else "Connected. Home uses your account’s recommendations.", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync listening with YouTube Music", style = MaterialTheme.typography.titleSmall)
                        Text("Adds new listens of 30 seconds or longer to your account history after the listening session ends.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = listeningSync.enabled, onCheckedChange = viewModel::setListeningSyncEnabled,
                        enabled = listeningSync.ready && (listeningSync.enabled || !account!!.needsReconnect))
                }
                Text(if (listeningSync.waitingForInternet) "Waiting for internet. Your listening reports are saved." else when (listeningSync.notice) {
                    "reconnect" -> "Sign in again to resume listening sync."
                    "storage" -> "Listening sync is paused because its queue could not be saved."
                    "unconfirmed" -> "One report could not be confirmed. It will not be resent automatically."
                    "unavailable" -> "The last song could not be reported. Playback is unaffected."
                    "failed" -> "A report failed after retries. New listens can still sync."
                    "retry" -> "Waiting to retry a listening report."
                    else -> when {
                        !listeningSync.enabled -> "Listening sync is off."
                        listeningSync.pending > 0 -> "Listening reports waiting: ${listeningSync.pending}"
                        listeningSync.lastAcceptedAt != null -> "Last listening report accepted by YouTube Music."
                        else -> "Ready for your next listening session."
                    }
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { session = ""; viewModel.disconnectAccount() }, enabled = !connection.loading) {
                    Text("Disconnect")
                }
            } else Text("Connect your account for personal recommendations. Your session stays encrypted on this phone.",
                style = MaterialTheme.typography.bodyMedium)
            if (account == null || account!!.needsReconnect) {
                Button(onClick = { session = ""; signIn.launch(Intent(context, YouTubeLoginActivity::class.java)) },
                    enabled = !connection.loading, modifier = Modifier.fillMaxWidth()) { Text("Sign in to YouTube Music") }
                Text("Sign in inside orynq., then choose Connect this account. Google may block embedded sign-in on some devices.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showManualImport = !showManualImport }) { Text(if (showManualImport) "Hide session import" else "Use session import instead") }
                if (showManualImport) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com"))) }
                        .onFailure { viewModel.connectionInputError() }
                }, modifier = Modifier.fillMaxWidth()) { Text("Open YouTube Music in browser") }
                Text("Browser sign-in does not automatically connect orynq. Import your session below.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showInstructions = !showInstructions }) { Text(if (showInstructions) "Hide instructions" else "How to connect") }
                if (showInstructions) {
                    Text("On a computer, sign in at music.youtube.com. Open browser Developer Tools → Network, then reload the page. Select a /youtubei/v1/browse request and copy only its Cookie request header. Paste it below, or import a browser.json / cookies.txt session file.\n\nTreat the session like a password. Only import your own account; do not send it to anyone. If multiple Google accounts are signed in, use the request’s X-Goog-AuthUser number below.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(value = session, onValueChange = { if (it.length <= 65_536) session = it },
                    label = { Text("Session cookie") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    enabled = !connection.loading && !fileLoading)
                OutlinedButton(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/octet-stream")) },
                    enabled = !connection.loading && !fileLoading) { Text("Import session file") }
                OutlinedTextField(value = accountIndex, onValueChange = { if (it.isEmpty() || (it.length == 1 && it[0] in '0'..'9')) accountIndex = it },
                    label = { Text("Account index") }, supportingText = { Text("Usually 0") }, singleLine = true,
                    enabled = !connection.loading, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    val input = session
                    session = ""
                    viewModel.connectAccount(input, accountIndex)
                }, enabled = session.isNotBlank() && accountIndex.isNotBlank() && !connection.loading && !fileLoading,
                    modifier = Modifier.fillMaxWidth()) { Text(if (connection.loading) "Connecting…" else "Connect account") }
                }
            }
            if (connection.loading || fileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            connection.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider()
            TextButton(onClick = { session = ""; onOtherServices() }) { Text("Other streaming services") }
        }
    }
}
