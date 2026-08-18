[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$hygieneScript = Join-Path $PSScriptRoot 'Test-RepositoryHygiene.ps1'
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ("nfa-hygiene-" + [Guid]::NewGuid())
$sentinel = 'NFA_HYGIENE_SENTINEL_DO_NOT_PRINT'

function Write-FixtureFile([string]$relativePath, [string]$content) {
    $path = Join-Path $fixtureRoot $relativePath
    $parent = Split-Path -Parent $path
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    [IO.File]::WriteAllText($path, $content, [Text.UTF8Encoding]::new($false))
}

try {
    New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null
    foreach ($relativePath in @(
        'app/src/main/AndroidManifest.xml',
        'app/src/debug/AndroidManifest.xml',
        'app/src/main/res/xml/backup_rules.xml',
        'app/src/main/res/xml/data_extraction_rules.xml'
    )) {
        $source = Join-Path $repositoryRoot $relativePath
        Write-FixtureFile $relativePath ([IO.File]::ReadAllText($source))
    }

    Write-FixtureFile 'fixture.txt' $sentinel
    Write-FixtureFile 'captures/captured-notification.json' '{}'
    Write-FixtureFile 'fixtures/real-notification-fixture.json' '{}'
    Write-FixtureFile 'outbox.db' 'not-a-database'
    Write-FixtureFile 'outbox.db-wal' 'not-a-database'
    Write-FixtureFile 'outbox.db-shm' 'not-a-database'
    Write-FixtureFile 'feature/src/main/AndroidManifest.xml' @'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.QUERY_ALL&#x5f;PACKAGES" />
</manifest>
'@

    git -C $fixtureRoot init -q
    git -C $fixtureRoot add -- . 2>$null

    $output = @(& $hygieneScript -RepositoryRoot $fixtureRoot -TestSecretValues @{
            NFA_INGEST_DEVICE_BEARER_CURRENT = $sentinel
        } 2>&1)
    $exitCode = $LASTEXITCODE
    $renderedOutput = $output | Out-String

    if ($exitCode -ne 1) {
        throw 'Expected hygiene fixture to fail.'
    }
    if ($renderedOutput.Contains($sentinel)) {
        throw 'Hygiene fixture output exposed the sentinel.'
    }

    foreach ($expectedLabel in @(
        'fixture.txt:error=active_secret_detected',
        'captures/captured-notification.json:error=prohibited_tracked_file',
        'fixtures/real-notification-fixture.json:error=prohibited_tracked_file',
        'outbox.db:error=prohibited_tracked_file',
        'outbox.db-wal:error=prohibited_tracked_file',
        'outbox.db-shm:error=prohibited_tracked_file',
        'feature/src/main/androidmanifest.xml:error=query_all_packages_outside_debug'
    )) {
        if (-not $renderedOutput.Contains($expectedLabel)) {
            throw "Expected hygiene label was absent: $expectedLabel"
        }
    }

    Write-Output 'PASS'
    exit 0
} finally {
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
