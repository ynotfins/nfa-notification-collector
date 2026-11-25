#Requires -Version 5.1
<#
.SYNOPSIS
  Print the current reliability grants for the collector phone. Read-only.
#>
param(
  [string] $Serial = "R3GL605J0AH",
  [string] $Adb = "C:\Users\ynotf\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Continue"
$packages = @(
  "com.nfaalerts.collector",
  "us.bnn.newsapp",
  "com.arlosoft.macrodroid"
)

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Args)
  & $Adb -s $Serial @Args 2>&1
}

Write-Host "=== devices ==="
Invoke-Adb devices -l

$whitelist = Invoke-Adb shell dumpsys deviceidle whitelist
Write-Host ""
Write-Host "=== deviceidle whitelist (selected packages) ==="
foreach ($package in $packages) {
  $hit = $whitelist | Select-String -SimpleMatch $package
  if ($hit) { Write-Host "WHITELISTED  $package" } else { Write-Host "MISSING      $package" }
}

Write-Host ""
Write-Host "=== standby inactive / appops ==="
foreach ($package in $packages) {
  Write-Host "-- $package"
  Invoke-Adb shell am get-inactive $package
  Invoke-Adb shell cmd appops get $package RUN_IN_BACKGROUND
  Invoke-Adb shell cmd appops get $package RUN_ANY_IN_BACKGROUND
}

Write-Host ""
Write-Host "=== notification listener components mentioning collector ==="
$listeners = Invoke-Adb shell settings get secure enabled_notification_listeners
if ("$listeners" -match "com.nfaalerts.collector") {
  Write-Host "LISTENER ENABLED  com.nfaalerts.collector"
} else {
  Write-Host "LISTENER MISSING  com.nfaalerts.collector"
}

Write-Host ""
Write-Host "=== battery optimization (dumpsys deviceidle; Unrestricted is also a Settings toggle) ==="
Write-Host "Confirm in the app: Battery shows Unrestricted."
Write-Host "Confirm in Settings: Battery and device care, Background usage limits, Never sleeping apps includes collector, MacroDroid, and BNN."
Write-Host "The app cannot read the Samsung Never sleeping list."
