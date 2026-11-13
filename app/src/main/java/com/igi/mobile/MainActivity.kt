package com.igi.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.igi.mobile.data.GameRepository
import com.igi.mobile.ui.AboutScreen
import com.igi.mobile.ui.DisclaimerDialog
import com.igi.mobile.ui.HomeScreen
import com.igi.mobile.ui.IgiBlack
import com.igi.mobile.ui.IgiTheme
import com.igi.mobile.ui.MissionsScreen
import com.igi.mobile.ui.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IgiTheme {
                IgiApp(GameRepository.get(this))
            }
        }
    }
}

private enum class Screen { HOME, MISSIONS, SETTINGS, ABOUT }

@Composable
private fun IgiApp(repository: GameRepository) {
    val state by repository.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screen by rememberSaveable { mutableStateOf(Screen.HOME.name) }

    val allBytes = remember(state.index, state.hdPack, state.flacPack, state.touchControls) {
        repository.allInstallBytes()
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startDownload(missions: Set<Int>, allMissions: Boolean) {
        requestNotificationPermission()
        repository.startDownload(missions, allMissions)
    }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    BackHandler(enabled = screen != Screen.HOME.name) { screen = Screen.HOME.name }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            repository.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize().background(IgiBlack).safeDrawingPadding()) {
        when (screen) {
            Screen.MISSIONS.name -> MissionsScreen(
                state = state,
                allBytes = allBytes,
                missionBytes = { repository.missionBytes(it) },
                bytesFor = { repository.bytesFor(it) },
                onBack = { screen = Screen.HOME.name },
                onDownload = { missions, all -> startDownload(missions, all) },
                onCancel = { repository.cancelDownload() }
            )

            Screen.SETTINGS.name -> SettingsScreen(
                state = state,
                onBack = { screen = Screen.HOME.name },
                onHdPack = { repository.setHdPack(it) },
                onFlacPack = { repository.setFlacPack(it) },
                onTouchControls = { repository.setTouchControls(it) },
                onWifiOnly = { repository.setWifiOnly(it) },
                onClearCache = { repository.clearCache() },
                onRefreshIndex = { repository.refreshIndex() }
            )

            Screen.ABOUT.name -> AboutScreen(
                version = BuildConfig.VERSION_NAME,
                onBack = { screen = Screen.HOME.name },
                onOpenUrl = ::openUrl
            )

            else -> HomeScreen(
                state = state,
                allBytes = allBytes,
                onPlay = {
                    scope.launch {
                        if (repository.ensureShell()) {
                            context.startActivity(Intent(context, GameActivity::class.java))
                        }
                    }
                },
                onMissions = { screen = Screen.MISSIONS.name },
                onSettings = { screen = Screen.SETTINGS.name },
                onAbout = { screen = Screen.ABOUT.name }
            )
        }
    }

    if (!state.accepted) {
        DisclaimerDialog(
            onAccept = { repository.acceptDisclaimer() },
            onExit = { (context as? ComponentActivity)?.finishAffinity() }
        )
    }
}
