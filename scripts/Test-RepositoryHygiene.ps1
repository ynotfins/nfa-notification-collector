[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$expectedDebugManifest = 'app/src/debug/androidmanifest.xml'
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$expectedDomains = [System.Collections.Generic.HashSet[string]]::new([string[]]@('database', 'sharedpref', 'file', 'root', 'external'))
$errors = [System.Collections.Generic.List[string]]::new()

function Add-Error([string]$path, [string]$error) {
    $errors.Add("${path}:error=$error")
}

function Read-Xml([string]$relativePath) {
    $absolutePath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        Add-Error $relativePath 'missing'
        return $null
    }

    try {
        $document = [System.Xml.XmlDocument]::new()
        $document.XmlResolver = $null
        $document.Load($absolutePath)
        return $document
    } catch {
        Add-Error $relativePath 'invalid_xml'
        return $null
    }
}

function Test-ExcludeDomains([System.Xml.XmlElement]$section, [string]$relativePath, [string]$sectionName) {
    if ($null -eq $section) {
        Add-Error $relativePath "missing_$sectionName"
        return
    }

    $actualDomains = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($exclude in $section.SelectNodes('./exclude')) {
        [void]$actualDomains.Add($exclude.GetAttribute('domain'))
    }

    if (-not $actualDomains.SetEquals($expectedDomains)) {
        Add-Error $relativePath "invalid_${sectionName}_excludes"
    }
}

function Get-QueryAllPackagesPermissionCount([System.Xml.XmlDocument]$document) {
    $namespaceManager = [System.Xml.XmlNamespaceManager]::new($document.NameTable)
    $namespaceManager.AddNamespace('android', $androidNamespace)
    return $document.SelectNodes(
        '/manifest/uses-permission[@android:name="android.permission.QUERY_ALL_PACKAGES"]',
        $namespaceManager
    ).Count
}

function Test-TrackedFiles {
    $patterns = @(
        '(^|/)\.env[^/]*$',
        '(^|/)local\.properties$',
        '\.(jks|keystore|p12)$',
        '\.(db|sqlite|sqlite3)$',
        '\.(apk|aab)$',
        '(^|/)(\.gradle|\.idea|build)(/|$)'
    )
    $trackedFiles = @(git -C $repositoryRoot ls-files)
    foreach ($trackedFile in $trackedFiles) {
        $normalizedPath = $trackedFile.Replace('\', '/')
        foreach ($pattern in $patterns) {
            if ($normalizedPath -match $pattern) {
                Add-Error $normalizedPath 'prohibited_tracked_file'
                break
            }
        }
    }

    $secretNames = @('NFA_COLLECTOR_BEARER', 'NFA_INGEST_BEARER', 'NFA_INGEST_TOKEN')
    $sourceFiles = $trackedFiles | Where-Object { $_ -notmatch '\.(jar|png|jpg|jpeg|gif|webp|ico)$' }
    foreach ($secretName in $secretNames) {
        $secret = [Environment]::GetEnvironmentVariable($secretName, 'User')
        if ([string]::IsNullOrEmpty($secret)) {
            continue
        }
        foreach ($sourceFile in $sourceFiles) {
            $sourcePath = Join-Path $repositoryRoot $sourceFile
            if ((Test-Path -LiteralPath $sourcePath -PathType Leaf) -and
                (Select-String -LiteralPath $sourcePath -SimpleMatch -Quiet -Pattern $secret)) {
                Add-Error $sourceFile 'active_secret_detected'
            }
        }
    }
}

Get-ChildItem -LiteralPath $repositoryRoot -Recurse -Filter AndroidManifest.xml | ForEach-Object {
    $relativePath = [IO.Path]::GetRelativePath($repositoryRoot, $_.FullName).Replace('\', '/').ToLowerInvariant()
    if ($relativePath -notmatch '(^|/)(build|\.gradle)/') {
        $content = Get-Content -LiteralPath $_.FullName -Raw
        if ($content.Contains('android.permission.QUERY_ALL_PACKAGES') -and $relativePath -ne $expectedDebugManifest) {
            Add-Error $relativePath 'query_all_packages_outside_debug'
        }
    }
}

$mainManifestPath = 'app/src/main/AndroidManifest.xml'
$mainManifest = Read-Xml $mainManifestPath
if ($null -ne $mainManifest) {
    $application = $mainManifest.DocumentElement.SelectSingleNode('./application')
    if ($null -eq $application) {
        Add-Error $mainManifestPath 'missing_application'
    } else {
        $attributes = @{
            allowBackup = 'false'
            fullBackupContent = '@xml/backup_rules'
            dataExtractionRules = '@xml/data_extraction_rules'
            usesCleartextTraffic = 'false'
        }
        foreach ($attribute in $attributes.GetEnumerator()) {
            if ($application.GetAttribute($attribute.Key, $androidNamespace) -ne $attribute.Value) {
                Add-Error $mainManifestPath "invalid_$($attribute.Key)"
            }
        }
    }
    if ((Get-QueryAllPackagesPermissionCount $mainManifest) -ne 0) {
        Add-Error $mainManifestPath 'query_all_packages_present'
    }
}

$debugManifestPath = 'app/src/debug/AndroidManifest.xml'
$debugManifest = Read-Xml $debugManifestPath
if ($null -ne $debugManifest -and (Get-QueryAllPackagesPermissionCount $debugManifest) -ne 1) {
    Add-Error $debugManifestPath 'query_all_packages_count'
}

$backupRulesPath = 'app/src/main/res/xml/backup_rules.xml'
$backupRules = Read-Xml $backupRulesPath
if ($null -ne $backupRules) {
    Test-ExcludeDomains $backupRules.DocumentElement $backupRulesPath 'legacy'
}

$extractionRulesPath = 'app/src/main/res/xml/data_extraction_rules.xml'
$extractionRules = Read-Xml $extractionRulesPath
if ($null -ne $extractionRules) {
    Test-ExcludeDomains ($extractionRules.DocumentElement.SelectSingleNode('./cloud-backup')) $extractionRulesPath 'cloud_backup'
    Test-ExcludeDomains ($extractionRules.DocumentElement.SelectSingleNode('./device-transfer')) $extractionRulesPath 'device_transfer'
}

Test-TrackedFiles

if ($errors.Count -gt 0) {
    $errors | Sort-Object -Unique | Write-Output
    exit 1
}

Write-Output 'PASS'
