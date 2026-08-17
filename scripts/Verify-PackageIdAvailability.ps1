[CmdletBinding()]
param(
    [string] $SearchRoot = 'D:\github',
    [string] $ExcludedRepository = 'D:\github\nfa-notification-collector',
    [string] $PackageId = 'com.nfaalerts.collector',
    [ValidateRange(1, 6)][int] $DiscoveryDepth = 3
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$skipDirectoryNames = @('.git','node_modules','build','.gradle','.venv','venv','vendor','dist','out','.cache','cache')
$repositories = [Collections.Generic.List[string]]::new()
$discoveryErrors = [Collections.Generic.List[object]]::new()
$queue = [Collections.Generic.Queue[object]]::new()
$queue.Enqueue([pscustomobject]@{ Path = [IO.Path]::GetFullPath($SearchRoot); Depth = 0 })

while ($queue.Count -gt 0) {
    $item = $queue.Dequeue()
    $path = [string] $item.Path
    if (Test-Path -LiteralPath (Join-Path $path '.git')) {
        if ([IO.Path]::GetFullPath($path) -cne [IO.Path]::GetFullPath($ExcludedRepository)) {
            $repositories.Add([IO.Path]::GetFullPath($path))
        }
        continue
    }
    if ([int]$item.Depth -ge $DiscoveryDepth) { continue }
    try {
        foreach ($child in Get-ChildItem -LiteralPath $path -Directory -Force -ErrorAction Stop) {
            if ($skipDirectoryNames -contains $child.Name) { continue }
            $queue.Enqueue([pscustomobject]@{ Path = $child.FullName; Depth = [int]$item.Depth + 1 })
        }
    } catch {
        $discoveryErrors.Add([pscustomobject]@{ stage='repository_discovery'; path=$path; error_code='directory_unreadable' })
    }
}

$matches = [Collections.Generic.List[object]]::new()
$scanErrors = [Collections.Generic.List[object]]::new()
$fallbackRepositories = [Collections.Generic.List[string]]::new()
$filesScanned = 0
foreach ($repository in @($repositories | Sort-Object -Unique)) {
    $tracked = @(& git -C $repository ls-files -- 2>&1)
    if ($LASTEXITCODE -ne 0) {
        $fallbackRepositories.Add($repository)
        $fallback = @(& rg -n -F --hidden --glob '!.git/**' --glob '!node_modules/**' --glob '!build/**' --glob '!.gradle/**' --glob '!.venv/**' --glob '!venv/**' --glob '!vendor/**' --glob '!dist/**' --glob '!out/**' --glob '!desktop.ini' -- $PackageId $repository 2>&1)
        $fallbackCode = $LASTEXITCODE
        if ($fallbackCode -eq 0) {
            foreach ($line in $fallback) {
                $matches.Add([pscustomobject]@{ repository=$repository; path=[string]$line; line=$null; scan_mode='bounded_raw_fallback' })
            }
        } elseif ($fallbackCode -gt 1) {
            $scanErrors.Add([pscustomobject]@{ stage='bounded_raw_fallback'; repository=$repository; error_code='rg_failed' })
        }
        continue
    }
    foreach ($relative in $tracked) {
        if ([string]::IsNullOrWhiteSpace([string]$relative)) { continue }
        if ([IO.Path]::GetFileName([string]$relative) -ieq 'desktop.ini') { continue }
        $fullPath = Join-Path $repository ([string]$relative)
        if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) { continue }
        try { $file = Get-Item -LiteralPath $fullPath -ErrorAction Stop }
        catch [System.Management.Automation.ItemNotFoundException] { continue }
        catch {
            $scanErrors.Add([pscustomobject]@{ stage='tracked_file_stat'; repository=$repository; path=[string]$relative; error_code='unreadable' })
            continue
        }
        if ($file.Length -gt 2MB) { continue }
        try {
            $hits = @(Select-String -LiteralPath $fullPath -SimpleMatch $PackageId -Encoding utf8 -ErrorAction Stop)
            $filesScanned++
            foreach ($hit in $hits) {
                $matches.Add([pscustomobject]@{ repository=$repository; path=[string]$relative; line=[int]$hit.LineNumber })
            }
        } catch {
            $scanErrors.Add([pscustomobject]@{ stage='tracked_file_read'; repository=$repository; path=[string]$relative; error_code='not_utf8_or_unreadable' })
        }
    }
}

$allErrors = @($discoveryErrors) + @($scanErrors)
$result = [ordered]@{
    schema_version = 1
    captured_at_utc = [DateTimeOffset]::UtcNow.ToString('o')
    search_root = [IO.Path]::GetFullPath($SearchRoot)
    excluded_repository = [IO.Path]::GetFullPath($ExcludedRepository)
    package_id = $PackageId
    discovery_depth = $DiscoveryDepth
    repositories_scanned = @($repositories | Sort-Object -Unique).Count
    fallback_repositories = @($fallbackRepositories | Sort-Object -Unique)
    tracked_files_scanned = $filesScanned
    matches = @($matches)
    errors = $allErrors
    available = $matches.Count -eq 0 -and $allErrors.Count -eq 0
}

$result | ConvertTo-Json -Depth 6
if (-not $result.available) { exit 2 }
