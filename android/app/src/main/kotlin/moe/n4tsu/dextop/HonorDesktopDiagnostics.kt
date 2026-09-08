package moe.n4tsu.dextop

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Read-only collection: no OEM settings, activities or services are started by a probe. */
internal class HonorDesktopDiagnostics(private val context: Context) {
    private val access = PrivilegedAccess("HonorDesktopDiagnostics")

    fun collect(stage: String): String = buildString {
        append("DEXTOP HONOR / EXTERNAL DISPLAY TEST 1\n")
        append("stage=$stage\n")
        append("time=${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())}\n")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        append("app=${context.packageName} ${info.versionName} (${info.longVersionCode})\n")
        append("manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} device=${Build.DEVICE}\n")
        append("sdk=${Build.VERSION.SDK_INT} firmware=${Build.FINGERPRINT}\n")
        append("privilegedAccess=${access.isAvailable()} sessionActive=${MirrorService.isActive()}\n")
        val environment = DesktopEnvironmentRegistry.current()
        append("environment=${environment.id} startup=${environment.startupMode} platformManaged=${environment.platformManaged}\n")
        append("creation=${environment.displayCreationStrategies} mirror=${environment.mirrorStrategies}\n")
        val externalPrefs = context.getSharedPreferences("external_display_trials_v1", Context.MODE_PRIVATE)
        append("externalAdjustment=${externalPrefs.getString("lastResult", "not tried")}\n")
        append("externalRecoveryPending=${externalPrefs.contains("pending")}\n")
        append("externalLastError=${externalPrefs.getString("lastUiError", "none")}\n")
        CapabilityProbe(context, access).run().forEach { (key, value) -> append("probe.$key=$value\n") }
        append("\n[DISPLAY SNAPSHOT]\n")
        val external = ExternalDisplayDetector(context).snapshot().displayIds
        val manager = context.getSystemService(DisplayManager::class.java)
        manager.displays.forEach { display ->
            append("displayId=${display.displayId} external=${display.displayId in external} ")
            append("type=${reflectDisplay(display, "getType")} uniqueId=${reflectDisplay(display, "getUniqueId")}\n")
            append("${display}\n")
            append("supportedModes=${display.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }}\n")
            if (access.isAvailable()) {
                appendCommand("wm size", arrayOf("wm", "size", "-d", display.displayId.toString()))
                appendCommand("wm density", arrayOf("wm", "density", "-d", display.displayId.toString()))
            }
        }
        append("\n[DESKTOP PACKAGES / COMPONENTS]\n")
        @Suppress("DEPRECATION")
        val packages = context.packageManager.getInstalledPackages(
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
        ).filter { pkg ->
            val name = pkg.packageName.lowercase()
            (name.startsWith("com.hihonor.") || name.startsWith("com.huawei.") || name.startsWith("com.honor.")) &&
                listOf("desktop", "pc", "projection", "launcher", "systemui").any(name::contains)
        }
        packages.forEach { pkg ->
            append("package=${pkg.packageName} version=${pkg.versionName}\n")
            pkg.activities.orEmpty().filter { it.name.contains("desktop", true) || it.name.contains("pc", true) }
                .forEach { append("activity=${it.name} exported=${it.exported} enabled=${it.enabled} permission=${it.permission}\n") }
            pkg.services.orEmpty().filter { it.name.contains("desktop", true) || it.name.contains("pc", true) }
                .forEach { append("service=${it.name} exported=${it.exported} enabled=${it.enabled} permission=${it.permission}\n") }
        }
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .forEach { append("home=${it.activityInfo.packageName}/${it.activityInfo.name}\n") }
        append("\n[DESKTOP SETTINGS]\n")
        listOf("overlay_display_devices", "enable_freeform_support", "force_resizable_activities",
            "force_desktop_mode_on_external_displays", "force_desktop_mode_on_secondary_displays",
            "override_desktop_experience_features", "override_desktop_mode_features").forEach {
            append("$it=${Settings.Global.getString(context.contentResolver, it)}\n")
        }
        if (access.isAvailable()) {
            append("\n[FRAMEWORK DISPLAY ATTRIBUTES]\n")
            appendCommand("display attributes", arrayOf("sh", "-c", "dumpsys display | grep -E 'mBaseDisplayInfo=|mOverrideDisplayInfo=' | head -20"))
            append("\n[OEM SERVICES]\n")
            appendCommand("services", arrayOf("sh", "-c", "service list | grep -Ei 'display|window|desktop|projection|pcmanager|magic'"))
            append("\n[ACTIVE DESKTOP COMPONENTS]\n")
            // Extract only component names. Never include intents, URIs, window titles or app content.
            val activities = access.execute("sh", "-c", "dumpsys activity activities | grep -oE '(com\\.hihonor|com\\.honor|com\\.huawei)\\.[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+' | sort -u | head -80")
            append("activityQueryExit=${activities.exitCode}\n")
            Regex("(?:com\\.hihonor|com\\.honor|com\\.huawei)\\.[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
                .findAll(activities.output).map { it.value }.distinct().take(80).forEach { append("$it\n") }
            appendCommand("desktop globals", arrayOf("sh", "-c", "settings list global | grep -Ei '^[a-z0-9_.]*(desktop|pc_mode|projection)[a-z0-9_.]*='"))
            appendCommand("desktop system", arrayOf("sh", "-c", "settings list system | grep -Ei '^[a-z0-9_.]*(desktop|pc_mode|projection)[a-z0-9_.]*='"))
        }
        val previousLog = OperationLog.readLastSession(context).takeLast(20_000)
        val currentLog = OperationLog.read(context).takeLast(20_000)
        if (previousLog != currentLog) {
            append("\n[PREVIOUS DEXTOP SESSION LOG]\n")
            append(previousLog)
        }
        append("\n[CURRENT LOG]\n")
        // The normal diagnostics report already includes the last completed session;
        // current startup attempts are useful while the failed session is still open.
        append(currentLog)
    }.replace(Regex("(?i)\\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\\b"), "[MAC redacted]")

    private fun reflectDisplay(display: Display, method: String): Any? = runCatching {
        Display::class.java.getMethod(method).invoke(display)
    }.getOrNull()

    private fun StringBuilder.appendCommand(label: String, args: Array<String>) {
        val result = access.execute(*args)
        append("$label exit=${result.exitCode}\n${result.output.take(12_000)}\n${result.error.take(2_000)}\n")
    }
}
