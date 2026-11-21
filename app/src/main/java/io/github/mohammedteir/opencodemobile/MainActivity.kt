package io.github.mohammedteir.opencodemobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
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
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
        buildSetupScreen()
        refreshButtons()
    }

    private fun buildSetupScreen() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        val title = TextView(this).apply {
            text = "OpenCode Mobile" + setup.installedVersion()?.let { " · V$it" }.orEmpty()
            textSize = 22f
            setTextColor(0xFF191919.toInt())
        }
        status = TextView(this).apply {
            text = "ARM64 only · OpenCode ${setup.installedVersion()?.let { "V$it" } ?: "not installed"}"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(10))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        rootfsButton = button("1. Install Debian rootfs") { runInstallRootfs() }
        smokeButton = button("2. Run M1 proot smoke test") { runSmokeTest() }
        installButton = button("3. Install OpenCode V2.3.2 + packages") { runInstallOpenCode() }
        serverButton = button("4. Start server and open WebView") { startServerAndOpen() }
        updateButton = button("5. Check for OpenCode updates") { runCheckForUpdates() }
        val warning = TextView(this).apply {
            text = "This app uses a Debian ARM64 rootfs and OpenCode. It does not bind Android storage. OpenCode actions use conservative prompts. Keep the server notification visible while it runs."
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        }
        logs = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFF222222.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(0xFFF3F3F6.toInt())
            text = "Setup output will appear here.\n"
        }
        val scroll = ScrollView(this).apply { addView(logs) }
        content.addView(title)
        content.addView(status)
        content.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        listOf(rootfsButton, smokeButton, installButton, serverButton, updateButton).forEach {
            content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(5) })
        }
        val batteryButton = button("5. Battery optimization settings") { requestBatteryExemption() }
        content.addView(batteryButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(4) })
        content.addView(warning)
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(content)
        appendLog("Ready. Install the Debian rootfs, then run M1 before continuing.\n")
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
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
        else if (setup.hasRootfs()) status.text = "Debian installed · run the M1 smoke test"
    }

    private fun runInstallRootfs() {
        setBusy(true, "Installing Debian rootfs…")
        lifecycleScope.launch {
            try {
                setup.installRootfs(::appendLog) { done, total -> showProgress(done, total, "Debian") }
                appendLog("M1 prerequisite ready. Press the smoke-test button.\n")
            } catch (t: Throwable) {
                appendLog("Rootfs setup failed: ${t.message}\n")
                status.text = "Setup failed — check output and retry"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun runSmokeTest() {
        setBusy(true, "Running M1 proot test…")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val p = ProotRunner.start(this@MainActivity, listOf("/bin/echo", "hi"))
                    val output = p.inputStream.bufferedReader().readText().trim()
                    val code = p.waitFor()
                    check(code == 0 && output.lineSequence().any { it == "hi" }) { "Expected `hi`, got exit $code: $output" }
                    output
                }
                appendLog("M1 PASS: proot printed $result (verify this on the target phone).\n")
                status.text = "M1 smoke test passed · install OpenCode next"
            } catch (t: Throwable) {
                appendLog("M1 FAILED: ${t.message}\n")
                status.text = "M1 failed — inspect native library packaging and PRoot log"
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
                appendLog("OpenCode V${setup.installedVersion()} install complete. Next start the local server.\n")
            } catch (t: Throwable) {
                appendLog("OpenCode setup failed: ${t.message}\n")
                status.text = "Setup failed — check output and retry"
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
        setBusy(true, "Checking GitHub for OpenCode updates…")
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
                status.text = "Server ready · authenticated WebView"
                showWebView()
            } else {
                status.text = "Server did not become ready in 60 seconds"
                appendLog("Authenticated /api/info did not report the installed OpenCode version. Check logs and confirm the server starts under PRoot.\n")
                Toast.makeText(this@MainActivity, "Server startup timed out", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showWebView() {
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
        content.removeAllViews()
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val back = Button(this).apply { text = "Back"; setOnClickListener { showSetupAgain() } }
        val reload = Button(this).apply { text = "Reload"; setOnClickListener { web.reload() } }
        bar.addView(back, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(reload, LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(bar)
        content.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
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

    override fun onBackPressed() {
        val web = webView
        if (web != null && web.canGoBack()) web.goBack() else if (web != null) showSetupAgain() else super.onBackPressed()
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
