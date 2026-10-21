package io.github.hassanjaveed84.aatube.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.AppManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.hassanjaveed84.aatube.BuildConfig
import io.github.hassanjaveed84.aatube.core.AppEvent
import io.github.hassanjaveed84.aatube.core.AppHub
import io.github.hassanjaveed84.aatube.core.SurfaceInfo
import io.github.hassanjaveed84.aatube.lab.LabLog
import io.github.hassanjaveed84.aatube.motion.LocationKeepAliveService
import io.github.hassanjaveed84.aatube.motion.SpeedMonitor
import io.github.hassanjaveed84.aatube.player.CarPlayerDisplay
import kotlinx.coroutines.launch

/**
 * One Android Auto connection. Owns the single SurfaceCallback (the host
 * does not re-deliver onSurfaceAvailable if the callback changes), the speed
 * monitor that drives the parked-only gate, and the bridge from app state to
 * the car-screen player.
 */
class TubeSession : Session(), DefaultLifecycleObserver {

    private var speedMonitor: SpeedMonitor? = null
    private var labLog: LabLog? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            val surface = surfaceContainer.surface ?: return
            val info = SurfaceInfo(surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi)
            AppHub.dispatch(AppEvent.SurfaceAvailable(info))
            CarPlayerDisplay.attach(carContext, surface, info.width, info.height, info.dpi)
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            CarPlayerDisplay.detach()
            AppHub.dispatch(AppEvent.SurfaceDestroyed)
        }

        override fun onVisibleAreaChanged(visibleArea: android.graphics.Rect) {
            AppHub.record("surface", "visible area $visibleArea")
        }

        override fun onStableAreaChanged(stableArea: android.graphics.Rect) {
            AppHub.record("surface", "stable area $stableArea")
        }

        override fun onClick(x: Float, y: Float) {
            CarPlayerDisplay.tap(x, y)
        }

        override fun onScroll(distanceX: Float, distanceY: Float) {
            CarPlayerDisplay.scroll(distanceX, distanceY)
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreateScreen(intent: Intent): Screen {
        if (BuildConfig.LAB) labLog = LabLog.start(carContext)
        AppHub.dispatch(AppEvent.CarSessionStarted)
        AppHub.record(
            "host",
            "car API level ${carContext.carAppApiLevel}, host ${carContext.hostInfo?.packageName}, " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) build ${Build.DISPLAY}",
        )
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

        val monitor = SpeedMonitor(carContext)
        speedMonitor = monitor
        monitor.start(lifecycleScope)
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) LocationKeepAliveService.start(carContext)
        requestMissingPermissions(monitor)

        lifecycleScope.launch { AppHub.state.collect { CarPlayerDisplay.render(it) } }
        lifecycleScope.launch { AppHub.commands.collect { CarPlayerDisplay.command(it) } }
        return PlayerScreen(carContext)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        speedMonitor?.stop()
        speedMonitor = null
        LocationKeepAliveService.stop(carContext)
        CarPlayerDisplay.detach()
        AppHub.dispatch(AppEvent.CarSessionEnded)
        labLog?.close()
        labLog = null
    }

    private fun requestMissingPermissions(monitor: SpeedMonitor) {
        val missing = listOf(SpeedMonitor.CAR_SPEED_PERMISSION, Manifest.permission.ACCESS_FINE_LOCATION)
            .filterNot(::granted)
        if (missing.isEmpty()) return
        try {
            carContext.requestPermissions(missing) { granted, rejected ->
                AppHub.record("permission", "granted=$granted rejected=$rejected")
                if (granted.isNotEmpty()) {
                    monitor.restartSources()
                    if (Manifest.permission.ACCESS_FINE_LOCATION in granted) LocationKeepAliveService.start(carContext)
                }
            }
        } catch (e: RuntimeException) {
            AppHub.record("permission", "car permission request failed: ${e.javaClass.simpleName}")
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(carContext, permission) == PackageManager.PERMISSION_GRANTED
}
