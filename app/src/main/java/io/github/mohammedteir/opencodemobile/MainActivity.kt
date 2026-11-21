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
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var setup: SetupManager
    private lateinit var status: TextView
    private lateinit var logs: TextView
    private lateinit var progress: ProgressBar
    private lateinit var setupButton: Button
    private lateinit var smokeButton: Button
    private lateinit var updateButton: Button
    private lateinit var repairPackagesButton: Button
    private lateinit var repairOpenCodeButton: Button
    private lateinit var rollbackOpenCodeButton: Button
    private lateinit var rootAccessButton: Button
    private lateinit var rootDiagnosticsButton: Button
    private lateinit var rootHardLinkButton: Button
    private lateinit var rootStatus: TextView
    private lateinit var rootHardLinkSwitch: CheckBox
    private lateinit var storageStatus: TextView
    private lateinit var storageButton: Button
    private lateinit var workspacePathLabel: TextView
    private lateinit var workspaceChooseButton: Button
    private lateinit var storageTestButton: Button
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
        if (webView != null) setWorkspaceImmersive(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && webView != null) setWorkspaceImmersive(true)
    }

    private fun setWorkspaceImmersive(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
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
            addView(text("One-time setup", 11f, "#827B7B", false))
        }
        page.addView(setupHeading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18)
            bottomMargin = dp(8)
        })

        val stepsCard = card("#1B1919", 16, "#302B2B").apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        setupButton = compactButton("Set up") { runFullSetup() }
        smokeButton = compactButton("Check", false) { runSmokeTest() }
        serverButton = compactButton("Launch") { startServerAndOpen() }

        addStepRow(stepsCard, "1", "Debian + PRoot + OpenCode", setupButton)
        addStepDivider(stepsCard)
        addStepRow(stepsCard, "2", "Runtime diagnostics", smokeButton)
        addStepDivider(stepsCard)
        addStepRow(stepsCard, "3", "Start workspace", serverButton)
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
        repairPackagesButton = compactButton("Repair Linux tools", false) { confirmPackageRepair() }
        page.addView(repairPackagesButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
            topMargin = dp(6)
        })
        val openCodeRecovery = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        repairOpenCodeButton = compactButton("Repair OpenCode", false) { confirmOpenCodeRepair() }
        rollbackOpenCodeButton = compactButton("Restore previous", false) { confirmOpenCodeRollback() }
        openCodeRecovery.addView(repairOpenCodeButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginEnd = dp(6)
        })
        openCodeRecovery.addView(rollbackOpenCodeButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(6)
        })
        page.addView(openCodeRecovery)

        val storageCard = card("#1B1919", 16, "#302B2B").apply {
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        storageCard.addView(text("SHARED STORAGE", 10f, "#D8D2D2", true).apply { letterSpacing = 0.09f })
        storageCard.addView(text(
            "Work with projects and files stored on your phone, including Downloads and Documents.",
            11f, "#A9A2A2", false
        ).apply { setPadding(0, dp(5), 0, dp(10)) })
        val storageStateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            background = rounded("#292525", 10)
        }
        storageStateRow.addView(View(this).apply { background = rounded("#827B7B", 20) },
            LinearLayout.LayoutParams(dp(8), dp(8)))
        storageStatus = text("Checking access…", 11f, "#D8D2D2", true).apply { setPadding(dp(9), 0, 0, 0) }
        storageStateRow.addView(storageStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        storageCard.addView(storageStateRow)
        storageButton = compactButton("Allow shared storage", false) { requestSharedStorageAccess() }
        storageCard.addView(storageButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(9) })

        val workspacePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#171515", 12, "#302B2B")
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }
        workspacePanel.addView(text("PROJECT WORKSPACE", 9f, "#A9A2A2", true).apply { letterSpacing = 0.08f })
        workspacePathLabel = text("/root/projects", 12f, "#F1ECEC", true).apply { setPadding(0, dp(5), 0, dp(9)) }
        workspacePanel.addView(workspacePathLabel)
        workspaceChooseButton = compactButton("Choose project folder", false) { chooseWorkspaceDirectory() }
        workspacePanel.addView(workspaceChooseButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        storageCard.addView(workspacePanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        storageTestButton = compactButton("Test shared storage", false) { runSharedStorageTest() }
        val storageAdvancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(storageTestButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        }
        val storageAdvancedToggle = text("Advanced storage tools  ↓", 11f, "#A9A2A2", true).apply {
            setPadding(0, dp(12), 0, dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val expanded = storageAdvancedPanel.visibility != View.VISIBLE
                storageAdvancedPanel.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "Advanced storage tools  ↑" else "Advanced storage tools  ↓"
            }
        }
        storageCard.addView(storageAdvancedToggle)
        storageCard.addView(storageAdvancedPanel)
        page.addView(storageCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        refreshStorageAccessUi()

        val rootCard = card("#1B1919", 14, "#302B2B").apply {
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        rootCard.addView(text("OPTIONAL ROOT SUPPORT", 10f, "#D8D2D2", true).apply {
            letterSpacing = 0.08f
        })
        rootCard.addView(text(
            "Not required for normal operation. Enable only for optional filesystem capabilities.",
            11f, "#A9A2A2", false
        ).apply { setPadding(0, dp(5), 0, dp(7)) })
        rootStatus = text("Not checked · standard PRoot mode is available", 11f, "#A9A2A2", true).apply {
            setPadding(0, dp(7), 0, dp(9))
        }
        rootCard.addView(rootStatus)
        rootAccessButton = compactButton("Check root availability", false) { requestRootAccessCheck() }
        rootCard.addView(rootAccessButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)))
        rootDiagnosticsButton = compactButton("Run diagnostics", false) { requestRootDiagnostics() }
        rootHardLinkButton = compactButton("Test hard links", false) { requestRootHardLinkTest() }
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
        val rootAdvancedPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val rootTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rootTools.addView(rootDiagnosticsButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(4) })
        rootTools.addView(rootHardLinkButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(4) })
        rootAdvancedPanel.addView(rootTools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        rootAdvancedPanel.addView(rootHardLinkSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
        })
        val advancedToggle = text("Advanced tools  ↓", 11f, "#A9A2A2", true).apply {
            setPadding(0, dp(12), 0, dp(2))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val expanded = rootAdvancedPanel.visibility != View.VISIBLE
                rootAdvancedPanel.visibility = if (expanded) View.VISIBLE else View.GONE
                text = if (expanded) "Advanced tools  ↑" else "Advanced tools  ↓"
            }
        }
        rootCard.addView(advancedToggle)
        rootCard.addView(rootAdvancedPanel)
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
        setupButton.isEnabled = !setup.isReady()
        setupButton.text = if (setup.hasRootfs()) "Continue setup" else "Set up"
        smokeButton.isEnabled = setup.hasRootfs()
        serverButton.isEnabled = setup.isReady()
        updateButton.isEnabled = setup.isReady()
        repairPackagesButton.isEnabled = setup.hasRootfs() && !OpenCodeService.isRunning
        repairOpenCodeButton.isEnabled = setup.hasRootfs() && !OpenCodeService.isRunning
        rollbackOpenCodeButton.isEnabled = setup.hasPreviousOpenCode() && !OpenCodeService.isRunning
        if (setup.isReady()) status.text = "OpenCode V${setup.installedVersion()} installed · ready to start"
        else if (setup.hasRootfs() && setup.hasOpenCodeBinary()) status.text = "Existing OpenCode install · version metadata missing"
        else if (setup.hasRootfs()) status.text = "Debian installed · run the runtime check"
        else status.text = "Ready to prepare your local environment"
    }

    private fun runFullSetup() {
        if (setup.isReady()) {
            refreshButtons()
            return
        }
        setBusy(true, "Preparing your complete OpenCode environment…")
        lifecycleScope.launch {
            try {
                setup.installAll(
                    log = ::appendLog,
                    progress = { done, total -> showProgress(done, total, "Environment") },
                    stage = { label -> runOnUiThread { status.text = label } }
                )
                status.text = "OpenCode installed · ready to start"
                appendLog("Setup complete. No separate Termux, Debian, PRoot, or OpenCode installation is required.\n")
            } catch (t: Throwable) {
                appendLog("Setup stopped: ${t.message}\n")
                status.text = "Setup paused — fix the issue and tap Continue setup"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }
    private fun runSmokeTest() {
        setBusy(true, "Checking Linux runtime and network…")
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val script = """
                        set +e
                        echo "=== PRoot runtime ==="
                        echo "PRoot command execution: OK"
                        echo
                        echo "=== DNS resolution (system resolver; no provider allowlist) ==="
                        dns_failures=0
                        for host in generativelanguage.googleapis.com api.openai.com api.anthropic.com github.com deb.debian.org; do
                            if getent ahosts "${'$'}host" >/dev/null 2>&1; then
                                echo "DNS_OK ${'$'}host"
                            else
                                echo "DNS_FAIL ${'$'}host"
                                dns_failures=$((dns_failures + 1))
                            fi
                        done
                        echo
                        echo "=== HTTPS connectivity ==="
                        if command -v curl >/dev/null 2>&1; then
                            for url in https://generativelanguage.googleapis.com/ https://api.openai.com/ https://github.com/; do
                                result=$(curl -sS -o /dev/null -w "HTTP %{http_code}" --connect-timeout 8 --max-time 15 "${'$'}url" 2>&1)
                                rc=$?
                                if [ "${'$'}rc" -eq 0 ]; then
                                    echo "HTTPS_OK ${'$'}url ${'$'}result"
                                else
                                    echo "HTTPS_FAIL ${'$'}url (curl exit ${'$'}rc): ${'$'}result"
                                fi
                            done
                        else
                            echo "HTTPS_SKIPPED curl is not installed yet; rerun this check after package installation."
                        fi
                        echo
                        if [ "${'$'}dns_failures" -eq 0 ]; then
                            echo "DNS_SUMMARY all tested hostnames resolved"
                        else
                            echo "DNS_SUMMARY ${'$'}dns_failures hostname(s) failed; inspect DNS configuration and active Android network"
                        fi
                    """.trimIndent()
                    val inventory = RuntimeManager(this@MainActivity).inspect().report()
                    val p = ProotRunner.start(this@MainActivity, listOf("/bin/sh", "-c", script))
                    val output = p.inputStream.bufferedReader().readText().trim()
                    val code = p.waitFor()
                    check(code == 0) { "PRoot diagnostic command failed (exit $code): $output" }
                    "$inventory\n\n=== Network diagnostics ===\n$output"
                }
                appendLog("Runtime and network diagnostics completed.\n${result}\n")
                val runtimeNeedsAttention = result.contains("[MISSING]") ||
                    result.contains("[DEGRADED]") || result.contains("[INCOMPLETE]")
                val dnsFailed = result.contains("DNS_FAIL")
                val httpsFailed = result.contains("HTTPS_FAIL")
                status.text = when {
                    runtimeNeedsAttention -> "Runtime components need attention · inspect activity"
                    dnsFailed -> "PRoot works · DNS needs attention"
                    httpsFailed -> "PRoot and DNS work · HTTPS needs attention"
                    result.contains("HTTPS_SKIPPED") -> "PRoot works · DNS checked; HTTPS test unavailable"
                    else -> "PRoot and network checks passed"
                }
            } catch (t: Throwable) {
                appendLog("Runtime check failed: ${t.message}\n")
                status.text = "Runtime check failed — inspect activity"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun confirmPackageRepair() {
        if (!setup.hasRootfs()) {
            Toast.makeText(this, "Install Debian before repairing Linux tools", Toast.LENGTH_LONG).show()
            return
        }
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop the OpenCode server before repairing Linux tools", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Repair Debian command-line tools?")
            .setMessage(
                "This refreshes Debian package indexes and reinstalls only Git, ripgrep, curl, CA certificates, procps, OpenSSH client, and unzip. It does not reinstall Debian, replace OpenCode, or overwrite your configuration or projects. An internet connection is required."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Repair") { _, _ -> runPackageRepair() }
            .show()
    }

    private fun runPackageRepair() {
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop the OpenCode server before repairing Linux tools", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "Repairing Debian command-line tools…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { setup.repairRequiredPackages(::appendLog) }
                status.text = "Debian tools repaired · run diagnostics to verify"
                appendLog("Package-only repair completed. Run Runtime diagnostics to verify the installed components.\n")
            } catch (t: Throwable) {
                appendLog("Debian package repair failed: ${t.message}\n")
                status.text = "Package repair failed · inspect activity"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun confirmOpenCodeRepair() {
        if (!setup.hasRootfs()) {
            Toast.makeText(this, "Install Debian before repairing OpenCode", Toast.LENGTH_LONG).show()
            return
        }
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop the OpenCode server before repairing OpenCode", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Repair OpenCode?")
            .setMessage(
                "This downloads the latest stable ARM64 OpenCode release in the app's supported major-version channel, verifies its SHA-256 digest, and replaces only the OpenCode executable. Debian, your OpenCode configuration, and project files are preserved. It will not downgrade an installed version."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Repair") { _, _ -> runOpenCodeRepair() }
            .show()
    }

    private fun runOpenCodeRepair() {
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop the OpenCode server before repairing OpenCode", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "Repairing OpenCode executable…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    setup.repairOpenCode(::appendLog) { done, total ->
                        runOnUiThread { showProgress(done, total, "OpenCode repair") }
                    }
                }
                status.text = "OpenCode repaired · ready to start"
                appendLog("OpenCode binary repair completed. Debian and user data were preserved.\n")
            } catch (t: Throwable) {
                appendLog("OpenCode repair failed: ${t.message}\n")
                status.text = "OpenCode repair failed · inspect activity"
            } finally {
                setBusy(false)
                refreshButtons()
            }
        }
    }

    private fun confirmOpenCodeRollback() {
        if (!setup.hasPreviousOpenCode()) {
            Toast.makeText(this, "No verified previous OpenCode binary is available", Toast.LENGTH_LONG).show()
            return
        }
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop the OpenCode server before restoring OpenCode", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Restore previous OpenCode version?")
            .setMessage(
                "The previous executable will become active. The current executable will be retained under a timestamped opencode.failed file. Debian, configuration, and projects will not be changed."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Restore") { _, _ -> runOpenCodeRollback() }
            .show()
    }

    private fun runOpenCodeRollback() {
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop the OpenCode server before restoring OpenCode", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "Restoring previous OpenCode version…")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { setup.restorePreviousOpenCode(::appendLog) }
                status.text = "Previous OpenCode version restored"
                appendLog("OpenCode rollback completed. Run Runtime diagnostics before starting the server.\n")
            } catch (t: Throwable) {
                appendLog("OpenCode rollback failed: ${t.message}\n")
                status.text = "OpenCode rollback failed · inspect activity"
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
        // Read-only diagnostics do not need a confirmation dialog.
        runRootOperation("Root diagnostics") {
            RootAccessManager.runDiagnostics(cacheDir, File(filesDir, "rootfs"))
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
                // Keep results inline; detailed command output stays in the collapsible activity log.
                rootStatus.text = result.message
                if (result.output.isNotBlank()) appendLog("[Details] ${result.output}\n")
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

    private fun refreshWorkspaceLabel() {
        val rootfs = File(filesDir, "rootfs")
        runCatching { StorageAccessManager.prepareDefaultWorkspace(rootfs) }
        val stored = getSharedPreferences(OpenCodeService.PREFERENCES, MODE_PRIVATE)
            .getString(OpenCodeService.PREF_WORKSPACE_DIRECTORY, "/root/projects") ?: "/root/projects"
        val available = StorageAccessManager.isGuestWorkspaceAvailable(rootfs, stored)
        workspacePathLabel.text = if (available) stored else "$stored (unavailable)"
        workspaceChooseButton.isEnabled = !OpenCodeService.isRunning
        storageTestButton.isEnabled = StorageAccessManager.sharedStorageDirectory() != null
    }

    private fun chooseWorkspaceDirectory() {
        if (OpenCodeService.isRunning) {
            Toast.makeText(this, "Stop OpenCode before changing its workspace directory", Toast.LENGTH_LONG).show()
            return
        }
        val rootfs = File(filesDir, "rootfs")
        val options = mutableListOf<String>()
        val roots = mutableListOf<Triple<File, File, String>>()
        val debianProjects = runCatching { StorageAccessManager.prepareDefaultWorkspace(rootfs) }.getOrNull()
        if (debianProjects != null) {
            options += "Debian filesystem  ·  /root"
            roots += Triple(File(rootfs, "root"), File(rootfs, "root"), "/root")
        }
        val androidStorage = StorageAccessManager.sharedStorageDirectory()
        if (androidStorage != null) {
            options += "Android shared storage  ·  /sdcard"
            roots += Triple(androidStorage, androidStorage, "/sdcard")
        }
        if (options.isEmpty()) {
            Toast.makeText(this, "Debian environment is not installed yet", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose workspace location")
            .setItems(options.toTypedArray()) { _, which ->
                val (start, hostRoot, guestRoot) = roots[which]
                val currentGuest = getSharedPreferences(OpenCodeService.PREFERENCES, MODE_PRIVATE)
                    .getString(OpenCodeService.PREF_WORKSPACE_DIRECTORY, "/root/projects") ?: "/root/projects"
                val current = directoryForGuestPath(rootfs, currentGuest, hostRoot, guestRoot) ?: start
                showWorkspaceDirectoryPicker(current, hostRoot, guestRoot)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun directoryForGuestPath(rootfs: File, guestPath: String, hostRoot: File, guestRoot: String): File? {
        if (guestPath != guestRoot && !guestPath.startsWith("$guestRoot/")) return null
        val relative = guestPath.removePrefix(guestRoot).trimStart('/')
        val candidate = if (relative.isBlank()) hostRoot else File(hostRoot, relative)
        return runCatching { candidate.canonicalFile }.getOrNull()
            ?.takeIf { it.isDirectory && (it.path == hostRoot.canonicalPath || it.path.startsWith(hostRoot.canonicalPath + File.separator)) }
    }

    private fun showWorkspaceDirectoryPicker(current: File, hostRoot: File, guestRoot: String) {
        val root = runCatching { hostRoot.canonicalFile }.getOrNull() ?: return
        val folder = runCatching { current.canonicalFile }.getOrNull() ?: return
        if (folder.path != root.path && !folder.path.startsWith(root.path + File.separator)) return
        val listing = runCatching { folder.listFiles() }
        val children = listing.getOrNull()
            ?.filter { child ->
                child.isDirectory &&
                    !(guestRoot == "/sdcard" && folder.name == "Android" && child.name in setOf("data", "obb"))
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        val guestCurrent = StorageAccessManager.guestPathForDirectory(folder, root, guestRoot)
        val pickerMessage = buildString {
            append(guestCurrent ?: folder.name)
            when {
                listing.isFailure -> append("\n\nCould not list this folder: ${listing.exceptionOrNull()?.message ?: "unknown error"}")
                listing.getOrNull() == null -> append("\n\nThis folder could not be listed.")
                children.isEmpty() -> append("\n\nNo subfolders found. You can select this folder or go back.")
                else -> append("\n\nChoose a subfolder, or use the current folder.")
            }
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Choose project folder")
            .setMessage(pickerMessage)
            .setItems(children.map { "📁  ${it.name}" }.toTypedArray()) { _, which ->
                if (which in children.indices) showWorkspaceDirectoryPicker(children[which], root, guestRoot)
            }
            .setPositiveButton("Use this folder") { _, _ ->
                val guestPath = StorageAccessManager.guestPathForDirectory(folder, root, guestRoot)
                if (guestPath == null) {
                    Toast.makeText(this, "This folder is not readable and writable", Toast.LENGTH_LONG).show()
                } else {
                    getSharedPreferences(OpenCodeService.PREFERENCES, MODE_PRIVATE).edit()
                        .putString(OpenCodeService.PREF_WORKSPACE_DIRECTORY, guestPath)
                        .apply()
                    refreshWorkspaceLabel()
                    appendLog("Workspace directory selected: $guestPath\n")
                    Toast.makeText(this, "Workspace saved", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
        if (folder.path != root.path) {
            dialog.setNeutralButton("Up") { _, _ ->
                folder.parentFile?.let { showWorkspaceDirectoryPicker(it, root, guestRoot) }
            }
        }
        dialog.show()
    }

    private fun runSharedStorageTest() {
        if (StorageAccessManager.sharedStorageDirectory() == null) {
            Toast.makeText(this, "Shared storage access is not enabled", Toast.LENGTH_LONG).show()
            refreshStorageAccessUi()
            return
        }
        storageTestButton.isEnabled = false
        storageTestButton.text = "Testing…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val token = java.util.UUID.randomUUID().toString().replace("-", "")
                    val script = "set -eu; mkdir -p /sdcard/Download; f=/sdcard/Download/.opencode-storage-test-$token; " +
                        "trap 'rm -f \"\$f\"' EXIT; printf 'opencode-storage-test' > \"\$f\"; " +
                        "cmp \"\$f\" /storage/emulated/0/Download/.opencode-storage-test-$token; " +
                        "echo 'Both shared-storage paths resolve to the same writable directory.'"
                    val process = ProotRunner.start(this@MainActivity, listOf("/bin/sh", "-c", script))
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        throw IllegalStateException("Storage test timed out")
                    }
                    val output = process.inputStream.bufferedReader().readText().trim()
                    check(process.exitValue() == 0) { output.ifBlank { "Shared-storage test failed." } }
                    output
                }
                storageStatus.text = "Access verified · project files are reachable"
                storageStatus.setTextColor(Color.parseColor("#B8D8B8"))
                appendLog("Shared-storage test passed: $result\n")
            } catch (error: Throwable) {
                val message = error.message ?: "Unknown storage test failure"
                storageStatus.text = "Storage test failed · $message"
                storageStatus.setTextColor(Color.parseColor("#E3B0A8"))
                appendLog("Shared-storage test failed: $message\n")
            } finally {
                storageTestButton.text = "Test shared storage"
                refreshStorageAccessUi()
            }
        }
    }

    private fun refreshStorageAccessUi() {
        if (!StorageAccessManager.isSupported()) {
            storageStatus.text = "Direct shared-storage mounting requires Android 11 or newer."
            storageButton.text = "Unavailable"
            storageButton.isEnabled = false
            refreshWorkspaceLabel()
            return
        }
        storageButton.isEnabled = true
        val storageEnabled = StorageAccessManager.sharedStorageDirectory() != null
        if (storageEnabled) {
            storageStatus.text = "Enabled · /sdcard and /storage/emulated/0"
            storageButton.text = "Manage permission"
            storageButton.isEnabled = true
        } else {
            storageStatus.text = "Not enabled · shared files are unavailable to Debian"
            storageButton.text = "Allow shared storage"
            storageButton.isEnabled = true
        }
        val preferences = getSharedPreferences(OpenCodeService.PREFERENCES, MODE_PRIVATE)
        if (OpenCodeService.isRunning &&
            preferences.contains(OpenCodeService.PREF_SHARED_STORAGE_MOUNTED) &&
            preferences.getBoolean(OpenCodeService.PREF_SHARED_STORAGE_MOUNTED, false) != storageEnabled
        ) {
            storageStatus.text = storageStatus.text.toString() +
                "\n\nAccess changed while OpenCode was running. Stop the server from its notification, then launch it again to apply the new mount configuration."
            storageStatus.setTextColor(Color.parseColor("#E3B0A8"))
        } else {
            storageStatus.setTextColor(Color.parseColor("#A9A2A2"))
        }
        refreshWorkspaceLabel()
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
        val selectedWorkspace = getSharedPreferences(OpenCodeService.PREFERENCES, MODE_PRIVATE)
            .getString(OpenCodeService.PREF_WORKSPACE_DIRECTORY, "/root/projects") ?: "/root/projects"
        val service = Intent(this, OpenCodeService::class.java)
            .putExtra(OpenCodeService.EXTRA_WORKING_DIRECTORY, selectedWorkspace)
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
                val pairingResult = runCatching {
                    withContext(Dispatchers.IO) {
                        val client = OkHttpClient.Builder()
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(10, TimeUnit.SECONDS)
                            .build()
                        val auth = Credentials.basic("opencode", SecretStore.getOrCreate(this@MainActivity))
                        val request = Request.Builder()
                            .url(SERVER_URL + "/api/pair")
                            .header("Authorization", auth)
                            .header("Accept", "application/json")
                            .post(ByteArray(0).toRequestBody(null))
                            .build()
                        client.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            check(response.isSuccessful) {
                                "Could not create local web session (HTTP ${response.code}): ${body.take(240)}"
                            }
                            org.json.JSONObject(body).getString("code")
                        }
                    }
                }
                val pairingCode = pairingResult.getOrElse { error ->
                    status.text = "Could not authenticate workspace"
                    appendLog("Automatic web pairing failed: ${error.message ?: error.javaClass.simpleName}\n")
                    Toast.makeText(this@MainActivity, "Could not open authenticated workspace", Toast.LENGTH_LONG).show()
                    return@launch
                }
                status.text = "Server ready · opening workspace"
                showWebView(pairingCode)
            } else {
                status.text = "Server did not become ready in 60 seconds"
                appendLog("Authenticated /api/info did not report the installed OpenCode version. Check logs and confirm the server starts under PRoot.\n")
                Toast.makeText(this@MainActivity, "Server startup timed out", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showWebView(pairingCode: String) {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

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
        content.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(content)
        setWorkspaceImmersive(true)
        // Redeem the single-use pairing code; OpenCode sets a browser session cookie and redirects to the workspace.
        web.loadUrl("$SERVER_URL/auth/connect/$pairingCode")
    }

    private fun showSetupAgain() {
        setWorkspaceImmersive(false)
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
        setupButton.isEnabled = !busy && !setup.isReady()
        smokeButton.isEnabled = !busy && setup.hasRootfs()
        serverButton.isEnabled = !busy && setup.isReady()
        updateButton.isEnabled = !busy && setup.isReady()
        repairPackagesButton.isEnabled = !busy && setup.hasRootfs() && !OpenCodeService.isRunning
        repairOpenCodeButton.isEnabled = !busy && setup.hasRootfs() && !OpenCodeService.isRunning
        rollbackOpenCodeButton.isEnabled = !busy && setup.hasPreviousOpenCode() && !OpenCodeService.isRunning
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
        // Keep the setup screen calm; details remain available in Activity log.
        if (activityPanel.visibility == View.VISIBLE) {
            activityPanel.post { activityPanel.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SERVER_URL = "http://127.0.0.1:4096"
        private const val REQUEST_FILE = 45
        private const val REQUEST_NOTIFICATIONS = 46
    }
}
