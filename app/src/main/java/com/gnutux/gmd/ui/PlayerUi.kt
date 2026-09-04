package com.gnutux.gmd.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gnutux.gmd.R
import com.gnutux.gmd.player.PlayerService
import com.gnutux.gmd.player.PlayerState

/** ‏mm:ss أو h:mm:ss حسبَ الطول. */
fun clockOf(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * شريطُ المشغّلِ المصغَّر.
 *
 * يبقى ظاهراً في كلِّ شاشةٍ ما دامَ في المشغّلِ مقطع — فالاستماعُ لا يُلغي التصفّحَ،
 * ومن يسمعُ كتاباً صوتيّاً قد يُنزّلُ غيرَه في أثنائِه. والنقرةُ عليه تفتحُ المشغّلَ
 * كاملاً، وهو عُرفٌ يعرفُه كلُّ مستمعٍ من تطبيقاتِ الصوت.
 */
@Composable
fun PlayerBar(state: PlayerState, onOpen: () -> Unit) {
    val context = LocalContext.current
    val track = state.current ?: return

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
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title.ifBlank { stringResource(R.string.player_title) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(clockOf(state.positionMs))
                            if (state.durationMs > 0) {
                                append(" / "); append(clockOf(state.durationMs))
                            }
                            if (state.queue.size > 1) {
                                append("  ·  ")
                                append(stringResource(R.string.phase_item,
                                    state.index + 1, state.queue.size))
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                FilledIconButton(onClick = { PlayerService.toggle(context) }) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(
                            if (state.playing) R.string.player_pause else R.string.player_play
                        ),
                    )
                }
                if (state.hasNext) {
                    IconButton(onClick = { PlayerService.next(context) }) {
                        Icon(Icons.Filled.SkipNext, stringResource(R.string.player_next))
                    }
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
fun PlayerScreen(state: PlayerState) {
    val context = LocalContext.current
    val track = state.current

    if (track == null) {
        Card(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.player_empty),
                Modifier.padding(18.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

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
            Icon(
                Icons.Filled.Album, null,
                modifier = Modifier.size(76.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
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
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous),
                    Modifier.size(34.dp))
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
                Icon(Icons.Filled.SkipNext, stringResource(R.string.player_next),
                    Modifier.size(34.dp))
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
