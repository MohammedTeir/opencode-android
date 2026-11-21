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
import android.os.Environment
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
import android.widget.CheckBox
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
import java.io.File
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
    private lateinit var rootAccessButton: Button
    private lateinit var rootDiagnosticsButton: Button
    private lateinit var rootHardLinkButton: Button
    private lateinit var rootStatus: TextView
    private lateinit var rootHardLinkSwitch: CheckBox
    private lateinit var storageStatus: TextView
    private lateinit var storageButton: Button
    private lateinit var serverButton: Button
    private lateinit var activityPanel: ScrollView
    private lateinit var activityToggle: TextView
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

    override fun onResume() {
        super.onResume()
        if (::storageStatus.isInitialized) refreshStorageAccessUi()
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
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }

        // Compact app bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val wordmark = ImageView(this).apply {
            setImageResource(R.drawable.opencode_wordmark_dark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "OpenCode"
        }
        header.addView(wordmark, LinearLayout.LayoutParams(dp(126), dp(25)))
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val platformLabel = TextView(this).apply {
            text = "ANDROID · ARM64"
            textSize = 9f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#B7B1B1"))
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded("#211E1E", 30)
        }
        header.addView(platformLabel)
        page.addView(header)

        // Status card: keep the most important state visible without a large hero section.
        val hero = card("#1B1919", 18).apply {
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        hero.addView(text("YOUR LOCAL WORKSPACE", 9f, "#A9A2A2", true).apply {
            letterSpacing = 0.10f
        })
        hero.addView(text("OpenCode on this device", 20f, "#F1ECEC", true).apply {
            setPadding(0, dp(4), 0, dp(9))
        })
        status = text("Preparing your environment…", 12f, "#D8D2D2", true).apply {
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded("#292525", 10)
        }
        hero.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D8D2D2"))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#454040"))
        }
        hero.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply {
            topMargin = dp(10)
        })
        page.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        val setupHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(text("SETUP", 10f, "#B7B1B1", true).apply { letterSpacing = 0.10f })
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(text("Complete in order", 11f, "#827B7B", false))
        }
        page.addView(setupHeading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
            bottomMargin = dp(8)
        })

        val stepsCard = card("#1B1919", 16, "#302B2B").apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        rootfsButton = compactButton("Install") { runInstallRootfs() }
        smokeButton = compactButton("Check", false) { runSmokeTest() }
        installButton = compactButton("Install") { runInstallOpenCode() }
        serverButton = compactButton("Launch") { startServerAndOpen() }

        addStepRow(stepsCard, "1", "Debian environment", rootfsButton)
        addStepDivider(stepsCard)
        addStepRow(stepsCard, "2", "Runtime check", smokeButton)
        addStepDivider(stepsCard)
        addStepRow(stepsCard, "3", "OpenCode runtime", installButton)
        addStepDivider(stepsCard)
        addStepRow(stepsCard, "4", "Start workspace", serverButton)
        page.addView(stepsCard)

        val maintenance = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        updateButton = compactButton("Check updates", false) { runCheckForUpdates() }
        val batteryButton = compactButton("Battery settings", false) { requestBatteryExemption() }
        maintenance.addView(updateButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginEnd = dp(6)
        })
        maintenance.addView(batteryButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(6)
        })
        page.addView(maintenance)

        val storageCard = card("#1B1919", 14, "#302B2B").apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        storageCard.addView(text("OPTIONAL ANDROID STORAGE", 10f, "#D8D2D2", true).apply {
            letterSpacing = 0.08f
        })
        storageCard.addView(text(
            "Allow Debian and OpenCode to work with files in shared storage, such as Downloads, Documents, and project folders. Android's system permission is required.",
            11f, "#A9A2A2", false
        ).apply {
            setPadding(0, dp(5), 0, dp(7))
        })
        storageStatus = text("Checking shared-storage access…", 11f, "#A9A2A2", true).apply {
            setPadding(0, dp(4), 0, dp(9))
        }
        storageCard.addView(storageStatus)
        storageButton = compactButton("Grant access", false) { requestSharedStorageAccess() }
        storageCard.addView(storageButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        page.addView(storageCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        refreshStorageAccessUi()

        val rootCard = card("#1B1919", 14, "#302B2B").apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        rootCard.addView(text("OPTIONAL ROOT SUPPORT", 10f, "#D8D2D2", true).apply {
            letterSpacing = 0.08f
        })
        rootCard.addView(text(
            "Root is never required. Check availability only when you choose; Android will ask your root manager for authorization.",
            11f, "#A9A2A2", false
        ).apply {
            setPadding(0, dp(5), 0, dp(9))
        })
        rootStatus = text("Root status has not been checked.", 11f, "#A9A2A2", true).apply {
            setPadding(0, dp(7), 0, dp(9))
        }
        rootCard.addView(rootStatus)
        rootAccessButton = compactButton("Check root", false) { requestRootAccessCheck() }
        rootDiagnosticsButton = compactButton("Diagnostics", false) { requestRootDiagnostics() }
        rootHardLinkButton = compactButton("Test hard links", false) { requestRootHardLinkTest() }
        rootCard.addView(rootAccessButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        val rootTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        rootTools.addView(rootDiagnosticsButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        rootTools.addView(rootHardLinkButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
        rootCard.addView(rootTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            topMargin = dp(7)
        })
        rootHardLinkSwitch = CheckBox(this).apply {
            text = "Use root-assisted hard links during Debian installation"
            textSize = 11f
            setTextColor(Color.parseColor("#D8D2D2"))
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#D8D2D2"))
            isEnabled = RootAccessManager.isRootHardLinksVerified(this@MainActivity)
            isChecked = RootAccessManager.isRootHardLinksEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                RootAccessManager.setRootHardLinksEnabled(this@MainActivity, checked)
                appendLog(if (checked) "Root-assisted Debian hard links enabled by user.\n" else "Root-assisted Debian hard links disabled by user.\n")
            }
        }
        rootCard.addView(rootHardLinkSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        page.addView(rootCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        page.addView(text("Runs locally at 127.0.0.1 · shared storage is optional and permission-gated.", 10f, "#777070", false).apply {
            setPadding(dp(2), dp(5), dp(2), dp(10))
        })

        // Logs are available on demand and automatically open when an operation emits output.
        val activityHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(8), dp(2), dp(8))
            isClickable = true
            isFocusable = true
        }
        activityHeader.addView(text("ACTIVITY LOG", 10f, "#B7B1B1", true).apply {
            letterSpacing = 0.10f
        })
        activityHeader.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        activityToggle = text("Show  ↓", 11f, "#A9A2A2", true)
        activityHeader.addView(activityToggle)
        activityPanel = ScrollView(this).apply {
            isFillViewport = false
            visibility = View.GONE
            background = rounded("#171515", 12, "#302B2B")
        }
        logs = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            setTextColor(Color.parseColor("#C7C0C0"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
            text = RootOperationLog.read(this@MainActivity).ifBlank { "No activity yet." }
        }
        activityPanel.addView(logs)
        activityHeader.setOnClickListener {
            val expanded = activityPanel.visibility != View.VISIBLE
            activityPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            activityToggle.text = if (expanded) "Hide  ↑" else "Show  ↓"
        }
        page.addView(activityHeader)
        page.addView(activityPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(132)))

        pageScroll.addView(page)
        content.addView(pageScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(content)
        applySystemBarInsets(content)
    }

    private fun compactButton(label: String, primary: Boolean = true, action: () -> Unit): Button {
        return button(label, primary, action).apply {
            textSize = 12f
            minHeight = dp(40)
            minimumHeight = dp(40)
            setPadding(dp(9), 0, dp(9), 0)
        }
    }

    private fun addStepRow(parent: LinearLayout, number: String, title: String, action: Button) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        val index = TextView(this).apply {
            text = number
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#E7E1E1"))
            background = rounded("#393333", 10)
        }
        row.addView(index, LinearLayout.LayoutParams(dp(30), dp(30)))
        row.addView(text(title, 13f, "#F1ECEC", true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(11)
            marginEnd = dp(8)
        })
        row.addView(action, LinearLayout.LayoutParams(dp(94), dp(40)))
        parent.addView(row)
    }

    private fun addStepDivider(parent: LinearLayout) {
        parent.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#302B2B"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
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

    private fun requestRootAccessCheck() {
        runRootOperation("Check root access") {
            RootAccessManager.checkAccess()
        }
    }

    private fun requestRootDiagnostics() {
        confirmRootOperation(
            "Run root diagnostics?",
            "This performs read-only checks of UID, SELinux status, app-private directory permissions, and filesystem capacity. It does not change system settings."
        ) {
            runRootOperation("Root diagnostics") {
                RootAccessManager.runDiagnostics(cacheDir, File(filesDir, "rootfs"))
            }
        }
    }

    private fun requestRootHardLinkTest() {
        confirmRootOperation(
            "Test native hard links?",
            "This creates two temporary files inside this app's private cache directory, verifies whether they share an inode, and removes the probe files. It does not change Debian or system files."
        ) {
            runRootOperation("Root hard-link test") {
                RootAccessManager.testHardLinks(cacheDir)
            }
        }
    }

    private fun confirmRootOperation(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> action() }
            .show()
    }

    private fun runRootOperation(label: String, operation: suspend () -> RootAccessManager.OperationResult) {
        setBusy(true, "$label…")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                rootStatus.text = result.message
                rootStatus.setTextColor(Color.parseColor(if (result.success) "#A8D5B5" else "#E3B0A8"))
                if (label == "Root hard-link test") {
                    RootAccessManager.setRootHardLinksVerified(this@MainActivity, result.success)
                    rootHardLinkSwitch.isEnabled = result.success
                    rootHardLinkSwitch.isChecked = result.success && RootAccessManager.isRootHardLinksEnabled(this@MainActivity)
                }
                RootOperationLog.append(this@MainActivity, result.title, result.success, result.message + "\n" + result.output)
                appendLog("[$label] ${result.message}\n")
                if (result.output.isNotBlank()) appendLog(result.output + "\n")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(result.title)
                    .setMessage(result.message + if (result.output.isNotBlank()) "\n\n" + result.output.take(6_000) else "")
                    .setPositiveButton("OK", null)
                    .show()
            } catch (error: Exception) {
                val message = error.message ?: "Unexpected root operation error."
                rootStatus.text = message
                rootStatus.setTextColor(Color.parseColor("#E3B0A8"))
                RootOperationLog.append(this@MainActivity, label, false, message)
                appendLog("[$label] failed: $message\n")
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun refreshStorageAccessUi() {
        if (!StorageAccessManager.isSupported()) {
            storageStatus.text = "Direct shared-storage mounting requires Android 11 or newer."
            storageButton.text = "Unavailable"
            storageButton.isEnabled = false
            return
        }
        storageButton.isEnabled = true
        if (StorageAccessManager.hasAccess(this)) {
            storageStatus.text = "Enabled · Debian paths: /sdcard and /storage/emulated/0"
            storageButton.text = "Manage access"
        } else {
            storageStatus.text = "Disabled · Debian cannot access Android shared storage."
            storageButton.text = "Grant access"
        }
    }

    private fun requestSharedStorageAccess() {
        if (!StorageAccessManager.isSupported()) {
            Toast.makeText(this, "Direct shared-storage access requires Android 11 or newer", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Allow shared-storage access?")
            .setMessage(
                "This optional permission lets the Debian environment and OpenCode read and write files in shared storage, including Downloads, Documents, and project folders. " +
                    "It does not grant access to other apps' private data, and Android still restricts Android/data and Android/obb. " +
                    "You can revoke this permission at any time in Android settings. OpenCode will still ask for its own external_directory permission when required."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (error: Exception) {
                        Toast.makeText(this, "Could not open Android storage settings: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
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
        rootAccessButton.isEnabled = !busy
        rootDiagnosticsButton.isEnabled = !busy
        rootHardLinkButton.isEnabled = !busy
        rootHardLinkSwitch.isEnabled = !busy && RootAccessManager.isRootHardLinksVerified(this)
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
        activityPanel.visibility = View.VISIBLE
        activityToggle.text = "Hide  ↑"
        activityPanel.post { activityPanel.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SERVER_URL = "http://127.0.0.1:4096"
        private const val REQUEST_FILE = 45
        private const val REQUEST_NOTIFICATIONS = 46
    }
}
