package com.mikewen.autopilot.sensor

import android.util.Log
import kotlin.math.*

/**
 * SensorFusion — portable heading/position fusion engine.
 *
 * NO Android imports beyond Log. Pure Kotlin math only.
 * Can be copied to any project (autopilot firmware companion, desktop tool, etc.)
 *
 * Inputs  (via processXxx() methods):
 *   processA1()  — IMU + Mag raw at 50Hz  (QMI8658C + MMC5603)
 *   processA2()  — LC02H GNSS heading at 1Hz  (PQTMTAR + RMC)
 *   processA3()  — Position at 0.2Hz  (RMC/GGA lat/lon)
 *   processCasicNav2Sol() — raw ECEF position+velocity from CASIC receiver
 *   processNmeaRmc()      — standard NMEA speed/heading (phone GPS)
 *   processNmeaGga()      — standard NMEA fix quality/sats (phone GPS)
 *
 * Output (via [onFusedHeading] callback, called after every update):
 *   FusedState — heading, speed, position, confidence, source
 *
 * Tuning constants (adjust to match your hardware):
 *   GYRO_SCALE_DEG_S   — QMI8658C gyro LSB → °/s
 *   MAX_HEADING_RATE   — rate limiter in °/s (rejects spikes)
 */
class SensorFusion {

    // ── Tuning ────────────────────────────────────────────────────────────────

    /** QMI8658C gyro scale. ± 128°/s range → 128/32768 . Adjust to firmware config. */
    var gyroScaleDegS: Float = 1f / 256f

    /** Set true if turning right decreases heading (gz axis inverted on your PCB). */
    var gyroZFlipped: Boolean = true   // flipped by default for this hardware setup

    /** Maximum believable yaw rate in °/s — rejects vibration spikes */
    var maxHeadingRateDegS: Float = 60f

    /** Base deadband in degrees (calm sea) */
    var baseDeadbandDeg: Float = 3f

    /** Additional deadband per unit of sea state (0–1) */
    var seaDeadbandScale: Float = 12f

    var lastGyroZDegS: Float = 0f

    // Raw A1 packet values (LSB counts) — exposed for calibration verification.
    // Compare: lastRawGz * gyroScaleDegS ≈ lastGyroZDegS (before bias subtraction)
    var lastRawGx: Short = 0; var lastRawGy: Short = 0; var lastRawGz: Short = 0
    var lastRawAx: Short = 0; var lastRawAy: Short = 0; var lastRawAz: Short = 0
    var lastRawMx: Short = 0; var lastRawMy: Short = 0; var lastRawMz: Short = 0

    // ── State ─────────────────────────────────────────────────────────────────

    data class FusedState(
        val headingDeg:           Float   = 0f,
        val speedKnots:           Float   = 0f,
        val latDeg:               Double  = 0.0,
        val lonDeg:               Double  = 0.0,
        val altM:                 Float   = 0f,
        val hasHeading:           Boolean = false,
        val hasFix:               Boolean = false,
        val satellites:           Int     = 0,
        val headingConf:          Float   = 0f,
        val seaState:             Float   = 0f,
        val tiltDeg:              Float   = 0f,
        val autoDeadbandDeg:      Float   = 2f,
        val magCalibrated:        Boolean = false,
        val rawMagHeadingDeg:     Float   = 0f,
        val magDeclinationDeg:    Float   = 0f,
        val tarMisalignDeg:       Float   = 0f,
        val tarMisalignCalibrated: Boolean = false,
        val source:               String  = "none",
        val debugMsg:             String  = "",
    )

    private var state = FusedState()
    fun getState(): FusedState = state

    /** Called after every state update. Implementations should be non-blocking. */
    var onFusedHeading: ((FusedState) -> Unit)? = null

    // ── #1 Sea state estimation ────────────────────────────────────────────────

    private val SEA_STATE_WINDOW = 100

    var seaAzCalm:   Float = 200f
    var seaAzRough:  Float = 2000f
    var seaGxyCalm:  Float = 100f
    var seaGxyRough: Float = 800f

    private val azWindow    = FloatArray(SEA_STATE_WINDOW)
    private val gxyWindow   = FloatArray(SEA_STATE_WINDOW)
    private var seaWinIdx   = 0
    private var seaWinFull  = false
    private var azLpf       = 0f
    private var gxLpf       = 0f
    private var gyLpf       = 0f

    private fun updateSeaState(az: Short, gx: Short, gy: Short): Float {
        val azF = az.toFloat(); azLpf = azLpf * 0.99f + azF * 0.01f
        val gxF = gx.toFloat(); gxLpf = gxLpf * 0.99f + gxF * 0.01f
        val gyF = gy.toFloat(); gyLpf = gyLpf * 0.99f + gyF * 0.01f

        azWindow[seaWinIdx]  = azF - azLpf
        val gxyHp = sqrt((gxF - gxLpf) * (gxF - gxLpf) + (gyF - gyLpf) * (gyF - gyLpf))
        gxyWindow[seaWinIdx] = gxyHp

        seaWinIdx = (seaWinIdx + 1) % SEA_STATE_WINDOW
        if (seaWinIdx == 0) seaWinFull = true

        val n = if (seaWinFull) SEA_STATE_WINDOW else seaWinIdx
        if (n < 10) return state.seaState

        fun stddev(arr: FloatArray): Float {
            var sum = 0f
            for (i in 0 until n) sum += arr[i]
            val mean = sum / n
            var variance = 0f
            for (i in 0 until n) { val d = arr[i] - mean; variance += d * d }
            return sqrt(variance / n)
        }
        val azStd  = stddev(azWindow)
        val gxyStd = stddev(gxyWindow)

        val azSea  = ((azStd  - seaAzCalm)  / (seaAzRough  - seaAzCalm )).coerceIn(0f, 1f)
        val gxySea = ((gxyStd - seaGxyCalm) / (seaGxyRough - seaGxyCalm)).coerceIn(0f, 1f)
        return maxOf(azSea, gxySea)
    }

    // ── #3 Automatic deadband ─────────────────────────────────────────────────

    fun computeAutoDeadband(seaState: Float): Float =
        baseDeadbandDeg + seaState * seaDeadbandScale

    // ── #2 GPS auto-calibration of MMC5603 ────────────────────────────────────

    private val MAG_CAL_SPEED_KT   = 2.0f
    private val MAG_CAL_SEA_STATE  = 0.15f
    private val MAG_CAL_GPS_CONF   = 0.75f
    private val MAG_CAL_WINDOW     = 30
    private val MAG_CAL_STABLE_DEG = 5f

    private val magBiasWindow = FloatArray(MAG_CAL_WINDOW)
    private var magBiasIdx    = 0
    private var magBiasFull   = false
    var magBiasEstimate: Float = 0f
        private set
    var magCalibrated: Boolean = false
        private set

    private fun updateMagCalibration(
        gpsHeading: Float, rawMagHeading: Float,
        speedKt: Float, seaState: Float, gpsConf: Float
    ) {
        if (speedKt < MAG_CAL_SPEED_KT || seaState > MAG_CAL_SEA_STATE || gpsConf < MAG_CAL_GPS_CONF)
            return

        var bias = gpsHeading - rawMagHeading
        while (bias >  180f) bias -= 360f
        while (bias < -180f) bias += 360f

        magBiasWindow[magBiasIdx] = bias
        magBiasIdx = (magBiasIdx + 1) % MAG_CAL_WINDOW
        if (magBiasIdx == 0) magBiasFull = true

        val n = if (magBiasFull) MAG_CAL_WINDOW else magBiasIdx
        if (n < MAG_CAL_WINDOW) return

        val mean = magBiasWindow.sum() / n
        val variance = magBiasWindow.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / n
        val stddev = sqrt(variance)

        if (stddev < MAG_CAL_STABLE_DEG) {
            magBiasEstimate = mean
            magCalibrated   = true
        }
    }

    // ── Manual calibration support ─────────────────────────────────────────────

    data class MagCalPoint(val mx: Float, val my: Float)
    private val manualCalPoints = mutableListOf<MagCalPoint>()
    var isManualCalActive = false
        private set
    var manualCalHardIronX = 0f; var manualCalHardIronY = 0f

    fun startManualMagCal() { manualCalPoints.clear(); isManualCalActive = true }

    fun feedManualMagSample(mx: Short, my: Short) {
        if (isManualCalActive) manualCalPoints.add(MagCalPoint(mx.toFloat(), my.toFloat()))
    }

    fun finishManualMagCal(): Boolean {
        isManualCalActive = false
        if (manualCalPoints.size < 36) return false
        manualCalHardIronX = (manualCalPoints.maxOf { it.mx } + manualCalPoints.minOf { it.mx }) / 2f
        manualCalHardIronY = (manualCalPoints.maxOf { it.my } + manualCalPoints.minOf { it.my }) / 2f
        return true
    }

    var gyroBiasX = 0f; var gyroBiasY = 0f; var gyroBiasZ = 0f
    private val gyroBiasSamples = mutableListOf<Triple<Float,Float,Float>>()
    var isGyroBiasCalActive = false
        private set

    fun startGyroBiasCal() { gyroBiasSamples.clear(); isGyroBiasCalActive = true }

    fun feedGyroBiasSample(gx: Short, gy: Short, gz: Short) {
        if (isGyroBiasCalActive)
            gyroBiasSamples.add(Triple(gx.toFloat(), gy.toFloat(), gz.toFloat()))
    }

    fun finishGyroBiasCal(): Boolean {
        isGyroBiasCalActive = false
        if (gyroBiasSamples.size < 100) return false
        gyroBiasX = gyroBiasSamples.map { it.first  }.average().toFloat()
        gyroBiasY = gyroBiasSamples.map { it.second }.average().toFloat()
        gyroBiasZ = gyroBiasSamples.map { it.third  }.average().toFloat()
        return true
    }

    // ── LC02H Install Misalignment Auto-Calibration ───────────────────────────

    private val TAR_MISALIGN_SPEED_KT  = 4.0f
    private val TAR_MISALIGN_SEA_STATE = 0.15f
    private val TAR_MISALIGN_WINDOW    = 30
    private val TAR_MISALIGN_STABLE    = 3.0f
    private val TAR_MISALIGN_MAX       = 15.0f

    private val misalignWindow = FloatArray(TAR_MISALIGN_WINDOW)
    private var misalignIdx    = 0
    private var misalignFull   = false

    var tarMisalignEstimate:   Float   = 0f
        private set
    var tarMisalignCalibrated: Boolean = false
        private set

    private fun updateTarMisalignment(cogDeg: Float, tarDeg: Float, speedKt: Float, seaState: Float) {
        if (speedKt < TAR_MISALIGN_SPEED_KT || seaState > TAR_MISALIGN_SEA_STATE) return

        var bias = cogDeg - tarDeg
        while (bias >  180f) bias -= 360f
        while (bias < -180f) bias += 360f

        misalignWindow[misalignIdx] = bias
        misalignIdx = (misalignIdx + 1) % TAR_MISALIGN_WINDOW
        if (misalignIdx == 0) misalignFull = true

        val n = if (misalignFull) TAR_MISALIGN_WINDOW else misalignIdx
        if (n < TAR_MISALIGN_WINDOW) return

        var sum = 0f
        for (i in 0 until n) sum += misalignWindow[i]
        val mean = sum / n
        var variance = 0f
        for (i in 0 until n) { val d = misalignWindow[i] - mean; variance += d * d }
        val stddev = sqrt(variance / n)

        if (stddev < TAR_MISALIGN_STABLE && abs(mean) < TAR_MISALIGN_MAX) {
            tarMisalignEstimate   = mean
            tarMisalignCalibrated = true
            Log.i("SensorFusion", "LC02H misalign calibrated: ${"%.2f".format(mean)}° stddev=${"%.2f".format(stddev)}°")
        }
    }

    // ── Magnetic Declination ──────────────────────────────────────────────────

    private var magDeclinationDeg: Float = 0f
    private var lastDeclinationLat: Double = Double.NaN
    private var lastDeclinationLon: Double = Double.NaN

    fun setDeclination(declinationDeg: Float) { magDeclinationDeg = declinationDeg }

    var onDeclinationUpdated: ((Float) -> Unit)? = null

    private fun updateDeclination(latDeg: Double, lonDeg: Double) {
        if (!lastDeclinationLat.isNaN() &&
            abs(latDeg - lastDeclinationLat) < 0.5 &&
            abs(lonDeg - lastDeclinationLon) < 0.5) return

        lastDeclinationLat = latDeg
        lastDeclinationLon = lonDeg
        magDeclinationDeg  = computeDeclination(latDeg, lonDeg).toFloat()
        Log.i("SensorFusion", "Declination updated: ${"%.2f".format(magDeclinationDeg)}° at " +
                "${"%.3f".format(latDeg)}N ${"%.3f".format(lonDeg)}E")
        onDeclinationUpdated?.invoke(magDeclinationDeg)
    }

    fun computeDeclination(latDeg: Double, lonDeg: Double): Double {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)

        val g10 = -29404.5
        val g11 =  -1450.7
        val h11 =   4652.9

        val latGc = lat - 0.1924 * Math.toRadians(sin(2 * lat) * 180 / Math.PI)

        val cosLat = cos(latGc); val sinLat = sin(latGc)
        val cosLon = cos(lon);   val sinLon = sin(lon)

        val bx = (g11 * cosLon + h11 * sinLon) * sinLat - g10 * cosLat
        val by = (-g11 * sinLon + h11 * cosLon)

        var decl = Math.toDegrees(atan2(by, bx))
        decl += 0.013 * latDeg + 0.004 * lonDeg
        return decl
    }

    // ── Complementary filter ──────────────────────────────────────────────────

    private var filteredHeading   = 0f
    private var filterInitialised = false
    private var lastImuTimeMs     = 0L
    private var lastGnssTimeMs    = 0L

    fun applyFilter(sensorHeading: Float, gyroZDegS: Float, sensorWeight: Float, dtS: Float): Float {
        if (!filterInitialised) {
            filteredHeading   = sensorHeading
            filterInitialised = true
            return sensorHeading
        }
        val gyroHeading = ((filteredHeading + gyroZDegS * dtS) + 360f) % 360f
        var diff = sensorHeading - gyroHeading
        while (diff >  180f) diff -= 360f
        while (diff < -180f) diff += 360f
        val maxStep    = maxHeadingRateDegS * dtS
        val correction = diff.coerceIn(-maxStep, maxStep)
        filteredHeading = ((gyroHeading + sensorWeight * correction) + 360f) % 360f
        return filteredHeading
    }

    fun resetFilter() { filterInitialised = false; kalman.reset() }

    // ── Kalman filter ─────────────────────────────────────────────────────────

    var useKalman: Boolean = false

    inner class KalmanHeading {
        var theta: Float = 0f; var bias: Float = 0f
        var p00: Float = 10f; var p01: Float = 0f
        var p10: Float = 0f;  var p11: Float = 1f
        var initialised: Boolean = false

        var sigmaGyro:  Float = 0.5f
        var sigmaDrift: Float = 0.005f
        var sigmaMag:   Float = 15f
        var sigmaGps:   Float = 2f

        fun reset() { initialised = false; bias = 0f; p00=10f; p01=0f; p10=0f; p11=1f }

        fun init(headingDeg: Float) { theta = headingDeg; initialised = true }

        fun predict(gzDegS: Float, dtS: Float) {
            if (!initialised) return
            val thetaNew = wrapAngle(theta + (gzDegS - bias) * dtS)
            val qTheta = sigmaGyro  * sigmaGyro  * dtS
            val qBias  = sigmaDrift * sigmaDrift * dtS
            val p00New = p00 - dtS * (p10 + p01) + dtS * dtS * p11 + qTheta
            val p01New = p01 - dtS * p11
            val p10New = p10 - dtS * p11
            val p11New = p11 + qBias
            theta = thetaNew
            p00 = p00New; p01 = p01New; p10 = p10New; p11 = p11New
        }

        fun update(measuredDeg: Float, measurementNoise: Float) {
            if (!initialised) { init(measuredDeg); return }
            var innov = measuredDeg - theta
            while (innov >  180f) innov -= 360f
            while (innov < -180f) innov += 360f

            val R = measurementNoise.coerceAtLeast(0.01f)
            val S = p00 + R
            if (S <= 0f) return

            val k0 = p00 / S; val k1 = p10 / S
            theta = wrapAngle(theta + k0 * innov)
            bias  = (bias + k1 * innov).coerceIn(-10f, 10f)

            val a00 = 1f - k0; val a01 = 0f
            val a10 = -k1;     val a11 = 1f
            val t00 = a00*p00 + a01*p10; val t01 = a00*p01 + a01*p11
            val t10 = a10*p00 + a11*p10; val t11 = a10*p01 + a11*p11
            p00 = t00*a00 + t01*a01 + k0*R*k0
            p01 = t00*a10 + t01*a11 + k0*R*k1
            p10 = t10*a00 + t11*a01 + k1*R*k0
            p11 = t10*a10 + t11*a11 + k1*R*k1
            p00 = p00.coerceAtLeast(1e-4f)
            p11 = p11.coerceAtLeast(1e-6f)
        }

        private fun wrapAngle(a: Float): Float { var r = a % 360f; if (r < 0f) r += 360f; return r }
    }

    val kalman = KalmanHeading()

    // ── 0xA1 — IMU + Mag (50 Hz) ─────────────────────────────────────────────

    fun processA1(
        ax: Short, ay: Short, az: Short,
        gx: Short, gy: Short, gz: Short,
        mx: Short, my: Short, mz: Short,
        nowMs: Long,
        accelRotated180: Boolean = false
    ) {
        val dtS = if (lastImuTimeMs > 0L)
            ((nowMs - lastImuTimeMs) / 1000f).coerceIn(0f, 0.1f)
        else 0.02f
        lastImuTimeMs = nowMs

        // Store raw LSB values for calibration display before any processing
        lastRawGx = gx; lastRawGy = gy; lastRawGz = gz
        lastRawAx = ax; lastRawAy = ay; lastRawAz = az
        lastRawMx = mx; lastRawMy = my; lastRawMz = mz

        val gzCorrected = gz - gyroBiasZ
        val gyroZDegS   = gzCorrected * gyroScaleDegS * (if (gyroZFlipped) -1f else 1f)
        lastGyroZDegS = gyroZDegS

        val seaState     = updateSeaState(az, gx, gy)
        val autoDeadband = computeAutoDeadband(seaState)

        val axEff = if (accelRotated180) (-ax.toInt()).toShort() else ax
        val ayEff = if (accelRotated180) (-ay.toInt()).toShort() else ay

        val mxCal = (mx - manualCalHardIronX).toInt().toShort()
        val myCal = (my - manualCalHardIronY).toInt().toShort()

        val roll  = atan2(ayEff.toFloat(), az.toFloat())
        val pitch = atan2(-axEff.toFloat(), ayEff * sin(roll) + az * cos(roll))

        val mxH = mxCal * cos(pitch) + mz * sin(pitch)
        val myH = mxCal * sin(roll) * sin(pitch) + myCal * cos(roll) - mz * sin(roll) * cos(pitch)
        val rawMagHeading = ((Math.toDegrees(atan2(myH.toDouble(), mxH.toDouble()))
            .toFloat() + 360f) % 360f)

        val magHeadingAfterBias = if (magCalibrated)
            ((rawMagHeading + magBiasEstimate) + 360f) % 360f
        else rawMagHeading

        val magHeading = ((magHeadingAfterBias + magDeclinationDeg) + 360f) % 360f

        val accelNorm  = sqrt((axEff * axEff + ayEff * ayEff + az * az).toFloat())
        val tiltDeg    = if (accelNorm > 0f)
            Math.toDegrees(acos((az / accelNorm).toDouble().coerceIn(-1.0, 1.0))).toFloat()
        else 90f
        val tiltFactor = (1f - tiltDeg / 30f).coerceIn(0f, 1f)

        val gnssRecent = (nowMs - lastGnssTimeMs) < 3_000L
        val gnssFactor = if (gnssRecent) (1f - state.headingConf * 0.8f) else 1f
        val magBase    = if (magCalibrated) 0.12f else 0.02f

        val speedKt = state.speedKnots
        val speedFactor = when {
            speedKt < 3.0f -> 1.0f
            speedKt > 5.0f -> 0.1f
            else -> 1.0f - ((speedKt - 3.0f) / 2.0f) * 0.9f
        }
        val magWeight = (magBase * tiltFactor * gnssFactor * speedFactor).coerceIn(0.01f, 0.35f)

        val fused: Float
        val filterLabel: String
        if (useKalman) {
            if (!kalman.initialised) kalman.init(magHeading)
            kalman.predict(gyroZDegS, dtS)
            val rMag = kalman.sigmaMag * kalman.sigmaMag * (1f + (1f - tiltFactor) * 2f) * (1f + seaState)
            kalman.update(magHeading, rMag)
            fused = kalman.theta
            filterLabel = "KF mag R=${"%.1f".format(rMag)} b=${"%.3f".format(kalman.bias)}"
        } else {
            fused = applyFilter(magHeading, gyroZDegS, magWeight, dtS)
            filterLabel = "CF w=${"%.4f".format(magWeight)}"
        }

        val magConf: Float = if (gnssRecent) {
            state.headingConf
        } else {
            val magBase2       = if (magCalibrated) 0.6f else 0.3f
            val turnPenaltyMag = (1f - abs(gyroZDegS) / 40f).coerceIn(0.2f, 1f)
            val seaPenaltyMag  = (1f - seaState * 0.6f).coerceIn(0.4f, 1f)
            val tiltPenaltyMag = tiltFactor.coerceIn(0.3f, 1f)
            (magBase2 * turnPenaltyMag * seaPenaltyMag * tiltPenaltyMag).coerceIn(0f, 1f)
        }

        state = state.copy(
            headingDeg       = fused,
            hasHeading       = true,
            headingConf      = magConf,
            seaState         = seaState,
            tiltDeg          = tiltDeg,
            autoDeadbandDeg  = autoDeadband,
            magCalibrated    = magCalibrated,
            rawMagHeadingDeg = rawMagHeading,
            source           = if (useKalman) "kf:imu+mag" else "cf:imu+mag",
            debugMsg         = "A1: gz=${"%.2f".format(gyroZDegS)} mag=${"%.1f".format(magHeading)} tilt=${"%.1f".format(tiltDeg)} sea=${"%.2f".format(seaState)} db=${"%.1f".format(autoDeadband)}° conf=${"%.2f".format(magConf)} $filterLabel → ${"%.1f".format(fused)}"
        )
        onFusedHeading?.invoke(state)
    }

    // ── 0xA2 — LC02H GNSS heading (1 Hz) ─────────────────────────────────────

    var cachedRmcHeading = 0f
    var cachedRmcSpeed   = 0f
    private var cachedRmcValid       = false
    private var lastRawTarHeadingDeg = 0f

    fun processA2(
        tarHeadingDeg: Float, pitchDeg: Float, rollDeg: Float,
        tarAccDeg: Float, gnssQuality: Int, satellites: Int, nowMs: Long
    ) {
        val speedKt = cachedRmcSpeed
        lastRawTarHeadingDeg = tarHeadingDeg

        val correctedTarHdg = if (tarMisalignCalibrated)
            ((tarHeadingDeg + tarMisalignEstimate) + 360f) % 360f
        else tarHeadingDeg

        val qualFactor     = when (gnssQuality) { 4 -> 1.0f; 6 -> 0.5f; else -> 0.0f }
        val accFactor      = (1f - tarAccDeg / 20f).coerceIn(0f, 1f)
        val satFactor      = ((satellites - 4f) / 4f).coerceIn(0f, 1f)
        val tarSpeedFactor = ((speedKt - 0.3f) / 0.2f).coerceIn(0.4f, 1f)

        var wTar = qualFactor * accFactor * satFactor * tarSpeedFactor
        var wRmc = if (cachedRmcValid) ((speedKt - 0.5f) / 1.5f).coerceIn(0f, 1f) else 0f

        val totalW = wTar + wRmc
        if (totalW <= 0f) return

        wTar /= totalW; wRmc /= totalW

        var diff = cachedRmcHeading - correctedTarHdg
        while (diff >  180f) diff -= 360f
        while (diff < -180f) diff += 360f
        val blended = ((correctedTarHdg + wRmc * diff) + 360f) % 360f

        val rawConf = (qualFactor * accFactor * satFactor * 0.8f +
                wRmc * (speedKt / 2f).coerceIn(0f, 1f) * 0.2f).coerceIn(0f, 1f)
        val turnPenalty    = (1f - abs(lastGyroZDegS) / 40f).coerceIn(0.2f, 1f)
        val seaPenaltyGnss = (1f - state.seaState * 0.6f).coerceIn(0.4f, 1f)
        val tiltPenalty    = (1f - state.tiltDeg / 60f).coerceIn(0.5f, 1f)
        val conf           = (rawConf * turnPenalty * seaPenaltyGnss * tiltPenalty).coerceIn(0f, 1f)
        val filterW        = (0.05f + conf * 0.15f).coerceIn(0.05f, 0.20f)

        lastGnssTimeMs = nowMs
        val turnRate = abs(lastGyroZDegS)

        val fused: Float
        val filterLabel: String
        if (useKalman) {
            val accDegClamped = tarAccDeg.coerceIn(0.1f, 30f)
            val rGps = (accDegClamped * accDegClamped) / qualFactor.coerceAtLeast(0.1f)
            kalman.update(blended, rGps * (1f + turnRate / 10f))
            fused = kalman.theta
            filterLabel = "KF R=${"%.1f".format(rGps)}"
        } else {
            val turnFactor = (1f - turnRate / 20f).coerceIn(0.2f, 1f)
            fused = applyFilter(blended, lastGyroZDegS, filterW * turnFactor, 0.1f)
            filterLabel = "CF w=${"%.3f".format(filterW)}"
        }

        updateMagCalibration(blended, state.rawMagHeadingDeg, speedKt, state.seaState, conf)

        state = state.copy(
            headingDeg            = fused,
            speedKnots            = speedKt,
            hasHeading            = true,
            hasFix                = gnssQuality >= 4,
            satellites            = satellites,
            headingConf           = conf,
            magCalibrated         = magCalibrated,
            tarMisalignDeg        = tarMisalignEstimate,
            tarMisalignCalibrated = tarMisalignCalibrated,
            source                = if (useKalman) "kf:gnss+imu" else "cf:gnss+imu",
            debugMsg              = "A2: tar=${"%.1f".format(tarHeadingDeg)} → ${"%.1f".format(fused)} conf=${"%.2f".format(conf)} $filterLabel"
        )
        onFusedHeading?.invoke(state)
    }

    // ── 0xA3 — Position + RMC COG (0.2 Hz) ──────────────────────────────────

    fun processA3(latDeg: Double, lonDeg: Double, speedKt: Float, cogDeg: Float, hasFix: Boolean) {
        cachedRmcSpeed   = speedKt
        cachedRmcHeading = cogDeg
        cachedRmcValid   = hasFix && speedKt >= 0.5f

        if (hasFix && latDeg != 0.0) updateDeclination(latDeg, lonDeg)
        if (speedKt >= TAR_MISALIGN_SPEED_KT)
            updateTarMisalignment(cogDeg, lastRawTarHeadingDeg, speedKt, state.seaState)

        state = state.copy(
            latDeg                = latDeg, lonDeg = lonDeg,
            speedKnots            = speedKt, hasFix = hasFix,
            magDeclinationDeg     = magDeclinationDeg,
            tarMisalignDeg        = tarMisalignEstimate,
            tarMisalignCalibrated = tarMisalignCalibrated,
            source                = state.source,
            debugMsg              = "A3: lat=${"%.6f".format(latDeg)} spd=${"%.2f".format(speedKt)}kt cog=${"%.1f".format(cogDeg)}"
        )
        onFusedHeading?.invoke(state)
    }

    // ── CASIC NAV2-SOL ────────────────────────────────────────────────────────

    fun processCasicNav2Sol(
        ecefX: Double, ecefY: Double, ecefZ: Double,
        ecefVX: Double, ecefVY: Double, ecefVZ: Double,
        sAccMs: Float, fixOk: Boolean, fixType: Int
    ) {
        val (lat, lon, alt) = ecefToLla(ecefX, ecefY, ecefZ)
        val speedMs  = sqrt(ecefVX * ecefVX + ecefVY * ecefVY + ecefVZ * ecefVZ)
        val speedKt  = (speedMs * 1.94384).toFloat()
        val (ve, vn) = ecefVelToEnu(ecefVX, ecefVY, ecefVZ, lat, lon)
        val conf     = if (sAccMs > 0f) (speedMs / sAccMs).toFloat().coerceIn(0f, 1f) else 0f
        val hasHdg   = speedKt >= 0.3f && conf > 0.1f
        val heading  = if (hasHdg) ((Math.toDegrees(atan2(ve, vn)) + 360) % 360).toFloat()
        else state.headingDeg
        val hasFix   = fixOk && fixType >= 2
        if (hasHdg) {
            val fused = applyFilter(heading, 0f, 0.15f, 0.1f)
            state = state.copy(headingDeg = fused, speedKnots = speedKt, hasHeading = true,
                hasFix = hasFix, latDeg = lat, lonDeg = lon, altM = alt.toFloat(),
                headingConf = conf, source = "casic",
                debugMsg = "CASIC: hdg=${"%.1f".format(fused)} spd=$speedKt conf=${"%.2f".format(conf)}")
        } else {
            state = state.copy(hasFix = hasFix, latDeg = lat, lonDeg = lon,
                altM = alt.toFloat(), speedKnots = speedKt)
        }
        onFusedHeading?.invoke(state)
    }

    // ── NMEA RMC ──────────────────────────────────────────────────────────────

    fun processNmeaRmc(speedKt: Float, cogDeg: Float?, hasFix: Boolean, latDeg: Double?, lonDeg: Double?) {
        // Always update declination from phone GPS position — even when cogDeg is null.
        // This is the primary way declination gets seeded before any BLE A3 packet arrives.
        if (latDeg != null && lonDeg != null && latDeg != 0.0)
            updateDeclination(latDeg, lonDeg)

        // Phone GPS COG is only used as a very low-weight heading nudge when no IMU/BLE
        // heading is available. Weight 0.03 means gyro dominates; phone just prevents
        // indefinite drift when BLE is silent. If cogDeg is null (low speed / poor fix)
        // we only update speed and position, never heading.
        val hasHdg  = cogDeg != null && speedKt >= 0.5f
        val heading = if (hasHdg) applyFilter(cogDeg!!, 0f, 0.03f, 0.1f) else state.headingDeg

        // Always update lat/lon from phone GPS even when hasFix=false —
        // the position is needed for declination, map centering, and bearing calculation
        // long before accuracy is good enough to mark as a "fix".
        state = state.copy(
            headingDeg        = heading, speedKnots = speedKt,
            hasHeading        = if (hasHdg) true else state.hasHeading,
            hasFix            = hasFix,
            latDeg            = if (latDeg != null && latDeg != 0.0) latDeg else state.latDeg,
            lonDeg            = if (lonDeg != null && lonDeg != 0.0) lonDeg else state.lonDeg,
            magDeclinationDeg = magDeclinationDeg,
            source            = "nmea",
        )
        onFusedHeading?.invoke(state)
    }

    // ── NMEA GGA ──────────────────────────────────────────────────────────────

    fun processNmeaGga(satellites: Int, altM: Float, fixQuality: Int, latDeg: Double?, lonDeg: Double?) {
        state = state.copy(satellites = satellites, altM = altM, hasFix = fixQuality > 0,
            latDeg = latDeg ?: state.latDeg, lonDeg = lonDeg ?: state.lonDeg)
        onFusedHeading?.invoke(state)
    }

    // ── Geo helpers ───────────────────────────────────────────────────────────

    fun ecefToLla(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val a  = 6378137.0; val e2 = 6.6943799901414e-3
        val lon = Math.toDegrees(atan2(y, x))
        var lat = atan2(z, sqrt(x * x + y * y))
        repeat(5) {
            val N = a / sqrt(1 - e2 * sin(lat).pow(2))
            lat   = atan2(z + e2 * N * sin(lat), sqrt(x * x + y * y))
        }
        val N   = a / sqrt(1 - e2 * sin(lat).pow(2))
        val alt = sqrt(x * x + y * y) / cos(lat) - N
        return Triple(Math.toDegrees(lat), lon, alt)
    }

    fun ecefVelToEnu(vx: Double, vy: Double, vz: Double, latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val lat = Math.toRadians(latDeg); val lon = Math.toRadians(lonDeg)
        val ve  = -sin(lon) * vx + cos(lon) * vy
        val vn  = -sin(lat) * cos(lon) * vx - sin(lat) * sin(lon) * vy + cos(lat) * vz
        return Pair(ve, vn)
    }

    fun haversineNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 3440.065
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return R * 2 * asin(sqrt(a))
    }

    fun bearingTo(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val φ1 = Math.toRadians(lat1); val φ2 = Math.toRadians(lat2)
        val Δλ = Math.toRadians(lon2 - lon1)
        val y  = sin(Δλ) * cos(φ2)
        val x  = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    fun headingError(target: Float, actual: Float): Float {
        var err = target - actual
        while (err >  180f) err -= 360f
        while (err < -180f) err += 360f
        return err
    }
}