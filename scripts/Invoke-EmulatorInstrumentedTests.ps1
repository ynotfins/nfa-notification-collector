param(
    [string]$Serial = "emulator-5554",
    [string]$Class = "com.nfaalerts.collector.retention.RetentionMaintenanceInstrumentedTest,com.nfaalerts.collector.retention.RetentionSoakInstrumentedTest"
)

$ErrorActionPreference = "Stop"

if ($Serial -notmatch '^emulator-\d+$') {
    throw "Refusing to run instrumented tests on '$Serial'. This script targets an emulator only; the primary S26 must never receive connectedDebugAndroidTest."
}

$state = (& adb -s $Serial get-state 2>&1 | Out-String).Trim()
if ($state -ne "device") {
    throw "Emulator '$Serial' is not ready (state='$state'). Start an AVD first."
}

& .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
if ($LASTEXITCODE -ne 0) { throw "APK build failed." }

& adb -s $Serial install -r -t app\build\outputs\apk\debug\app-debug.apk | Out-Host
& adb -s $Serial install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk | Out-Host
& adb -s $Serial logcat -c

$output = & adb -s $Serial shell am instrument -w -e class $Class com.nfaalerts.collector.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | Out-String
$output | Out-Host

$measured = & adb -s $Serial logcat -d -s RetentionTest:I | Select-String "RETENTION_"
$measured | Out-Host

$shadeCapLines = @(& adb -s $Serial logcat -d | Select-String "already posted or enqueued 50").Count
Write-Output "SHADE_CAP_LINES=$shadeCapLines"

if ($output -match "FAILURES!!!") {
    throw "Instrumented tests failed."
}
if ($output -notmatch "OK \(\d+ test") {
    throw "Instrumented runner did not report a clean OK result."
}
