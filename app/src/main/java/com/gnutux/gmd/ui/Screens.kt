package com.gnutux.gmd.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gnutux.gmd.R
import com.gnutux.gmd.download.AudioFormat
import com.gnutux.gmd.download.DownloadService
import com.gnutux.gmd.download.Progress
import com.gnutux.gmd.download.Quality
import com.gnutux.gmd.update.Updater

/** حقل الرابط مع زرّ لصقٍ — على الهاتف اللصق أكثر من الكتابة بكثير. */
@Composable
private fun UrlField(vm: GmdViewModel) {
    val context = LocalContext.current
    val url by vm.url.collectAsStateWithLifecycle()
    OutlinedTextField(
        value = url,
        onValueChange = vm::setUrl,
        label = { Text(stringResource(R.string.enter_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        trailingIcon = {
            IconButton(onClick = {
                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    ?.let { text -> Regex("""https?://\S+""").find(text)?.value ?: text }
                    ?.let(vm::setUrl)
            }) {
                Icon(Icons.Filled.ContentPaste, stringResource(R.string.paste_from_clip))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(vm: GmdViewModel, progress: Progress, isAudio: Boolean, enabled: Boolean) {
    val context = LocalContext.current
    val url by vm.url.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val format by vm.audioFormat.collectAsStateWithLifecycle()
    val running = progress is Progress.Running

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UrlField(vm)

        Text(
            stringResource(if (isAudio) R.string.format else R.string.quality),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isAudio) {
                AudioFormat.entries.forEach { f ->
                    FilterChip(
                        selected = format == f,
                        onClick = { vm.audioFormat.value = f },
                        label = { Text(f.ext.uppercase()) },
                    )
                }
            } else {
                Quality.entries.forEach { q ->
                    FilterChip(
                        selected = quality == q,
                        onClick = { vm.quality.value = q },
                        label = { Text(stringResource(labelOf(q))) },
                    )
                }
            }
        }

        if (running) {
            val p = progress as Progress.Running
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (p.percent > 0f) {
                    LinearProgressIndicator({ p.percent / 100f }, Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${p.percent.toInt()}%", style = MaterialTheme.typography.labelMedium)
                    if (p.etaSeconds > 0) {
                        Text("%02d:%02d".format(p.etaSeconds / 60, p.etaSeconds % 60),
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            OutlinedButton(
                onClick = { DownloadService.stop(context) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.cancel)) }
        } else {
            Button(
                onClick = {
                    DownloadService.reset()
                    DownloadService.start(context, url.trim(), isAudio,
                        if (isAudio) format.name else quality.name)
                },
                enabled = enabled && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download))
            }
        }

        when (progress) {
            is Progress.Done -> ResultCard(
                icon = Icons.Filled.CheckCircle,
                title = stringResource(R.string.notif_done),
                body = "${progress.displayName}\n${stringResource(R.string.save_to)}: ${progress.relativePath}",
                action = stringResource(R.string.open_folder) to {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                type = if (isAudio) "audio/*" else "video/*"
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }
                    Unit
                },
            )
            is Progress.Failed -> ResultCard(
                icon = Icons.Filled.ErrorOutline,
                title = stringResource(R.string.notif_failed),
                body = progress.message,
                error = true,
            )
            else -> Unit
        }
    }
}

private fun labelOf(q: Quality) = when (q) {
    Quality.BEST -> R.string.qbest
    Quality.P1080 -> R.string.q1080
    Quality.P720 -> R.string.q720
    Quality.P480 -> R.string.q480
}

@Composable
private fun ResultCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    error: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (error)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null)
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(body, style = MaterialTheme.typography.bodySmall)
            action?.let { (label, onClick) ->
                TextButton(onClick = onClick) { Text(label) }
            }
        }
    }
}

@Composable
fun InfoScreen(vm: GmdViewModel, enabled: Boolean) {
    val url by vm.url.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val loading by vm.infoLoading.collectAsStateWithLifecycle()
    val error by vm.infoError.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UrlField(vm)
        Button(
            onClick = vm::loadInfo,
            enabled = enabled && url.isNotBlank() && !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.fetch_info))
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
        info?.let { i ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow(stringResource(R.string.info_media_title), i.title)
                    InfoRow(stringResource(R.string.info_uploader), i.uploader)
                    InfoRow(stringResource(R.string.info_duration), i.duration)
                    InfoRow(stringResource(R.string.info_resolution), i.resolution)
                    InfoRow(stringResource(R.string.info_format), i.ext)
                    InfoRow(stringResource(R.string.info_views), i.views)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(88.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SettingsScreen(vm: GmdViewModel) {
    val context = LocalContext.current
    val update by vm.update.collectAsStateWithLifecycle()
    val autoCheck by vm.autoCheckUpdates.collectAsStateWithLifecycle(initialValue = true)
    val allowPre by vm.allowPrerelease.collectAsStateWithLifecycle(initialValue = false)
    val ytdlp by vm.ytdlpVersion.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadYtDlpVersion() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)

        UpdateSection(vm, update, context)

        HorizontalDivider()

        SwitchRow(stringResource(R.string.update_auto), stringResource(R.string.update_auto_desc),
            autoCheck, vm::setAutoCheck)
        SwitchRow(stringResource(R.string.update_allow_pre), stringResource(R.string.update_allow_pre_desc),
            allowPre, vm::setAllowPrerelease)

        HorizontalDivider()

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("yt-dlp", style = MaterialTheme.typography.bodyLarge)
                Text(ytdlp ?: "—", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { vm.updateYtDlp { } }) {
                Text(stringResource(R.string.update_check_now))
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.about_free), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/SalehGNUTUX/GMD-PHONE")))
            }
        }) { Text(stringResource(R.string.about_repo)) }
    }
}

@Composable
private fun UpdateSection(vm: GmdViewModel, update: UpdatePhase, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = vm::checkForUpdates,
            enabled = update !is UpdatePhase.Checking && update !is UpdatePhase.Downloading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (update is UpdatePhase.Checking) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.update_check_now))
        }

        when (val u = update) {
            is UpdatePhase.UpToDate ->
                Text(stringResource(R.string.update_up_to_date, Updater.currentVersion(context)),
                    style = MaterialTheme.typography.bodySmall)

            is UpdatePhase.Error ->
                Text("${stringResource(R.string.update_failed)}: ${u.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)

            is UpdatePhase.Available -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.update_available, u.info.version),
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        if (u.info.prerelease) {
                            AssistChip(onClick = {}, label = { Text(stringResource(R.string.update_prerelease)) })
                        }
                    }
                    if (u.info.notes.isNotBlank()) {
                        Text(u.info.notes.take(400), style = MaterialTheme.typography.bodySmall,
                            maxLines = 8, overflow = TextOverflow.Ellipsis)
                    }
                    val asset = u.info.asset
                    if (asset == null) {
                        Text(stringResource(R.string.update_no_asset),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.info.releaseUrl)))
                            }
                        }) { Text(stringResource(R.string.update_open_release)) }
                    } else {
                        Button(onClick = { vm.downloadUpdate(asset) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${stringResource(R.string.update_download)} (${asset.size / 1048576} MB)")
                        }
                    }
                }
            }

            is UpdatePhase.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val fraction = if (u.total > 0) u.received.toFloat() / u.total else 0f
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
                Text("${u.received / 1048576} / ${u.total / 1048576} MB",
                    style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = vm::cancelUpdateDownload) { Text(stringResource(R.string.cancel)) }
            }

            is UpdatePhase.Downloaded -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!Updater.canInstall(context)) {
                    Text(stringResource(R.string.update_allow_unknown),
                        style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { Updater.install(context, u.file) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_restart))
                }
            }

            UpdatePhase.Idle, UpdatePhase.Checking -> Unit
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
