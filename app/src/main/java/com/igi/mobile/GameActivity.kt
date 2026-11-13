package com.igi.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.igi.mobile.data.GamePaths
import com.igi.mobile.data.GameRepository
import com.igi.mobile.data.Net
import com.igi.mobile.web.GameWebViewClient
import java.io.File

class GameActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var overlay: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button

    private var lastBackPress = 0L
    private var mainFrameFailed = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * The co-op pairing screen scans the host's QR code with getUserMedia(). WebView routes that
     * through onPermissionRequest; hold the request while the Android runtime prompt is shown.
     */
    private var pendingCameraRequest: PermissionRequest? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingCameraRequest
        pendingCameraRequest = null
        if (request == null) return@registerForActivityResult
        if (granted) {
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        } else {
            request.deny()
            Toast.makeText(
                this,
                "Camera permission is needed to scan pairing QR codes.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(webView)

        overlay = buildOverlay()
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)
        configureWebView()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPress < 2200L) {
                    finish()
                    return
                }
                lastBackPress = now
                sendEscapeToGame()
                Toast.makeText(this@GameActivity, "Press back again to leave the game", Toast.LENGTH_SHORT).show()
            }
        })

        val touch = GameRepository.get(this).state.value.touchControls
        val startUrl = GamePaths.missionGameUrl(touch)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(startUrl)
        }
    }

    private fun buildOverlay(): FrameLayout {
        val box = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
        }
        statusText = TextView(this).apply {
            text = "LOADING I.G.I."
            setTextColor(Color.parseColor("#C8D2B4"))
            textSize = 16f
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 12)
        }
        retryButton = Button(this).apply {
            text = "RETRY"
            visibility = View.GONE
            setOnClickListener {
                showLoading()
                webView.reload()
            }
        }
        column.addView(progressBar)
        column.addView(statusText)
        column.addView(retryButton)
        box.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        return box
    }

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            textZoom = 100
            userAgentString = Net.USER_AGENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = false
            }
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2) {
                @Suppress("DEPRECATION")
                forceDark = WebSettings.FORCE_DARK_OFF
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val repository = GameRepository.get(this)
        webView.webViewClient = object : WebViewClient() {
            private val local = GameWebViewClient(
                shellDir = GamePaths.shellDir(this@GameActivity),
                dataDir = GamePaths.dataDir(this@GameActivity)
            ) { uri -> openExternal(uri) }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ) = local.shouldInterceptRequest(view, request)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ) = local.shouldOverrideUrlLoading(view, request)

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                mainFrameFailed = false
                // Belt and braces: the injected boot script already blocks registration.
                view.evaluateJavascript(
                    "(function(){try{if(navigator.serviceWorker&&navigator.serviceWorker.getRegistrations){navigator.serviceWorker.getRegistrations().then(function(rs){rs.forEach(function(r){r.unregister()})})}}catch(e){}})()",
                    null
                )
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (!mainFrameFailed) overlay.visibility = View.GONE
                // Pairing QR scanning depends on these two browser capabilities.
                view.evaluateJavascript(
                    "(function(){try{return (typeof window.BarcodeDetector)+'|'+" +
                        "(!!(navigator.mediaDevices&&navigator.mediaDevices.getUserMedia))}catch(e){return 'error'}})()"
                ) { value -> Log.i("IgiGame", "WebView capabilities barcodeDetector|getUserMedia = $value") }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    mainFrameFailed = true
                    showError("Could not load the game. ${error.description}")
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                Toast.makeText(
                    this@GameActivity,
                    "The game renderer stopped. Reopen the app to continue.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true

            override fun onPermissionRequest(request: PermissionRequest) {
                val trusted = request.origin.host.equals("playigi.com", ignoreCase = true)
                val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!trusted || !wantsCamera) {
                    request.deny()
                    return
                }
                val granted = ContextCompat.checkSelfPermission(
                    this@GameActivity,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingCameraRequest?.deny()
                    pendingCameraRequest = request
                    cameraPermission.launch(Manifest.permission.CAMERA)
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingCameraRequest === request) pendingCameraRequest = null
            }
        }
        webView.setOnLongClickListener { true }

        if (repository.state.value.installedMissions.isEmpty() && !Net.isOnline(this)) {
            showError("No offline game data yet. Connect to the internet once, then download data from the app.")
        }
    }

    private fun showLoading() {
        overlay.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        statusText.text = "LOADING I.G.I."
    }

    private fun showError(message: String) {
        overlay.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
        statusText.text = message
    }

    private fun sendEscapeToGame() {
        val js = "(function(){function k(t){try{document.dispatchEvent(new KeyboardEvent(t,{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true,cancelable:true}))}catch(e){}}k('keydown');setTimeout(function(){k('keyup')},40)})()"
        webView.evaluateJavascript(js, null)
    }

    private fun openExternal(uri: Uri) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun requestAudioFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        webView.onResume()
        webView.resumeTimers()
        requestAudioFocus()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        abandonAudioFocus()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        abandonAudioFocus()
        runCatching {
            webView.loadUrl("about:blank")
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
