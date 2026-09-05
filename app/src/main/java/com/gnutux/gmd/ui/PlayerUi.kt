package com.gnutux.gmd.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.gnutux.gmd.R
import com.gnutux.gmd.media.MediaLibrary
import com.gnutux.gmd.player.PlayerService
import com.gnutux.gmd.player.PlayerState
import com.gnutux.gmd.player.Track

/** ‏mm:ss أو h:mm:ss حسبَ الطول. */
fun clockOf(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * رسمُ «التالي» و«السابق» في اتّجاهِ الواجهةِ لا في اتّجاهِ اللاتينيّة.
 *
 * الزرّانِ في موضعِهما الصحيحِ من الصفِّ لأنّ الصفَّ نفسَه ينعكسُ في العربيّة،
 * لكنّ الرسمَين لا ينعكسانِ معه — فكانَ سهمُ «التالي» يشيرُ إلى حيثُ يقعُ
 * «السابق». وهما مرآةُ أحدِهما الآخَر، فالتبادلُ بينهما هو العكسُ بعينِه ويُغني
 * عن رسمٍ ثالث.
 */
@Composable
private fun skipIcons(): Pair<ImageVector, ImageVector> =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl)
        Icons.Filled.SkipNext to Icons.Filled.SkipPrevious
    else
        Icons.Filled.SkipPrevious to Icons.Filled.SkipNext

/**
 * صورةُ المقطعِ الجاري، وإن لم يكن له غلافٌ فغلافُ صفِّه.
 *
 * الكتابُ الصوتيُّ يحملُ غلافَه في أوّلِ مقاطعِه غالباً وتخلو بقيّتُها منه، فطلبُ
 * الصورةِ من المقطعِ وحدَه كانَ يُخلي الإطارَ في كلِّ ما بعدَ الأوّل. والبحثُ
 * محدودٌ بأوائلِ الصفِّ: استخراجُ الغلافِ يفتحُ الملفَّ، فلا يُفتَحُ سبعون ملفّاً
 * لأجلِ صورة.
 */
@Composable
private fun rememberArtwork(track: Track, queue: List<Track>): Bitmap? {
    val context = LocalContext.current
    val state by produceState<Bitmap?>(null, track.uri, queue.size) {
        value = MediaLibrary.thumbnailOf(context, Uri.parse(track.uri))
            ?: queue.take(4).firstNotNullOfOrNull { other ->
                if (other.uri == track.uri) null
                else MediaLibrary.thumbnailOf(context, Uri.parse(other.uri))
            }
    }
    return state
}

/**
 * شريطُ المشغّلِ المصغَّر.
 *
 * يبقى ظاهراً في كلِّ شاشةٍ ما دامَ في المشغّلِ مقطع — فالاستماعُ لا يُلغي التصفّحَ،
 * ومن يسمعُ كتاباً صوتيّاً قد يُنزّلُ غيرَه في أثنائِه. والنقرةُ عليه تفتحُ المشغّلَ
 * كاملاً، وهو عُرفٌ يعرفُه كلُّ مستمعٍ من تطبيقاتِ الصوت.
 *
 * والاسمُ في شريطٍ رفيعٍ فوقَ الأدوات: كانَ مزاحماً لها في صفٍّ واحدٍ فلا يبقى
 * له إلّا كلمتانِ، وقد ضاقَ الصفُّ عن زرِّ «السابق» فغابَ أصلاً — والمستمعُ إلى
 * قائمةٍ يرجعُ فيها كما يتقدّم.
 */
@Composable
fun PlayerBar(state: PlayerState, onOpen: () -> Unit) {
    val context = LocalContext.current
    val track = state.current ?: return
    val (prevIcon, nextIcon) = skipIcons()
    val art = rememberArtwork(track, state.queue)

    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        // `Scaffold` لا يُزيحُ شريطَه السفليَّ عن شريطِ تنقُّلِ النظامِ من تلقائِه —
        // يفعلُ ذلك في العلويِّ وحدَه — فكانَ المشغّلُ محجوباً تحتَ أزرارِ النظام.
        // والإزاحةُ على المحتوى لا على السطحِ نفسِه، فيبقى اللونُ ممتدّاً خلفَ
        // الشريطِ بدلَ أن ينقطعَ دونَه.
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // خيطُ التقدُّمِ على حافّةِ الشريطِ لا شريطٌ منفصل: أضيقُ ما يكفي ليُرى
            val fraction = if (state.durationMs > 0)
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )

            // ── الشريطُ الرفيع: الاسمُ وموضعُه من الصفّ ───────────────────────
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    track.title.ifBlank { stringResource(R.string.player_title) },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (state.queue.size > 1) {
                    Text(
                        stringResource(R.string.phase_item, state.index + 1, state.queue.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            // ── أدواتُ المشغّل ──────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                                )
                            )
                        )
                        .clickable(onClick = onOpen),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) {
                        Image(
                            bitmap = art.asImageBitmap(), contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        )
                    } else {
                        Icon(Icons.Filled.MusicNote, null,
                            tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    buildString {
                        append(clockOf(state.positionMs))
                        if (state.durationMs > 0) {
                            append(" / "); append(clockOf(state.durationMs))
                        }
                    },
                    Modifier.weight(1f).clickable(onClick = onOpen),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                IconButton(
                    onClick = { PlayerService.previous(context) },
                    enabled = state.hasPrevious || state.positionMs > 5_000,
                ) {
                    Icon(prevIcon, stringResource(R.string.player_previous))
                }
                FilledIconButton(onClick = { PlayerService.toggle(context) }) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(
                            if (state.playing) R.string.player_pause else R.string.player_play
                        ),
                    )
                }
                IconButton(
                    onClick = { PlayerService.next(context) },
                    enabled = state.hasNext,
                ) {
                    Icon(nextIcon, stringResource(R.string.player_next))
                }
                IconButton(onClick = { PlayerService.stop(context) }) {
                    Icon(Icons.Filled.Close, stringResource(R.string.player_stop))
                }
            }
        }
    }
}

/** المشغّلُ كاملاً: المقطعُ الجاري ومقبضُ الزمنِ وأزرارُه، ثمّ صفُّ التشغيل. */
@Composable
fun PlayerScreen(state: PlayerState, onOpenGallery: () -> Unit = {}) {
    val context = LocalContext.current
    val track = state.current

    if (track == null) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.player_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // القسمُ يُفتَحُ الآنَ من القائمةِ رأساً، فقد يصلُه من لم يُشغّل شيئاً
                // بعد: يُدَلُّ على المعرضِ بدلَ أن يُترَكَ أمامَ شاشةٍ خاوية
                Button(onClick = onOpenGallery) {
                    Text(stringResource(R.string.menu_gallery))
                }
            }
        }
        return
    }

    val (prevIcon, nextIcon) = skipIcons()
    val art = rememberArtwork(track, state.queue)

    // المقبضُ يتبعُ التشغيلَ إلّا حينَ يسحبُه المستخدمُ بيدِه، وإلّا نازعَه التحديثُ
    // كلَّ نصفِ ثانيةٍ فأفلتَ من إصبعِه
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val position = if (dragging) dragValue.toLong() else state.positionMs

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.Album, null,
                    modifier = Modifier.size(76.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Text(
            track.title.ifBlank { stringResource(R.string.player_title) },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        if (state.queue.size > 1) {
            Text(
                stringResource(R.string.phase_item, state.index + 1, state.queue.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column {
            Slider(
                value = position.toFloat(),
                valueRange = 0f..(state.durationMs.takeIf { it > 0 } ?: 1L).toFloat(),
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = {
                    dragging = false
                    PlayerService.seek(context, dragValue.toLong())
                },
                enabled = state.durationMs > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clockOf(position), style = MaterialTheme.typography.labelSmall)
                Text(clockOf(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { PlayerService.previous(context) },
                enabled = state.hasPrevious || state.positionMs > 5_000,
            ) {
                Icon(prevIcon, stringResource(R.string.player_previous), Modifier.size(34.dp))
            }
            Spacer(Modifier.width(18.dp))
            FilledIconButton(
                onClick = { PlayerService.toggle(context) },
                modifier = Modifier.size(66.dp),
                shape = CircleShape,
            ) {
                Icon(
                    if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    stringResource(if (state.playing) R.string.player_pause else R.string.player_play),
                    Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.width(18.dp))
            IconButton(onClick = { PlayerService.next(context) }, enabled = state.hasNext) {
                Icon(nextIcon, stringResource(R.string.player_next), Modifier.size(34.dp))
            }
        }

        if (state.resumed) {
            Text(
                stringResource(R.string.player_resume_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.queue.size > 1) {
            HorizontalDivider()
            Text(stringResource(R.string.player_queue), style = MaterialTheme.typography.labelLarge)
            // الشاشةُ داخلَ تمريرٍ واحدٍ في قشرةِ التطبيق، فالصفُّ عمودٌ محدودُ
            // الارتفاعِ لا شبكةٌ كسولةٌ ينهارُ قياسُها
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                state.queue.forEachIndexed { i, t ->
                    val isCurrent = i == state.index
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { PlayerService.jump(context, i) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (isCurrent && state.playing) {
                            Icon(Icons.Filled.GraphicEq, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                        } else {
                            Text("${i + 1}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            t.title, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (t.durationMs > 0) {
                            Text(clockOf(t.durationMs), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
