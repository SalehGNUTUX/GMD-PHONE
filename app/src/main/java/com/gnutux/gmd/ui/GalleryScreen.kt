package com.gnutux.gmd.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.gnutux.gmd.player.PlaylistStore
import com.gnutux.gmd.player.UserPlaylist
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
fun GalleryScreen(
    onTrim: (MediaEntry) -> Unit = {},
    /** [onPlay] صفُّ التشغيلِ وموضعُ المقطعِ منه؛ للصوتِ وحدَه. */
    onPlay: (List<MediaEntry>, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var hasRead by remember { mutableStateOf(MediaLibrary.hasReadPermission(context)) }
    /**
     * المجلَّد المفتوح: قائمةُ تشغيلٍ يُتصفَّح داخلُها، أو `null` للجذر.
     *
     * ويحملُ نوعَه معه: `Movies/GMD/رحلة` و`Music/GMD/رحلة` مجلَّدانِ مختلفانِ
     * يتشابهُ اسماهما، ففتحُ أحدِهما بالاسمِ وحدَه يخلطُ عناصرَهما.
     */
    var openFolder by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    /**
     * قوائمُ المستخدمِ ومفتوحُها.
     *
     * تُقرأُ مرّةً وتُحفَظُ في الحالة: القراءةُ من التفضيلاتِ عندَ كلِّ إعادةِ تركيبٍ
     * تقعُ في خيطِ الواجهةِ عشراتِ المرّات.
     */
    var playlists by remember { mutableStateOf<List<UserPlaylist>>(emptyList()) }
    var openUser by remember { mutableStateOf<String?>(null) }
    /** مقاطعُ اختيرَت لتُضَمَّ إلى قائمة، والحوارُ مفتوحٌ عليها. */
    var addTo by remember { mutableStateOf<List<MediaEntry>?>(null) }
    var renaming by remember { mutableStateOf<UserPlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<UserPlaylist?>(null) }

    val deletedLabel = stringResource(R.string.gallery_deleted)
    val noAppLabel = stringResource(R.string.gallery_no_player)

    suspend fun reload() { items = MediaLibrary.list(context) }
    fun reloadPlaylists() { playlists = PlaylistStore.load(context) }

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

    // إذنُ قراءةِ الوسائط: يُطلَبُ عندَ الحاجةِ إليه لا عندَ الإقلاع، فالحالةُ
    // الغالبةُ — مقاطعُ نزّلها التطبيقُ وما زالَ مالكَها — لا تحتاجُه أصلاً.
    var accessBlocked by remember { mutableStateOf(false) }

    val askRead = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasRead = granted.values.any { it } || MediaLibrary.hasReadPermission(context)
        // من رفض الإذنَ مرّتين يمنعُه أندرويد من رؤية النافذة ثانيةً، فيعودُ الطلبُ
        // فوراً بالرفض ولا يظهرُ للمستخدم شيء. ولا يُميَّزُ هذا الرفضُ الصامتُ عن
        // رفضٍ حقيقيٍّ إلّا بأنّ النظامَ يمتنعُ عن عرض التبرير — فحينَها الطريقُ
        // الوحيدُ صفحةُ إعدادات التطبيق، ولا يُترَكُ المستخدمُ أمام زرٍّ لا يفعل شيئاً.
        if (!hasRead) {
            val activity = context as? Activity
            accessBlocked = activity != null && MediaLibrary.readPermissions().none {
                ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            }
        }
        scope.launch { reload() }
    }

    /** يطلبُ الإذنَ، فإن كان النظامُ قد أغلقَ بابَ الطلبِ فتحَ صفحةَ الإعدادات. */
    fun requestAccess() {
        if (accessBlocked) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null))
                )
            }
        } else {
            askRead.launch(MediaLibrary.readPermissions())
        }
    }

    LaunchedEffect(Unit) { reload() }
    LaunchedEffect(Unit) { reloadPlaylists() }

    val all = items
    // في الجذر تُعرَض الملفّاتُ المفردة، وداخلَ مجلَّدٍ تُعرَض عناصرُه وحدَها
    val open = openFolder
    /** التبويب المعروض: المرئيّات أوّلاً ثمّ الصوتيّات. */
    var audioTab by rememberSaveable { mutableStateOf(false) }
    val openPlaylist = playlists.firstOrNull { it.id == openUser }
    val list = all?.let { entries ->
        when {
            // قائمةُ المستخدمِ ترتيبُها من صنعِه، فتُرتَّبُ بعناوينِها لا بشيءٍ آخر،
            // ويسقطُ منها ما حُذِفَ من المعرض
            openPlaylist != null -> openPlaylist.uris.mapNotNull { u ->
                entries.firstOrNull { it.uri.toString() == u }
            }
            // داخلَ مجلَّدِ تنزيلٍ يُحتَرَمُ ترتيبُه: الاسمُ يبدأُ برقمِ العنصرِ فيكفي
            // الترتيبُ به. والفرزُ بتاريخِ الإضافةِ كانَ يخلطُها — العنصرُ السادسُ
            // قبلَ الأوّلِ لأنّ التنزيلَ لا يمضي على ترتيبِ القائمةِ دائماً.
            open != null -> entries
                .filter { it.folder == open.first && it.isAudio == open.second }
                .sortedBy { it.name }
            else -> entries.filter { it.folder == null && it.isAudio == audioTab }
        }
    }
    // المجلَّداتُ تُجمَّعُ على الاسمِ **والنوعِ** معاً
    val folders = remember(all) {
        all.orEmpty().filter { it.folder != null }
            .groupBy { it.folder!! to it.isAudio }
            .toList()
            .sortedByDescending { (_, v) -> v.maxOf { it.addedSeconds } }
    }
    /** مقاطعُ قائمةِ مستخدمٍ بترتيبِها، بعدَ إسقاطِ ما لم يَعُد في المعرض. */
    fun entriesOf(pl: UserPlaylist): List<MediaEntry> =
        pl.uris.mapNotNull { u -> all?.firstOrNull { it.uri.toString() == u } }
    val userPlaylists = playlists.filter { it.isAudio == audioTab }
    val videoFolders = folders.filter { !it.first.second }
    val audioFolders = folders.filter { it.first.second }
    val chosen = list.orEmpty().filter { it.uri.toString() in selected }

    // زرُّ الرجوع يُغلق المجلَّد قبل أن يعود إلى القائمة: هذا المستوى أعمقُ من
    // الشاشة، فيجب أن يُستهلَك هنا لا هناك.
    BackHandler(enabled = open != null || openUser != null) {
        openFolder = null; openUser = null; selected = emptySet()
    }

    if (open != null || openPlaylist != null) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = { openFolder = null; openUser = null; selected = emptySet() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            }
            Icon(
                when {
                    openPlaylist != null -> Icons.AutoMirrored.Filled.QueueMusic
                    open?.second == true -> Icons.Filled.MusicNote
                    else -> Icons.Filled.PlaylistPlay
                },
                null, tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                openPlaylist?.name ?: open?.first.orEmpty(),
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // قائمةُ المستخدمِ وحدَها تُسمّى وتُحذَف: المجلَّدُ ملفّاتٌ على القرصِ
            // لا ترتيبٌ في تفضيلاتِنا
            openPlaylist?.let { pl ->
                IconButton(onClick = { renaming = pl }) {
                    Icon(Icons.Filled.DriveFileRenameOutline,
                        stringResource(R.string.gallery_playlist_rename))
                }
                IconButton(onClick = { deletingPlaylist = pl }) {
                    Icon(Icons.Filled.PlaylistRemove,
                        stringResource(R.string.gallery_playlist_delete))
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── التبويبان ────────────────────────────────────────────────────────
        if (open == null && openPlaylist == null && !all.isNullOrEmpty()) {
            TabRow(selectedTabIndex = if (audioTab) 1 else 0, containerColor = Color.Transparent) {
                Tab(
                    selected = !audioTab,
                    onClick = { audioTab = false; selected = emptySet() },
                    text = { Text(stringResource(R.string.gallery_videos)) },
                    icon = { Icon(Icons.Filled.Movie, null) },
                )
                Tab(
                    selected = audioTab,
                    onClick = { audioTab = true; selected = emptySet() },
                    text = { Text(stringResource(R.string.gallery_audios)) },
                    icon = { Icon(Icons.Filled.MusicNote, null) },
                )
            }
        }

        // ── شريطُ الإجراءات ──────────────────────────────────────────────────
        if (list != null && (list.isNotEmpty() || userPlaylists.isNotEmpty() ||
                (if (audioTab) audioFolders else videoFolders).isNotEmpty())) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val tabFolders = if (audioTab) audioFolders else videoFolders
                Text(
                    if (selected.isEmpty()) {
                        if (open == null && tabFolders.isNotEmpty())
                            stringResource(R.string.gallery_count_with_folders,
                                list.size, tabFolders.size)
                        else stringResource(R.string.gallery_count, list.size)
                    }
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
                // ضمُّ المختارِ إلى قائمةٍ من صنعِ المستخدم: ما نُزِّلَ فرادى لا
                // مجلَّدَ يجمعُه، فالقائمةُ هي ما يجعلُه يُسمَعُ متتابعاً
                IconButton(
                    enabled = chosen.isNotEmpty(),
                    onClick = { addTo = chosen },
                ) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd,
                        stringResource(R.string.gallery_add_to_playlist))
                }
                // القصُّ لمقطعٍ واحد: حدّانِ زمنيّانِ لا يصلحانِ لمقاطعَ مختلفةِ
                // الطول، وهو القيدُ نفسُه الذي يمنعُ الاقتصاصَ في قائمةِ تشغيل
                IconButton(
                    enabled = chosen.size == 1,
                    onClick = { chosen.firstOrNull()?.let(onTrim) },
                ) { Icon(Icons.Filled.ContentCut, stringResource(R.string.menu_trim)) }
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

            // «فارغ» تُقاسُ بالمقاطعِ **وقوائمِ التشغيلِ** معاً: تبويبُ الصوتيّاتِ قد
            // لا يكونُ فيه مقطعٌ مفردٌ وفيه قائمتان، فقياسُ المفرداتِ وحدَها كانَ
            // يبتلعُ القوائمَ ويقولُ لصاحبِها لا شيءَ هنا وهي أمامَه في العدّاد
            list.isEmpty() && userPlaylists.isEmpty() &&
                (if (audioTab) audioFolders else videoFolders).isEmpty() ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.gallery_empty),
                        style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            if (hasRead) R.string.gallery_empty_hint
                            else R.string.gallery_empty_no_access
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!hasRead) {
                        Button(onClick = { requestAccess() }) {
                            Text(stringResource(
                                if (accessBlocked) R.string.gallery_open_settings
                                else R.string.gallery_grant_access
                            ))
                        }
                    }
                }
            }

            else -> {
                // صفٌّ واحدٌ من المقاطع، يُستعمَل في القسمَين وداخلَ المجلَّد
                val row: @Composable (MediaEntry, PlaylistControls?) -> Unit = { entry, controls ->
                    val key = entry.uri.toString()
                    val isSelected = key in selected
                    EntryRow(
                        entry = entry,
                        selected = isSelected,
                        selecting = selected.isNotEmpty(),
                        onClick = {
                            if (selected.isNotEmpty()) {
                                selected = if (isSelected) selected - key else selected + key
                            } else if (entry.isAudio) {
                                // الصوتُ يُشغَّلُ في المشغّلِ الداخليّ، وما يُرى الآنَ
                                // هو صفُّ التشغيل: نقرةٌ على مقطعٍ من قائمةٍ تُسمِعُها
                                // كلَّها من موضعِه
                                val queue = list.filter { it.isAudio }
                                onPlay(queue, queue.indexOf(entry).coerceAtLeast(0))
                            } else {
                                val ok = runCatching {
                                    context.startActivity(MediaLibrary.viewIntent(entry)); true
                                }.getOrDefault(false)
                                if (!ok) Toast.makeText(context, noAppLabel, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onLongClick = { selected = if (isSelected) selected - key else selected + key },
                        playlist = controls,
                    )
                }

                if (openPlaylist != null) {
                    if (list.isEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.gallery_playlist_empty),
                                Modifier.padding(18.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    // الترتيبُ يُنقَلُ في العناوينِ المحفوظةِ لا في المعروضِ منها:
                    // ما حُذِفَ من المعرضِ ما زالَ في القائمةِ ولا يُرى، فالفهرسُ
                    // المرئيُّ لا يطابقُ المحفوظ
                    list.forEachIndexed { i, entry ->
                        row(entry, PlaylistControls(
                            canUp = i > 0,
                            canDown = i < list.size - 1,
                            onUp = {
                                PlaylistStore.move(
                                    context, openPlaylist.id,
                                    openPlaylist.uris.indexOf(entry.uri.toString()),
                                    openPlaylist.uris.indexOf(list[i - 1].uri.toString()),
                                )
                                reloadPlaylists()
                            },
                            onDown = {
                                PlaylistStore.move(
                                    context, openPlaylist.id,
                                    openPlaylist.uris.indexOf(entry.uri.toString()),
                                    openPlaylist.uris.indexOf(list[i + 1].uri.toString()),
                                )
                                reloadPlaylists()
                            },
                            onRemove = {
                                PlaylistStore.removeFrom(
                                    context, openPlaylist.id, entry.uri.toString())
                                reloadPlaylists()
                                selected = emptySet()
                            },
                        ))
                    }
                } else if (open != null) {
                    list.forEach { row(it, null) }
                } else {
                    // تبويبانِ في الأعلى لا قسمانِ متتابعان: كانت الصوتيّاتُ أسفلَ
                    // كلِّ المرئيّاتِ فلا تُبلَغُ إلّا بتمريرٍ طويل.
                    val tabFolders = if (audioTab) audioFolders else videoFolders
                    if (tabFolders.isNotEmpty() || list.isNotEmpty() ||
                        userPlaylists.isNotEmpty()) {
                        // قوائمُ المستخدمِ أوّلاً: هي ما صنعَه بيدِه، وما نزّله
                        // مجموعاً يليه
                        if (userPlaylists.isNotEmpty()) {
                            GroupHeader(R.string.gallery_my_playlists, Icons.AutoMirrored.Filled.QueueMusic,
                                userPlaylists.size)
                            userPlaylists.forEach { pl ->
                                val entries = entriesOf(pl)
                                FolderCard(
                                    name = pl.name,
                                    entries = entries,
                                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                                    onPlayAll = if (pl.isAudio && entries.isNotEmpty()) {
                                        { onPlay(entries, 0) }
                                    } else null,
                                    onOpen = { openUser = pl.id; selected = emptySet() },
                                )
                            }
                        }
                        // وفي كلِّ تبويبٍ قسمانِ بعنوانَيهما: قوائمُ التشغيلِ ثمّ
                        // المقاطعُ المفردة
                        if (tabFolders.isNotEmpty()) {
                            GroupHeader(R.string.gallery_playlists, Icons.Filled.PlaylistPlay,
                                tabFolders.size)
                            tabFolders.forEach { (key, entries) ->
                                FolderCard(
                                    name = key.first,
                                    entries = entries,
                                    // القائمةُ الصوتيّةُ تُسمَعُ كلُّها بنقرةٍ واحدةٍ
                                    // بترتيبِها — والاسمُ يبدأُ برقمِ العنصرِ فيكفي
                                    onPlayAll = if (key.second) {
                                        { onPlay(entries.sortedBy { it.name }, 0) }
                                    } else null,
                                    onOpen = { openFolder = key; selected = emptySet() },
                                )
                            }
                        }
                        if (list.isNotEmpty()) {
                            GroupHeader(R.string.gallery_singles,
                                if (audioTab) Icons.Filled.MusicNote else Icons.Filled.Movie,
                                list.size)
                            list.forEach { row(it, null) }
                            // من لم يصنع قائمةً بعدُ لا يعرفُ أنّ في وسعِه ذلك
                            if (userPlaylists.isEmpty()) {
                                Text(
                                    stringResource(R.string.gallery_playlist_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.gallery_empty),
                                Modifier.padding(18.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }

        // القائمةُ قد تكونُ عامرةً وينقصُها ما فقدَ التطبيقُ مِلكيّتَه، ولا سبيلَ إلى
        // معرفةِ ذلك بلا الإذنِ نفسِه — فيبقى العرضُ متاحاً بلا إلحاح.
        if (!hasRead && !all.isNullOrEmpty()) {
            TextButton(onClick = { requestAccess() }) {
                Text(
                    stringResource(R.string.gallery_missing_older),
                    style = MaterialTheme.typography.bodySmall,
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

    // ── الضمُّ إلى قائمةٍ من صنعِ المستخدم ────────────────────────────────────
    addTo?.let { picked ->
        // القائمةُ الصوتيّةُ لا تُخلَطُ بالمرئيّة: المشغّلُ الداخليُّ للصوتِ وحدَه،
        // فقائمةٌ فيها مرئيٌّ تنقطعُ عندَه
        val pickedAudio = picked.all { it.isAudio }
        val candidates = playlists.filter { it.isAudio == pickedAudio }
        var newName by remember(picked) { mutableStateOf("") }

        fun done(name: String) {
            addTo = null
            selected = emptySet()
            Toast.makeText(
                context,
                context.getString(R.string.gallery_added_to_playlist, name),
                Toast.LENGTH_SHORT,
            ).show()
        }

        AlertDialog(
            onDismissRequest = { addTo = null },
            title = { Text(stringResource(R.string.gallery_add_to_playlist)) },
            text = {
                Column(
                    Modifier.heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    candidates.forEach { pl ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    PlaylistStore.addTo(context, pl.id,
                                        picked.map { it.uri.toString() })
                                    reloadPlaylists()
                                    done(pl.name)
                                }
                                .padding(horizontal = 6.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Text(pl.name, Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.gallery_folder_items, pl.uris.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (candidates.isNotEmpty()) HorizontalDivider()
                    Text(
                        stringResource(R.string.gallery_new_playlist),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.gallery_playlist_name)) },
                        singleLine = true,
                        textStyle = centeredFieldStyle,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val created = PlaylistStore.create(
                            context, newName, picked.map { it.uri.toString() }, pickedAudio,
                        )
                        reloadPlaylists()
                        done(created.name)
                    },
                ) { Text(stringResource(R.string.gallery_create)) }
            },
            dismissButton = {
                TextButton(onClick = { addTo = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    renaming?.let { pl ->
        var name by remember(pl.id) { mutableStateOf(pl.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.gallery_playlist_rename)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.gallery_playlist_name)) },
                    singleLine = true, textStyle = centeredFieldStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    PlaylistStore.rename(context, pl.id, name)
                    reloadPlaylists()
                    renaming = null
                }) { Text(stringResource(R.string.gallery_playlist_rename)) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    deletingPlaylist?.let { pl ->
        AlertDialog(
            onDismissRequest = { deletingPlaylist = null },
            title = { Text(stringResource(R.string.gallery_playlist_delete)) },
            text = { Text(stringResource(R.string.gallery_playlist_delete_confirm, pl.name)) },
            confirmButton = {
                TextButton(onClick = {
                    PlaylistStore.delete(context, pl.id)
                    reloadPlaylists()
                    deletingPlaylist = null
                    if (openUser == pl.id) openUser = null
                }) { Text(stringResource(R.string.gallery_playlist_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingPlaylist = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** عنوانُ قسمٍ في المعرض: المرئيّاتُ أو الصوتيّات. */
@Composable
private fun GroupHeader(
    label: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    count: Int? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text(
            if (count != null) "${stringResource(label)}  ·  $count" else stringResource(label),
            style = MaterialTheme.typography.titleSmall,
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

/** مجلَّدُ قائمةِ تشغيل: صورةُ أوّلِ عنصرٍ وعددُه ومجموعُ حجمِه. */
@Composable
private fun FolderCard(
    name: String,
    entries: List<MediaEntry>,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.PlaylistPlay,
    onPlayAll: (() -> Unit)? = null,
    onOpen: () -> Unit,
) {
    val context = LocalContext.current
    // قائمةُ مستخدمٍ قد تخلو من مقاطعِها إن حُذِفَت من المعرض، وتبقى هي: تُعرَضُ
    // فارغةً ليحذفَها صاحبُها أو يملأَها، لا تختفي بلا خبر
    val first = entries.maxByOrNull { it.addedSeconds }
    val thumb by produceState<Bitmap?>(null, first?.uri) {
        value = first?.let { MediaLibrary.thumbnail(context, it) }
    }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
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
                        bitmap = bmp.asImageBitmap(), contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    )
                }
                Icon(
                    icon, null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.55f)).padding(2.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.gallery_folder_items, entries.size) + "  ·  " +
                        MediaLibrary.formatSize(entries.sumOf { it.sizeBytes }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            onPlayAll?.let { play ->
                IconButton(onClick = play) {
                    Icon(Icons.Filled.PlayCircle, stringResource(R.string.player_play_all),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ما يُفعَلُ بمقطعٍ داخلَ قائمةِ مستخدم: ترتيبُه فيها وإخراجُه منها. */
private data class PlaylistControls(
    val canUp: Boolean,
    val canDown: Boolean,
    val onUp: () -> Unit,
    val onDown: () -> Unit,
    val onRemove: () -> Unit,
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: MediaEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** غيرُ فارغٍ داخلَ قائمةِ مستخدمٍ وحدَها. */
    playlist: PlaylistControls? = null,
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
            } else if (playlist != null) {
                // ثلاثةُ أفعالٍ في زرٍّ واحد: الصفُّ ضيّقٌ باسمٍ طويلٍ ومصغَّرة،
                // وثلاثةُ أزرارٍ ظاهرةٍ تسرقُ عرضَ الاسم
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.gallery_share))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.gallery_playlist_up)) },
                            enabled = playlist.canUp,
                            leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, null) },
                            onClick = { menu = false; playlist.onUp() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.gallery_playlist_down)) },
                            enabled = playlist.canDown,
                            leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null) },
                            onClick = { menu = false; playlist.onDown() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.gallery_share)) },
                            leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = {
                                menu = false
                                runCatching {
                                    context.startActivity(
                                        MediaLibrary.shareIntent(listOf(entry)))
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.gallery_playlist_remove)) },
                            leadingIcon = { Icon(Icons.Filled.PlaylistRemove, null) },
                            onClick = { menu = false; playlist.onRemove() },
                        )
                    }
                }
            } else {
                IconButton(onClick = {
                    runCatching { context.startActivity(MediaLibrary.shareIntent(listOf(entry))) }
                }) { Icon(Icons.Filled.Share, stringResource(R.string.gallery_share)) }
            }
        }
    }
}
