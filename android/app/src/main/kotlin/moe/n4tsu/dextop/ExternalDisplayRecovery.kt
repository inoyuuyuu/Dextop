package moe.n4tsu.dextop

/** The same scripts run on Android and in host tests with fake display commands. */
internal object ExternalDisplayRecovery {
    private fun quote(value: String) = ExternalDisplayMetrics.shellQuote(value)

    fun identityGuard(displayId: Int, uniqueId: String): String {
        require(displayId > 0 && uniqueId.isNotBlank())
        return "dumpsys display | grep -F -- ${quote("uniqueId \"$uniqueId\"")} | " +
            "grep -E -- ${quote("displayId $displayId([, }]|$)")} >/dev/null"
    }

    fun restore(displayId: Int, uniqueId: String, changeSize: Boolean, changeDensity: Boolean,
        width: Int?, height: Int?, density: Int?): String = buildString {
        val guard = identityGuard(displayId, uniqueId)
        require(width == null && height == null || width != null && height != null && width > 0 && height > 0)
        require(density == null || density > 0)
        append("restore_result=0\n")
        if (changeSize) {
            append("$guard || exit 1\n")
            val size = if (width == null) "reset" else "${width}x$height"
            append("wm size $size -d $displayId || restore_result=1\n")
        }
        if (changeDensity) {
            append("$guard || exit 1\n")
            append("wm density ${density ?: "reset"} -d $displayId || restore_result=1\n")
        }
        append("exit \$restore_result")
    }

    fun watchdog(directory: String, rollback: String, seconds: Int = 30): String {
        require(seconds in 0..30)
        return "sleep $seconds\nif mkdir ${quote(directory)}/decision 2>/dev/null; then\n$rollback\nfi\n"
    }
}
