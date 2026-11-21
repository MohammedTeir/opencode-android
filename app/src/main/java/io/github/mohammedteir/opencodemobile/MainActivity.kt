package io.github.mohammedteir.opencodemobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var setup: SetupManager
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var progress: ProgressBar
    private lateinit var rootfsButton: Button
    private lateinit var smokeButton: Button
    private lateinit var installButton: Button
    private lateinit var updateButton: Button
    private lateinit var serverButton: Button
    private lateinit var content: LinearLayout
    private var webView: WebView? = null
    private var fileChooser: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setup = SetupManager(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val web = webView
                when {
                    web != null && web.canGoBack() -> web.goBack()
                    web != null -> showSetupAgain()
                    else -> finish()
                }
            }
        })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        buildSetupScreen()
        refreshButtons()
    }

    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                maxOf(safeInsets.left, imeInsets.left),
                safeInsets.top,
                maxOf(safeInsets.right, imeInsets.right),
                maxOf(safeInsets.bottom, imeInsets.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun buildSetupScreen() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101010"))
        }

        val pageScroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#101010"))
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(28))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val wordmark = ImageView(this).apply {
            setImageResource(R.drawable.opencode_wordmark_dark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "OpenCode"
        }
        header.addView(wordmark, LinearLayout.LayoutParams(dp(142), dp(28)))
        val headerSpacer = View(this)
        header.addView(headerSpacer, LinearLayout.LayoutParams(0, 1, 1f))
        val platformLabel = TextView(this).apply {
            text = "ANDROID  •  ARM64"
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#A9A2A2"))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded("#211E1E", 30)
        }
        header.addView(platformLabel)
        page.addView(header)

        val hero = card("#1B1919", 22).apply {
            setPadding(dp(20), dp(22), dp(20), dp(20))
        }
        hero.addView(text("LOCAL RUNTIME  /  PRIVATE WORKSPACE", 10f, "#B7B1B1", true).apply {
            letterSpacing = 0.10f
        })
        hero.addView(text("OpenCode,\nready when you are.", 29f, "#F1ECEC", true).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(0, dp(13), 0, dp(9))
        })
        hero.addView(text("Set up a lightweight Debian environment, install the OpenCode runtime, then connect to your local workspace.", 14f, "#A9A2A2", false).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        status = text("Preparing your environment…", 12f, "#D8D2D2", true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded("#292525", 12)
        }
        hero.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#B7B1B1"))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#454040"))
        }
        hero.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply {
            topMargin = dp(14)
        })
        page.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(26)
        })

        page.addView(sectionHeading("GET STARTED", "A one-time setup, in four steps."), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(26) })

        rootfsButton = button("Install Debian environment") { runInstallRootfs() }
        smokeButton = button("Run environment check", false) { runSmokeTest() }
        installButton = button("Install OpenCode + packages") { runInstallOpenCode() }
        serverButton = button("Start local workspace") { startServerAndOpen() }

        addStepCard(page, "01", "Debian environment", "Download and prepare the ARM64 Linux root filesystem.", rootfsButton)
        addStepCard(page, "02", "Runtime check", "Verify that PRoot can execute inside the new environment.", smokeButton)
        addStepCard(page, "03", "OpenCode runtime", "Install the pinned OpenCode version and required packages.", installButton)
        addStepCard(page, "04", "Launch workspace", "Start the authenticated local server and open its interface.", serverButton)

        val toolsCard = card("#1B1919", 18)
        toolsCard.addView(text("MAINTENANCE", 10f, "#B7B1B1", true).apply { letterSpacing = 0.10f })
        toolsCard.addView(text("Keep your runtime current", 17f, "#F1ECEC", true).apply {
            setPadding(0, dp(7), 0, dp(4))
        })
        toolsCard.addView(text("Check the stable release channel configured for this app.", 13f, "#A9A2A2", false))
        updateButton = button("Check for updates", false) { runCheckForUpdates() }
        toolsCard.addView(updateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(14)
        })
        val batteryButton = button("Battery optimization settings", false) { requestBatteryExemption() }
        toolsCard.addView(batteryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
            topMargin = dp(8)
        })
        page.addView(toolsCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val privacyNote = text("Your workspace is served locally on this device at 127.0.0.1. Android storage is not automatically mounted into the Linux environment. Keep the foreground service notification visible while the server runs.", 12f, "#827B7B", false)
        privacyNote.setPadding(dp(2), dp(18), dp(2), dp(10))
        page.addView(privacyNote)

        page.addView(text("ACTIVITY", 10f, "#B7B1B1", true).apply {
            letterSpacing = 0.10f
            setPadding(dp(2), dp(12), 0, dp(9))
        })
        logs = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(Color.parseColor("#C7C0C0"))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setTextIsSelectable(true)
            text = "Setup output will appear here.\n"
            background = rounded("#171515", 14, "#302B2B")
        }
        val logScroll = ScrollView(this).apply {
            isFillViewport = false
            addView(logs)
            background = rounded("#171515", 14, "#302B2B")
        }
        page.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(156)))

        pageScroll.addView(page)
        content.addView(pageScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(content)
        applySystemBarInsets(content)
        appendLog("Ready. Install the Debian environment, then run the runtime check.\n")
    }

    private fun sectionHeading(kicker: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(kicker, 10f, "#B7B1B1", true).apply { letterSpacing = 0.10f })
            addView(text(subtitle, 14f, "#8F8888", false).apply { setPadding(0, dp(5), 0, 0) })
        }
    }

    private fun addStepCard(parent: LinearLayout, number: String, title: String, description: String, action: Button) {
        val container = card("#1B1919", 18).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val index = TextView(this).apply {
            text = number
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#E7E1E1"))
            background = rounded("#393333", 12)
        }
        header.addView(index, LinearLayout.LayoutParams(dp(38), dp(38)))
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(text(title, 15f, "#F1ECEC", true))
            addView(text(description, 12f, "#968F8F", false).apply {
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        header.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(header)
        container.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(15)
        })
        parent.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
    }

    private fun text(value: String, size: Float, color: String, medium: Boolean): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.parseColor(color))
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }
    }

    private fun card(color: String, radius: Int, stroke: String? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color, radius, stroke)
        }
    }

    private fun rounded(color: String, radius: Int, stroke: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
        }
    }

    private fun button(label: String, primary: Boolean = true, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(Color.parseColor(if (primary) "#211E1E" else "#E7E1E1"))
        background = rounded(if (primary) "#D8D2D2" else "#302B2B", 14, if (primary) null else "#494242")
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun refreshButtons() {
        rootfsButton.isEnabled = !setup.hasRootfs()
        smokeButton.isEnabled = setup.hasRootfs()
        installButton.isEnabled = setup.hasRootfs() && !setup.isReady()
        serverButton.isEnabled = setup.isReady()
        updateButton.isEnabled = setup.isReady()
        if (setup.isReady()) status.text = "OpenCode V${setup.installedVersion()} installed · ready to start"
        else if (setup.hasRootfs() && setup.hasOpenCodeBinary()) status.text = "Existing OpenCode install · version metadata missing"
        else if (setup.hasRootfs()) status.text = "Debian installed · run the runtime check"
        else status.text = "Ready to prepare your local environment"
    }

    private fun runInstallRootfs() {
        setBusy(true, "Installing Debian environment…")
        lifecycleScope.launch {
            try {
                setup.installRootfs(::appendLog) { done, total -> showProgress(done, total, "Debian") }
                appendLog("Debian environment ready. Run the runtime check next.\n")
            } catch (t: Throwable) {
                appendLog("Rootfs setup failed: ${t.message}\n")
                status.text = "Setup failed — check activity and retry"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun runSmokeTest() {
        setBusy(true, "Checking the Linux runtime…")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val p = ProotRunner.start(this@MainActivity, listOf("/bin/echo", "hi"))
                    val output = p.inputStream.bufferedReader().readText().trim()
                    val code = p.waitFor()
                    check(code == 0 && output.lineSequence().any { it == "hi" }) { "Expected `hi`, got exit $code: $output" }
                    output
                }
                appendLog("Runtime check passed: PRoot printed $result.\n")
                status.text = "Runtime check passed · install OpenCode next"
            } catch (t: Throwable) {
                appendLog("Runtime check failed: ${t.message}\n")
                status.text = "Runtime check failed — inspect activity"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun runInstallOpenCode() {
        setBusy(true, "Installing OpenCode and packages…")
        lifecycleScope.launch {
            try {
                setup.installOpenCodeAndPackages(::appendLog) { done, total -> showProgress(done, total, "OpenCode") }
                appendLog("OpenCode V${setup.installedVersion()} installed. Start the local workspace when ready.\n")
            } catch (t: Throwable) {
                appendLog("OpenCode setup failed: ${t.message}\n")
                status.text = "Setup failed — check activity and retry"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun runCheckForUpdates() {
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop the OpenCode server before checking for updates", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "Checking for stable OpenCode updates…")
        lifecycleScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { setup.checkForUpdate() }
                val installed = setup.installedVersion() ?: "unknown"
                if (!setup.isNewerThanInstalled(update)) {
                    status.text = "OpenCode V$installed is up to date"
                    appendLog("No newer stable OpenCode ARM64 release found. Latest checked: V${update.displayVersion}.\n")
                } else {
                    appendLog("OpenCode V${update.displayVersion} is available (installed: V$installed).\n")
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("OpenCode update available")
                        .setMessage("Install OpenCode V${update.displayVersion}? The current binary will be backed up before replacement.")
                        .setNegativeButton("Not now", null)
                        .setPositiveButton("Install update") { _, _ -> runInstallUpdate(update) }
                        .show()
                }
            } catch (t: Throwable) {
                appendLog("Update check failed: ${t.message}\n")
                status.text = "Update check failed"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun runInstallUpdate(update: SetupManager.OpenCodeUpdate) {
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop the OpenCode server before installing an update", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "Installing OpenCode V${update.displayVersion}…")
        lifecycleScope.launch {
            try {
                setup.installOpenCodeUpdate(update, ::appendLog) { done, total -> showProgress(done, total, "OpenCode update") }
                status.text = "OpenCode V${update.displayVersion} installed · ready to start"
            } catch (t: Throwable) {
                appendLog("OpenCode update failed: ${t.message}\n")
                status.text = "Update failed; previous binary retained"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery optimization is already disabled for this app", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startServerAndOpen() {
        status.text = "Starting OpenCode server…"
        appendLog("Starting OpenCode on 127.0.0.1:4096…\n")
        val service = Intent(this, OpenCodeService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) {
                val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
                val auth = Credentials.basic("opencode", SecretStore.getOrCreate(this@MainActivity))
                val until = System.currentTimeMillis() + 60_000
                while (System.currentTimeMillis() < until) {
                    try {
                        val request = Request.Builder()
                            .url(SERVER_URL + "/api/info")
                            .header("Authorization", auth)
                            .header("Accept", "application/json")
                            .build()
                        client.newCall(request).execute().use { response ->
                            val contentType = response.header("Content-Type").orEmpty()
                            val json = response.body?.string().orEmpty()
                            if (response.isSuccessful && contentType.contains("application/json", ignoreCase = true) && json.isNotBlank()) {
                                val info = org.json.JSONObject(json)
                                if (info.optString("version") == setup.installedVersion() && info.optLong("pid") > 0L) {
                                    return@withContext true
                                }
                            }
                        }
                    } catch (_: Exception) { }
                    kotlinx.coroutines.delay(500)
                }
                false
            }
            if (ready) {
                if (setup.deletePreviousBinary()) appendLog("Verified updated OpenCode server; previous binary removed.\n")
                status.text = "Server ready · authenticated workspace"
                showWebView()
            } else {
                status.text = "Server did not become ready in 60 seconds"
                appendLog("Authenticated /api/info did not report the installed OpenCode version. Check logs and confirm the server starts under PRoot.\n")
                Toast.makeText(this@MainActivity, "Server startup timed out", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showWebView() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#101010"))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded("#171515", 0)
        }
        val back = button("Back", false) { showSetupAgain() }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(76), dp(44)))
        val pageTitle = text("OpenCode workspace", 14f, "#F1ECEC", true)
        toolbar.addView(pageTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(12)
        })
        val reload = button("Reload", false) { webView?.reload() }
        toolbar.addView(reload, LinearLayout.LayoutParams(dp(82), dp(44)))
        content.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))

        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                    if (host == "127.0.0.1") handler.proceed("opencode", SecretStore.getOrCreate(this@MainActivity))
                    else handler.cancel()
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val allowedOrigin = uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 4096
                    return !allowedOrigin
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileChooser?.onReceiveValue(null)
                    fileChooser = callback
                    return try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(params.createIntent(), REQUEST_FILE)
                        true
                    } catch (_: Exception) {
                        fileChooser = null
                        callback.onReceiveValue(null)
                        false
                    }
                }
            }
        }
        webView = web
        content.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(content)
        applySystemBarInsets(content)
        web.loadUrl(SERVER_URL)
    }

    private fun showSetupAgain() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        buildSetupScreen()
        refreshButtons()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FILE) {
            val result = if (resultCode == Activity.RESULT_OK) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            fileChooser?.onReceiveValue(result)
            fileChooser = null
        }
    }

    private fun setBusy(busy: Boolean, label: String? = null) {
        label?.let { status.text = it }
        rootfsButton.isEnabled = !busy && !setup.hasRootfs()
        smokeButton.isEnabled = !busy && setup.hasRootfs()
        installButton.isEnabled = !busy && setup.hasRootfs() && !setup.isReady()
        serverButton.isEnabled = !busy && setup.isReady()
        updateButton.isEnabled = !busy && setup.isReady()
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showProgress(done: Long, total: Long, name: String) {
        runOnUiThread {
            if (total > 0) {
                progress.progress = ((done * 1000L / total).coerceIn(0, 1000)).toInt()
                if (done == total || done % (8L * 1024 * 1024) < 64 * 1024) {
                    status.text = "$name download: ${done / (1024 * 1024)} / ${total / (1024 * 1024)} MB"
                }
            } else status.text = "$name download: ${done / (1024 * 1024)} MB"
        }
    }

    private fun appendLog(message: String) = runOnUiThread {
        logs.append(message)
        val parent = logs.parent as? ScrollView
        parent?.post { parent.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SERVER_URL = "http://127.0.0.1:4096"
        private const val REQUEST_FILE = 45
        private const val REQUEST_NOTIFICATIONS = 46
    }
}
