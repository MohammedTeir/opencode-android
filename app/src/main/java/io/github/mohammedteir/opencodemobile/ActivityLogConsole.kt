package io.github.mohammedteir.opencodemobile

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class EventSeverity { SUCCESS, INFO, WARNING, ERROR }
internal data class ActivityEvent(val time: Long, val severity: EventSeverity, val title: String, val summary: String, val raw: String)

internal class ActivityLogStore(context: Context) {
    private val file = File(context.filesDir, "activity/activity.log")
    private val legacyMarker = File(context.filesDir, "activity/legacy-imported")
    @Synchronized fun append(value: String) {
        if (value.isBlank()) return
        file.parentFile?.mkdirs()
        val safeValue = redact(value).take(32 * 1024)
        val record = System.currentTimeMillis().toString() + "\n" + safeValue
        file.appendText(Base64.encodeToString(record.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "\n")
        if (file.length() > 1024L * 1024L) file.writeText(file.readLines().takeLast(500).joinToString("\n", postfix = "\n"))
    }
    @Synchronized fun clear() { file.delete() }
    @Synchronized fun importLegacyOnce(value: String) {
        if (legacyMarker.exists()) return
        if (value.isNotBlank() && events().isEmpty()) append(value)
        legacyMarker.parentFile?.mkdirs()
        legacyMarker.writeText("imported")
    }
    private fun redact(value: String): String = Regex("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|authorization|secret)(\\s*[:=]\\s*)([^\\s,;]+)")
        .replace(value) { it.groupValues[1] + it.groupValues[2] + "[REDACTED]" }
    @Synchronized fun events(): List<ActivityEvent> = if (!file.isFile) emptyList() else file.readLines().mapNotNull { row ->
        runCatching {
            val decoded = String(Base64.decode(row, Base64.NO_WRAP), Charsets.UTF_8)
            val split = decoded.indexOf('\n')
            val time = decoded.substring(0, split).toLong()
            val raw = decoded.substring(split + 1).trimEnd()
            val lines = raw.lines().filter { it.isNotBlank() }
            val first = lines.firstOrNull() ?: error("Empty activity event")
            val lower = raw.lowercase(Locale.ROOT)
            val severity = when {
                listOf("fatal", "exception", "failed", "failure", "permission denied", "could not").any { lower.contains(it) } -> EventSeverity.ERROR
                listOf("warning", "http 4", "http 5", "retry", "fallback", "unavailable").any { lower.contains(it) } -> EventSeverity.WARNING
                listOf("success", "installed", "ready", "passed", "completed", "https_ok", "dns_ok").any { lower.contains(it) } -> EventSeverity.SUCCESS
                else -> EventSeverity.INFO
            }
            ActivityEvent(time, severity, first.take(100), lines.drop(1).firstOrNull()?.take(180) ?: first, raw)
        }.getOrNull()
    }.takeLast(500)
    fun raw(): String = events().joinToString("\n\n") { "[" + time(it.time) + "] [" + it.severity + "]\n" + it.raw }
}

private fun time(value: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value))

/** Persistent native activity timeline with filters, search, raw details, and export. */
internal class ActivityLogConsole(
    context: Context,
    private val store: ActivityLogStore = ActivityLogStore(context),
    private val fullScreenAllowed: Boolean = true
) : LinearLayout(context) {
    private val adapter = EventAdapter()
    private lateinit var eventList: RecyclerView
    private lateinit var count: TextView
    private lateinit var summary: TextView
    private lateinit var query: EditText
    private var filter = "ALL"

    init {
        orientation = VERTICAL
        store.importLegacyOnce(RootOperationLog.read(context))
        setPadding(dp(12), dp(12), dp(12), dp(10))
        background = rounded("#171515", 14, "#302B2B")
        val heading = row()
        heading.addView(label("RECENT EVENTS", 10f, "#B7B1B1", true), LayoutParams(0, -2, 1f))
        count = label("0 events", 10f, "#A9A2A2", false)
        heading.addView(count)
        addView(heading)
        summary = label("No activity recorded yet", 12f, "#D8D2D2", true)
        summary.setPadding(0, dp(5), 0, dp(9))
        addView(summary)
        query = EditText(context).apply {
            hint = "Search activity…"; textSize = 12f; singleLine = true
            setTextColor(Color.parseColor("#F1ECEC")); setHintTextColor(Color.parseColor("#827B7B"))
            setPadding(dp(10), 0, dp(10), 0); background = rounded("#211E1E", 10, "#393333")
        }
        addView(query, LayoutParams(-1, dp(40)))
        val filters = row().apply { setPadding(0, dp(8), 0, dp(8)) }
        listOf("ALL", "ERROR", "WARNING", "SUCCESS").forEach { value ->
            val chip = label(value.lowercase().replaceFirstChar { it.uppercase() }, 10f, "#D8D2D2", true).apply {
                tag = value; gravity = Gravity.CENTER; setPadding(dp(5), dp(7), dp(5), dp(7))
                background = rounded(if (value == filter) "#494242" else "#211E1E", 18)
                setOnClickListener {
                    filter = value
                    for (i in 0 until filters.childCount) {
                        val item = filters.getChildAt(i) as TextView
                        item.background = rounded(if (item.tag == filter) "#494242" else "#211E1E", 18)
                    }
                    refresh()
                }
            }
            filters.addView(chip, LayoutParams(0, dp(32), 1f).apply { if (value != "ALL") marginStart = dp(5) })
        }
        addView(filters)
        eventList = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context); adapter = this@ActivityLogConsole.adapter; itemAnimator = null
        }
        addView(eventList, if (fullScreenAllowed) LayoutParams(-1, dp(210)) else LayoutParams(-1, 0, 1f))
        val actions = row().apply { setPadding(0, dp(7), 0, 0) }
        addAction(actions, "Copy") { copy() }
        addAction(actions, "Export") { export() }
        if (fullScreenAllowed) addAction(actions, "Full screen") { openFullScreen() }
        addAction(actions, "Clear") { clear() }
        addView(actions)
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refresh()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refresh()
    }

    fun append(value: String) {
        val followTail = ::eventList.isInitialized && !eventList.canScrollVertically(1)
        store.append(value)
        refresh()
        if (followTail) eventList.post { if (adapter.itemCount > 0) eventList.scrollToPosition(adapter.itemCount - 1) }
    }
    fun refresh() {
        if (!::count.isInitialized) return
        val all = store.events()
        val term = query.text.toString().trim()
        val visible = all.filter {
            (filter == "ALL" || it.severity.name == filter) &&
                (term.isBlank() || it.raw.contains(term, true))
        }
        adapter.submit(visible)
        if (all.isNotEmpty() && !eventList.canScrollVertically(1)) eventList.post { if (adapter.itemCount > 0) eventList.scrollToPosition(adapter.itemCount - 1) }
        count.text = all.size.toString() + " events"
        val errors = all.count { it.severity == EventSeverity.ERROR }
        val warnings = all.count { it.severity == EventSeverity.WARNING }
        summary.text = if (all.isEmpty()) "No activity recorded yet"
            else errors.toString() + " errors · " + warnings + " warnings · latest " + time(all.last().time)
    }

    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun addAction(parent: LinearLayout, name: String, action: () -> Unit) {
        parent.addView(label(name, 10f, "#D8D2D2", true).apply {
            gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8)); setOnClickListener { action() }
        }, LayoutParams(0, dp(36), 1f))
    }
    private fun copy() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenCode activity", store.raw()))
        Toast.makeText(context, "Activity log copied", Toast.LENGTH_SHORT).show()
    }
    private fun export() {
        val report = "OpenCode Android activity report\nDevice: " + android.os.Build.MANUFACTURER + " " +
            android.os.Build.MODEL + "\nAndroid: " + android.os.Build.VERSION.RELEASE + "\n\n" + store.raw()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "OpenCode Android activity report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(Intent.createChooser(send, "Export activity report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun clear() {
        AlertDialog.Builder(context).setTitle("Clear activity log?")
            .setMessage("This removes saved activity history only. Debian, OpenCode, and project files are not affected.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> store.clear(); refresh() }.show()
    }
    private fun openFullScreen() {
        val dialog = Dialog(context)
        val console = ActivityLogConsole(context, store, false)
        val shell = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(12), dp(18), dp(12), dp(12)); setBackgroundColor(Color.parseColor("#101010"))
            val header = row()
            header.addView(label("ACTIVITY LOG", 14f, "#F1ECEC", true), LayoutParams(0, -2, 1f))
            header.addView(label("Close", 12f, "#D8D2D2", true).apply { setOnClickListener { dialog.dismiss() } })
            addView(header); addView(console, LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); dialog.setContentView(shell); dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    private fun label(value: String, size: Float, color: String, medium: Boolean) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.parseColor(color))
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun rounded(color: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class EventAdapter : RecyclerView.Adapter<EventAdapter.Holder>() {
        private var items = emptyList<ActivityEvent>()
        private val expanded = mutableSetOf<Long>()
        private fun key(event: ActivityEvent) = event.time xor event.raw.hashCode().toLong()
        fun submit(value: List<ActivityEvent>) { items = value; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            return Holder(LinearLayout(parent.context).apply {
                orientation = VERTICAL; setPadding(dp(9), dp(9), dp(9), dp(9)); background = rounded("#211E1E", 10)
            })
        }
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val event = items[position]
            val color = when (event.severity) {
                EventSeverity.SUCCESS -> "#75C995"; EventSeverity.INFO -> "#A9B8D0"
                EventSeverity.WARNING -> "#E8BE72"; EventSeverity.ERROR -> "#E58C85"
            }
            holder.box.removeAllViews()
            val line = row()
            line.addView(label("●", 12f, color, true), LayoutParams(dp(18), -2))
            val body = LinearLayout(context).apply { orientation = VERTICAL }
            body.addView(label(event.title, 12f, "#F1ECEC", true))
            body.addView(label(event.summary, 10f, "#A9A2A2", false).apply { maxLines = 2 })
            line.addView(body, LayoutParams(0, -2, 1f))
            line.addView(label(time(event.time), 9f, "#827B7B", false))
            holder.box.addView(line)
            if (expanded.contains(key(event))) {
                holder.box.addView(label(event.raw, 10f, "#BFB8B8", false).apply {
                    typeface = Typeface.create("monospace", Typeface.NORMAL); setTextIsSelectable(true)
                    setPadding(dp(22), dp(9), 0, dp(2))
                })
                holder.box.addView(label("Copy event", 10f, "#D8D2D2", true).apply {
                    setPadding(dp(22), dp(8), 0, 0)
                    setOnClickListener {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Activity event", event.raw))
                        Toast.makeText(context, "Event copied", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            holder.box.setOnClickListener {
                if (!expanded.add(key(event))) expanded.remove(key(event))
                notifyItemChanged(position)
            }
        }
        override fun getItemCount() = items.size
        inner class Holder(val box: LinearLayout) : RecyclerView.ViewHolder(box)
    }
}
