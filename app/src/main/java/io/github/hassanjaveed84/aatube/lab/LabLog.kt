package io.github.hassanjaveed84.aatube.lab

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import io.github.hassanjaveed84.aatube.BuildConfig
import io.github.hassanjaveed84.aatube.core.AppHub
import io.github.hassanjaveed84.aatube.core.TransitionLog
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lab build only: appends every AppHub log line to a per-session file in the
 * app's private storage, for export from the phone screen. Lines are already
 * redacted by TransitionLog. No telemetry: the file never leaves the phone
 * unless the user shares it.
 */
class LabLog private constructor(private val file: File) {

    private val io = Executors.newSingleThreadExecutor()
    private val writer = FileWriter(file, true)
    private val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT)
    private val listener: (TransitionLog.Entry) -> Unit = { entry ->
        io.execute {
            writer.write("${time.format(Date(entry.atMillis))} [${entry.category}] ${entry.message}\n")
            writer.flush()
        }
    }

    fun close() {
        AppHub.removeLogListener(listener)
        io.execute { writer.close() }
        io.shutdown()
    }

    companion object {
        fun directory(context: Context): File = File(context.filesDir, "lab").apply { mkdirs() }

        fun latest(context: Context): File? =
            directory(context).listFiles()?.filter { it.name.endsWith(".log") }?.maxByOrNull { it.lastModified() }

        fun start(context: Context): LabLog {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val file = File(directory(context), "aatube-lab-$stamp.log")
            file.writeText(header(context))
            return LabLog(file).also { AppHub.addLogListener(it.listener) }
        }

        private fun header(context: Context): String {
            val pm = context.packageManager
            fun version(pkg: String) = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0).versionName
                }
            }.getOrNull() ?: "not found"
            return buildString {
                appendLine("# AA Tube lab log")
                appendLine("# app ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}")
                appendLine("# phone ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
                appendLine("# Android Auto ${version("com.google.android.projection.gearhead")}")
                appendLine("# WebView ${WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"}")
                appendLine("# parked-only gate: enabled (all builds)")
            }
        }
    }
}
