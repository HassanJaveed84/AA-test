package io.github.hassanjaveed84.aatube.motion

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import io.github.hassanjaveed84.aatube.core.AppEvent
import io.github.hassanjaveed84.aatube.core.AppHub
import io.github.hassanjaveed84.aatube.core.GateState
import io.github.hassanjaveed84.aatube.core.ParkedGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feeds the parked-only gate from the car's speed (Car App Library, car API
 * level 3+, CAR_SPEED permission) and, as a fallback, the phone's GPS speed.
 * Everything runs on the main thread. Readings are only read, never written
 * back to the car, and the gate cannot be bypassed from here.
 */
class SpeedMonitor(private val carContext: CarContext) {

    private val gate = ParkedGate()
    private var lastGate: GateState? = null
    private var ticker: Job? = null
    private var carListener: OnCarDataAvailableListener<Speed>? = null
    private var lastCarLog = 0L
    private var lastPhoneLog = 0L

    private val locationListener = LocationListener { location: Location ->
        val speed = if (location.hasSpeed()) location.speed else null
        val now = AppHub.clock()
        gate.onPhoneSpeed(speed, now)
        if (now - lastPhoneLog > SAMPLE_LOG_INTERVAL_MS) {
            lastPhoneLog = now
            AppHub.record("speed", "phone gps speed=${speed ?: "n/a"} m/s acc=${location.accuracy}m")
        }
        evaluate()
    }

    fun start(scope: CoroutineScope) {
        startCarSpeed()
        startPhoneGps()
        ticker = scope.launch {
            while (isActive) {
                evaluate()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        carListener?.let { listener ->
            runCatching {
                carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(listener)
            }
        }
        carListener = null
        runCatching {
            carContext.getSystemService(LocationManager::class.java).removeUpdates(locationListener)
        }
    }

    /** Restarts the sources after permissions change. */
    fun restartSources() {
        stop()
        startCarSpeed()
        startPhoneGps()
    }

    private fun startCarSpeed() {
        val level = carContext.carAppApiLevel
        if (level < 3) {
            AppHub.record("speed", "car speed unavailable: host car API level $level < 3")
            return
        }
        if (!granted(CAR_SPEED_PERMISSION)) {
            AppHub.record("speed", "car speed unavailable: $CAR_SPEED_PERMISSION not granted")
            return
        }
        val listener = OnCarDataAvailableListener<Speed> { speed ->
            val raw = speed.rawSpeedMetersPerSecond
            val ok = raw.status == CarValue.STATUS_SUCCESS && raw.value != null
            val now = AppHub.clock()
            gate.onCarSpeed(raw.value, ok, now)
            if (now - lastCarLog > SAMPLE_LOG_INTERVAL_MS || !ok) {
                lastCarLog = now
                AppHub.record("speed", "car speed=${raw.value ?: "n/a"} m/s status=${statusName(raw.status)}")
            }
            evaluate()
        }
        try {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(ContextCompat.getMainExecutor(carContext), listener)
            carListener = listener
            AppHub.record("speed", "car speed listener registered")
        } catch (e: SecurityException) {
            AppHub.record("speed", "car speed refused by host: ${e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            AppHub.record("speed", "car speed unsupported: ${e.javaClass.simpleName}")
        }
    }

    private fun startPhoneGps() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AppHub.record("speed", "phone GPS unavailable: location permission not granted")
            return
        }
        try {
            carContext.getSystemService(LocationManager::class.java).requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper(),
            )
            AppHub.record("speed", "phone GPS updates requested")
        } catch (e: SecurityException) {
            AppHub.record("speed", "phone GPS refused: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            AppHub.record("speed", "phone GPS provider missing")
        }
    }

    private fun evaluate() {
        val state = gate.evaluate(AppHub.clock())
        if (state != lastGate) {
            lastGate = state
            AppHub.dispatch(AppEvent.GateChanged(state))
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(carContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun statusName(status: Int): String = when (status) {
        CarValue.STATUS_SUCCESS -> "SUCCESS"
        CarValue.STATUS_UNIMPLEMENTED -> "UNIMPLEMENTED"
        CarValue.STATUS_UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }

    companion object {
        const val CAR_SPEED_PERMISSION = "com.google.android.gms.permission.CAR_SPEED"
        private const val TICK_MS = 500L
        private const val GPS_INTERVAL_MS = 1_000L
        private const val SAMPLE_LOG_INTERVAL_MS = 5_000L
    }
}
