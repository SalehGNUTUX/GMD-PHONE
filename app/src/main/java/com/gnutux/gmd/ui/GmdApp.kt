package com.gnutux.gmd.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gnutux.gmd.GmdApp as AppClass
import com.gnutux.gmd.R
import com.gnutux.gmd.ToolsState
import com.gnutux.gmd.download.AudioFormat
import com.gnutux.gmd.download.DownloadService
import com.gnutux.gmd.download.Downloader
import com.gnutux.gmd.download.Quality
import com.gnutux.gmd.download.VideoFormat
import com.gnutux.gmd.download.Progress
import kotlinx.coroutines.launch
import com.gnutux.gmd.media.MediaEntry
import com.gnutux.gmd.media.Trimmer
import com.gnutux.gmd.media.TrimService
import com.gnutux.gmd.player.PlayerService
import com.gnutux.gmd.player.Track

enum class Screen { Menu, Video, Audio, Trim, Gallery, Player, History, Info, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmdApp(vm: GmdViewModel, incomingUrl: String?, onUrlConsumed: () -> Unit) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.Menu) }

    val tools by AppClass.instance.tools.collectAsStateWithLifecycle()
    // حالةُ كلِّ قسمٍ على حدة: تنزيلانِ قد يجريانِ معاً
    val progress by DownloadService.progress.collectAsStateWithLifecycle()
    val trim by TrimService.progress.collectAsStateWithLifecycle()
    val player by PlayerService.state.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    // شكلُ بطاقاتِ القائمة: مربَّعاتٌ اثنانِ في الصفِّ افتراضاً، كنسخةِ الحاسوب
    val squareCards by vm.squareCards.collectAsStateWithLifecycle(initialValue = true)

    // رابطٌ وصل من ورقة المشاركة: يملأ الحقل ويقفز إلى شاشة الفيديو مباشرةً،
    // فالمستخدم شارك الرابط ليُنزّله لا ليعيد لصقه.
    LaunchedEffect(incomingUrl) {
        incomingUrl?.let {
            vm.video.setUrl(it)
            screen = Screen.Video
            onUrlConsumed()
        }
    }

    LaunchedEffect(Unit) { vm.checkForUpdatesOnLaunch() }

    // ما كانَ يعملُ حينَ أُغلِقَ التطبيقُ يعودُ إلى شاشتِه: الخدمةُ تبقى حيّةً
    // بإشعارِها بينما تُهدَمُ الشاشةُ ونموذجُها، فكانَ المستخدمُ يعودُ إلى حقلٍ
    // فارغٍ وشريطٍ ساكنٍ والتنزيلُ يجري
    LaunchedEffect(Unit) { vm.restoreRunningJobs() }

    // وآخرُ ما استُمِعَ إليه يعودُ موقوفاً عندَ موضعِه، فنقرةٌ واحدةٌ تُكمِلُ ما بدأ
    LaunchedEffect(Unit) { PlayerService.restore(context) }

    val scope = rememberCoroutineScope()
    val goneLabel = stringResource(R.string.gallery_no_player)
    var confirmExit by remember { mutableStateOf(false) }
    /** قسمٌ طُلِبَ إليه رابطٌ جديدٌ وهو يعمل؛ يُسألُ صاحبُه قبلَ أن يُطمَسَ ما يجري. */
    var busySection by remember { mutableStateOf<Screen?>(null) }

    // زرُّ الرجوع: يعودُ إلى القائمة من أيِّ شاشةٍ فرعيّة، ولا يخرجُ من التطبيقِ
    // إلّا من القائمةِ نفسِها وبعدَ تأكيد. وكان يخرجُ رأساً من أيِّ موضعٍ بلا سؤال،
    // فيُفقَد ما في الحقولِ بضغطةٍ واحدةٍ غيرِ مقصودة.
    BackHandler(enabled = screen != Screen.Menu) { screen = Screen.Menu }
    BackHandler(enabled = screen == Screen.Menu && !confirmExit) { confirmExit = true }

    busySection?.let { target ->
        AlertDialog(
            onDismissRequest = { busySection = null },
            title = { Text(stringResource(R.string.history_busy_title)) },
            text = { Text(stringResource(R.string.history_busy_message)) },
            confirmButton = {
                TextButton(onClick = { screen = target; busySection = null }) {
                    Text(stringResource(R.string.history_open_section))
                }
            },
            dismissButton = {
                TextButton(onClick = { busySection = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmExit) {
        val activity = LocalContext.current as? Activity
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_message)) },
            confirmButton = {
                TextButton(onClick = { confirmExit = false; activity?.finish() }) {
                    Text(stringResource(R.string.exit_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) {
                    Text(stringResource(R.string.exit_stay))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Menu -> stringResource(R.string.app_title)
                            Screen.Video -> stringResource(R.string.video_title)
                            Screen.Audio -> stringResource(R.string.audio_title)
                            Screen.Trim -> stringResource(R.string.trim_title)
                            Screen.Gallery -> stringResource(R.string.gallery_title)
                            Screen.Player -> stringResource(R.string.player_title)
                            Screen.History -> stringResource(R.string.history_title)
                            Screen.Info -> stringResource(R.string.info_title)
                            Screen.Settings -> stringResource(R.string.settings_title)
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (screen != Screen.Menu) {
                        IconButton(onClick = { screen = Screen.Menu }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        // المشغّلُ يبقى ظاهراً في كلِّ شاشةٍ ما دامَ فيه مقطع: الاستماعُ لا يُلغي
        // التصفُّحَ ولا التنزيل
        bottomBar = {
            if (player.current != null && screen != Screen.Player) {
                PlayerBar(player) { screen = Screen.Player }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToolsBanner(tools)

            AnimatedVisibility(update is UpdatePhase.Available && screen != Screen.Settings) {
                val info = (update as? UpdatePhase.Available)?.info
                if (info != null) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.SystemUpdateAlt, null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.update_banner, info.version),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { screen = Screen.Settings }) {
                                Text(stringResource(R.string.update_title))
                            }
                        }
                    }
                }
            }

            when (screen) {
                Screen.Menu -> MenuScreen(
                    onPick = { screen = it },
                    nowPlaying = player.current?.title?.takeIf { it.isNotBlank() },
                    square = squareCards,
                    onToggleShape = { vm.setSquareCards(!squareCards) },
                )
                Screen.Video -> DownloadScreen(
                    vm, progress[Downloader.Kind.VIDEO] ?: Progress.Idle,
                    isAudio = false, enabled = tools is ToolsState.Ready,
                    onOpenGallery = { screen = Screen.Gallery },
                )
                Screen.Audio -> DownloadScreen(
                    vm, progress[Downloader.Kind.AUDIO] ?: Progress.Idle,
                    isAudio = true, enabled = tools is ToolsState.Ready,
                    onOpenGallery = { screen = Screen.Gallery },
                )
                Screen.Trim -> TrimScreen(vm, trim, onOpenGallery = { screen = Screen.Gallery })
                Screen.Player -> PlayerScreen(player,
                    onOpenGallery = { screen = Screen.Gallery })
                Screen.Gallery -> GalleryScreen(
                    onPlay = { queue: List<MediaEntry>, index: Int ->
                        PlayerService.play(
                            context,
                            queue.map { Track(it.uri.toString(), it.name, it.durationMs) },
                            index,
                        )
                    },
                    onTrim = { entry: MediaEntry ->
                    // القصُّ من المعرضِ يبدأُ بالمادّةِ في اليد، فلا يُسأَلُ المستخدمُ
                    // عن ملفٍّ اختارَه لتوّه
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
                    screen = Screen.Trim
                })
                Screen.History -> HistoryScreen(
                    onPlay = { entry ->
                        // صفٌّ من مجلَّدِ القائمةِ مرتَّباً، أو المقطعُ وحدَه
                        scope.launch {
                            val queue = vm.queueFor(entry)
                            if (queue.isNotEmpty()) PlayerService.play(context, queue, 0)
                            else Toast.makeText(context, goneLabel, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onRetry = { entry ->
                    // إعادةُ المحاولةِ بالجودةِ نفسِها: أنفعُ ما في السجلّ، فإغلاقُ
                    // بطاقةِ الخطأِ كان يُضيعُ الرابطَ وسببَ الفشلِ معاً.
                    val kind = if (entry.isAudio) Downloader.Kind.AUDIO else Downloader.Kind.VIDEO
                    val target = if (entry.isAudio) Screen.Audio else Screen.Video
                    // وقسمٌ يعملُ لا يُكتَبُ فوقَه: كانَ الرابطُ يحلُّ محلَّ الرابطِ
                    // الجاري فيغيبُ عن صاحبِه تقدُّمُه وما تمَّ من قائمتِه، والتنزيلُ
                    // يُكمِلُ في الخلفيّةِ لا يدري به
                    if (progress[kind] is Progress.Running) {
                        busySection = target
                        return@HistoryScreen
                    }
                    val st = vm.section(entry.isAudio)
                    st.setUrl(entry.url)
                    st.setSection(entry.sectionStart, entry.sectionEnd)
                    if (entry.isAudio) {
                        runCatching { st.audioFormat.value = AudioFormat.valueOf(entry.choice) }
                        screen = Screen.Audio
                    } else {
                        runCatching { st.quality.value = Quality.valueOf(entry.choice) }
                        // والحاويةُ كذلك: إعادةُ المحاولةِ بمثلِ ما طُلِبَ أوّلَ مرّة
                        entry.container?.let { c ->
                            runCatching { st.videoFormat.value = VideoFormat.valueOf(c) }
                        }
                        screen = Screen.Video
                    }
                    DownloadService.reset(if (entry.isAudio) Downloader.Kind.AUDIO
                                          else Downloader.Kind.VIDEO)
                })
                Screen.Info -> InfoScreen(vm, enabled = tools is ToolsState.Ready)
                Screen.Settings -> SettingsScreen(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolsBanner(tools: ToolsState) {
    val context = LocalContext.current
    val copiedLabel = stringResource(R.string.error_copied)
    when (tools) {
        is ToolsState.Preparing -> Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.init_tools), style = MaterialTheme.typography.bodyMedium)
            }
        }
        is ToolsState.Failed -> Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Filled.WarningAmber, null)
                    Text(
                        stringResource(R.string.init_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                // نصُّ الاستثناء كاملاً: بلا جهازٍ متّصلٍ بـlogcat هذه هي الطريقة
                // الوحيدة ليصل الخطأُ من جهاز المستخدم إلى المطوِّر.
                Text(
                    tools.message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { AppClass.instance.retryTools() }) {
                        Text(stringResource(R.string.retry))
                    }
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("GMD", tools.message))
                        Toast.makeText(context, copiedLabel, Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.copy_error))
                    }
                }
            }
        }
        ToolsState.Ready -> Unit
    }
}

@Composable
private fun MenuScreen(
    onPick: (Screen) -> Unit,
    nowPlaying: String? = null,
    square: Boolean = true,
    onToggleShape: () -> Unit = {},
) {
    // البطاقاتُ والأيقوناتُ والألوانُ كنسخةِ الحاسوب، فيستوي وجهُ البرنامجِ على
    // الجهازَين: لكلِّ قسمٍ أيقونتُه في مربَّعٍ ملوَّنٍ ووصفٌ تحتَ اسمِه.
    data class Item(
        val screen: Screen,
        val icon: ImageVector,
        val label: Int,
        val desc: Int,
        val tint: Color,
    )
    val items = listOf(
        Item(Screen.Video, Icons.Filled.LocalMovies, R.string.menu_video, R.string.desc_video,
            Color(0xFFDC2626)),
        Item(Screen.Audio, Icons.Filled.MusicNote, R.string.menu_audio, R.string.desc_audio,
            Color(0xFFEA580C)),
        Item(Screen.Trim, Icons.Filled.ContentCut, R.string.menu_trim, R.string.desc_trim,
            Color(0xFF059669)),
        Item(Screen.Gallery, Icons.Filled.VideoLibrary, R.string.menu_gallery, R.string.desc_gallery,
            Color(0xFF0D9488)),
        // المشغّلُ بابٌ في القائمةِ لا شاشةٌ خلفَ شريطٍ سفليّ: كانَ لا يُبلَغُ إلّا
        // بتشغيلِ مقطعٍ من المعرضِ ثمّ النقرِ على الشريط، فمن أغلقَه لم يجد طريقاً
        // إلى ما كانَ يسمعُه
        Item(Screen.Player, Icons.Filled.GraphicEq, R.string.player_title, R.string.desc_player,
            Color(0xFFC026D3)),
        Item(Screen.History, Icons.Filled.History, R.string.menu_history, R.string.desc_history,
            Color(0xFF7C3AED)),
        Item(Screen.Info, Icons.Filled.Info, R.string.menu_info, R.string.desc_info,
            Color(0xFF0284C7)),
        Item(Screen.Settings, Icons.Filled.Settings, R.string.menu_settings, R.string.desc_settings,
            Color(0xFF52525B)),
    )

    /** مربَّعُ الأيقونةِ الملوَّن، مشترَكٌ بينَ الشكلَين. */
    @Composable
    fun IconTile(item: Item, size: Int) {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(item.tint, item.tint.copy(alpha = 0.75f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, null, tint = Color.White,
                modifier = Modifier.size((size * 0.55f).dp))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.welcome),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            // تبديلُ الشكل: مربَّعانِ في الصفِّ أو مستطيلٌ في عمود
            IconButton(onClick = onToggleShape) {
                Icon(
                    if (square) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                    stringResource(if (square) R.string.cards_list else R.string.cards_square),
                )
            }
        }
        Text(
            stringResource(R.string.share_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))

        if (square) {
            // مربَّعانِ في كلِّ صفّ. والصفوفُ مبنيّةٌ باليدِ لا بشبكةٍ كسولة: الشاشةُ
            // داخلَ تمريرٍ واحدٍ في القشرة، وارتفاعُ الشبكةِ غيرُ محدودٍ فيه فينهارُ
            // قياسُها.
            items.chunked(2).forEach { pair ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    pair.forEach { item ->
                        ElevatedCard(
                            onClick = { onPick(item.screen) },
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                IconTile(item, 44)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    stringResource(item.label),
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (item.screen == Screen.Player && nowPlaying != null)
                                        nowPlaying else stringResource(item.desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    // صفٌّ فيه عنصرٌ واحد: يبقى المربَّعُ مربَّعاً ولا يمتدُّ عرضَ الشاشة
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            items.forEach { item ->
                ElevatedCard(onClick = { onPick(item.screen) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        IconTile(item, 40)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(item.label),
                                style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (item.screen == Screen.Player && nowPlaying != null)
                                    nowPlaying else stringResource(item.desc),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
