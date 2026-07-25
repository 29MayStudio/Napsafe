# <p align="center"><img src="app/src/main/res/drawable/ic_launcher_foreground.xml" width="96" height="96" alt="NapSafe Icon" /><br>NapSafe</p>

<p align="center">
  <a href="https://github.com/29MayStudio/Napsafe"><img src="https://img.shields.io/github/v/release/29MayStudio/Napsafe?style=flat-square&color=6200ee" alt="Release"></a>
  <a href="https://github.com/29MayStudio/Napsafe/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/29MayStudio/Napsafe/build.yml?style=flat-square&logo=github" alt="Build Status"></a>
  <a href="https://github.com/29MayStudio/Napsafe/blob/main/LICENSE"><img src="https://img.shields.io/github/license/29MayStudio/Napsafe?style=flat-square&color=blue" alt="License"></a>
  <a href="https://android.com"><img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android" alt="Platform"></a>
  <a href="https://kotlinlang.org"><img src="https://badgen.net/badge/Language/Kotlin/blue?icon=github" alt="Language"></a>
</p>

---

## 📖 Overview

**NapSafe** is an elegant, Material You-enabled Android companion application designed specifically for travelers. No more missing your stop on trains, buses, or road trips! Simply search or tap on the map to set your target destination, choose your alarm radius, and enjoy a safe, relaxed nap.

NapSafe monitors your proximity continuously in the background and wakes you up with a crisp, full-screen alarm immediately when you enter the destination range.

---

## ✨ Features

- **📍 Interactive Maps**: Powered by Google Maps with custom point-and-tap destination configuration.
- **🔍 AutoComplete Suggestions**: Integrated with the system `Geocoder` to fetch real-time address results dynamically as you type.
- **🎯 Dynamic Routing & Path Drawing**: Always draws and updates a beautiful path (`Polyline`) directly to your destination from your live location, even if you deviate from the original route.
- **🚀 Background Resiliency**: Built with modern Android foreground services and `WorkManager` API chains to stay active when closed, minimizing battery drain while guaranteeing your wake-up call.
- **🔋 Unrestricted Battery Opt-In**: Prompts the user for doze-mode exclusion, ensuring uninterrupted location tracking even when the device goes to sleep.
- **🎨 Material You Dynamic Theme**: Adapts to your system's dynamic color palettes for a highly cohesive, modern look and feel.
- **🌙 True Dark Mode Support**: Handcrafted, theme-attributed design built with system semantic attributes for stellar visual contrast on night-time journeys.
- **🔔 High-Resolution Alarm System**: Crystal-clear custom alarm view utilizing modern vector bell iconography and full-screen overlay wake-locks.

---

## 🛠️ Technology Stack & Architecture

- **Language**: Kotlin 100%
- **Framework**: Jetpack (WorkManager, ConstraintLayout, Material Design 3)
- **Maps & Location**: Google Play Services Location (`Priority.PRIORITY_HIGH_ACCURACY`), Google Maps SDK
- **Design System**: Material Design 3 (Dynamic Color)
- **Minimum SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)

---

## 📥 Installation

Clone the repository and build the project directly using Gradle:

```bash
# Clone the repository
git clone https://github.com/29MayStudio/Napsafe.git

# Navigate to the project root
cd Napsafe

# Compile the debug APK
./gradlew assembleDebug
```

---

## 📝 License

Distributed under the MIT License. See `LICENSE` for more information.

<p align="center">Made with ❤️ by 29MayStudio</p>
