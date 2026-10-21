package io.github.hassanjaveed84.aatube.core

/** How far each speed source got when the car session started it. */
enum class SourceSetup {
    NOT_STARTED,
    STARTED,
    API_LEVEL_TOO_LOW,
    NO_PERMISSION,
    REFUSED,
    UNSUPPORTED,
    PROVIDER_MISSING,
}

/** State of the location keep-alive foreground service. */
enum class KeepAliveState { NOT_STARTED, START_REQUESTED, RUNNING, REFUSED, STOPPED }

/**
 * Everything needed to explain a gate decision after the fact: which
 * speed source decided it (car, phone GPS or neither), each source's last
 * reading and age, permissions, sensor availability and the keep-alive
 * service. Formatted as one greppable log line.
 */
data class GateDiagnostics(
    val nowMillis: Long,
    val evaluation: ParkedGate.Evaluation?,
    val carApiLevel: Int,
    val carSetup: SourceSetup,
    val carSpeedPermission: Boolean,
    val car: SpeedReading?,
    val carStatus: String?,
    val carCallbacks: Int,
    val fineLocationPermission: Boolean,
    val coarseLocationPermission: Boolean,
    val gpsSetup: SourceSetup,
    val gpsProviderEnabled: Boolean?,
    val phone: SpeedReading?,
    val phoneAccuracyMeters: Float?,
    val phoneFixes: Int,
    val keepAlive: KeepAliveState,
    val notificationsPermission: Boolean,
) {
    /** Which source(s) the gate could use at the last evaluation. */
    val speedSource: String
        get() {
            val e = evaluation ?: return "NONE"
            return when {
                e.carUsable && e.phoneUsable -> "CAR+PHONE_GPS"
                e.carUsable -> "CAR"
                e.phoneUsable -> "PHONE_GPS"
                else -> "NONE"
            }
        }

    fun format(): String = buildString {
        val state = evaluation?.state
        append("decision=").append(state?.let(TransitionLog::describe) ?: "not evaluated")
        append(" speedSource=").append(speedSource)
        if (!evaluation?.movingBy.isNullOrEmpty()) append(" movingBy=").append(evaluation!!.movingBy.joinToString("+"))
        append(" | car: api=").append(carApiLevel)
        append(" setup=").append(carSetup)
        append(" perm=").append(granted(carSpeedPermission))
        append(" last=").append(reading(car))
        append(" status=").append(carStatus ?: "none")
        append(" callbacks=").append(carCallbacks)
        append(" | gps: perm fine=").append(granted(fineLocationPermission))
        append(" coarse=").append(granted(coarseLocationPermission))
        append(" setup=").append(gpsSetup)
        append(" provider=").append(
            when (gpsProviderEnabled) {
                true -> "enabled"
                false -> "disabled"
                null -> "unknown"
            },
        )
        append(" last=").append(reading(phone))
        phoneAccuracyMeters?.let { append(" acc=").append(fmt(it)).append("m") }
        append(" fixes=").append(phoneFixes)
        append(" | keepAlive=").append(keepAlive)
        append(" notifications=").append(granted(notificationsPermission))
    }

    private fun reading(r: SpeedReading?): String = when {
        r == null -> "none"
        !r.available || r.metersPerSecond == null -> "unavailable(age ${age(r)})"
        else -> "${fmt(r.metersPerSecond)}m/s(age ${age(r)})"
    }

    private fun age(r: SpeedReading): String = fmt((nowMillis - r.atMillis) / 1000f) + "s"

    private fun granted(value: Boolean) = if (value) "granted" else "missing"

    private fun fmt(value: Float): String = "%.2f".format(java.util.Locale.ROOT, value)
}
