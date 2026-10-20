#Requires -Version 5.1
<#
.SYNOPSIS
  Export Room package, key, tag, and postTime for the soak oracle.
  The database copy and CSV stay in docs/evidence/soak-oracle.
#>
param(
  [string] $Serial = "R3GL605J0AH",
  [string] $Adb = "C:\Users\ynotf\AppData\Local\Android\Sdk\platform-tools\adb.exe",
  [string] $PackageName = "com.nfaalerts.collector"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$oracleDir = Join-Path $root "docs\evidence\soak-oracle"
New-Item -ItemType Directory -Force -Path $oracleDir | Out-Null
$db = Join-Path $oracleDir "collector.db"
$wal = Join-Path $oracleDir "collector.db-wal"
$csv = Join-Path $oracleDir "room-identities.csv"

function Save-DeviceFile {
  param([string] $Remote, [string] $Local)
  if (Test-Path $Local) { Remove-Item -Force $Local }
  $quotedAdb = '"' + $Adb + '"'
  $quotedLocal = '"' + $Local + '"'
  cmd /c "$quotedAdb -s $Serial exec-out run-as $PackageName cat $Remote > $quotedLocal"
}

Save-DeviceFile "databases/nfa-notification-collector.db" $db
cmd /c "`"$Adb`" -s $Serial exec-out run-as $PackageName cat databases/nfa-notification-collector.db-wal > `"$wal`"" | Out-Null
if ((Test-Path $wal) -and ((Get-Item $wal).Length -eq 0)) { Remove-Item -Force $wal }

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { throw "Python is required to read the Room file." }
& python (Join-Path $PSScriptRoot "compare_notification_oracle.py") export --database $db --csv $csv
if ($LASTEXITCODE -ne 0) { throw "Room identity export failed." }
Write-Host "Wrote package, key, tag, and postTime rows to the gitignored soak-oracle folder."
