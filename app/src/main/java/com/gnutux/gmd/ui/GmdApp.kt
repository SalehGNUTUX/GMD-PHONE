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
import com.gnutux.gmd.download.Progress
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
                Screen.Menu -> MenuScreen(onPick = { screen = it })
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
                Screen.Player -> PlayerScreen(player)
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
                Screen.History -> HistoryScreen(onRetry = { entry ->
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
private fun MenuScreen(onPick: (Screen) -> Unit) {
    data class Item(val screen: Screen, val icon: ImageVector, val label: Int)
    val items = listOf(
        Item(Screen.Video, Icons.Filled.Movie, R.string.menu_video),
        Item(Screen.Audio, Icons.Filled.MusicNote, R.string.menu_audio),
        Item(Screen.Trim, Icons.Filled.ContentCut, R.string.menu_trim),
        Item(Screen.Gallery, Icons.Filled.PhotoLibrary, R.string.menu_gallery),
        Item(Screen.History, Icons.Filled.History, R.string.menu_history),
        Item(Screen.Info, Icons.Filled.Info, R.string.menu_info),
        Item(Screen.Settings, Icons.Filled.Settings, R.string.menu_settings),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.share_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        items.forEach { item ->
            ElevatedCard(onClick = { onPick(item.screen) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(item.label), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
