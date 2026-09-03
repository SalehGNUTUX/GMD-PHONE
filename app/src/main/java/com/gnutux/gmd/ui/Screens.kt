package com.gnutux.gmd.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.gnutux.gmd.R
import com.gnutux.gmd.download.AudioFormat
import com.gnutux.gmd.download.DownloadService
import com.gnutux.gmd.data.LocalePrefs
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(
    vm: GmdViewModel,
    progress: Progress,
    isAudio: Boolean,
    enabled: Boolean,
    onOpenGallery: () -> Unit,
) {
    val context = LocalContext.current
    val url by vm.url.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val format by vm.audioFormat.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()
    val clipOn by vm.clipEnabled.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val plSelection by vm.playlistSelection.collectAsStateWithLifecycle()
    val running = progress is Progress.Running

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UrlField(vm)
        MediaPreviewCard(vm)
        PlaylistCard(vm)

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

        if (playlist == null) ClipSection(vm)

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
                    val i = info
                    val sec = vm.section()
                    val pl = playlist
                    DownloadService.start(
                        context, url.trim(), isAudio,
                        if (isAudio) format.name else quality.name,
                        title = i?.title, uploader = i?.uploader,
                        duration = i?.duration, thumbnail = i?.thumbnail,
                        sectionStart = sec?.startSec ?: -1,
                        sectionEnd = sec?.endSec ?: -1,
                        playlistFolder = pl?.folderName(),
                        playlistItems = if (pl != null) plSelection.sorted().toIntArray() else null,
                        playlistTitle = pl?.title,
                    )
                },
                enabled = enabled && url.isNotBlank() &&
                    (!clipOn || vm.section() != null) &&
                    (playlist == null || plSelection.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (playlist != null)
                        stringResource(R.string.playlist_download_n, plSelection.size)
                    else stringResource(R.string.download)
                )
            }
        }

        when (progress) {
            is Progress.Done -> DoneCard(progress, isAudio, onOpenGallery)
            is Progress.Failed -> FailureCard(vm, progress.message)
            else -> Unit
        }
    }
}

/**
 * بطاقةُ الفشل.
 *
 * خرجُ yt-dlp يفتحُ غالباً بتحذيرِ قِدَمِ النسخةِ — خمسةُ أسطرٍ — فيغرقُ سطرُ
 * `ERROR:` تحتَه، فنرفعُ أسطرَ الخطأِ إلى الأعلى ونُبقي الخرجَ كاملاً للنسخ.
 * ثمّ نترجمُ الأعطابَ الشائعةَ إلى نصيحةٍ قابلةٍ للتنفيذ: رسالةٌ إنجليزيّةٌ لا
 * إجراءَ لها تتركُ المستخدمَ واقفاً، وأكثرُ ما يُصلِحُها تحديثُ yt-dlp نفسِه
 * لأنّ المواقعَ تتغيّرُ أسرعَ من الحزمةِ المشحونةِ في التطبيق.
 */
@Composable
private fun FailureCard(vm: GmdViewModel, message: String) {
    val context = LocalContext.current

    val errorLines = remember(message) {
        message.lineSequence()
            .filter { it.trimStart().startsWith("ERROR:") }
            .joinToString("\n")
            .ifBlank { message.trim() }
    }

    val hint = remember(message) {
        when {
            message.contains("Requested format is not available", true) -> R.string.err_no_format
            message.contains("Unsupported URL", true) ||
                message.contains("Unable to extract", true) ||
                message.contains("Cannot parse data", true) ||
                message.contains("login required", true) ||
                message.contains("private video", true) -> R.string.err_extractor
            message.contains("Unable to download webpage", true) ||
                message.contains("HTTP Error", true) ||
                message.contains("timed out", true) ||
                message.contains("Temporary failure in name resolution", true) -> R.string.err_network
            else -> null
        }
    }
    val offerToolUpdate = hint == R.string.err_no_format ||
        hint == R.string.err_extractor ||
        message.contains("older than", true)

    var updating by remember(message) { mutableStateOf(false) }
    var updateResult by remember(message) { mutableStateOf<String?>(null) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.ErrorOutline, null)
                Text(stringResource(R.string.notif_failed), style = MaterialTheme.typography.titleSmall)
            }
            Text(errorLines, style = MaterialTheme.typography.bodySmall)
            hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            updateResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (offerToolUpdate) {
                    TextButton(
                        enabled = !updating,
                        onClick = {
                            updating = true
                            updateResult = null
                            vm.updateYtDlp { ok ->
                                updating = false
                                updateResult = context.getString(
                                    if (ok) R.string.ytdlp_updated else R.string.ytdlp_update_failed
                                )
                            }
                        },
                    ) {
                        Text(stringResource(
                            if (updating) R.string.ytdlp_updating else R.string.ytdlp_update
                        ))
                    }
                }
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("GMD", message))
                    Toast.makeText(context, context.getString(R.string.error_copied),
                        Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.copy_error))
                }
            }
        }
    }
}

/**
 * بطاقةُ المقطعِ قبلَ تنزيله: صورتُه المصغَّرةُ وعنوانُه ومدّتُه.
 *
 * تظهرُ من تلقائِها بعدَ لصقِ الرابط — لا زرَّ لجلبِها — لأنّ المستخدمَ يريدُ أن
 * يتيقّنَ أنّ الرابطَ هو المقصودُ قبلَ أن يبدأَ تنزيلاً قد يبلغُ مئاتِ الميغابايت.
 * وإن فشلَ الجلبُ لم نُظهِر شيئاً: التنزيلُ نفسُه سيُبلّغُ عن العطبِ إن وقع، ولا
 * معنى لإنذارِ المستخدمِ مرّتين.
 */
@Composable
private fun MediaPreviewCard(vm: GmdViewModel) {
    val info by vm.info.collectAsStateWithLifecycle()
    val loading by vm.infoLoading.collectAsStateWithLifecycle()

    if (loading) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.fetching_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val i = info ?: return
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            i.thumbnail?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 104.dp, height = 68.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(i.title, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(i.uploader, i.duration).filter { it != "\u2014" }.joinToString("  \u00b7  "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * بطاقةُ الاكتمال.
 *
 * كان زرُّ «فتح المجلّد» يُطلِقُ `ACTION_VIEW` بنوعٍ بلا عنوانٍ إطلاقاً، وهي نيّةٌ
 * لا يقبلُها أيُّ تطبيق، فلم يكن يحدثُ شيءٌ عندَ نقره — ولم تكن العلّةُ صلاحيّات.
 * صارَ للمستخدمِ ثلاثةُ أفعالٍ صحيحة: يفتحُ المقطعَ نفسَه بعنوانِه في المعرض، أو
 * يشاركُه، أو ينتقلُ إلى معرضِ GMD داخلَ التطبيق.
 */
@Composable
private fun DoneCard(done: Progress.Done, isAudio: Boolean, onOpenGallery: () -> Unit) {
    val context = LocalContext.current
    val noAppLabel = stringResource(R.string.gallery_no_player)
    val uri = remember(done.uri) { Uri.parse(done.uri) }
    val mime = if (isAudio) "audio/*" else "video/*"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.CheckCircle, null)
                Text(stringResource(R.string.notif_done),
                    style = MaterialTheme.typography.titleSmall)
            }
            Text(
                if (done.count > 1)
                    "${done.displayName}  —  ${stringResource(R.string.done_files, done.count)}"
                else done.displayName,
                style = MaterialTheme.typography.bodySmall,
            )
            Text("${stringResource(R.string.save_to)}: ${done.relativePath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (done.count == 1) TextButton(onClick = {
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
                if (done.count == 1) TextButton(onClick = {
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
 * اقتصاصُ جزءٍ من المادّة.
 *
 * على الهاتفِ هذا توفيرٌ لا زينة: المسلكُ الأوّلُ يطلبُ من الخادمِ الجزءَ وحدَه فلا
 * يُنزَّلُ ما لا يُراد. وإن رفضَ الموقعُ ذلك — يوتيوب يردُّ 403 على جلبِ ffmpeg
 * نطاقاً من روابطه — نُزِّلت المادّةُ كاملةً واقتُصّت في الجهاز، بلا تدخّلٍ من
 * المستخدمِ ولا سؤال.
 */
@Composable
private fun ClipSection(vm: GmdViewModel) {
    val enabled by vm.clipEnabled.collectAsStateWithLifecycle()
    val start by vm.clipStart.collectAsStateWithLifecycle()
    val end by vm.clipEnd.collectAsStateWithLifecycle()

    val startOk = start.isBlank() || vm.parseClock(start) != null
    val endOk = end.isBlank() || vm.parseClock(end) != null
    val rangeOk = !enabled || vm.section() != null

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.clip_title), Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge)
            Switch(checked = enabled, onCheckedChange = { vm.clipEnabled.value = it })
        }

        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { vm.clipStart.value = it },
                    label = { Text(stringResource(R.string.clip_from)) },
                    placeholder = { Text("0:00") },
                    singleLine = true,
                    isError = !startOk,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = end,
                    onValueChange = { vm.clipEnd.value = it },
                    label = { Text(stringResource(R.string.clip_to)) },
                    placeholder = { Text("1:30") },
                    singleLine = true,
                    isError = !endOk,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                stringResource(if (rangeOk) R.string.clip_hint else R.string.clip_invalid),
                style = MaterialTheme.typography.labelSmall,
                color = if (rangeOk) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * يعرضُ ملاحظاتِ الإصدارِ نصّاً مقروءاً لا ترميزاً خاماً.
 *
 * الملاحظاتُ تأتي من CHANGELOG بصيغةِ Markdown، وكانت تُعرَضُ كما هي فتظهرُ
 * النجومُ والشَّرَطاتُ حروفاً بينَ يدَي القارئ. ولا مُصيِّرَ Markdown في Compose،
 * وإدخالُ مكتبةٍ لأجلِ بطاقةِ تحديثٍ ثمنٌ بلا مقابل — فيكفي تحويلُ ما يَرِدُ فعلاً
 * في سجلِّ هذا المشروع: العريضُ والشيفرةُ والعناوينُ والقوائم.
 */
private fun renderNotes(md: String): AnnotatedString = buildAnnotatedString {
    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val code = SpanStyle(fontFamily = FontFamily.Monospace)

    md.lineSequence().forEach { raw ->
        val line = raw.trimEnd()
        when {
            // الفواصل الأفقيّة لا معنى لها في بطاقةٍ صغيرة
            line.trim().matches(Regex("^-{3,}$")) -> return@forEach
            line.isBlank() -> { append("\n"); return@forEach }
            else -> Unit
        }

        var text = line
        // العنوان يُجرَّد من علاماته ويُكتَبُ عريضاً
        val heading = Regex("^#{1,6}\\s+").find(text)
        if (heading != null) text = text.removeRange(heading.range)
        // عنصر القائمة يأخذ نقطةً بدل الشَّرطة
        val bullet = Regex("^[-*]\\s+").find(text)
        if (bullet != null) {
            text = text.removeRange(bullet.range)
            append("• ")
        }

        // ثمّ يُقسَم السطر على العريض والشيفرة بحسب ترتيب ورودها
        val marks = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var last = 0
        marks.findAll(text).forEach { m ->
            append(text.substring(last, m.range.first))
            val b = m.groupValues[1]
            if (b.isNotEmpty()) withStyle(bold) { append(b) }
            else withStyle(code) { append(m.groupValues[2]) }
            last = m.range.last + 1
        }
        append(text.substring(last))

        if (heading != null) {
            // العنوان بلا علاماته يبقى بلا تمييز، فيُعرَّض كلُّه
            addStyle(bold, length - text.length, length)
        }
        append("\n")
    }
}

/**
 * قائمةُ التشغيلِ حين يقعُ خلفَ الرابطِ أكثرُ من مقطع.
 *
 * الأسلوبُ من نسخةِ الحاسوب — كشفٌ تلقائيّ، وقائمةُ عناصرَ بمربّعاتِ اختيار،
 * وتحديدُ الكلِّ، وعدَدٌ ظاهرٌ على زرِّ التنزيل — والقالبُ من الهاتف: بطاقةُ
 * Material 3 داخلَ الشاشةِ لا نافذةٌ منبثقةٌ تحجبُ ما وراءَها، فالشاشةُ ضيّقةٌ
 * والنافذةُ فيها تُخفي الجودةَ والصيغةَ اللتَين قد يريدُ تغييرَهما قبلَ التنزيل.
 */
@Composable
private fun PlaylistCard(vm: GmdViewModel) {
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val selection by vm.playlistSelection.collectAsStateWithLifecycle()
    val pl = playlist ?: return

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(
                        pl.title.ifBlank { stringResource(R.string.playlist_detected) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(R.string.playlist_selected_of, selection.size, pl.count),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.togglePlaylistAll() }) {
                    Text(stringResource(
                        if (selection.size == pl.count) R.string.gallery_select_none
                        else R.string.gallery_select_all
                    ))
                }
            }

            Text(
                stringResource(R.string.playlist_folder_note, pl.folderName()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // العناصرُ في عمودٍ محدودِ الارتفاع: الشاشةُ كلُّها داخلَ تمريرٍ واحد،
            // فقائمةٌ من مئةِ عنصرٍ بلا حدٍّ تدفعُ زرَّ التنزيلِ خارجَ متناولِ اليد.
            Column(
                Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
            ) {
                pl.entries.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.togglePlaylistItem(e.index) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = e.index in selection,
                            onCheckedChange = { vm.togglePlaylistItem(e.index) },
                        )
                        Text(
                            "${e.index}. ${e.title}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (e.duration.isNotBlank()) {
                            Text(e.duration, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
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
    val toolPhase by vm.ytdlpPhase.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadYtDlpVersion() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LanguageSection()

        HorizontalDivider()

        Text(stringResource(R.string.update_title), style = MaterialTheme.typography.titleMedium)

        UpdateSection(vm, update, context)

        HorizontalDivider()

        SwitchRow(stringResource(R.string.update_auto), stringResource(R.string.update_auto_desc),
            autoCheck, vm::setAutoCheck)
        SwitchRow(stringResource(R.string.update_allow_pre), stringResource(R.string.update_allow_pre_desc),
            allowPre, vm::setAllowPrerelease)

        HorizontalDivider()

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("yt-dlp", style = MaterialTheme.typography.bodyLarge)
                    Text(ytdlp ?: "—", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (toolPhase is ToolPhase.Working) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                }
                TextButton(
                    enabled = toolPhase !is ToolPhase.Working,
                    onClick = { vm.updateYtDlp() },
                ) {
                    // الزرُّ يُحدِّث الأداة، ونصُّه كان «تحقّق من وجود تحديث» —
                    // مفتاحُ زرِّ تحديثِ البرنامج نفسِه، فيلتبس الفعلان.
                    Text(stringResource(R.string.ytdlp_update))
                }
            }
            when (val t = toolPhase) {
                is ToolPhase.Working -> Text(stringResource(R.string.ytdlp_updating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                is ToolPhase.UpToDate -> Text(stringResource(R.string.ytdlp_up_to_date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                is ToolPhase.Updated -> Text(stringResource(R.string.ytdlp_updated_to, t.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
                is ToolPhase.Failed -> Text("${stringResource(R.string.ytdlp_update_failed)} — ${t.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                ToolPhase.Idle -> Unit
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

/**
 * اختيارُ لغةِ الواجهة — كما في نسخةِ الحاسوب.
 *
 * الاختيارُ يُحفَظُ في SharedPreferences ثمّ تُعادُ الشاشةُ بناءً: لا سبيلَ إلى
 * تبديلِ لغةِ مواردَ محمَّلةٍ سلفاً في أندرويد، فإعادةُ البناءِ هي الطريقُ لتُقرَأَ
 * الموارِدُ من جديدٍ في `attachBaseContext`. و«تلقائيّ» يُعيدُ الأمرَ إلى النظام.
 */
@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(LocalePrefs.get(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.language_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                LocalePrefs.SYSTEM to R.string.language_system,
                "ar" to R.string.language_ar,
                "en" to R.string.language_en,
            ).forEach { (tag, label) ->
                FilterChip(
                    selected = current == tag,
                    onClick = {
                        if (current == tag) return@FilterChip
                        current = tag
                        LocalePrefs.set(context, tag)
                        (context as? Activity)?.recreate()
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }
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
                        Text(renderNotes(u.info.notes.take(600)),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 10, overflow = TextOverflow.Ellipsis)
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
