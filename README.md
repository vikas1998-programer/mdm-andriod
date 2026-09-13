# RRV MDM — Android Device Policy Controller (DPC) & Enterprise Launcher

![Platform](https://img.shields.io/badge/Platform-Android%20Enterprise-green.svg)
![Target SDK](https://img.shields.io/badge/Target%20SDK-34%20(Android%2014)-blue.svg)
![Min SDK](https://img.shields.io/badge/Min%20SDK-28%20(Android%209)-orange.svg)
![Architecture](https://img.shields.io/badge/Architecture-Device%20Owner%20%2F%20Kiosk-purple.svg)

## 📌 Overview

**RRV MDM Android DPC** is a purpose-built, high-security Android Enterprise Device Policy Controller (DPC) and Custom Lockdown Launcher designed for dedicated enterprise endpoints, single-purpose devices, and frontline kiosk fleets. 

Operating in **Device Owner (DO)** mode, the application enforces zero-trust policy compliance, remote hardware peripheral governance, default-deny application management, real-time spatial geofencing, and encrypted bidirectional telemetry over MQTT TLS.

---

## 🏗️ Architecture & Core Components

```
                    MDM SERVER (Spring Boot + MQTT Broker)
                                    │
                         MQTT TLS (QoS 1) / REST API
                                    ▼
       ┌─────────────────────────────────────────────────────────┐
       │                RRV MDM DPC AGENT                         │
       │                                                         │
       │  ┌───────────────────┐      ┌────────────────────────┐  │
       │  │ MdmMqttManager    │◄────►│ CommandProcessor       │  │
       │  │ (Heartbeat & Tele)│      │ (Lock/Wipe/Policy)     │  │
       │  └───────────────────┘      └────────────────────────┘  │
       │            │                             │              │
       │            ▼                             ▼              │
       │  ┌───────────────────┐      ┌────────────────────────┐  │
       │  │ DpmPolicyManager  │◄────►│ Zero-Trust Launcher    │  │
       │  │ (Hardware & DLP)  │      │ (Default-Deny Kiosk)   │  │
       │  └───────────────────┘      └────────────────────────┘  │
       └─────────────────────────────────────────────────────────┘
```

### 1. Device Policy & Security Governance (`DpmPolicyManager`)
- **Hardware Peripherals:** OS-level disabling of Cameras, USB Data (MTP/ADB), Bluetooth, SD Card storage, and Microphone.
- **DLP & Anti-Exfiltration:** Screenshot / Screen recording blocking (`FLAG_SECURE`), Cross-profile clipboard isolation (`DISALLOW_CROSS_PROFILE_COPY_PASTE`).
- **Device Integrity:** Factory Reset Protection (FRP), Safe Mode boot suppression, Developer Options & ADB lockout.
- **Passcode Enforcement:** Quality, length, history, and biometric fallbacks via `DevicePolicyManager`.

### 2. Zero-Trust Application Governance & Capability Engine
- **Default-Deny Launcher:** All newly discovered, pre-installed, or side-loaded applications are hidden (`setApplicationHidden`) and suspended (`setPackagesSuspended`) unless explicitly allowed by the central server policy.
- **Overview / Recent Apps Lockout:** Non-approved apps are hidden from Android Recent Apps switcher.
- **Package Classification:** Distinguishes `PRE_INSTALLED_SYSTEM_APP`, `MDM_INSTALLED_APP`, `USER_INSTALLED_APP`, `CATALOG_ONLY_APP`, and `MDM_CORE_APP`.
- **Protected Core:** The MDM DPC agent itself and system partition binaries cannot be uninstalled.

### 3. Bidirectional Command Engine (`CommandProcessor`)
- **Remote Actions:** Screen Lock, Device Unlock, Password Reset, Enterprise Wipe, Reboot, Remote Diagnostic Log Flush.
- **Silent APK Deployment:** Background APK streaming via `PackageInstaller` session API with automatic signature verification.

---

## 🛠️ Technology Stack

- **Language:** Kotlin 1.9.22
- **Android SDK:** Compile SDK 34, Target SDK 34, Min SDK 28
- **Core Libraries:**
  - AndroidX Core KTX, Lifecycle & ViewModel
  - Kotlin Coroutines & Flow
  - Android Enterprise `DevicePolicyManager` & `UserManager`
  - Eclipse Paho MQTT Client (v3.1.1 TLS)
  - WorkManager (Periodic background telemetry and APK workers)
  - Room Database & Jetpack Security (EncryptedSharedPreferences)
  - Retrofit2 + OkHttp3 + Gson

---

## 🚀 Building & Testing

### Prerequisites
- Android Studio Iguana / Jellyfish or later
- JDK 17+ (Java 17 baseline)
- Android SDK with Build Tools 34.0.0

### Compile Debug APK
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Compile Release APK
```bash
./gradlew assembleRelease
```

### Run Unit Tests
```bash
./gradlew test
```

---

## 📲 Device Provisioning (Device Owner Setup)

### Option A: ADB Provisioning (Development / Lab Testing)
1. Factory reset device or ensure no accounts (Google/Samsung) are added on device:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell dpm set-device-owner com.rrv.mdm.dpc/.receiver.RrvDeviceAdminReceiver
   ```
2. Launch DPC launcher:
   ```bash
   adb shell monkey -p com.rrv.mdm.dpc -c android.intent.category.LAUNCHER 1
   ```

### Option B: Google Zero-Touch / Samsung KME / QR Code Enrollment
Scan the QR code generated from the RRV MDM Portal containing the provisioning bundle:
```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.rrv.mdm.dpc/.receiver.RrvDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://mdm.rrvsoftware.com/api/v1/apps/dpc/download",
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
    "serverUrl": "https://mdm.rrvsoftware.com/api/v1",
    "enrollmentToken": "<ENROLLMENT_TOKEN>"
  },
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
}
```

---

## 🔒 Security Notes

- No private keystores or credentials are baked into source control.
- All MQTT and HTTP communications are secured via TLS 1.3 / mTLS.
- Offline policy cache ensures persistence across battery drain and system restarts.
