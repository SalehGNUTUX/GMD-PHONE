package com.gnutux.gmd.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gnutux.gmd.R
import com.gnutux.gmd.media.MediaEntry
import com.gnutux.gmd.media.MediaLibrary
import com.gnutux.gmd.media.TrimProgress
import com.gnutux.gmd.media.TrimService
import com.gnutux.gmd.media.Trimmer
import kotlinx.coroutines.launch

/**
 * اقتصاصُ ملفٍّ موجودٍ في الجهاز.
 *
 * الاقتصاصُ عندَ التنزيلِ يخدمُ من يعرفُ الجزءَ الذي يريدُه قبلَ أن ينزّل. ومن نزّلَ
 * مقطعاً كاملاً بالأمسِ كان عليه أن يُعيدَ تنزيلَه ليقتصَّ منه اليوم — وهذه الشاشةُ
 * تُغنيه: المادّةُ في يدِه وffmpeg مشحونٌ في الحزمة، فلا شبكةَ ولا انتظار.
 *
 * والأصلُ لا يُمَسّ: الناتجُ ملفٌّ جديدٌ إلى جانبِه في `Movies/GMD` أو `Music/GMD`.
 */
@Composable
fun TrimScreen(vm: GmdViewModel, progress: TrimProgress, onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val source by vm.trimSource.collectAsStateWithLifecycle()
    val start by vm.trimStart.collectAsStateWithLifecycle()
    val end by vm.trimEnd.collectAsStateWithLifecycle()

    var showLibrary by remember { mutableStateOf(false) }
    val running = progress is TrimProgress.Copying ||
        progress is TrimProgress.Cutting || progress is TrimProgress.Saving

    // نافذةُ وثائقِ النظام: تصلحُ لأيِّ ملفٍّ في الجهازِ لا لما نزّلَه GMD وحدَه
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            // الإذنُ الممنوحُ مع النتيجةِ يسقطُ بانتهاءِ المهمّة، والقصُّ يجري في
            // خدمةٍ قد تعيشُ بعدَها — فيُثبَّتُ إن قبِلَ المزوِّدُ ذلك.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            TrimService.reset()
            vm.setTrimSource(Trimmer.describe(context, uri))
        }
    }

    val src = source

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (src == null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.trim_pick_title),
                        style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.trim_pick_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { showLibrary = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PhotoLibrary, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.trim_from_gmd))
                    }
                    OutlinedButton(
                        onClick = { pick.launch(arrayOf("video/*", "audio/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.trim_from_device))
                    }
                }
            }
        } else {
            SourceCard(src, enabled = !running) {
                vm.setTrimSource(null)
                TrimService.reset()
            }

            val duration = (src.durationMs / 1000).toInt()
            val startSec = vm.parseClock(start) ?: 0
            val endSec = vm.parseClock(end)
            val startOk = start.isBlank() || vm.parseClock(start) != null
            val endOk = end.isBlank() || endSec != null
            // المدّةُ قد تكونُ مجهولةً في ملفٍّ لا يقرأُ النظامُ بياناتِه، فلا يُمنَعُ
            // حينَها حدٌّ بحجّةِ تجاوزِه مدّةً لا تُعرَف
            val overrun = duration > 0 && endSec != null && endSec > duration
            val ready = endSec != null && endSec > startSec && !overrun

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { vm.trimStart.value = it },
                    textStyle = centeredFieldStyle,
                    label = { Text(stringResource(R.string.clip_from)) },
                    placeholder = { Text("0:00") },
                    singleLine = true,
                    enabled = !running,
                    isError = !startOk,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { vm.trimEnd.value = it },
                    textStyle = centeredFieldStyle,
                    label = { Text(stringResource(R.string.clip_to)) },
                    placeholder = { Text(MediaLibrary.formatDuration(src.durationMs) ?: "1:30") },
                    singleLine = true,
                    enabled = !running,
                    isError = !endOk || overrun,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(
                    when {
                        overrun -> R.string.trim_beyond_end
                        !ready && (start.isNotBlank() || end.isNotBlank()) -> R.string.clip_invalid
                        else -> R.string.clip_hint
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (ready || (start.isBlank() && end.isBlank()))
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )

            if (running) {
                val percent = when (progress) {
                    is TrimProgress.Copying -> progress.percent
                    is TrimProgress.Cutting -> progress.percent
                    else -> 100f
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (percent > 0f) {
                        LinearProgressIndicator({ percent / 100f }, Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(
                        when (progress) {
                            is TrimProgress.Copying ->
                                stringResource(R.string.trim_copying, percent.toInt())
                            is TrimProgress.Cutting ->
                                stringResource(R.string.trim_cutting, percent.toInt())
                            else -> stringResource(R.string.trim_saving)
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                OutlinedButton(
                    onClick = { TrimService.stop(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.cancel)) }
            } else {
                Button(
                    onClick = {
                        TrimService.reset()
                        TrimService.start(
                            context, src,
                            com.gnutux.gmd.download.Section(startSec, endSec ?: 0),
                        )
                    },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.ContentCut, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.trim_action))
                }
            }
        }

        when (progress) {
            is TrimProgress.Done -> TrimDoneCard(progress, src?.isAudio ?: false, onOpenGallery)
            is TrimProgress.Failed -> TrimFailedCard(progress.message)
            else -> Unit
        }
    }

    if (showLibrary) {
        LibraryPicker(
            onDismiss = { showLibrary = false },
            onPick = { entry ->
                showLibrary = false
                TrimService.reset()
                vm.setTrimSource(
                    Trimmer.Source(
                        uri = entry.uri,
                        displayName = entry.name,
                        isAudio = entry.isAudio,
                        durationMs = entry.durationMs,
                        sizeBytes = entry.sizeBytes,
                    )
                )
            },
        )
    }
}

@Composable
private fun SourceCard(source: Trimmer.Source, enabled: Boolean, onChange: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                if (source.isAudio) Icons.Filled.MusicNote else Icons.Filled.Movie, null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.displayName, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val bits = listOfNotNull(
                    MediaLibrary.formatDuration(source.durationMs),
                    source.sizeBytes.takeIf { it > 0 }?.let { MediaLibrary.formatSize(it) },
                )
                if (bits.isNotEmpty()) {
                    Text(bits.joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onChange, enabled = enabled) {
                Text(stringResource(R.string.trim_change_source))
            }
        }
    }
}

/** قائمةُ ما نزّله GMD، لاختيارِ ما يُقتَصُّ منه بلا نافذةِ نظام. */
@Composable
private fun LibraryPicker(onDismiss: () -> Unit, onPick: (MediaEntry) -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<MediaEntry>?>(null) }
    LaunchedEffect(Unit) { items = MediaLibrary.list(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trim_from_gmd)) },
        text = {
            val list = items
            when {
                list == null -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }

                list.isEmpty() -> Text(stringResource(R.string.gallery_empty),
                    style = MaterialTheme.typography.bodySmall)

                else -> Column(
                    Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    list.forEach { entry ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(entry) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                if (entry.isAudio) Icons.Filled.MusicNote else Icons.Filled.Movie,
                                null, tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val bits = listOfNotNull(
                                    MediaLibrary.formatDuration(entry.durationMs),
                                    MediaLibrary.formatSize(entry.sizeBytes),
                                )
                                Text(bits.joinToString("  ·  "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun TrimDoneCard(done: TrimProgress.Done, isAudio: Boolean, onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val noAppLabel = stringResource(R.string.gallery_no_player)
    val uri = remember(done.uri) { Uri.parse(done.uri) }
    val mime = if (isAudio) "audio/*" else "video/*"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CheckCircle, null)
                Text(stringResource(R.string.notif_trim_done),
                    style = MaterialTheme.typography.titleSmall)
            }
            Text(done.displayName, style = MaterialTheme.typography.bodySmall)
            Text("${stringResource(R.string.save_to)}: ${done.relativePath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    val ok = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        )
                        true
                    }.getOrDefault(false)
                    if (!ok) Toast.makeText(context, noAppLabel, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.play_file)) }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType(mime)
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            null,
                        ))
                    }
                }) { Text(stringResource(R.string.gallery_share)) }
                TextButton(onClick = onOpenGallery) {
                    Text(stringResource(R.string.menu_gallery))
                }
            }
        }
    }
}

/**
 * بطاقةُ الفشل: نصُّ ffmpeg كاملاً مع زرِّ نسخ.
 *
 * بلا جهازٍ متّصلٍ بـlogcat هذه هي الطريقُ الوحيدةُ ليصلَ الخطأُ من جهازِ المستخدمِ
 * إلى المطوِّر، وهو العرفُ نفسُه في بطاقةِ فشلِ التنزيل.
 */
@Composable
private fun TrimFailedCard(message: String) {
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.error_copied)

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.WarningAmber, null)
                Text(stringResource(R.string.notif_trim_failed),
                    style = MaterialTheme.typography.titleSmall)
            }
            Text(message, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("GMD", message))
                Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy_error)) }
        }
    }
}
