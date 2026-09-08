package moe.n4tsu.dextop

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

/** Phone-side recovery controls remain visible while the external screen changes. */
class ExternalDisplayToolsActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val controller by lazy { ExternalDisplayController(applicationContext) }
    private val japanese get() = resources.configuration.locales[0].language == "ja"
    private lateinit var content: LinearLayout
    private lateinit var selector: Spinner
    private lateinit var information: TextView
    private lateinit var status: TextView
    private lateinit var widthInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var dpiInput: EditText
    private lateinit var changeSize: CheckBox
    private lateinit var reportStatus: TextView
    private val controls = mutableListOf<View>()
    private var screens = emptyList<ExternalDisplayController.Screen>()
    private var busy = false
    private var trial: ExternalDisplayController.Trial? = null
    private var timer: CountDownTimer? = null
    private var confirmation: AlertDialog? = null
    private val reports by lazy { getSharedPreferences("honor_diagnostic_reports_v1", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        label(tr("外部画面・HONOR診断", "External display & HONOR diagnostics"), 23f)
        label(tr("検証版1：DPI・作業領域の調整", "Test 1: DPI and working area"), 16f)
        label(tr("Magic Desktopを外部モニターで起動してから調整してください。出力解像度はこの画面では変更しません。",
            "Start Magic Desktop on your monitor before adjusting it. These controls change the working area and DPI; the output mode stays the same."))
        selector = Spinner(this).also { content.addView(it); controls += it }
        information = label("")
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateFields() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        button(tr("モニターを再検出", "Refresh monitors")) { refresh() }
        dpiInput = numeric(tr("DPI（100～640）", "DPI (100–640)"))
        val presets = LinearLayout(this)
        listOf(120, 160, 200, 240).forEach { dpi ->
            val button = Button(this).apply {
                text = dpi.toString()
                setOnClickListener { dpiInput.setText(dpi.toString()) }
            }
            controls += button
            presets.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(presets)
        changeSize = CheckBox(this).apply { text = tr("作業領域の幅・高さも変更する", "Also change working-area width and height") }
        controls += changeSize
        content.addView(changeSize)
        widthInput = numeric(tr("作業領域の幅（px）", "Working-area width (px)"))
        heightInput = numeric(tr("作業領域の高さ（px）", "Working-area height (px)"))
        changeSize.setOnCheckedChangeListener { _, _ -> updateEnabled() }
        button(tr("変更を試す（自動復元付き）", "Try changes (automatic rollback)")) { applyChanges() }
        button(tr("このアプリでの変更を元に戻す", "Restore changes made by this app")) {
            selected()?.let { screen -> execute({ controller.reset(screen) }) {
                status.text = tr("元の設定に戻しました。", "Original settings restored.")
                refresh()
            } }
        }
        status = label("")
        label(tr("HONOR診断", "HONOR diagnostics"), 20f)
        label(tr("各状態で診断を保存し、最後に「診断をまとめてコピー」を押してください。診断は設定を変更しません。",
            "Save a snapshot in each state, then copy the combined report. Diagnostics do not change settings."))
        listOf("normal" to tr("① モニター未接続・Dextop停止中", "1. Disconnected, Dextop stopped"),
            "external" to tr("② 外部でMagic Desktop動作中", "2. Magic Desktop on monitor"),
            "failed" to tr("③ スマホ上のDextop起動失敗後", "3. After Dextop fails on phone")).forEach { (key, title) ->
            button(title) {
                execute({ HonorDesktopDiagnostics(applicationContext).collect(key) }) { report ->
                    reports.edit().putString(key, report).apply()
                    reportStatus.text = tr("診断を保存しました：", "Saved: ") + title
                }
            }
        }
        button(tr("診断をまとめてコピー", "Copy combined diagnostics")) {
            val combined = listOf("normal", "external", "failed").mapNotNull { reports.getString(it, null) }.joinToString("\n\n----------------\n\n")
            if (combined.isBlank()) {
                reportStatus.text = tr("先に診断を保存してください。", "Save a snapshot first.")
            } else {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dextop HONOR diagnostics", combined))
                reportStatus.text = tr("コピーしました。このチャットに貼り付けてください。", "Copied. Paste the report into this chat.")
            }
        }
        reportStatus = label("")
        button(tr("戻る", "Back")) { finish() }
        refresh()
    }

    private fun selected(): ExternalDisplayController.Screen? = screens.getOrNull(selector.selectedItemPosition).also {
        if (it == null) status.text = tr("外部モニターを接続してください。", "Connect an external monitor.")
    }

    private fun refresh() {
        execute({ controller.recover(); controller.screens() }) { displays ->
            val previous = screens.getOrNull(selector.selectedItemPosition)?.identity
            screens = displays
            selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                displays.map { "${it.name} (ID ${it.id})" }.ifEmpty { listOf(tr("外部モニター未接続", "No external monitor")) })
            selector.setSelection(displays.indexOfFirst { it.identity == previous }.coerceAtLeast(0))
            updateFields()
        }
    }

    private fun updateFields() {
        val screen = screens.getOrNull(selector.selectedItemPosition)
        if (screen == null) {
            information.text = tr("診断はモニター未接続でも使えます。", "Diagnostics also work without a monitor.")
            return
        }
        information.text = tr("出力：", "Output: ") + screen.output + "\n" +
            tr("現在の作業領域：", "Current working area: ") + "${screen.width} × ${screen.height} / ${screen.density} DPI"
        widthInput.setText(screen.width.toString())
        heightInput.setText(screen.height.toString())
        dpiInput.setText(screen.density.toString())
    }

    private fun applyChanges() {
        val screen = selected() ?: return
        val width = widthInput.text.toString().toIntOrNull() ?: 0
        val height = heightInput.text.toString().toIntOrNull() ?: 0
        val dpi = dpiInput.text.toString().toIntOrNull() ?: 0
        val resize = changeSize.isChecked
        execute({ controller.apply(screen, width, height, dpi, resize) }) { value ->
            trial = value
            updateEnabled()
            showConfirmation(value)
        }
    }

    private fun showConfirmation(value: ExternalDisplayController.Trial) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("外部モニターを確認してください", "Check your monitor"))
            .setMessage("")
            .setPositiveButton(tr("この設定を使う", "Keep changes"), null)
            .setNegativeButton(tr("元に戻す", "Revert"), null)
            .setCancelable(false).create()
        confirmation = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!busy) execute({ controller.confirm(value) }) {
                    endTrial()
                    status.text = tr("設定を確定しました。", "Changes confirmed.")
                    refresh()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { revertTrial() }
        }
        dialog.show()
        timer = object : CountDownTimer((value.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1), 500) {
            override fun onTick(remaining: Long) {
                dialog.setMessage(tr("文字・アイコン・クリック位置を確認してください。\nあと", "Check text, icons and pointer alignment.\nReverting in ") +
                    "${(remaining + 999) / 1000}" + tr("秒で自動的に元へ戻します。", " seconds."))
            }
            override fun onFinish() { revertTrial() }
        }.start()
    }

    private fun revertTrial() {
        val value = trial ?: return
        if (busy) return // The shell watchdog also owns the deadline, independently of this UI.
        execute({ controller.revert(value) }) {
            endTrial()
            status.text = tr("試した変更を戻しました。", "Trial changes reverted.")
            refresh()
        }
    }

    private fun endTrial() {
        trial = null
        timer?.cancel()
        timer = null
        confirmation?.dismiss()
        confirmation = null
        updateEnabled()
    }

    private fun <T> execute(action: () -> T, complete: (T) -> Unit) {
        if (busy) return
        busy = true
        status.text = tr("処理中…", "Working…")
        updateEnabled()
        worker.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                updateEnabled()
                result.onSuccess {
                    status.text = ""
                    complete(it)
                }.onFailure {
                    status.text = tr("適用できませんでした：", "Operation failed: ") + (it.cause?.message ?: it.message)
                    if (trial != null) {
                        confirmation?.setMessage(status.text)
                        // A failed confirmation must never leave an unconfirmed override behind.
                        val failed = trial!!
                        worker.execute { runCatching { controller.revert(failed) } }
                        endTrial()
                    }
                }
            }
        }
    }

    private fun updateEnabled() {
        controls.forEach { it.isEnabled = !busy && trial == null }
        if (::widthInput.isInitialized && ::changeSize.isInitialized) {
            widthInput.isEnabled = !busy && trial == null && changeSize.isChecked
            heightInput.isEnabled = widthInput.isEnabled
        }
    }

    private fun label(text: String, size: Float = 14f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dp(8), 0, dp(8))
        content.addView(this)
    }

    private fun numeric(hint: String): EditText {
        label(hint)
        return EditText(this).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            content.addView(this)
            controls += this
        }
    }

    private fun button(title: String, action: () -> Unit) {
        val button = Button(this).apply { text = title; isAllCaps = false; setOnClickListener { action() } }
        controls += button
        content.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun onDestroy() {
        timer?.cancel()
        confirmation?.dismiss()
        trial?.let { value -> worker.execute { runCatching { controller.revert(value) } } }
        worker.shutdown()
        super.onDestroy()
    }

    private fun tr(ja: String, en: String): String = if (japanese) ja else en
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
