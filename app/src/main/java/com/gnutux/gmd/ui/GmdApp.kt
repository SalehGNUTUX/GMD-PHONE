package com.gnutux.gmd.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.gnutux.gmd.download.DownloadService
import com.gnutux.gmd.download.Progress

enum class Screen { Menu, Video, Audio, Info, Settings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GmdApp(vm: GmdViewModel, incomingUrl: String?, onUrlConsumed: () -> Unit) {
    val context = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.Menu) }

    val tools by AppClass.instance.tools.collectAsStateWithLifecycle()
    val progress by DownloadService.progress.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()

    // رابطٌ وصل من ورقة المشاركة: يملأ الحقل ويقفز إلى شاشة الفيديو مباشرةً،
    // فالمستخدم شارك الرابط ليُنزّله لا ليعيد لصقه.
    LaunchedEffect(incomingUrl) {
        incomingUrl?.let {
            vm.setUrl(it)
            screen = Screen.Video
            onUrlConsumed()
        }
    }

    LaunchedEffect(Unit) { vm.checkForUpdatesOnLaunch() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (screen) {
                            Screen.Menu -> stringResource(R.string.app_title)
                            Screen.Video -> stringResource(R.string.video_title)
                            Screen.Audio -> stringResource(R.string.audio_title)
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
                Screen.Video -> DownloadScreen(vm, progress, isAudio = false, enabled = tools is ToolsState.Ready)
                Screen.Audio -> DownloadScreen(vm, progress, isAudio = true, enabled = tools is ToolsState.Ready)
                Screen.Info -> InfoScreen(vm, enabled = tools is ToolsState.Ready)
                Screen.Settings -> SettingsScreen(vm)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ToolsBanner(tools: ToolsState) {
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
            Row(
                Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.WarningAmber, null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.init_failed), style = MaterialTheme.typography.bodyMedium)
                    Text(tools.message, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { AppClass.instance.retryTools() }) {
                    Text(stringResource(R.string.update_check_now))
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
