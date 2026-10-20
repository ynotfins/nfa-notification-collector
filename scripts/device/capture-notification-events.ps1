#Requires -Version 5.1
<#
.SYNOPSIS
  Copy notification_enqueue and notification_canceled lines for selected packages.
  Writes only under docs/evidence/soak-oracle. Does not print notification text.
#>
param(
  [string] $Serial = "R3GL605J0AH",
  [string] $Adb = "C:\Users\ynotf\AppData\Local\Android\Sdk\platform-tools\adb.exe",
  [string[]] $Packages = @("us.bnn.newsapp"),
  [int] $DurationSeconds = 0,
  [string] $OutFile = ""
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$oracleDir = Join-Path $root "docs\evidence\soak-oracle"
New-Item -ItemType Directory -Force -Path $oracleDir | Out-Null
if (-not $OutFile) {
  $OutFile = Join-Path $oracleDir "notification-events.events.log"
}
$resolvedOut = [System.IO.Path]::GetFullPath($OutFile)
$oracleRoot = [System.IO.Path]::GetFullPath($oracleDir)
if (-not $resolvedOut.StartsWith($oracleRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Refusing to write outside docs/evidence/soak-oracle"
}

$temp = Join-Path $oracleDir "events-buffer.tmp"
if (Test-Path $temp) { Remove-Item -Force $temp }
if ($DurationSeconds -gt 0) {
  $proc = Start-Process -FilePath $Adb -ArgumentList @(
    "-s", $Serial, "logcat", "-b", "events", "-v", "time"
  ) -RedirectStandardOutput $temp -NoNewWindow -PassThru
  Start-Sleep -Seconds $DurationSeconds
  if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force }
} else {
  & $Adb -s $Serial logcat -b events -d -v time > $temp
}

$wanted = $Packages | Where-Object { $_ }
$kept = New-Object System.Collections.Generic.List[string]
Get-Content -Path $temp -ErrorAction SilentlyContinue | ForEach-Object {
  $line = $_
  $kind = $line -match "notification_enqueue" -or $line -match "notification_canceled"
  if (-not $kind) { return }
  foreach ($package in $wanted) {
    if ($line.Contains($package)) {
      $kept.Add($line)
      break
    }
  }
}
[System.IO.File]::WriteAllLines($resolvedOut, $kept)
Remove-Item -Force $temp -ErrorAction SilentlyContinue
Write-Host "Wrote $($kept.Count) event lines to the gitignored soak-oracle folder."
Write-Host "Packages: $($wanted -join ', ')"
