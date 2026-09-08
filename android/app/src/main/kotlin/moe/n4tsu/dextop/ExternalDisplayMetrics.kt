package moe.n4tsu.dextop

/** Kept independent of Android so malformed OEM output cannot turn into a reset. */
internal data class ExternalDisplayMetrics(
    val physicalWidth: Int,
    val physicalHeight: Int,
    val physicalDensity: Int,
    val overrideWidth: Int?,
    val overrideHeight: Int?,
    val overrideDensity: Int?
) {
    val width: Int get() = overrideWidth ?: physicalWidth
    val height: Int get() = overrideHeight ?: physicalHeight
    val density: Int get() = overrideDensity ?: physicalDensity

    companion object {
        fun parse(size: String, density: String): ExternalDisplayMetrics {
            val physical = Regex("(?m)^Physical size: (\\d+)x(\\d+)\\s*$").find(size.trim())
                ?: error("Cannot read physical display size: $size")
            val initialDpi = Regex("(?m)^Physical density: (\\d+)\\s*$").find(density.trim())
                ?: error("Cannot read physical display density: $density")
            val forced = Regex("(?m)^Override size: (\\d+)x(\\d+)\\s*$").find(size.trim())
            val forcedDpi = Regex("(?m)^Override density: (\\d+)\\s*$").find(density.trim())
            check(!size.contains("Override") || forced != null) { "Unreadable size override" }
            check(!density.contains("Override") || forcedDpi != null) { "Unreadable density override" }
            return ExternalDisplayMetrics(
                physical.groupValues[1].toInt(), physical.groupValues[2].toInt(),
                initialDpi.groupValues[1].toInt(), forced?.groupValues?.get(1)?.toInt(),
                forced?.groupValues?.get(2)?.toInt(), forcedDpi?.groupValues?.get(1)?.toInt()
            ).also { check(it.width > 0 && it.height > 0 && it.density > 0) }
        }

        fun validate(width: Int, height: Int, density: Int) {
            require(width in 320..7680 && height in 320..7680) { "Width / height: 320–7680 px" }
            require(width.toLong() * height <= 33_177_600L) { "Working area is too large (maximum 8K)" }
            require(density in 100..640) { "DPI: 100–640" }
        }

        fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
