#Requires -Version 5.1
<#
.SYNOPSIS
  Apply non-root reliability grants for the NFA collector on the Samsung phone.

.DESCRIPTION
  Uses adb only. Does not pass the ingest bearer. Does not disable other listeners.
  Samsung "Never sleeping apps" still has to be set in Settings; this script cannot toggle that list.
#>
param(
  [string] $Serial = "R3GL605J0AH",
  [string] $Adb = "C:\Users\ynotf\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)

$ErrorActionPreference = "Stop"
$packages = @(
  "com.nfaalerts.collector",
  "us.bnn.newsapp",
  "com.arlosoft.macrodroid"
)

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Args)
  & $Adb -s $Serial @Args
  if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Args -join ' ')" }
}

$devices = & $Adb devices
if ($devices -notmatch [regex]::Escape($Serial)) {
  throw "Device $Serial is not in 'adb devices'. Connect USB or wireless ADB first."
}

foreach ($package in $packages) {
  Invoke-Adb shell dumpsys deviceidle whitelist "+$package"
  Invoke-Adb shell am set-inactive $package false
  Invoke-Adb shell cmd appops set $package RUN_IN_BACKGROUND allow
  Invoke-Adb shell cmd appops set $package RUN_ANY_IN_BACKGROUND allow
  Write-Host "Applied idle whitelist, active bucket, and background appops for $package"
}

Write-Host "Done. Run scripts/device/verify-reliability-grants.ps1 next."
Write-Host "Still set in Settings: Never sleeping apps, Notification Access (including Silent), battery Unrestricted."
