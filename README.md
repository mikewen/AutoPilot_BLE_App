# AutoPilot BLE App

Android BLE autopilot controller for boats.  
Supports tiller, differential-thrust, and thrust-vector autopilots via Bluetooth Low Energy.

**GitHub:** https://github.com/mikewen/AutoPilot_BLE_App  
**Version:** 1.2.0 · Min SDK 26 (Android 8.0) · Target SDK 34  
**Language:** Kotlin · Jetpack Compose · Material 3

---

## Hardware Support

### Autopilot Devices (BLE advertised name → type)

| BLE Name | Type | Notes |
|---|---|---|
| `BLE_tiller` | Tiller | Linear motor + rudder sensor |
| `GPS_PWM` | Tiller | Tiller with integrated GPS module |
| `GPS_Steer` | Thrust Vector | GPS + MMC5603/QMC6308 shaft sensor |
| `ESC_PWM` | Differential Thrust | Dual ESC / RC-PWM motors |
| `BLDC_PWM` | Differential Thrust | Dual BLDC direct-duty motors |
| `THRUST_VECTOR`, `THRUST_VEC*` | Thrust Vector | Vectored nozzle / servo rudder |

Substring matching — e.g. `ESC_PWM_v2` is recognised as Differential Thrust.

### IMU Sensor (optional, any autopilot type)

| BLE Name | Notes |
|---|---|
| `IMU_PWM`, `IMU_*` | External compass/attitude sensor (ae00/ae30 hardware protocol) |

### LOOKBON BLE Remote (optional)

| BLE Name | Notes |
|---|---|
| `LOOKBON*` | BLE joystick remote — course adjust when engaged, manual steer when standby |

### BLE Protocol (AC6329C hardware — ae00/ae30 service)

| Characteristic | Direction | Purpose |
|---|---|---|
| `ae02` | NOTIFY | Sensor packet stream |
| `ae03` | WRITE NO RESPONSE | Motor + autopilot commands |

**Packet types on ae02:**

| Byte[0] | Name | Rate | Content |
|---|---|---|---|
| `0xA1` | IMU+Mag | 20 Hz | QMI8658C accel/gyro · MMC5603NJ mag |
| `0xA2` | GNSS Heading | 1 Hz | PQTMTAR dual-antenna heading, accuracy, baseline, quality |
| `0xA3` | Position | 0.2 Hz | GNRMC lat/lon, speed, course |
| `0xA5` | Shaft/Rudder | 20 Hz | MMC5603NJ 20-bit (11 bytes) or QMC6308 16-bit (8 bytes) |

**GPS_Steer steer command (ae03, 5 bytes):**

```
byte[0]  's'  0x73   steer command
byte[1]  side  'L' 0x4C = port | 'R' 0x52 = stbd | 'C' 0x43 = stop
byte[2]  PWM power (0–255, currently fixed at 100)
byte[3]  runtimeMs low  byte (uint16 LE)
byte[4]  runtimeMs high byte
runtimeMs = steerScaleMs × abs(step)
```

**Autopilot commands on ae03:**

| CMD | Value | Payload |
|---|---|---|
| `CMD_ENGAGE` | `0x01` | — |
| `CMD_STANDBY` | `0x02` | — |
| `CMD_SET_HDG` | `0x03` | heading (uint16 BE, 0–359°) |
| `CMD_ADJUST_HDG` | `0x04` | delta (int8, −10…+10°) |
| `CMD_SET_PID` | `0x05` | Kp, Ki, Kd (uint16 × 100 each) |
| `CMD_SET_DEADBAND` | `0x07` | tenths of a degree (uint16) |

---

## Boat Profiles

Three independent boat profiles, each with its own full `PidConfig`:

| Profile | Icon | Description |
|---|---|---|
| `CL16` | ⛵ | CL 16 — tiller autopilot |
| `Mac25` | 🚢 | Macgregor 25 — main boat |
| `Toy` | 🛥 | Test / toy boat |

Select profile on the **TypeSelectScreen** before scanning. All settings (PID gains, steer scale, shaft limits, filter choice) are stored independently per profile. Switching profiles instantly loads that boat's saved config.

---

## PID Architecture

### Cascaded PID + Feed-Forward

```
Heading error (°)
      │
      ▼  Outer loop (Kp × error + Ki × ∫error·dt)
Desired yaw rate (°/s)
      │
      ├──▶ Feed-Forward (ffGain × desiredYawRate) ──────────────────────────┐
      │                                                                      │
      ▼  Inner loop                                                          │
yawRateError = desiredYawRate − gyroZ                                       │
innerCorrection = kpInner × yawRateError − kdInner × gyroAccel             │
      │                                                                      │
      └──────────────────────────────────────────────────────────────────────┤
                                                                             ▼
                                                                    Raw output = FF + inner + bias
                                                                             │
                                                                      Rate limiter
                                                                             │
                                                                      Shaft supervision
                                                                             │
                                                                      Final output
```

**Why it handles wind gusts better:** The feed-forward pre-positions the rudder the moment a heading error starts building — no lag waiting for the integrator. The inner loop then sees the residual yaw rate error and damps it with gyro acceleration feedback. A sudden gust changes `gyroZ` immediately, so `kdInner × gyroAccel` produces an instant corrective bump.

Set `ffGain=0`, `kpInner=0`, `kdInner=0` to fall back to classic single-loop PID.

### PID Loop Rate

The PID runs on the **autopilot MCU at 20 Hz**, driven by A1 packet cadence. The phone is not in the control loop — it configures, monitors, and displays a shadow PID result for tuning. `computeShadowPid()` runs at 1 Hz on the phone for display only.

### PidConfig Fields (all persisted per BoatProfile)

| Field | Default | Description |
|---|---|---|
| `kp` | 1.5 | Outer loop proportional |
| `ki` | 0.05 | Outer loop integral |
| `kd` | 0.3 | Gyro D-term (single-loop mode) |
| `ffGain` | 0.3 | Feed-forward gain on desired yaw rate |
| `kpInner` | 0.5 | Inner loop P on yaw rate error |
| `kdInner` | 0.05 | Inner loop D on gyro acceleration |
| `outputLimitDeg` | 30° | Max rudder / thrust differential |
| `rateLimitDegPerSec` | 60°/s | Max output change per second (0=off) |
| `deadbandDeg` | 3° | Error below this suppresses output |
| `steeringBiasDeg` | 0° | Constant offset for hull asymmetry |
| `offCourseAlarmDeg` | 15° | Off-course alarm threshold |
| `maxScaleSpeedKt` | 6 kt | Speed at which gain scaling reaches minimum |
| `minSpeedScale` | 0.4 | Minimum gain multiplier at high speed |
| `steerScaleMs` | 200 ms | GPS_Steer motor runtime per step unit |
| `useKalmanFilter` | false | Kalman vs complementary filter in SensorFusion |
| `useSteerSensor` | false | Enable shaft sensor supervision in PID |
| `shaftLimitPortDeg` | 40° | Hard port stop |
| `shaftLimitStbdDeg` | 40° | Hard stbd stop |
| `shaftLagThresholdDeg` | 2° | Min shaft movement expected in lag window |
| `shaftLagWindowMs` | 2000 ms | Lag detection window |

### Shaft Sensor Supervision (when `useSteerSensor=true`)

1. **Hard limit enforcement** — output zeroed when shaft hits port/stbd limit; integral reset (anti-windup)
2. **Anti-windup** — integral clamped when motor is against a mechanical stop
3. **Lag/failure detection** — if output > 10% of limit for `shaftLagWindowMs` but shaft moved < `shaftLagThresholdDeg` → `ShaftStatus.LAGGING` alarm shown in dashboard

### ShaftStatus enum

`OK | AT_PORT_LIMIT | AT_STBD_LIMIT | LAGGING | NO_SENSOR`

---

## Architecture

```
MainActivity
└── AutoPilotApp (NavHost)
    ├── TypeSelectScreen     — boat profile + autopilot type selection
    ├── ScanScreen           — scan autopilot, IMU, LOOKBON remote (3 sections)
    ├── DashboardScreen      — main navigation display
    ├── SettingsScreen       — full PID tuning (persisted per profile)
    ├── CalibrationScreen    — gyro/mag/shaft sensor calibration
    └── MapTargetScreen      — OSMDroid offline map waypoint picker
```

```
AutopilotViewModel
├── BleManager          — Android GATT, ae00/ae30 auto-detect, write queue
├── ImuManager          — IMU_* BLE scan, ae00/ae30 auto-detect
├── GpsManager          — phone GPS + SensorFusion + shaft sensor
│   └── SensorFusion    — pure-Kotlin CF/Kalman, WMM-2020, mag auto-cal
├── RemoteManager       — LOOKBON BLE remote + VoicePrompt TTS
├── SettingsRepository  — DataStore, per-profile PidConfig
└── StateFlows
    ├── autopilotState  — heading, speed, shaft angle, PID output
    ├── gpsData         — position, speed, raw mag, shaft angle, GPS COG
    ├── imuState        — heading, pitch, roll, calibrated
    ├── activeProfile   — current BoatProfile
    ├── shadowPid       — phone-side PID result for display (1 Hz)
    └── pidConfig       — full PidConfig for active profile
```

### Heading Source Priority

1. **IMU sensor** (`IMU_*`) — BLE compass, ae00/ae30 protocol, A1 packets
2. **SensorFusion** — fuses A1 mag + gyro + A2 GNSS heading + A3 COG
3. **BLE autopilot** heading characteristic (fallback)

Phone GPS = position + speed only, never heading.

---

## Sensor Fusion

- **Filters:** Complementary (default) or Kalman — switchable per profile, persisted
- **WMM-2020 declination** — auto-computed from GPS, persisted
- **Mag hard-iron correction** — manual cal from CalibrationScreen
- **Gyro bias** — 5-second still calibration
- **A2 PQTMTAR gate:** dual-antenna heading accepted only when baseline within tolerance and tilt < 25°
- **Mag auto-cal:** quality=4 PQTMTAR trusted at any speed; RMC COG blended when speed > 1.5 m/s

---

## Shaft / Rudder Position Sensor

### Sensor Types (auto-detected by A5 packet length)

| Sensor | Packet | Range | Calibration method |
|---|---|---|---|
| MMC5603NJ | 11 bytes | 20-bit unsigned, centred at 524288 | 3-point LUT (SET ZERO / PORT / STBD) |
| QMC6308 | 8 bytes | 16-bit signed LE | Least-squares ellipse fit (sweep cal) |

### QMC6308 Calibration (sweep)

1. Tap **START SWEEP CAL** — drive shaft slowly port → stbd → centre
2. Progress bar: amber < 36 samples, teal < 72, green ≥ 72 (20 Hz → ~4 s for 72)
3. Tap **FINISH** — 5×5 least-squares fit computes ellipse centre
4. Tap **SET PORT** at full port, **SET STBD** at full stbd — sets angle reference
5. Angle output = `atan2(cy, cx)` from ellipse centre, zeroed to neutral position

### MMC5603 Calibration (LUT)

Drive to each position and tap the button: **SET ZERO** → **SET PORT** → **SET STBD**. All saved to SharedPreferences immediately.

---

## LOOKBON Remote

Connect from the **LOOKBON REMOTE** section in ScanScreen.

| Button | Autopilot engaged | Autopilot standby |
|---|---|---|
| LEFT_1 | −1° course adjust | Steer motor port 1 step |
| LEFT_10 | −10° course adjust | Steer motor port 5 steps |
| RIGHT_1 | +1° course adjust | Steer motor stbd 1 step |
| RIGHT_10 | +10° course adjust | Steer motor stbd 5 steps |
| ENGAGE | Engage autopilot | Engage autopilot |
| DISENGAGE | Standby | Standby |
| SPEED_STOP | Hard stop motors | Hard stop motors |

Voice announcements: "Engaged", "Standby", "Stop", debounced course/speed, debounced steer direction ("Port" / "Hard port" / "Starboard" / "Hard starboard").

---

## Dashboard Layout

1. TopBar — device name, RSSI, IMU name, active boat profile icon
2. ImuStatusChip — shows fused heading, "Data OK" when A1 flowing
3. CompassCard — dial + CURRENT/TARGET headings + sensor data row (RAW MAG | FUSED | GPS COG)
4. NavDataRow — SPEED | ERROR | TILT
5. EngagePanel — ENGAGE button or engaged data (error, PID out, speed, rudder/shaft, ShaftStatus)
6. ManualSteeringRow — L5 | L1 | ⊙ | R1 | R5 (direct motor/rudder control)
7. CourseAdjustRow — ◀◀10° | ◀1° | 1°▶ | 10°▶▶
8. ManualThrottlePanel (Differential Thrust, standby only)
9. ImuAttitudeCard (when IMU connected)
10. TillerPanel / DiffThrustPanel (type-specific, shows shaft badge when A5 active)
11. AlarmBanner
12. HeadingChart (2-min history, at bottom)

---

## Calibration Screen

Live sensor display at 20 Hz (from BLE A1 + A5 packets):

- **GYRO** — raw GX/GY/GZ LSB, GZ in °/s, net rotation angle (CW+/CCW−, RESET button)
- **ACCEL** — raw AX/AY/AZ + tilt
- **MAG** — raw MX/MY/MZ + raw mag heading + hard-iron offsets
- **SHAFT/RUDDER** — motor control (L5/L1/STOP/R1/R5), live shaft angle, raw X/Y, sensor type, calibration UI

---

## Settings Sections

1. **Deadband & Alarms** — deadband width, off-course alarm
2. **PID Gains** — Kp, Ki, Kd
3. **Cascaded PID** — FF gain, Inner Kp, Inner Kd
4. **Output Limits** — output limit, rate limit
5. **Steering Correction** — steering bias
6. **Speed Scaling** — full-scale speed, min gain multiplier + live preview table
7. **Steer Motor (GPS_Steer)** — steer scale ms + runtime preview
8. **Steer Sensor Supervision** — enable switch + port/stbd limits, lag threshold/window
9. **Apply** button — sends Kp/Ki/Kd + deadband to autopilot MCU

All values typed or slid, auto-saved per boat profile. Profile name shown in top bar and banner.

---

## Permissions

| Permission | Purpose |
|---|---|
| `BLUETOOTH_SCAN` | BLE scanning (Android 12+) |
| `BLUETOOTH_CONNECT` | BLE GATT (Android 12+) |
| `ACCESS_FINE_LOCATION` | Phone GPS + BLE scanning |
| `INTERNET` | OSMDroid tile download |
| `WRITE_EXTERNAL_STORAGE` | OSMDroid tile cache (Android ≤ 9) |

AndroidManifest also includes `<queries>` block for TTS engine (VoicePrompt requires Android 11+).

---

## Project Structure

```
app/src/main/java/com/mikewen/autopilot/
├── ble/
│   ├── BleManager.kt          ae00/ae30 auto-detect, GATT write queue, steer 5-byte cmd
│   ├── ImuManager.kt          ae00/ae30 auto-detect for IMU_PWM
│   ├── LookbonRemote.kt       Nordic BLE, LOOKBON joystick
│   ├── RemoteCommand.kt       RemoteBleManager constants (GRP_*, CRS_*, SPD_*)
│   └── RemoteManager.kt       LOOKBON → ViewModel bridge + VoicePrompt
├── data/
│   └── SettingsRepository.kt  DataStore, per-profile keys (CL16_kp, MAC25_kp …)
├── model/
│   └── AutopilotModels.kt     BoatProfile, AutopilotType, PidConfig, PidController,
│                              ShaftStatus, PidResult, BleCommand, BleUuids
├── sensor/
│   ├── GpsManager.kt          FusedLocation, ae02 routing, A5 shaft sensor,
│   │                          LUT + LS ellipse cal, computeShaftAngleQmc/Lut
│   ├── SensorFusion.kt        CF/Kalman, WMM-2020, mag auto-cal, spike rejection
│   └── VoicePrompt.kt         Debounced TTS wrapper
├── ui/
│   ├── AutoPilotApp.kt        NavHost
│   └── screens/
│       ├── TypeSelectScreen.kt  boat profile + autopilot type cards
│       ├── ScanScreen.kt        autopilot + IMU + LOOKBON scan sections
│       ├── DashboardScreen.kt
│       ├── SettingsScreen.kt    inline-editable sliders
│       ├── CalibrationScreen.kt gyro/mag/shaft cal
│       └── MapTargetScreen.kt
└── viewmodel/
    └── AutopilotViewModel.kt
```

---

## Dependencies

```groovy
// Compose BOM 2024.02
androidx.compose.material3
androidx.compose.material:material-icons-extended
androidx.navigation:navigation-compose:2.7.7
androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0
androidx.datastore:datastore-preferences:1.0.0

// BLE
no.nordicsemi.android:ble:2.7.4
no.nordicsemi.android:ble-ktx:2.7.4

// GPS
com.google.android.gms:play-services-location:21.2.0

// Map
org.osmdroid:osmdroid-android:6.1.18

// Permissions
com.google.accompanist:accompanist-permissions:0.34.0
```

---

## Build

```bash
./gradlew assembleDebug
```

Java 17 · Gradle 8.6 · Android Gradle Plugin 8.x