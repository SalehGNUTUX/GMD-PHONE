package com.gnutux.gmd.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gnutux.gmd.R
import com.gnutux.gmd.media.DeleteOutcome
import com.gnutux.gmd.media.MediaEntry
import com.gnutux.gmd.media.MediaLibrary
import kotlinx.coroutines.launch

/**
 * معرضُ ما نزّله GMD.
 *
 * الشاشةُ تُغلَّفُ بعمودٍ مُمرَّرٍ في [GmdApp]، فلا تُستعمَلُ هنا شبكةٌ كسولةٌ
 * (‏`LazyVerticalGrid`) لأنّ ارتفاعَها غيرُ محدودٍ داخلَ تمريرٍ آخرَ فينهارُ القياس.
 * والعددُ هنا عشراتٌ لا آلاف، فصفوفٌ عاديّةٌ تكفي وتُغني عن التعقيد.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val deletedLabel = stringResource(R.string.gallery_deleted)
    val noAppLabel = stringResource(R.string.gallery_no_player)

    suspend fun reload() { items = MediaLibrary.list(context) }

    // موافقةُ النظامِ على الحذف: تعودُ نتيجتُها هنا فنُعيدُ القراءةَ ونخرجُ من التحديد
    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        scope.launch {
            if (result.resultCode == Activity.RESULT_OK) {
                selected = emptySet()
                Toast.makeText(context, deletedLabel, Toast.LENGTH_SHORT).show()
            }
            reload()
        }
    }

    LaunchedEffect(Unit) { reload() }

    val list = items
    val chosen = list.orEmpty().filter { it.uri.toString() in selected }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── شريطُ الإجراءات ──────────────────────────────────────────────────
        if (list != null && list.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    if (selected.isEmpty()) stringResource(R.string.gallery_count, list.size)
                    else stringResource(R.string.gallery_selected, selected.size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selected = if (selected.size == list.size) emptySet()
                    else list.map { it.uri.toString() }.toSet()
                }) {
                    Text(stringResource(
                        if (selected.size == list.size) R.string.gallery_select_none
                        else R.string.gallery_select_all
                    ))
                }
                IconButton(
                    enabled = chosen.isNotEmpty(),
                    onClick = { runCatching { context.startActivity(MediaLibrary.shareIntent(chosen)) } },
                ) { Icon(Icons.Filled.Share, stringResource(R.string.gallery_share)) }
                IconButton(
                    enabled = chosen.isNotEmpty(),
                    onClick = { confirmDelete = true },
                ) { Icon(Icons.Filled.Delete, stringResource(R.string.gallery_delete)) }
            }
        }

        when {
            list == null -> Row(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }

            list.isEmpty() -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.gallery_empty),
                        style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.gallery_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            else -> list.forEach { entry ->
                val key = entry.uri.toString()
                val isSelected = key in selected
                EntryRow(
                    entry = entry,
                    selected = isSelected,
                    selecting = selected.isNotEmpty(),
                    onClick = {
                        if (selected.isNotEmpty()) {
                            selected = if (isSelected) selected - key else selected + key
                        } else {
                            val ok = runCatching {
                                context.startActivity(MediaLibrary.viewIntent(entry)); true
                            }.getOrDefault(false)
                            if (!ok) Toast.makeText(context, noAppLabel, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onLongClick = { selected = if (isSelected) selected - key else selected + key },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.gallery_delete)) },
            text = { Text(stringResource(R.string.gallery_delete_confirm, chosen.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        when (val r = MediaLibrary.delete(context, chosen)) {
                            is DeleteOutcome.Done -> {
                                selected = emptySet()
                                Toast.makeText(context, deletedLabel, Toast.LENGTH_SHORT).show()
                                reload()
                            }
                            is DeleteOutcome.NeedsConsent ->
                                consent.launch(IntentSenderRequest.Builder(r.sender).build())
                            is DeleteOutcome.Failed ->
                                Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.gallery_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: MediaEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, entry.uri) {
        value = MediaLibrary.thumbnail(context, entry)
    }

    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected)
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(width = 92.dp, height = 62.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = thumb
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(
                        if (entry.isAudio) Icons.Filled.MusicNote else Icons.Filled.Movie,
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MediaLibrary.formatDuration(entry.durationMs)?.let { d ->
                    Text(
                        d,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 4.dp),
                    )
                }
            }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(MediaLibrary.formatSize(entry.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (selecting) {
                Checkbox(checked = selected, onCheckedChange = { onLongClick() })
            } else {
                IconButton(onClick = {
                    runCatching { context.startActivity(MediaLibrary.shareIntent(listOf(entry))) }
                }) { Icon(Icons.Filled.Share, stringResource(R.string.gallery_share)) }
            }
        }
    }
}
