# AutoPilot BLE App

Android BLE autopilot controller for boats.  
Supports tiller autopilots and differential-thrust (dual-motor) autopilots via Bluetooth Low Energy.

**GitHub:** https://github.com/mikewen/AutoPilot_BLE_App  
**Version:** 1.1.0 · Min SDK 26 (Android 8.0) · Target SDK 34  
**Language:** Kotlin · Jetpack Compose · Material 3

---

## Hardware Support

### Autopilot Devices (BLE advertised name → type)

| BLE Name | Type | Notes |
|---|---|---|
| `BLE_tiller` | Tiller | Linear motor + rudder sensor |
| `GPS_PWM` | Tiller | Tiller with integrated GPS module |
| `ESC_PWM` | Differential Thrust | Dual ESC / RC-PWM motors |
| `BLDC_PWM` | Differential Thrust | Dual BLDC direct-duty motors |

Substring matching also works — e.g. `ESC_PWM_v2` is recognised as Differential Thrust.

### IMU Sensor (optional, any autopilot type)

| BLE Name | Notes |
|---|---|
| `IMU_PWM`, `IMU_*` | External compass/attitude sensor |

The IMU sensor provides heading, pitch, roll, and calibration status. When connected it becomes the primary heading source, overriding the autopilot controller's own heading characteristic.

### BLE Protocol (AC6329C hardware — ae00/ae30 service)

| Characteristic | Direction | Purpose |
|---|---|---|
| `ae02` | NOTIFY | A1/A2/A3 packet stream (50 Hz IMU, 1 Hz GNSS, 0.2 Hz position) |
| `ae03` | WRITE NO RESPONSE | Motor commands + autopilot commands |
| `ae10` | READ/WRITE | Status string / mode switch |

**Packet types on ae02:**

| Byte[0] | Name | Rate | Content |
|---|---|---|---|
| `0xA1` | IMU+Mag | 50 Hz | ax,ay,az (QMI8658C) · gx,gy,gz · mx,my,mz (MMC5603) |
| `0xA2` | GNSS Heading | 1 Hz | PQTMTAR dual-antenna heading, accuracy, quality, satellites |
| `0xA3` | Position | 0.2 Hz | GNRMC lat/lon, speed, course |

**Commands on ae03 (5-byte motor packet):**

```
[CMD, portLo, portHi, stbdLo, stbdHi]   little-endian 16-bit duties
```

| CMD | Value | Mode | Duty range |
|---|---|---|---|
| `CMD_ESC_PWM` | `0x01` | RC PWM | 500–1000 (500=1000µs stop, 1000=2000µs full) |
| `CMD_BLDC_DUTY` | `0x02` | BLDC direct | 0–10000 (0=stop, 10000=100%) |
| `CMD_STOP` | `0xFF` | Hard stop | Zero duty both channels |

**Autopilot commands on ae03 (1–3 byte):**

| CMD | Value | Payload |
|---|---|---|
| `CMD_ENGAGE` | `0x01` | — |
| `CMD_STANDBY` | `0x02` | — |
| `CMD_SET_HDG` | `0x03` | heading (uint16 BE, 0–359°) |
| `CMD_ADJUST_HDG` | `0x04` | delta (int8, –10…+10°) |
| `CMD_SET_PID` | `0x05` | Kp, Ki, Kd (uint16 × 100 each) |
| `CMD_SET_DEADBAND` | `0x07` | tenths of a degree (uint16) |

---

## Architecture

```
MainActivity
└── AutoPilotApp (NavHost)
    ├── TypeSelectScreen     — choose Tiller or Differential Thrust
    ├── ScanScreen           — scan + connect autopilot & IMU (2 separate buttons)
    ├── DashboardScreen      — main navigation display
    ├── SettingsScreen       — PID / deadband tuning (persisted via DataStore)
    ├── CalibrationScreen    — gyro bias + magnetometer hard-iron calibration
    └── MapTargetScreen      — OSMDroid offline map, tap to set waypoint target
```

```
AutopilotViewModel
├── BleManager          — Android GATT, ae02 notify, ae03 write, GATT write queue
├── ImuManager          — IMU_* BLE scan, c1–c4 characteristics
├── GpsManager          — phone GPS + SensorFusion wrapper
│   └── SensorFusion    — pure-Kotlin heading/position fusion engine
├── SettingsRepository  — DataStore persistence for PidConfig
└── StateFlows
    ├── autopilotState  — merged heading: IMU → SensorFusion → BLE
    ├── gpsData         — position/speed from phone GPS or BLE GNSS
    ├── imuState        — heading, pitch, roll, temperature, calibrated
    ├── targetWaypoint  — waypoint set from map
    └── pidConfig       — Kp/Ki/Kd/deadband/alarm/outputLimit
```

### Heading source priority

1. **IMU sensor** (`IMU_*` BLE device) — direct BLE compass heading
2. **SensorFusion** — complementary/Kalman filter fusing:
    - A1 magnetometer (tilt-compensated, hard-iron corrected, declination applied)
    - A1 gyro Z (bias-corrected, scale-applied, flip-corrected)
    - A2 PQTMTAR dual-antenna GNSS heading (1 Hz)
    - A3 RMC COG (cached, blended with A2)
3. **BLE autopilot** own heading characteristic (fallback)

Phone GPS contributes **speed and position only** — never heading/COG.

### A1/A2/A3 routing

All connected BLE devices route raw ae02 bytes into the same `GpsManager.feedAe02Bytes()`:

```
BleManager.incomingData  ──┐
ImuManager.onRawBytes    ──┼──▶  GpsManager.feedAe02Bytes()
sensor2.onAe02Raw        ──┘         │
                                     ▼
                              parseAcPacket()
                                ├── A1 → feedGyroBiasSample / feedManualMagSample (if cal active)
                                │        → SensorFusion.processA1()
                                ├── A2 → SensorFusion.processA2()
                                └── A3 → SensorFusion.processA3()
```

---

## Key Features

### Dashboard
- **Compass** — animated dial, current heading (56sp) + target heading, deadband arc
- **Nav row** — speed · heading error · distance/ETA to target (or GPS source indicator)
- **Engage panel** — large ENGAGE button when standby; when engaged shows error, PID output, speed, rudder/differential with pulsing STANDBY button
- **Course adjust** — 4 large buttons: ◀◀ 10° · ◀ 1° · 1° ▶ · 10° ▶▶
- **Manual Throttle** (Differential Thrust, standby only):
    - ESC/BLDC mode toggle
    - SYNC switch — locks both motors to equal speed with single POWER slider
    - Independent PORT/STBD sliders when SYNC off
    - ZERO + HARD STOP buttons

### Sensor Fusion (`SensorFusion.kt`)
- **Complementary filter** (default) or **Kalman filter** (switchable)
- **WMM-2020 magnetic declination** — auto-computed from GPS position, persisted to SharedPreferences
- **Gyro bias correction** — subtracted from gz before integration
- **Mag hard-iron correction** — subtracted before tilt compensation
- **Sea state estimation** — accel Z + gyro XY variance → 0–1 sea state → auto-deadband
- **Speed-based mag weight** — mag influence reduced above 5 kt (GPS/gyro dominate)
- **GPS auto-calibration** — accumulates GPS-vs-mag bias at speed in calm conditions
- **LC02H misalignment calibration** — auto-corrects sensor mounting offset

### Calibration Screen
- **Live raw LSB display** (20 Hz from BLE A1 packets):
    - GX / GY / GZ in large monospace + scale factor + flip flag
    - AX / AY / AZ + tilt angle
    - MX / MY / MZ + raw mag heading
- **Gyro bias calibration** — 5-second sample, keep sensor still
- **Mag hard-iron calibration** — rotate 360°, progress bar shows accumulated rotation angle (integrated from BLE gyro Z), raw mag heading updates live

### Map Target Picker
- OSMDroid (OpenStreetMap) — offline-capable after first tile download
- Tap to place target marker → shows bearing + distance from current position
- SET AS TARGET → sends bearing to autopilot heading
- Save up to 10 waypoints per session
- Centres on current GPS position

### Settings (persisted via DataStore)
- Kp, Ki, Kd, output limit, deadband, off-course alarm
- All sliders auto-save on change, restored on next launch

---

## GPS / Position Handling

| Source | Used for | Priority |
|---|---|---|
| BLE A3 (GNRMC) | position, speed | highest when fresh (<5 s) |
| Phone GPS | position, speed, declination seed | fallback when BLE GPS stale |
| Neither | — | position unavailable |

Phone GPS `hasFix` threshold: **50 m accuracy** (relaxed so position flows through earlier for declination and map centering).

BLE GPS stale timeout: **5 seconds** — if no A2/A3 received, phone GPS resumes automatically.

A1 (IMU) updates **never** change the GPS source — heading and position sources are tracked independently.

---

## Permissions

| Permission | Purpose |
|---|---|
| `BLUETOOTH_SCAN` | BLE device scanning (Android 12+) |
| `BLUETOOTH_CONNECT` | BLE GATT connection (Android 12+) |
| `ACCESS_FINE_LOCATION` | Phone GPS + BLE scanning (all versions) |
| `INTERNET` | OSMDroid tile download |
| `WRITE_EXTERNAL_STORAGE` | OSMDroid tile cache (Android ≤ 9) |

All runtime permissions are requested together on the Scan screen.

---

## Project Structure

```
app/src/main/
├── AndroidManifest.xml
└── java/com/mikewen/autopilot/
    ├── MainActivity.kt
    ├── ble/
    │   ├── BleManager.kt          — Android GATT, ae00/ae30 protocol, GATT write queue
    │   ├── ImuManager.kt          — IMU_* BLE scan + c1–c4 characteristics
    │   └── MotorController.kt     — Nordic BLE library wrapper, ae00 motor commands
    ├── data/
    │   └── SettingsRepository.kt  — DataStore persistence for PidConfig
    ├── model/
    │   └── AutopilotModels.kt     — enums, data classes, BleCommand, BleUuids
    ├── sensor/
    │   ├── SensorFusion.kt        — pure Kotlin, no Android imports, WMM-2020
    │   └── GpsManager.kt          — FusedLocation + ae02 routing + trip logging
    ├── ui/
    │   ├── AutoPilotApp.kt        — NavHost, BackHandler on every screen
    │   └── screens/
    │       ├── TypeSelectScreen.kt
    │       ├── ScanScreen.kt       — 2 scan buttons (autopilot + IMU)
    │       ├── DashboardScreen.kt
    │       ├── SettingsScreen.kt
    │       ├── CalibrationScreen.kt
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
no.nordicsemi.android:ble:2.7.4          // MotorController (Nordic BLE library)
no.nordicsemi.android:ble-ktx:2.7.4

// GPS
com.google.android.gms:play-services-location:21.2.0

// Map
org.osmdroid:osmdroid-android:6.1.18    // offline OpenStreetMap

// Permissions
com.google.accompanist:accompanist-permissions:0.34.0

// Serialization (PidConfig DataStore)
org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2
```

---

## Build

```bash
./gradlew assembleDebug
```

Requires Java 17 · Gradle 8.6 · Android Gradle Plugin 8.x