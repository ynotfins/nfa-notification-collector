#Requires -Version 5.1
<#
.SYNOPSIS
  Compare a gitignored events capture with a Room identity export.
  The report lists package, id, and tag only.
#>
param(
  [string] $EventsFile = "",
  [string] $RoomCsv = "",
  [string] $ReportFile = ""
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$oracleDir = Join-Path $root "docs\evidence\soak-oracle"
if (-not $EventsFile) { $EventsFile = Join-Path $oracleDir "notification-events.events.log" }
if (-not $RoomCsv) { $RoomCsv = Join-Path $oracleDir "room-identities.csv" }
if (-not $ReportFile) { $ReportFile = Join-Path $oracleDir "compare-report.txt" }
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { throw "Python is required to compare the soak oracle." }
& python (Join-Path $PSScriptRoot "compare_notification_oracle.py") compare `
  --events $EventsFile --room $RoomCsv --report $ReportFile
if ($LASTEXITCODE -ne 0) { throw "Oracle compare failed." }
Write-Host "Wrote a package/id/tag report to the gitignored soak-oracle folder."
