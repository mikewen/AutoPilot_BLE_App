# Architecture

## Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        Android App                              │
│                                                                 │
│  ┌──────────────┐    ┌──────────────────┐    ┌───────────────┐ │
│  │ TypeSelect   │    │   ScanScreen     │    │  Dashboard    │ │
│  │ Screen       │───▶│  (permissions +  │───▶│  Screen       │ │
│  └──────────────┘    │   BLE scan)      │    │  (tiller or   │ │
│                      └──────────────────┘    │   diff thrust)│ │
│                                              └───────┬───────┘ │
│                              ┌───────────────────────┘         │
│                              ▼                                  │
│                    ┌─────────────────┐                          │
│                    │ AutopilotViewModel│                        │
│                    │  - selectedType  │                         │
│                    │  - pidConfig     │                         │
│                    │  - targetHeading │                         │
│                    └────────┬────────┘                          │
│                             │                                   │
│                             ▼                                   │
│                    ┌─────────────────┐                          │
│                    │   BleManager    │                          │
│                    │                 │                          │
│                    │  StateFlows:    │                          │
│                    │  · connectionState                         │
│                    │  · scannedDevices                          │
│                    │  · autopilotState                          │
│                    └────────┬────────┘                          │
└─────────────────────────────┼───────────────────────────────────┘
                              │ BLE GATT (Bluetooth LE)
                              ▼
              ┌───────────────────────────────┐
              │      Autopilot Hardware       │
              │                               │
              │  ┌─────────────┐              │
              │  │   Tiller    │  linear motor│
              │  │  Controller │  rudder pos  │
              │  └─────────────┘              │
              │         OR                    │
              │  ┌─────────────┐              │
              │  │    Diff     │  port motor  │
              │  │   Thrust    │  stbd motor  │
              │  └─────────────┘              │
              └───────────────────────────────┘
```

## Data Flow

```
BLE Notify (hardware → phone)
  GATT Notification
      │
      ▼
  BleManager.gattCallback.onCharacteristicChanged()
      │
      ▼
  parseNotification()  →  _autopilotState.update {}
      │
      ▼
  autopilotState: StateFlow<AutopilotState>
      │
      ▼
  AutopilotViewModel (exposes same flow)
      │
      ▼
  DashboardScreen (collectAsState)  →  recompose UI

BLE Write (phone → hardware)
  UI button click
      │
      ▼
  ViewModel method (engage / portOne / setHeading …)
      │
      ▼
  BleManager.sendCommand(ByteArray)
      │
      ▼
  BluetoothGatt.writeCharacteristic(CHAR_COMMAND)
```

## Screen Navigation

```
TypeSelectScreen
      │
      │  (type chosen)
      ▼
ScanScreen
      │
      │  (BleConnectionState.Connected)
      ▼
DashboardScreen ──── (settings icon) ──▶ SettingsScreen
      │
      │  (disconnect)
      ▼
TypeSelectScreen (stack cleared)
```

## Threading

- BLE callbacks arrive on the **Binder thread pool** → immediately bridged to `Dispatchers.IO` via coroutine scope
- StateFlow collectors run on **main thread** via `collectAsState()` in Compose
- All UI state mutations use `StateFlow.update {}` which is thread-safe

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Single `BleManager` class | All BLE logic in one place; easy to mock/test |
| `StateFlow` for state | Push-based; no polling; Compose integrates natively |
| `sealed class BleConnectionState` | Exhaustive state machine; no impossible states |
| Shared `AutopilotState` for both types | Common fields (heading, speed) shared; type-specific fields are zero/unused for the other type |
| `AutopilotType` enum | Determines which UI panel to show; passed to firmware via advertising data detection |
