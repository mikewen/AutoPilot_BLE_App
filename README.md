# AutoPilot BLE App

[![Android CI](https://github.com/mikewen/AutoPilot_BLE_App/actions/workflows/android.yml/badge.svg)](https://github.com/mikewen/AutoPilot_BLE_App/actions)
![Min SDK](https://img.shields.io/badge/minSdk-26-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.02-green)

Android BLE controller app for two marine autopilot types — **Tiller** (`BLE_tiller`) and **Differential Thrust** (`ESC_PWM` / `BLDC_PWM`). Built with Jetpack Compose, Material 3, and a custom deep-navy marine UI.

---

## Screenshots

> _(Add screenshots after first build)_

| Type Select | Scan | Dashboard (Tiller) | Dashboard (Diff Thrust) | Settings |
|-------------|------|--------------------|------------------------|----------|
| ![](docs/screenshot_type.png) | ![](docs/screenshot_scan.png) | ![](docs/screenshot_tiller.png) | ![](docs/screenshot_diff.png) | ![](docs/screenshot_settings.png) |

---

## Features

- 🔵 **BLE scan** — scans all nearby devices, auto-identifies autopilots by advertised device name
- ⚓ **Tiller autopilot** — real-time rudder angle bar, linear motor feedback, rudder target display
- ⚡ **Differential Thrust autopilot** — port/starboard throttle gauges, differential readout
- 🧭 **Animated compass** — current heading needle (teal) + target heading needle (amber) + deadband arc
- 🎛 **PID + Deadband tuning** — Kp / Ki / Kd / deadband sliders with live send-to-device
- 🔔 **Alarms** — off-course alarm, low battery alarm with pulsing banner
- 📡 **RSSI display** — live signal strength in top bar

---

## Project Structure

```
AutoPilot_BLE_App/
├── app/src/main/java/com/mikewen/autopilot/
│   ├── MainActivity.kt
│   ├── ble/
│   │   └── BleManager.kt          # BLE scan, connect, GATT, notify, send
│   ├── model/
│   │   └── AutopilotModels.kt     # Data classes, enums, BLE UUIDs, commands
│   ├── viewmodel/
│   │   └── AutopilotViewModel.kt  # Bridges BleManager → UI state
│   └── ui/
│       ├── AutoPilotApp.kt        # NavHost / navigation graph
│       ├── theme/
│       │   ├── Theme.kt           # Material3 dark colour scheme
│       │   └── Typography.kt      # System fonts (upgrade to Orbitron later)
│       └── screens/
│           ├── TypeSelectScreen.kt    # Choose tiller or diff thrust
│           ├── ScanScreen.kt          # BLE scan + permission handling
│           ├── DashboardScreen.kt     # Main control & telemetry
│           └── SettingsScreen.kt      # PID + deadband tuning
├── .github/workflows/android.yml  # CI: lint + test + build APK
└── docs/                          # Architecture & font notes
```

---

## BLE Device Names & Discovery

The app scans **all** nearby BLE devices and identifies autopilots purely by their **advertised device name**. No service UUID filter is applied, so the ESP32 does not need to advertise a specific UUID in its scan response.

| Advertised BLE Name | Autopilot Type | Hardware |
|---------------------|---------------|----------|
| `BLE_tiller` | Tiller | Linear motor + rudder position sensor |
| `ESC_PWM` | Differential Thrust | Dual ESC / PWM motor control |
| `BLDC_PWM` | Differential Thrust | Dual BLDC motor control |

Substring matching is also supported, so names like `ESC_PWM_v2` or `BLE_tiller_port` will still be detected correctly.

---

## BLE Protocol

### Service UUIDs

Each autopilot type has its own GATT service. Set these UUIDs in your ESP32 firmware.

| Device | Advertised Name | Service UUID |
|--------|----------------|--------------|
| Tiller | `BLE_tiller` | `12345678-1234-1234-1234-1234567890aa` |
| Diff Thrust | `ESC_PWM` / `BLDC_PWM` | `12345678-1234-1234-1234-1234567890bb` |
| Battery | (both) | `0000180f-0000-1000-8000-00805f9b34fb` _(standard)_ |

### Tiller Characteristics (`BLE_tiller`)

| Characteristic UUID | Direction | Description |
|---------------------|-----------|-------------|
| `...1234-1234567890a1` | Write | Command byte(s) |
| `...1234-1234567890a2` | Notify | State flags (bit0=engaged, bit1=off-course, bit2=in-deadband) |
| `...1234-1234567890a3` | Notify | Heading × 10 (2 bytes) + Speed × 10 (2 bytes) |
| `...1234-1234567890a4` | Read/Write | PID config |
| `...1234-1234567890a5` | Notify | Rudder angle × 10 + 450 (2 bytes, range 0–900 = -45° to +45°) |
| `...1234-1234567890a6` | Notify | Linear motor status byte |

### Differential Thrust Characteristics (`ESC_PWM` / `BLDC_PWM`)

| Characteristic UUID | Direction | Description |
|---------------------|-----------|-------------|
| `...1234-1234567890b1` | Write | Command byte(s) |
| `...1234-1234567890b2` | Notify | State flags (bit0=engaged, bit1=off-course, bit2=in-deadband) |
| `...1234-1234567890b3` | Notify | Heading × 10 (2 bytes) + Speed × 10 (2 bytes) |
| `...1234-1234567890b4` | Read/Write | PID config |
| `...1234-1234567890b5` | Notify | Port throttle 0–100 (1 byte, percent) |
| `...1234-1234567890b6` | Notify | Starboard throttle 0–100 (1 byte, percent) |

### Battery Characteristic (both devices)

| Characteristic UUID | Direction | Description |
|---------------------|-----------|-------------|
| `00002a19-0000-1000-8000-00805f9b34fb` | Notify | Battery level 0–100 % |

### Command Bytes (written to `...a1` or `...b1`)

| Byte(s) | Command |
|---------|---------|
| `0x01` | Engage autopilot |
| `0x02` | Standby (disengage) |
| `0x03 HH LL` | Set heading — 16-bit value = degrees × 10, big-endian |
| `0x04 DD` | Adjust heading — signed byte, ±1 to ±10° |
| `0x05 KP_H KP_L KI_H KI_L KD_H KD_L` | Set PID — each coefficient as 16-bit fixed-point × 100 |
| `0x07 DB_H DB_L` | Set deadband — 16-bit value = degrees × 10 |

### State Flags Byte (notified from `...a2` / `...b2`)

| Bit | Mask | Meaning |
|-----|------|---------|
| 0 | `0x01` | Engaged |
| 1 | `0x02` | Off-course alarm |
| 2 | `0x04` | Currently inside deadband |

---

## Architecture

```
UI (Compose Screens)
       │
       ▼
AutopilotViewModel  ◄─── StateFlow / SharedFlow
       │
       ▼
BleManager
  ├── BluetoothLeScanner  →  scannedDevices: StateFlow<List<BleDevice>>
  │     scan all BLE, filter by device name (BLE_tiller / ESC_PWM / BLDC_PWM)
  ├── BluetoothGatt       →  connectionState: StateFlow<BleConnectionState>
  │     routes to SERVICE_TILLER or SERVICE_DIFF_THRUST based on device.type
  └── GATT Notifications  →  autopilotState: StateFlow<AutopilotState>
```

---

## Getting Started

### Prerequisites
- Android Studio Hedgehog or newer
- **JDK 17** set as Gradle JVM _(File → Project Structure → SDK Location → Gradle JDK)_
- Android device with BLE support (API 26+)
- ESP32 firmware advertising as `BLE_tiller`, `ESC_PWM`, or `BLDC_PWM`

### 1. Clone
```bash
git clone https://github.com/mikewen/AutoPilot_BLE_App.git
cd AutoPilot_BLE_App
```

### 2. Build & run
```bash
./gradlew assembleDebug
# or open in Android Studio and press Run
```

### 3. Optional — custom fonts
The app uses system fonts by default. To upgrade to Orbitron + Share Tech Mono, see [`docs/FONTS_README.md`](docs/FONTS_README.md).

---

## BLE Permissions

| Permission | API Level | Why |
|------------|-----------|-----|
| `BLUETOOTH_SCAN` | 31+ | Discover nearby devices |
| `BLUETOOTH_CONNECT` | 31+ | Connect to GATT server |
| `ACCESS_FINE_LOCATION` | < 31 | Required for BLE scan on Android ≤ 11 |

---

## Adding a New Autopilot Type

1. Add your type to `AutopilotType` enum in `AutopilotModels.kt`
2. Add service + characteristic UUIDs to `BleUuids` and the helper `when` functions
3. Add device name detection in `BleManager.scanCallback`
4. Add notification parsing in `BleManager.parseNotification()`
5. Create a new panel composable in `DashboardScreen.kt`

---

## License

MIT License — see [LICENSE](LICENSE)