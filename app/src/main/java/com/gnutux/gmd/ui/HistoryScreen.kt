package com.gnutux.gmd.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.gnutux.gmd.R
import com.gnutux.gmd.history.HistoryEntry
import com.gnutux.gmd.history.HistoryStore
import com.gnutux.gmd.history.Outcome
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** مُرشِّحُ العرض. */
private enum class Filter { ALL, SUCCESS, FAILED }

/**
 * سجلُّ المحاولات.
 *
 * المعرضُ يعرضُ الملفّاتِ وهذا يعرضُ المحاولات، والفرقُ جوهريّ: المحاولةُ الفاشلةُ
 * لا ملفَّ لها فلا موضعَ لها هناك، والملفُّ الذي حذفَه المستخدمُ تبقى محاولتُه هنا.
 *
 * وأنفعُ ما فيه إعادةُ المحاولة: كانَ إغلاقُ بطاقةِ الخطأِ يُضيعُ الرابطَ وسببَ
 * الفشلِ معاً، فلا يبقى للمستخدمِ ما يُعيدُ به الكرّة.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onRetry: (HistoryEntry) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<HistoryEntry>?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var filter by remember { mutableStateOf(Filter.ALL) }
    var confirmDelete by remember { mutableStateOf<Boolean?>(null) }   // true = الكلّ

    val copiedLabel = stringResource(R.string.error_copied)
    val goneLabel = stringResource(R.string.history_file_gone)

    suspend fun reload() { items = HistoryStore.all(context) }
    LaunchedEffect(Unit) { reload() }

    val all = items
    val shown = all.orEmpty().filter {
        when (filter) {
            Filter.ALL -> true
            Filter.SUCCESS -> it.outcome == Outcome.SUCCESS
            Filter.FAILED -> it.outcome != Outcome.SUCCESS
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (!all.isNullOrEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Filter.ALL to R.string.history_filter_all,
                    Filter.SUCCESS to R.string.history_filter_success,
                    Filter.FAILED to R.string.history_filter_failed,
                ).forEach { (f, label) ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f; selected = emptySet() },
                        label = { Text(stringResource(label)) },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (selected.isEmpty()) stringResource(R.string.history_count, shown.size)
                    else stringResource(R.string.gallery_selected, selected.size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selected = if (selected.size == shown.size) emptySet()
                    else shown.map { it.id }.toSet()
                }) {
                    Text(stringResource(
                        if (selected.size == shown.size && shown.isNotEmpty())
                            R.string.gallery_select_none else R.string.gallery_select_all
                    ))
                }
                IconButton(
                    enabled = selected.isNotEmpty(),
                    onClick = { confirmDelete = false },
                ) { Icon(Icons.Filled.Delete, stringResource(R.string.gallery_delete)) }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.DeleteSweep, stringResource(R.string.history_clear))
                }
            }
        }

        when {
            all == null -> Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }

            all.isEmpty() -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.history_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            else -> shown.forEach { entry ->
                val isSelected = entry.id in selected
                EntryCard(
                    entry = entry,
                    selected = isSelected,
                    selecting = selected.isNotEmpty(),
                    onClick = {
                        when {
                            selected.isNotEmpty() ->
                                selected = if (isSelected) selected - entry.id else selected + entry.id
                            entry.outcome == Outcome.SUCCESS && entry.savedUri != null -> {
                                val ok = runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW)
                                            .setDataAndType(
                                                Uri.parse(entry.savedUri),
                                                if (entry.isAudio) "audio/*" else "video/*",
                                            )
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    )
                                    true
                                }.getOrDefault(false)
                                // الملفّ قد يكون حُذف من المعرض والمحاولة باقية هنا
                                if (!ok) Toast.makeText(context, goneLabel, Toast.LENGTH_SHORT).show()
                            }
                            else -> onRetry(entry)
                        }
                    },
                    onLongClick = {
                        selected = if (isSelected) selected - entry.id else selected + entry.id
                    },
                    onRetry = { onRetry(entry) },
                    onCopy = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("GMD", it))
                        Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    confirmDelete?.let { isAll ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = {
                Text(stringResource(if (isAll) R.string.history_clear else R.string.gallery_delete))
            },
            text = {
                Text(
                    if (isAll) stringResource(R.string.history_clear_confirm)
                    else stringResource(R.string.history_delete_confirm, selected.size)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        if (isAll) HistoryStore.clear(context)
                        else HistoryStore.remove(context, selected)
                        selected = emptySet()
                        reload()
                    }
                }) { Text(stringResource(R.string.gallery_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryCard(
    entry: HistoryEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val ok = entry.outcome == Outcome.SUCCESS
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                entry.thumbnail?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 78.dp, height = 52.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.title ?: entry.url,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append(stringResource(
                                if (entry.isAudio) R.string.history_kind_audio
                                else R.string.history_kind_video
                            ))
                            append("  ·  ")
                            append(DateFormat.getDateTimeInstance(
                                DateFormat.SHORT, DateFormat.SHORT
                            ).format(Date(entry.timestamp)))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(stringResource(
                            when (entry.outcome) {
                                Outcome.SUCCESS -> R.string.history_ok
                                Outcome.FAILED -> R.string.history_failed
                                Outcome.CANCELLED -> R.string.history_cancelled
                            }
                        ))
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor =
                            if (ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    ),
                )
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onLongClick() })
            }

            Text(
                entry.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            entry.error?.let { err ->
                Text(
                    err.lineSequence().filter { it.trimStart().startsWith("ERROR:") }
                        .joinToString("\n").ifBlank { err }.trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }

            if (!selecting) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(
                            if (ok) R.string.history_again else R.string.retry
                        ))
                    }
                    TextButton(onClick = { onCopy(entry.url) }) {
                        Text(stringResource(R.string.history_copy_url))
                    }
                    entry.error?.let { err ->
                        TextButton(onClick = { onCopy(err) }) {
                            Text(stringResource(R.string.copy_error))
                        }
                    }
                }
            }
        }
    }
}
