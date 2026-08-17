# Android Toolchain and Documentation Baseline

Captured: 2026-08-17. Reverify immediately before dependency pinning.

## Installed workstation evidence

- Android SDK: `C:\Users\ynotf\AppData\Local\Android\Sdk`
- Installed platforms: API 30, 33, 34, 35, 36, 36.1
- Installed Build Tools: 30.0.3, 34.0.0, 35.0.0, 36.0.0
- SDK command-line tools: 20.0
- SDK-owned ADB: version 37.0.0; prefer `C:\Users\ynotf\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- PATH ADB: version 36.0.0 at `C:\platform-tools\adb.exe`; do not mix binaries during one acceptance run
- Android Studio path: `C:\Program Files\Android\Android Studio`; bundled runtime appears incomplete, so CLI builds should use an explicit external JDK
- `JAVA_HOME`: Temurin 17.0.16
- PATH `java`: Temurin 21.0.11
- Cached complete Gradle distributions include 8.7, 8.9, 9.3.1, 9.4.1
- No global Gradle; create/use the repository Gradle wrapper
- No device was connected: `adb devices -l` returned none

Resolve the Java mismatch explicitly in the plan. For AGP 9.2, official compatibility currently identifies JDK 17 and Gradle 9.4.1; do not rely on PATH Java 21 accidentally.

## Official documentation checkpoint

Arabold Docs was attempted first during bootstrap and returned an MCP SSE 404. The fallback used current official primary documentation. The Plan-mode agent must retry Arabold before pinning and record the result.

- Notification listener declaration and callback behavior: <https://developer.android.com/reference/android/service/notification/NotificationListenerService>
- Package visibility and `QUERY_ALL_PACKAGES`: <https://developer.android.com/training/package-visibility/declaring>
- Android 16 SDK setup (`compileSdk`/`targetSdk` 36): <https://developer.android.com/about/versions/16/setup-sdk>
- Android Gradle Plugin 9.2 compatibility: <https://developer.android.com/build/releases/agp-9-2-0-release-notes>
- Java versions in Android builds: <https://developer.android.com/build/jdks>
- Compose BOM: <https://developer.android.com/develop/ui/compose/bom>
- Stable AndroidX releases: <https://developer.android.com/jetpack/androidx/versions/stable-channel>
- Persistent background work: <https://developer.android.com/develop/background-work/background-tasks/persistent>
- WorkManager reference: <https://developer.android.com/reference/androidx/work/WorkManager>
- Android Keystore: <https://developer.android.com/privacy-and-security/keystore>

Bootstrap candidates—not final pins until Plan-mode verification:

- `compileSdk = 36`, `targetSdk = 36`
- AGP 9.2.x with Gradle 9.4.1 and JDK 17
- Stable Compose BOM (official page showed `2026.06.00` at capture time)
- Room 2.8.4
- WorkManager 2.11.2
- Activity Compose 1.13.0

Avoid Android 17/API 37 preview/beta as the default production baseline unless the Plan-mode agent proves a compelling Samsung compatibility reason and the operator accepts preview risk.

## Device discovery gate

When the Samsung is connected:

1. use the SDK-owned ADB consistently;
2. record model, product, Android release, API level, security patch, and authorized state;
3. query installed packages and discover BNN rather than guessing;
4. historical repository evidence suggests `us.bnn.newsapp`, but this is not device proof;
5. do not pass the bearer through shell/ADB command arguments.
