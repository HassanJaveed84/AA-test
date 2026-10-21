package io.github.hassanjaveed84.aatube.car

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for the Android Auto host. Registered in the NAVIGATION
 * category, the only Android Auto category granted a drawable surface
 * (ACCESS_SURFACE). That is not a supported way to ship a video app: this
 * app is for personal sideload/internal testing and must not be published.
 */
class TubeCarAppService : CarAppService() {

    // hosts_allowlist_sample is the allowlist Android's own docs point to.
    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return if (debuggable) {
            // Debug builds also accept the Desktop Head Unit and test hosts.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }
    }

    override fun onCreateSession(): Session = TubeSession()
}
