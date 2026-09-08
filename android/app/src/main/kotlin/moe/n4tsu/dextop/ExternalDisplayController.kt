package moe.n4tsu.dextop

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONObject
import java.util.UUID

/** Explicit user-triggered external-display edits; never changes the phone or a Dextop overlay. */
internal class ExternalDisplayController(private val context: Context) {
    private val access = PrivilegedAccess("DextopExternalDisplay")
    private val manager = context.getSystemService(DisplayManager::class.java)
    private val prefs = context.getSharedPreferences("external_display_trials_v1", Context.MODE_PRIVATE)

    data class Screen(val id: Int, val identity: String, val name: String,
        val width: Int, val height: Int, val density: Int, val output: String)
    data class Trial(val record: JSONObject, val deadline: Long)

    fun screens(): List<Screen> = ExternalDisplayDetector(context).snapshot().displayIds.mapNotNull { id ->
        manager.getDisplay(id)?.let { display ->
            val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
            Screen(id, identity(display), display.name, metrics.widthPixels, metrics.heightPixels,
                metrics.densityDpi, "${display.mode.physicalWidth} × ${display.mode.physicalHeight} @ ${display.mode.refreshRate} Hz")
        }
    }

    fun read(screen: Screen): ExternalDisplayMetrics {
        requireSame(screen)
        return ExternalDisplayMetrics.parse(command("wm", "size", "-d", screen.id.toString()),
            command("wm", "density", "-d", screen.id.toString()))
    }

    fun apply(screen: Screen, width: Int, height: Int, density: Int, changeSize: Boolean): Trial = synchronized(lock) {
        check(access.isAvailable()) { "Dextopの初期設定でアクセスを有効にしてください / Enable privileged access in setup" }
        recover()
        check(prefs.getString(PENDING, null) == null) { "未復元の変更があります。対象モニターを再接続してください / Reconnect the pending display" }
        val before = read(screen)
        val nextWidth = if (changeSize) width else before.width
        val nextHeight = if (changeSize) height else before.height
        ExternalDisplayMetrics.validate(nextWidth, nextHeight, density)
        val sizeChanged = nextWidth != before.width || nextHeight != before.height
        val dpiChanged = density != before.density
        require(sizeChanged || dpiChanged) { "値が変わっていません / No values changed" }
        val directory = "/data/local/tmp/dextop-external-${UUID.randomUUID()}"
        val record = JSONObject().put("identity", screen.identity).put("displayId", screen.id)
            .put("requestedWidth", nextWidth).put("requestedHeight", nextHeight).put("requestedDensity", density)
            .put("directory", directory).put("sizeChanged", sizeChanged).put("dpiChanged", dpiChanged)
            .put("physicalWidth", before.physicalWidth).put("physicalHeight", before.physicalHeight)
            .put("physicalDensity", before.physicalDensity)
            .put("overrideWidth", before.overrideWidth ?: JSONObject.NULL)
            .put("overrideHeight", before.overrideHeight ?: JSONObject.NULL)
            .put("overrideDensity", before.overrideDensity ?: JSONObject.NULL)
        // Verify the exact OEM dump format before relying on the out-of-process watchdog.
        val identityCheck = access.execute("sh", "-c", identityGuard(screen.id, screen.identity))
        check(identityCheck.succeeded) {
            "画面の識別情報を確認できません。診断②を保存してください / Cannot verify this display; save diagnostic 2. ${identityCheck.error}"
        }
        check(prefs.edit().putString(PENDING, record.toString()).commit()) { "Cannot save recovery journal" }
        try {
            val quotedDir = quote(directory)
            val rollback = restoreScript(record, screen.id)
            val guardian = ExternalDisplayRecovery.watchdog(directory, rollback)
            command("sh", "-c", "umask 077\nmkdir $quotedDir || exit 1\n" +
                "nohup sh -c ${quote(guardian)} </dev/null >$quotedDir/watchdog.log 2>&1 &\n" +
                "watchdog_pid=\$!\nkill -0 \$watchdog_pid")
            val deadline = SystemClock.elapsedRealtime() + 28_000L
            if (sizeChanged) command("wm", "size", "${nextWidth}x$nextHeight", "-d", screen.id.toString())
            requireSame(screen)
            if (dpiChanged) command("wm", "density", density.toString(), "-d", screen.id.toString())
            awaitMetrics(screen, nextWidth, nextHeight, density)
            OperationLog.i(context, "ExternalDisplay", "trial display=${screen.id} ${nextWidth}x$nextHeight/$density")
            prefs.edit().putString("lastResult", "trial display=${screen.id} ${nextWidth}x$nextHeight/$density").apply()
            Trial(record, deadline)
        } catch (error: Throwable) {
            runCatching { restorePending(record) }.onFailure { error.addSuppressed(it) }
            prefs.edit().putString("lastResult", "failed: ${error.message}; rollback=${error.suppressed.joinToString { it.message.orEmpty() }}").apply()
            throw error
        }
    }

    fun confirm(trial: Trial) = synchronized(lock) {
        check(SystemClock.elapsedRealtime() < trial.deadline) { "確認期限を過ぎました / Trial expired" }
        val record = trial.record
        check(prefs.getString(PENDING, null) == record.toString()) { "Trial is no longer active" }
        val screen = screenFor(record) ?: error("外部モニターが切断されました / Display disconnected")
        requireSame(screen)
        awaitMetrics(screen, record.getInt("requestedWidth"), record.getInt("requestedHeight"), record.getInt("requestedDensity"))
        check(SystemClock.elapsedRealtime() < trial.deadline) { "確認期限を過ぎました / Trial expired" }
        // mkdir is the single atomic decision shared by confirmation and watchdog.
        command("sh", "-c", "mkdir ${quote(record.getString("directory"))}/decision")
        val key = "original:${screen.identity}"
        val existing = prefs.getString(key, null)?.let(::JSONObject)
        val baseline = existing ?: JSONObject(record.toString())
        // Later trials can change a metric that the first trial left untouched.
        for (metric in listOf("sizeChanged", "dpiChanged")) {
            if (record.getBoolean(metric) && !(existing?.optBoolean(metric) ?: false)) {
                val fields = if (metric == "sizeChanged") listOf("overrideWidth", "overrideHeight", "physicalWidth", "physicalHeight")
                    else listOf("overrideDensity", "physicalDensity")
                fields.forEach { baseline.put(it, record.get(it)) }
            }
            baseline.put(metric, record.getBoolean(metric) || (existing?.optBoolean(metric) ?: false))
        }
        check(prefs.edit().putString(key, baseline.toString()).remove(PENDING).commit()) { "Cannot save display settings" }
        cleanup(record)
        OperationLog.i(context, "ExternalDisplay", "confirmed display=${screen.id}")
    }

    fun revert(trial: Trial) = synchronized(lock) {
        if (prefs.getString(PENDING, null) != trial.record.toString()) return@synchronized
        check(restorePending(trial.record)) { "復元待ちです。同じモニターを再接続して再検出してください / Reconnect the same monitor, then refresh to restore" }
    }

    fun recover() = synchronized(lock) {
        prefs.getString(PENDING, null)?.let { restorePending(JSONObject(it)) }
    }

    fun reset(screen: Screen) = synchronized(lock) {
        recover()
        val key = "original:${screen.identity}"
        val record = prefs.getString(key, null)?.let(::JSONObject)
            ?: error("このアプリで確定した変更はありません / No confirmed changes to restore")
        restore(record, screen)
        check(prefs.edit().remove(key).commit())
        OperationLog.i(context, "ExternalDisplay", "restored original display=${screen.id}")
    }

    private fun restorePending(record: JSONObject): Boolean {
        val screen = screenFor(record) ?: return false // Retain the journal until this exact display returns.
        restore(record, screen)
        cleanup(record)
        check(prefs.edit().remove(PENDING).commit())
        return true
    }

    private fun restore(record: JSONObject, screen: Screen) {
        requireSame(screen)
        // Both metrics are attempted even if the first restoration is rejected.
        val result = access.execute("sh", "-c", restoreScript(record, screen.id))
        val current = read(screen)
        val width = record.optIntOrNull("overrideWidth") ?: current.physicalWidth
        val height = record.optIntOrNull("overrideHeight") ?: current.physicalHeight
        val density = record.optIntOrNull("overrideDensity") ?: current.physicalDensity
        awaitMetrics(screen, if (record.getBoolean("sizeChanged")) width else current.width,
            if (record.getBoolean("sizeChanged")) height else current.height,
            if (record.getBoolean("dpiChanged")) density else current.density)
        check(result.succeeded) { "復元に失敗しました / Restore failed: ${result.error}" }
    }

    private fun restoreScript(record: JSONObject, displayId: Int): String = ExternalDisplayRecovery.restore(
        displayId, record.getString("identity"), record.getBoolean("sizeChanged"), record.getBoolean("dpiChanged"),
        record.optIntOrNull("overrideWidth"), record.optIntOrNull("overrideHeight"), record.optIntOrNull("overrideDensity")
    )

    private fun awaitMetrics(screen: Screen, width: Int, height: Int, density: Int) {
        repeat(12) {
            val value = read(screen)
            val live = screens().firstOrNull { it.id == screen.id && it.identity == screen.identity }
            val sizeVisible = live != null && ((live.width == width && live.height == height) ||
                (live.width == height && live.height == width))
            if (value.width == width && value.height == height && value.density == density &&
                sizeVisible && live?.density == density) return
            Thread.sleep(200)
        }
        error("要求した値が画面に反映されませんでした。変更を戻します / Display did not apply the requested metrics")
    }

    private fun requireSame(screen: Screen) {
        check(screen.id > 0 && screen.identity.isNotBlank()) { "Cannot identify external display" }
        check(ExternalDisplayDetector(context).snapshot().displayIds.contains(screen.id)) { "External display disconnected" }
        check(manager.getDisplay(screen.id)?.let(::identity) == screen.identity) { "Display identity changed" }
    }

    private fun screenFor(record: JSONObject): Screen? = screens().firstOrNull { it.identity == record.getString("identity") }
    private fun identity(display: Display): String = runCatching {
        Display::class.java.getMethod("getUniqueId").invoke(display) as String
    }.getOrDefault("")

    private fun identityGuard(displayId: Int, uniqueId: String) = ExternalDisplayRecovery.identityGuard(displayId, uniqueId)

    private fun cleanup(record: JSONObject) {
        val directory = record.getString("directory")
        check(Regex("/data/local/tmp/dextop-external-[0-9a-f-]{36}").matches(directory))
        access.execute("sh", "-c", "rm -f ${quote(directory)}/watchdog.log\nrmdir ${quote(directory)}/decision 2>/dev/null\nrmdir ${quote(directory)} 2>/dev/null")
    }

    private fun command(vararg args: String): String {
        val result = access.execute(*args)
        check(result.succeeded) { "${args.take(2).joinToString(" ")}: ${result.error.ifBlank { result.output }}" }
        return result.output
    }

    private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun quote(value: String): String = ExternalDisplayMetrics.shellQuote(value)

    companion object {
        private val lock = Any()
        private const val PENDING = "pending"
    }
}
