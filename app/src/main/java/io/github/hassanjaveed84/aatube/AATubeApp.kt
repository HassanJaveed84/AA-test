package io.github.hassanjaveed84.aatube

import android.app.Application
import android.util.Log
import io.github.hassanjaveed84.aatube.core.AppHub

/**
 * Mirrors every AppHub log line (already redacted) to logcat under the tag
 * [LOG_TAG], so `adb logcat -s AATube` collects the gate, speed, surface,
 * player and pipeline evidence from any build without root or file access.
 */
class AATubeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppHub.addLogListener { entry -> Log.i(LOG_TAG, "[${entry.category}] ${entry.message}") }
        AppHub.record("app", "process start ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }

    companion object {
        const val LOG_TAG = "AATube"
    }
}
