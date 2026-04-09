package com.mikewen.autopilot.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Environment
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.mikewen.autopilot.ble.MotorController
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/**
 * GpsManager — Android wrapper around [SensorFusion].
 *
 * Responsibilities:
 *   - Phone GPS via FusedLocationProviderClient → SensorFusion.processNmeaRmc
 *   - ae02 BLE bytes routing (from IMU sensor via MotorController.onAe02Raw):
 *       0xA1 → SensorFusion.processA1  (IMU+Mag 50Hz)
 *       0xA2 → SensorFusion.processA2  (LC02H heading 1Hz)
 *       0xA3 → SensorFusion.processA3  (position 0.2Hz)
 *       0xBA 0xCE → CASIC NAV2-SOL → SensorFusion.processCasicNav2Sol
 *   - Trip distance and max speed accumulation
 *   - CSV logging to Downloads/
 *   - Source preference (phone vs BLE)
 *
 * The secondary BLE sensor (IMU_PWM / ESC_PWM / BLDC_PWM) is connected via
 * [connectSensor2]. Its ae02 stream feeds SensorFusion for IMU+GPS fusion.
 *
 * All sensor fusion math lives in [SensorFusion].
 */
class GpsManager(private val context: Context) {

    companion object {
        private const val TAG = "GpsManager"
    }

    // ── Public data types ─────────────────────────────────────────────────────

    enum class Source { NONE, PHONE, BLE }

    data class GpsData(
        val source:            Source  = Source.NONE,
        val speedKnots:        Float   = 0f,
        val headingDeg:        Float   = 0f,
        val hasHeading:        Boolean = false,
        val hasFix:            Boolean = false,
        val satellites:        Int     = 0,
        val altitudeM:         Float   = 0f,
        val latDeg:            Double  = 0.0,
        val lonDeg:            Double  = 0.0,
        val speedAccMs:        Float   = Float.MAX_VALUE,
        val headingConfidence: Float   = 0f,
        val seaState:          Float   = 0f,
        val tiltDeg:           Float   = 0f,
        val autoDeadbandDeg:   Float   = 3f,
        val magCalibrated:     Boolean = false,
        val magDeclinationDeg: Float   = 0f,
        val source2:           String  = "none",   // SensorFusion source label
        val debugMsg:          String  = "",
        val bleGpsActive:      Boolean = false   // true = BLE A2/A3 fresh within last 5 s
    ) {
        val speedKmh: Float get() = speedKnots * 1.852f
        val headingCardinal: String get() = when {
            !hasHeading                               -> "—"
            headingDeg < 22.5 || headingDeg >= 337.5 -> "N"
            headingDeg < 67.5  -> "NE"
            headingDeg < 112.5 -> "E"
            headingDeg < 157.5 -> "SE"
            headingDeg < 202.5 -> "S"
            headingDeg < 247.5 -> "SW"
            headingDeg < 292.5 -> "W"
            else               -> "NW"
        }
    }

    // ── Fusion engine ─────────────────────────────────────────────────────────

    val fusion = SensorFusion()

    init {
        // Restore saved magnetic declination from SharedPreferences
        val calPrefs = context.getSharedPreferences("autopilot_calibration", Context.MODE_PRIVATE)
        val savedDecl = calPrefs.getFloat("mag_declination_deg", Float.NaN)
        if (!savedDecl.isNaN()) {
            fusion.setDeclination(savedDecl)
            Log.i(TAG, "Declination restored: ${"%.2f".format(savedDecl)}°")
        }
        // Restore mag hard-iron offsets
        fusion.manualCalHardIronX = calPrefs.getFloat("mag_hard_iron_x", 0f)
        fusion.manualCalHardIronY = calPrefs.getFloat("mag_hard_iron_y", 0f)
        // Restore gyro bias
        fusion.gyroBiasX = calPrefs.getFloat("gyro_bias_x", 0f)
        fusion.gyroBiasY = calPrefs.getFloat("gyro_bias_y", 0f)
        fusion.gyroBiasZ = calPrefs.getFloat("gyro_bias_z", 0f)

        // Persist declination whenever auto-updated from GPS
        fusion.onDeclinationUpdated = { decl ->
            calPrefs.edit().putFloat("mag_declination_deg", decl).apply()
            Log.i(TAG, "Declination saved: ${"%.2f".format(decl)}°")
        }
    }

    /** Persist current calibration values to SharedPreferences. */
    fun saveCalibration() {
        val calPrefs = context.getSharedPreferences("autopilot_calibration", Context.MODE_PRIVATE)
        calPrefs.edit()
            .putFloat("mag_hard_iron_x", fusion.manualCalHardIronX)
            .putFloat("mag_hard_iron_y", fusion.manualCalHardIronY)
            .putFloat("gyro_bias_x", fusion.gyroBiasX)
            .putFloat("gyro_bias_y", fusion.gyroBiasY)
            .putFloat("gyro_bias_z", fusion.gyroBiasZ)
            .apply()
    }

    private var currentData   = GpsData()
    private var currentSource = Source.NONE

    // ── Callbacks ─────────────────────────────────────────────────────────────

    var onUpdate:    ((GpsData) -> Unit)? = null
    var onNmeaDebug: ((String)  -> Unit)? = null
    var onLogStatus: ((String)  -> Unit)? = null

    // ── Secondary BLE sensor (IMU / ESC_PWM / BLDC_PWM / IMU_PWM) ────────────
    // Routes ae02 bytes into SensorFusion (A1/A2/A3/CASIC packets).

    private var sensor2: MotorController? = null
    val isSensor2Connected: Boolean get() = sensor2?.isConnected ?: false

    /**
     * Connect a secondary BLE sensor (any device using the ae00/ae30 service).
     * Its ae02 stream will be fed into SensorFusion alongside the primary device.
     * Typically used for an IMU_PWM sensor board.
     */
    fun connectSensor2(device: android.bluetooth.BluetoothDevice) {
        if (sensor2 == null) {
            sensor2 = MotorController(context)
            sensor2?.onAe02Raw = { bytes -> feedAe02Bytes(bytes) }
        }
        sensor2?.connectToDevice(device)
        Log.i(TAG, "Sensor2 connecting: ${device.name}")
    }

    fun disconnectSensor2() {
        sensor2?.disconnect()?.enqueue()
        sensor2?.close()
        sensor2 = null
    }

    // ── Hardware configuration ────────────────────────────────────────────────

    /** True if QMI8658C accel X/Y are mounted 180° from MMC5603 on the PCB. */
    var accelRotated180: Boolean = false

    var tripDistanceNm: Double = 0.0; private set
    var maxSpeedKnots:  Float  = 0f;  private set
    private var lastFixLat = Double.NaN
    private var lastFixLon = Double.NaN

    fun resetTrip() { tripDistanceNm = 0.0; maxSpeedKnots = 0f; lastFixLat = Double.NaN; lastFixLon = Double.NaN }

    // ── Source preference ─────────────────────────────────────────────────────

    private var usePhoneGps = true
    fun setPreferBleGps()   { usePhoneGps = false }
    fun setPreferPhoneGps() { usePhoneGps = true  }
    fun getCurrentData()    = currentData

    /** Timestamp of last received A2 or A3 packet (ms). Used for phone GPS fallback. */
    private var lastA2A3TimeMs: Long = 0L

    /**
     * Returns true if the BLE device has not sent an A2 or A3 packet for more than [timeoutMs].
     * When true the phone GPS is allowed regardless of [usePhoneGps], because the BLE
     * GPS source has gone silent and phone GPS is the only available position reference.
     */
    fun isBleGpsStale(timeoutMs: Long = 5_000L): Boolean =
        lastA2A3TimeMs == 0L || (System.currentTimeMillis() - lastA2A3TimeMs) > timeoutMs

    // ── Fusion callback wiring ────────────────────────────────────────────────

    init {
        fusion.onFusedHeading = { fs ->
            val isPhoneUpdate = fs.source == "nmea"
            // IMU-only sources (A1: cf:imu+mag, kf:imu+mag) provide heading but NOT position.
            // They must not change currentSource — that would block phone GPS permanently.
            // Only GPS sources (nmea=phone, gnss/casic/A2/A3=BLE) affect currentSource.
            val isImuOnly = fs.source.contains("imu+mag") || fs.source.contains("kf:imu")
            val newSource = when {
                isPhoneUpdate -> Source.PHONE
                isImuOnly     -> currentSource   // keep existing source — don't claim BLE GPS
                else          -> Source.BLE
            }
            if (!(isPhoneUpdate && currentSource == Source.BLE && !usePhoneGps && !isBleGpsStale())) {
                currentData = GpsData(
                    source            = newSource,
                    speedKnots        = fs.speedKnots,
                    headingDeg        = fs.headingDeg,
                    hasHeading        = fs.hasHeading,
                    hasFix            = fs.hasFix,
                    satellites        = fs.satellites,
                    altitudeM         = fs.altM,
                    latDeg            = fs.latDeg,
                    lonDeg            = fs.lonDeg,
                    headingConfidence = fs.headingConf,
                    seaState          = fs.seaState,
                    tiltDeg           = fs.tiltDeg,
                    autoDeadbandDeg   = fs.autoDeadbandDeg,
                    magCalibrated     = fs.magCalibrated,
                    magDeclinationDeg = fs.magDeclinationDeg,
                    source2           = fs.source,
                    debugMsg          = fs.debugMsg,
                    bleGpsActive      = !isBleGpsStale()
                )
                if (!isImuOnly) currentSource = newSource   // only GPS sources update currentSource
                accumulateTrip(currentData)
                logData(currentData)
                Log.d(TAG, fs.debugMsg)
                onUpdate?.invoke(currentData)
            }
        }
    }

    // ── Phone GPS ─────────────────────────────────────────────────────────────

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)

    private val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
        .setMinUpdateIntervalMillis(500)
        .setWaitForAccurateLocation(false)
        .setMinUpdateDistanceMeters(0f)
        .build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            // Block phone GPS only when BLE is active AND fresh (A2/A3 within last 5 s)
            if (currentSource == Source.BLE && !usePhoneGps && !isBleGpsStale()) return

            val speedKt = loc.speed * 1.94384f
            // hasFix: use relaxed threshold (50m) so lat/lon flow through earlier.
            // Position is always passed to processNmeaRmc regardless — declination
            // and map centering need a rough position even before a quality fix.
            val hasFix  = loc.accuracy < 50f
            val sAccMs  = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond else 0.5f

            // Phone GPS provides speed and position only — never heading/COG.
            fusion.processNmeaRmc(
                speedKt = speedKt,
                cogDeg  = null,          // never use phone COG for heading
                hasFix  = hasFix,
                latDeg  = loc.latitude,  // always pass position, even without fix quality
                lonDeg  = loc.longitude
            )
            currentData = currentData.copy(speedAccMs = sAccMs)
        }
    }

    fun startPhoneGps() {
        if (!hasLocationPermission()) return
        try {
            fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
            Log.i(TAG, "Phone GPS started")
        } catch (e: SecurityException) { Log.e(TAG, "GPS security: ${e.message}") }
        catch (e: Exception)          { Log.e(TAG, "GPS start error: ${e.message}") }
    }

    fun stopPhoneGps() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    // ── ae02 BLE byte routing ─────────────────────────────────────────────────

    private val nmeaBuffer  = StringBuilder()
    private val casicBuffer = mutableListOf<Byte>()
    private var casicState  = CasicState.IDLE

    private enum class CasicState { IDLE, SYNC2, LEN1, LEN2, CLASS, ID, PAYLOAD, CK }
    private var casicLen   = 0
    private var casicClass = 0.toByte()
    private var casicId    = 0.toByte()

    private val CASIC_SYNC1: Byte = 0xBA.toByte()
    private val CASIC_SYNC2: Byte = 0xCE.toByte()
    private val CASIC_CLASS: Byte = 0x11.toByte()
    private val CASIC_ID:    Byte = 0x02.toByte()
    private val CASIC_PAYLOAD_LEN = 72

    fun feedAe02Bytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val b0 = bytes[0]
        if (b0 == 0xA1.toByte() || b0 == 0xA2.toByte() || b0 == 0xA3.toByte()) {
            parseAcPacket(bytes); return
        }
        for (b in bytes) {
            when {
                casicState != CasicState.IDLE -> processCasicByte(b)
                b == CASIC_SYNC1              -> processCasicByte(b)
                else                          -> nmeaBuffer.append(b.toInt().toChar())
            }
        }
        while (nmeaBuffer.contains('$')) {
            val start = nmeaBuffer.indexOf('$')
            val end   = nmeaBuffer.indexOf('\n', start)
            if (start < 0 || end < 0) break
            val sentence = nmeaBuffer.substring(start, end).trim()
            nmeaBuffer.delete(0, end + 1)
            onNmeaDebug?.invoke(sentence)
        }
        if (!nmeaBuffer.contains('$') && nmeaBuffer.length > 256) {
            onNmeaDebug?.invoke(bytes.joinToString(" ") { "%02X".format(it) })
            nmeaBuffer.clear()
        }
    }

    // ── AC6329C / IMU packet parser ───────────────────────────────────────────
    // 0xA1: IMU+Mag raw (20 bytes)
    // 0xA2: PQTMTAR orientation (17 bytes)
    // 0xA3: GNRMC position (17 bytes)

    private fun parseAcPacket(b: ByteArray) {
        val buf = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        when (b[0]) {
            0xA1.toByte() -> {
                if (b.size < 20) return
                val ax = getI2(b, 2);  val ay = getI2(b, 4);  val az = getI2(b, 6)
                val gx = getI2(b, 8);  val gy = getI2(b, 10); val gz = getI2(b, 12)
                val mx = getI2(b, 14); val my = getI2(b, 16); val mz = getI2(b, 18)
                // Feed active calibration procedures before processA1 applies offsets
                if (fusion.isGyroBiasCalActive) fusion.feedGyroBiasSample(gx, gy, gz)
                if (fusion.isManualCalActive)   fusion.feedManualMagSample(mx, my)
                fusion.processA1(ax, ay, az, gx, gy, gz, mx, my, mz,
                    System.currentTimeMillis(), accelRotated180)
            }
            0xA2.toByte() -> {
                if (b.size < 17) return
                lastA2A3TimeMs = System.currentTimeMillis()
                val quality    = b[5].toInt() and 0xFF
                val pitch      = buf.getShort(8)  / 100.0f
                val roll       = buf.getShort(10) / 100.0f
                val heading    = (buf.getShort(12).toInt() and 0xFFFF) / 100.0f
                val accHeading = (buf.getShort(14).toInt() and 0xFFFF) / 1000.0f
                val usedSV     = b[16].toInt() and 0xFF
                fusion.processA2(
                    tarHeadingDeg = heading, pitchDeg = pitch, rollDeg = roll,
                    tarAccDeg = accHeading, gnssQuality = quality,
                    satellites = usedSV, nowMs = System.currentTimeMillis()
                )
            }
            0xA3.toByte() -> {
                if (b.size < 17) return
                lastA2A3TimeMs = System.currentTimeMillis()
                val rawLat  = buf.getInt(5)  / 10000.0f
                val rawLon  = buf.getInt(9)  / 10000.0f
                val speedKt = (buf.getShort(13).toInt() and 0xFFFF) / 100.0f
                val course  = (buf.getShort(15).toInt() and 0xFFFF) / 100.0f
                val lat     = convertNmeaToDecimal(rawLat)
                val lon     = convertNmeaToDecimal(rawLon)
                val hasFix  = rawLat != 0f || rawLon != 0f
                if (hasFix && usePhoneGps) {
                    Log.i(TAG, "A3 valid fix — switching to BLE GPS")
                    usePhoneGps = false
                }
                fusion.processA3(lat, lon, speedKt, course, hasFix)
            }
        }
    }

    private fun convertNmeaToDecimal(nmea: Float): Double {
        val degrees = (nmea / 100).toInt()
        val minutes = nmea - degrees * 100f
        return degrees + minutes / 60.0
    }

    // ── CASIC NAV2-SOL parser ─────────────────────────────────────────────────

    private fun processCasicByte(b: Byte) {
        when (casicState) {
            CasicState.IDLE    -> if (b == CASIC_SYNC1) casicState = CasicState.SYNC2
            CasicState.SYNC2   -> if (b == CASIC_SYNC2) { casicState = CasicState.LEN1; casicBuffer.clear() }
            else casicState = CasicState.IDLE
            CasicState.LEN1    -> { casicLen = b.toInt() and 0xFF; casicState = CasicState.LEN2 }
            CasicState.LEN2    -> { casicLen = casicLen or ((b.toInt() and 0xFF) shl 8); casicState = CasicState.CLASS }
            CasicState.CLASS   -> { casicClass = b; casicState = CasicState.ID }
            CasicState.ID      -> { casicId = b; casicState = CasicState.PAYLOAD }
            CasicState.PAYLOAD -> { casicBuffer.add(b); if (casicBuffer.size >= casicLen) casicState = CasicState.CK }
            CasicState.CK      -> {
                casicBuffer.add(b)
                if (casicBuffer.size >= casicLen + 4) {
                    val payload = casicBuffer.subList(0, casicLen).toByteArray()
                    val ckBytes = casicBuffer.subList(casicLen, casicLen + 4).toByteArray()
                    casicState  = CasicState.IDLE
                    if (verifyCasicChecksum(casicLen, casicClass, casicId, payload, ckBytes) &&
                        casicClass == CASIC_CLASS && casicId == CASIC_ID) {
                        parseCasicNav2Sol(payload)
                    }
                }
            }
        }
    }

    private fun verifyCasicChecksum(len: Int, cls: Byte, id: Byte, payload: ByteArray, ckBytes: ByteArray): Boolean {
        var ck = ((id.toInt() and 0xFF) shl 24) + ((cls.toInt() and 0xFF) shl 16) + len
        var i  = 0
        while (i + 3 < payload.size) {
            val w = (payload[i].toInt() and 0xFF) or ((payload[i+1].toInt() and 0xFF) shl 8) or
                    ((payload[i+2].toInt() and 0xFF) shl 16) or ((payload[i+3].toInt() and 0xFF) shl 24)
            ck = (ck + w) and 0xFFFFFFFF.toInt()
            i += 4
        }
        val rx = (ckBytes[0].toInt() and 0xFF) or ((ckBytes[1].toInt() and 0xFF) shl 8) or
                ((ckBytes[2].toInt() and 0xFF) shl 16) or ((ckBytes[3].toInt() and 0xFF) shl 24)
        return ck == rx
    }

    private fun parseCasicNav2Sol(p: ByteArray) {
        if (p.size < CASIC_PAYLOAD_LEN) return
        val fixflags = p[8].toInt() and 0xFF
        val fixOk    = (fixflags and 0x01) != 0
        val fixType  = (fixflags shr 1) and 0x07
        val x  = getR8(p, 24); val y = getR8(p, 32); val z = getR8(p, 40)
        val vx = getR4(p, 52).toDouble(); val vy = getR4(p, 56).toDouble(); val vz = getR4(p, 60).toDouble()
        val sAcc = getR4(p, 64)
        fusion.processCasicNav2Sol(x, y, z, vx, vy, vz, sAcc, fixOk, fixType)
    }

    // ── Trip accumulation ─────────────────────────────────────────────────────

    private fun accumulateTrip(data: GpsData) {
        if (!data.hasFix) return
        if (data.speedKnots > maxSpeedKnots) maxSpeedKnots = data.speedKnots
        val lat = data.latDeg; val lon = data.lonDeg
        if (!lastFixLat.isNaN() && (lat != lastFixLat || lon != lastFixLon))
            tripDistanceNm += fusion.haversineNm(lastFixLat, lastFixLon, lat, lon)
        lastFixLat = lat; lastFixLon = lon
    }

    // ── CSV Logging ───────────────────────────────────────────────────────────

    private var logWriter: PrintWriter? = null
    private var logFile:   File?        = null
    var isLogging: Boolean = false; private set
    private val logDateFmt  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    private val fileNameFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun startLogging(): String? {
        if (isLogging) return logFile?.absolutePath
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            logFile   = File(dir, "AutoPilot_GPS_${fileNameFmt.format(Date())}.csv")
            logWriter = PrintWriter(FileWriter(logFile!!, false))
            logWriter!!.println("timestamp,source,lat,lon,speedKt,headingDeg,altM,satellites,hasFix,headingConf,seaState,tiltDeg,magCalibrated")
            logWriter!!.flush()
            isLogging = true
            logFile!!.absolutePath
        } catch (e: Exception) { Log.e(TAG, "Log start error: ${e.message}"); null }
    }

    fun stopLogging() {
        if (!isLogging) return
        logWriter?.flush(); logWriter?.close(); logWriter = null
        isLogging = false
        onLogStatus?.invoke("Saved: ${logFile?.name}")
    }

    private fun logData(data: GpsData) {
        if (!isLogging || logWriter == null) return
        try {
            logWriter!!.println(
                "${logDateFmt.format(Date())},${data.source}," +
                        "${"%.8f".format(data.latDeg)},${"%.8f".format(data.lonDeg)}," +
                        "${"%.2f".format(data.speedKnots)},${"%.1f".format(data.headingDeg)}," +
                        "${"%.1f".format(data.altitudeM)},${data.satellites},${data.hasFix}," +
                        "${"%.2f".format(data.headingConfidence)},${"%.2f".format(data.seaState)}," +
                        "${"%.1f".format(data.tiltDeg)},${data.magCalibrated}"
            )
            logWriter!!.flush()
        } catch (e: Exception) { Log.e(TAG, "Log write: ${e.message}") }
    }

    // ── Binary helpers ────────────────────────────────────────────────────────

    private fun getI2(b: ByteArray, o: Int): Short =
        ((b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8)).toShort()

    private fun getI4(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or
                ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)

    private fun getR4(b: ByteArray, o: Int): Float =
        java.lang.Float.intBitsToFloat(getI4(b, o))

    private fun getR8(b: ByteArray, o: Int): Double {
        val lo = (b[o].toLong() and 0xFF) or ((b[o+1].toLong() and 0xFF) shl 8) or
                ((b[o+2].toLong() and 0xFF) shl 16) or ((b[o+3].toLong() and 0xFF) shl 24)
        val hi = (b[o+4].toLong() and 0xFF) or ((b[o+5].toLong() and 0xFF) shl 8) or
                ((b[o+6].toLong() and 0xFF) shl 16) or ((b[o+7].toLong() and 0xFF) shl 24)
        return java.lang.Double.longBitsToDouble(lo or (hi shl 32))
    }
}