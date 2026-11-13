package com.igi.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igi.mobile.data.GameRepository
import com.igi.mobile.data.IgiState
import java.util.Locale

object Legal {
    const val TEXT = "IGI Mobile is an unofficial, fan-made client for the original " +
        "Project I.G.I.: I'm Going In browser port that is published at playigi.com.\n\n" +
        "It is not affiliated with, endorsed by, or sponsored by Innerloop Studios, " +
        "Eidos Interactive, the current rights holders, or playigi.com.\n\n" +
        "Project I.G.I. and all game content belong to their respective rights holders. " +
        "This build includes a local copy of the web port and its mission data so it can run " +
        "offline. It is intended for personal use only.\n\n" +
        "Do not publish, sell or redistribute this app or its contents without written " +
        "permission from the rights holders."
}

@Composable
fun HomeScreen(
    state: IgiState,
    allBytes: Long,
    onPlay: () -> Unit,
    onMissions: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit
) {
    val missionsTotal = state.index?.levels?.size ?: 14
    val fraction = if (allBytes <= 0L) 0f else (state.onDeviceBytes.toFloat() / allBytes.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(IgiBlack)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(26.dp))
        Text(
            text = "PROJECT I.G.I.",
            style = MaterialTheme.typography.displaySmall,
            color = IgiGold
        )
        Text(
            text = "I'M GOING IN  //  MOBILE",
            color = IgiGreen.copy(alpha = 0.8f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = IgiGreen.copy(alpha = 0.22f))
        Spacer(Modifier.height(20.dp))

        TerminalPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("OFFLINE STATUS")
                Grow()
                StatusPill(
                    if (state.bundledBuild) "BUNDLE" else "SHELL",
                    when {
                        state.bundlePreparing -> "SETUP"
                        state.shellReady -> "READY"
                        else -> "MISSING"
                    },
                    state.shellReady && !state.bundlePreparing
                )
            }
            Spacer(Modifier.height(10.dp))
            KeyValueRow("On this device", formatBytes(state.onDeviceBytes))
            KeyValueRow("Missions ready", "${state.installedMissions.size} / $missionsTotal")
            if (allBytes > 0L) {
                KeyValueRow("Full install", formatBytes(allBytes))
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = IgiGreen,
                    trackColor = IgiSurface2
                )
            }
            if (state.installedMissions.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Download once, then play with no connection. The game shell and mission data stay in private app storage.",
                    color = IgiDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (state.bundlePreparing) {
            SectionLabel("EXPANDING BUNDLED GAME DATA")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = state.bundleProgress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = IgiGold,
                trackColor = IgiSurface2
            )
            Spacer(Modifier.height(10.dp))
        }

        if (state.preparingShell) {
            SectionLabel("PREPARING GAME SHELL")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = state.shellProgress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = IgiGold,
                trackColor = IgiSurface2
            )
            Spacer(Modifier.height(10.dp))
        }

        IgiButton(
            text = if (state.preparingShell || state.bundlePreparing) "PREPARING…" else "PLAY",
            primary = true,
            enabled = !state.preparingShell && !state.bundlePreparing && !state.downloading,
            onClick = onPlay
        )
        Spacer(Modifier.height(10.dp))
        IgiButton(text = "OFFLINE MISSIONS", onClick = onMissions)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IgiButton(text = "SETTINGS", modifier = Modifier.weight(1f), onClick = onSettings)
            IgiButton(text = "ABOUT", modifier = Modifier.weight(1f), onClick = onAbout)
        }

        state.error?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = IgiRed, fontSize = 12.sp, lineHeight = 18.sp)
        }
        state.indexError?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = IgiGold, fontSize = 12.sp, lineHeight = 18.sp)
        }

        Spacer(Modifier.height(22.dp))
        HorizontalDivider(color = IgiGreen.copy(alpha = 0.16f))
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (state.bundledBuild) {
                "Unofficial build with game content bundled for personal offline use. " +
                    "Do not redistribute. All game assets belong to their rights holders."
            } else {
                "Unofficial companion for the playigi.com browser port. No game assets are bundled in this app."
            },
            color = IgiDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Text(
            text = "Touch controls are supplied by the web build. Play in landscape.",
            color = IgiDim,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun MissionsScreen(
    state: IgiState,
    allBytes: Long,
    missionBytes: (Int) -> Long,
    bytesFor: (Set<Int>) -> Long,
    onBack: () -> Unit,
    onDownload: (Set<Int>, Boolean) -> Unit,
    onCancel: () -> Unit
) {
    val missions = remember(state.index) {
        state.index?.levels?.keys?.sorted() ?: (1..14).toList()
    }
    val initial = remember(missions, state.index) {
        missions.filterNot { it in state.installedMissions }.toSet()
    }
    var selected by remember(missions, state.index) { mutableStateOf(initial) }
    val selectedBytes = remember(selected, state.index, state.hdPack, state.flacPack, state.touchControls) {
        bytesFor(selected)
    }
    val installed = state.installedMissions

    Column(Modifier.fillMaxSize().background(IgiBlack)) {
        TopBar("MISSION DATA", onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            TerminalPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("FULL INSTALL")
                    Grow()
                    Text(formatBytes(allBytes), color = IgiGold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "All 14 missions, shared assets and touch controls." +
                        (if (state.hdPack) " HD textures included." else "") +
                        (if (state.flacPack) " Lossless audio included." else ""),
                    color = IgiDim,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                IgiButton(
                    text = "DOWNLOAD EVERYTHING",
                    enabled = !state.downloading,
                    onClick = { onDownload(emptySet(), true) }
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("MISSIONS")
                Grow()
                TextButton(onClick = { selected = missions.toSet() }) {
                    Text("ALL", color = IgiGreen, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                TextButton(onClick = { selected = emptySet() }) {
                    Text("NONE", color = IgiDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(4.dp))

            missions.forEach { mission ->
                MissionRow(
                    number = mission,
                    name = GameRepository.MISSION_NAMES.getOrElse(mission - 1) { "Mission $mission" },
                    size = missionBytes(mission),
                    installed = mission in installed,
                    selected = mission in selected,
                    enabled = !state.downloading,
                    onToggle = {
                        selected = if (mission in selected) selected - mission else selected + mission
                    }
                )
                Spacer(Modifier.height(7.dp))
            }
            Spacer(Modifier.height(16.dp))
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(IgiSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            if (state.downloading) {
                val progress = state.progress
                val percent = ((progress?.fraction ?: 0f) * 100f).toInt()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(if (state.cancelling) "CANCELLING" else "DOWNLOADING")
                    Grow()
                    Text("$percent%", color = IgiGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = progress?.fraction ?: 0f,
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = IgiGreen,
                    trackColor = IgiSurface2
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${formatBytes(progress?.doneBytes ?: 0L)} / ${formatBytes(progress?.totalBytes ?: 0L)}" +
                        "  •  ${progress?.doneFiles ?: 0}/${progress?.totalFiles ?: 0} files" +
                        (formatSpeed(progress?.speedBytesPerSecond ?: 0L).let { if (it.isNotEmpty()) "  •  $it" else "" }),
                    color = IgiDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(10.dp))
                IgiButton(
                    text = if (state.cancelling) "CANCELLING…" else "CANCEL DOWNLOAD",
                    enabled = !state.cancelling,
                    onClick = onCancel
                )
            } else {
                IgiButton(
                    text = "DOWNLOAD ${selected.size} SELECTED  •  ${formatBytes(selectedBytes)}",
                    primary = true,
                    enabled = selected.isNotEmpty() && !state.downloading,
                    onClick = { onDownload(selected, false) }
                )
            }
            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = IgiRed, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun MissionRow(
    number: Int,
    name: String,
    size: Long,
    installed: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) IgiGreen.copy(alpha = 0.07f) else Color.Transparent,
                RoundedCornerShape(2.dp)
            )
            .border(
                1.dp,
                if (selected) IgiGreen.copy(alpha = 0.42f) else IgiDim.copy(alpha = 0.22f),
                RoundedCornerShape(2.dp)
            )
            .clickable(enabled = enabled) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = String.format(Locale.US, "%02d", number),
            color = IgiGold,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = IgiText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = if (installed) "READY OFFLINE" else formatBytes(size),
                color = if (installed) IgiGreen else IgiDim,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp
            )
        }
        Checkbox(
            checked = selected,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = IgiGreen,
                uncheckedColor = IgiDim,
                checkmarkColor = Color.Black,
                disabledCheckedColor = IgiGreen.copy(alpha = 0.4f),
                disabledUncheckedColor = IgiDim.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
fun SettingsScreen(
    state: IgiState,
    onBack: () -> Unit,
    onHdPack: (Boolean) -> Unit,
    onFlacPack: (Boolean) -> Unit,
    onTouchControls: (Boolean) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onRefreshIndex: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    val missionsTotal = state.index?.levels?.size ?: 14

    Column(Modifier.fillMaxSize().background(IgiBlack)) {
        TopBar("SETTINGS", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            TerminalPanel {
                SectionLabel("GRAPHICS & AUDIO")
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "HD textures",
                    subtitle = "Optional pack, about 69 MB extra. SD is always installed as fallback.",
                    checked = state.hdPack,
                    onChange = onHdPack
                )
                ToggleRow(
                    title = "Lossless audio",
                    subtitle = "Optional FLAC soundtrack, about 223 MB extra.",
                    checked = state.flacPack,
                    onChange = onFlacPack
                )
            }

            Spacer(Modifier.height(14.dp))
            TerminalPanel {
                SectionLabel("CONTROLS")
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "Touch controls",
                    subtitle = "On-screen sticks and buttons supplied by the web build.",
                    checked = state.touchControls,
                    onChange = onTouchControls
                )
                Text(
                    text = "Changing controls changes which touch sprites are downloaded. Re-download to apply.",
                    color = IgiDim,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }

            Spacer(Modifier.height(14.dp))
            TerminalPanel {
                SectionLabel("DOWNLOADS")
                Spacer(Modifier.height(6.dp))
                ToggleRow(
                    title = "Wi-Fi only",
                    subtitle = "Block game-data downloads on mobile data.",
                    checked = state.wifiOnly,
                    onChange = onWifiOnly
                )
            }

            Spacer(Modifier.height(14.dp))
            TerminalPanel {
                SectionLabel("STORAGE")
                Spacer(Modifier.height(8.dp))
                KeyValueRow("On this device", formatBytes(state.onDeviceBytes))
                KeyValueRow("Missions ready", "${state.installedMissions.size} / $missionsTotal")
                Spacer(Modifier.height(12.dp))
                IgiButton(text = "CLEAR CACHED DATA", onClick = { confirmClear = true })
                Spacer(Modifier.height(8.dp))
                IgiButton(text = "REFRESH GAME INDEX", onClick = onRefreshIndex)
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Clearing removes downloaded data and the cached game shell. Both will be downloaded again on the next play.",
                color = IgiDim,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = IgiSurface,
            titleContentColor = IgiGold,
            textContentColor = IgiText,
            title = {
                Text(
                    "CLEAR CACHED DATA?",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Text(
                    "This deletes ${formatBytes(state.onDeviceBytes)} of downloaded game data. Mission progress stored inside the game is not affected.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearCache()
                }) {
                    Text("CLEAR", color = IgiRed, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("CANCEL", color = IgiDim, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = IgiText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = IgiDim, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = IgiGreen,
                uncheckedThumbColor = IgiDim,
                uncheckedTrackColor = IgiSurface2,
                uncheckedBorderColor = IgiDim.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(IgiBlack)) {
        TopBar("ABOUT", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Text("IGI MOBILE", style = MaterialTheme.typography.headlineSmall, color = IgiGold)
            Text(
                "Version $version  •  native Android shell for the playigi.com web port",
                color = IgiDim,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(16.dp))

            TerminalPanel {
                SectionLabel("WHAT THIS IS")
                Spacer(Modifier.height(6.dp))
                Text(
                    "A native Kotlin app that runs the original Project I.G.I. browser port full screen, " +
                        "in landscape, with touch controls. This build carries the web port and all 14 " +
                        "missions inside the APK and expands them into private app storage on first launch, " +
                        "so it plays with no connection and no download step.",
                    color = IgiText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            TerminalPanel {
                SectionLabel("CREDITS")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Web port, multiplayer and co-op: Sachal — playigi.com\n" +
                        "Optional HD textures: Neonix Modding Team\n" +
                        "Original game: Innerloop Studios, published by Eidos Interactive (2000)",
                    color = IgiText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            TerminalPanel {
                SectionLabel("LEGAL")
                Spacer(Modifier.height(6.dp))
                Text(Legal.TEXT, color = IgiText, fontSize = 12.sp, lineHeight = 18.sp)
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextLink("PLAYIGI.COM") { onOpenUrl("https://playigi.com") }
                TextLink("MISSIONS") { onOpenUrl("https://playigi.com/missions.html") }
                TextLink("DISCORD") { onOpenUrl("https://discord.gg/YjWF4h7t7v") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun DisclaimerDialog(onAccept: () -> Unit, onExit: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        containerColor = IgiSurface,
        titleContentColor = IgiGold,
        textContentColor = IgiText,
        title = {
            Text(
                "UNOFFICIAL COMPANION",
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                letterSpacing = 1.sp
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(Legal.TEXT, fontSize = 12.sp, lineHeight = 18.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("I UNDERSTAND", color = IgiGreen, fontFamily = FontFamily.Monospace)
            }
        },
        dismissButton = {
            TextButton(onClick = onExit) {
                Text("EXIT", color = IgiDim, fontFamily = FontFamily.Monospace)
            }
        }
    )
}
