package io.github.hassanjaveed84.aatube.motion

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import io.github.hassanjaveed84.aatube.core.AppEvent
import io.github.hassanjaveed84.aatube.core.AppHub
import io.github.hassanjaveed84.aatube.core.GateDiagnostics
import io.github.hassanjaveed84.aatube.core.GateState
import io.github.hassanjaveed84.aatube.core.ParkedGate
import io.github.hassanjaveed84.aatube.core.SourceSetup
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
 *
 * Every gate transition, and a periodic snapshot, is logged with which
 * source decided it, each source's last reading and age, permissions and
 * sensor setup (see [GateDiagnostics]).
 */
class SpeedMonitor(private val carContext: CarContext) {

    private val gate = ParkedGate()
    private var lastGate: GateState? = null
    private var ticker: Job? = null
    private var carListener: OnCarDataAvailableListener<Speed>? = null
    private var gpsRegistered = false
    private var lastCarLog = 0L
    private var lastPhoneLog = 0L
    private var lastSnapshot = 0L

    private var carSetup = SourceSetup.NOT_STARTED
    private var gpsSetup = SourceSetup.NOT_STARTED
    private var carStatus: String? = null
    private var carCallbacks = 0
    private var phoneFixes = 0
    private var phoneAccuracy: Float? = null

    private val locationListener = LocationListener { location: Location ->
        val speed = if (location.hasSpeed()) location.speed else null
        val now = AppHub.clock()
        phoneFixes++
        phoneAccuracy = if (location.hasAccuracy()) location.accuracy else null
        gate.onPhoneSpeed(speed, now)
        if (now - lastPhoneLog > SAMPLE_LOG_INTERVAL_MS) {
            lastPhoneLog = now
            val speedAccuracy = if (location.hasSpeedAccuracy()) {
                " speedAcc=${location.speedAccuracyMetersPerSecond}m/s"
            } else {
                ""
            }
            AppHub.record(
                "speed",
                "phone gps speed=${speed ?: "n/a"} m/s acc=${phoneAccuracy ?: "n/a"}m$speedAccuracy provider=${location.provider}",
            )
        }
        evaluate()
    }

    fun start(scope: CoroutineScope) {
        logPermissions("session start")
        startSources()
        ticker = scope.launch {
            while (isActive) {
                evaluate()
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        stopSources()
    }

    /**
     * Restarts the speed sources after permissions change. The evaluation
     * ticker keeps running: without it, phone GPS readings would never expire
     * and a SETTLING gate would only reopen on the next reading.
     */
    fun restartSources() {
        logPermissions("permissions changed")
        stopSources()
        startSources()
        evaluate(forceSnapshot = true)
    }

    private fun startSources() {
        startCarSpeed()
        startPhoneGps()
    }

    private fun stopSources() {
        carListener?.let { listener ->
            runCatching {
                carContext.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(listener)
            }
        }
        carListener = null
        if (gpsRegistered) {
            runCatching { carContext.getSystemService(LocationManager::class.java).removeUpdates(locationListener) }
            gpsRegistered = false
        }
    }

    private fun startCarSpeed() {
        val level = carContext.carAppApiLevel
        if (level < 3) {
            carSetup = SourceSetup.API_LEVEL_TOO_LOW
            AppHub.record("speed", "car speed unavailable: host car API level $level < 3")
            return
        }
        if (!granted(CAR_SPEED_PERMISSION)) {
            carSetup = SourceSetup.NO_PERMISSION
            AppHub.record("speed", "car speed unavailable: $CAR_SPEED_PERMISSION not granted")
            return
        }
        val listener = OnCarDataAvailableListener<Speed> { speed ->
            val raw = speed.rawSpeedMetersPerSecond
            val ok = raw.status == CarValue.STATUS_SUCCESS && raw.value != null
            val now = AppHub.clock()
            carCallbacks++
            val status = statusName(raw.status)
            val statusChanged = status != carStatus
            carStatus = status
            gate.onCarSpeed(raw.value, ok, now)
            if (now - lastCarLog > SAMPLE_LOG_INTERVAL_MS || statusChanged || carCallbacks == 1) {
                lastCarLog = now
                AppHub.record("speed", "car speed=${raw.value ?: "n/a"} m/s status=$status (callback #$carCallbacks)")
            }
            evaluate()
        }
        try {
            carContext.getCarService(CarHardwareManager::class.java).carInfo
                .addSpeedListener(ContextCompat.getMainExecutor(carContext), listener)
            carListener = listener
            carSetup = SourceSetup.STARTED
            AppHub.record("speed", "car speed listener registered (car API level $level)")
        } catch (e: SecurityException) {
            carSetup = SourceSetup.REFUSED
            AppHub.record("speed", "car speed refused by host: ${e.javaClass.simpleName}")
        } catch (e: IllegalStateException) {
            carSetup = SourceSetup.UNSUPPORTED
            AppHub.record("speed", "car speed unsupported: ${e.javaClass.simpleName}")
        }
    }

    private fun startPhoneGps() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            gpsSetup = SourceSetup.NO_PERMISSION
            AppHub.record("speed", "phone GPS unavailable: precise location permission not granted")
            return
        }
        val manager = carContext.getSystemService(LocationManager::class.java)
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_INTERVAL_MS, 0f, locationListener, Looper.getMainLooper(),
            )
            gpsRegistered = true
            gpsSetup = SourceSetup.STARTED
            AppHub.record("speed", "phone GPS updates requested (provider ${providerState(manager)})")
        } catch (e: SecurityException) {
            gpsSetup = SourceSetup.REFUSED
            AppHub.record("speed", "phone GPS refused: ${e.javaClass.simpleName}")
        } catch (e: IllegalArgumentException) {
            gpsSetup = SourceSetup.PROVIDER_MISSING
            AppHub.record("speed", "phone GPS provider missing")
        }
    }

    private fun evaluate(forceSnapshot: Boolean = false) {
        val now = AppHub.clock()
        val state = gate.evaluate(now)
        val changed = state != lastGate
        if (changed) {
            lastGate = state
            AppHub.dispatch(AppEvent.GateChanged(state))
        }
        if (changed || forceSnapshot || now - lastSnapshot >= SNAPSHOT_INTERVAL_MS) {
            lastSnapshot = now
            AppHub.record(if (changed) "gate-transition" else "gate-snapshot", diagnostics(now).format())
        }
    }

    private fun diagnostics(now: Long): GateDiagnostics {
        val manager = runCatching { carContext.getSystemService(LocationManager::class.java) }.getOrNull()
        return GateDiagnostics(
            nowMillis = now,
            evaluation = gate.lastEvaluation,
            carApiLevel = carContext.carAppApiLevel,
            carSetup = carSetup,
            carSpeedPermission = granted(CAR_SPEED_PERMISSION),
            car = gate.carReading,
            carStatus = carStatus,
            carCallbacks = carCallbacks,
            fineLocationPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseLocationPermission = granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            gpsSetup = gpsSetup,
            gpsProviderEnabled = manager?.let { runCatching { it.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull() },
            phone = gate.phoneReading,
            phoneAccuracyMeters = phoneAccuracy,
            phoneFixes = phoneFixes,
            keepAlive = LocationKeepAliveService.state,
            notificationsPermission = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    private fun logPermissions(why: String) {
        val list = listOf(
            CAR_SPEED_PERMISSION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).joinToString(" ") { "${it.substringAfterLast('.')}=${if (granted(it)) "granted" else "missing"}" }
        AppHub.record("permission", "$why: $list")
    }

    private fun providerState(manager: LocationManager): String =
        when (runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrNull()) {
            true -> "enabled"
            false -> "DISABLED: turn on Location"
            null -> "unknown"
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
        private const val SNAPSHOT_INTERVAL_MS = 10_000L
    }
}
