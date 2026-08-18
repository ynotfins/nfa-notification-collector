[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [hashtable]$TestSecretValues = @{}
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath $RepositoryRoot).Path
$expectedDebugManifest = 'app/src/debug/androidmanifest.xml'
$androidNamespace = 'http://schemas.android.com/apk/res/android'
$expectedDomains = [System.Collections.Generic.HashSet[string]]::new([string[]]@('database', 'sharedpref', 'file', 'root', 'external'))
$errors = [System.Collections.Generic.List[string]]::new()

function Add-Error([string]$path, [string]$error) {
    $errors.Add("${path}:error=$error")
}

function Read-XmlFile([string]$absolutePath, [string]$displayPath) {
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        Add-Error $displayPath 'missing'
        return $null
    }

    try {
        $settings = [System.Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [System.Xml.XmlReader]::Create($absolutePath, $settings)
        try {
            $document = [System.Xml.XmlDocument]::new()
            $document.XmlResolver = $null
            $document.Load($reader)
            return $document
        } finally {
            $reader.Dispose()
        }
    } catch {
        Add-Error $displayPath 'invalid_xml'
        return $null
    }
}

function Read-RepositoryXml([string]$relativePath) {
    return Read-XmlFile (Join-Path $repositoryRoot $relativePath) $relativePath
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
        '/*[local-name()="manifest"]/*[starts-with(local-name(), "uses-permission") and @android:name="android.permission.QUERY_ALL_PACKAGES"]',
        $namespaceManager
    ).Count
}

function Get-SecretValue([string]$secretName) {
    if ($TestSecretValues.ContainsKey($secretName)) {
        return [string]$TestSecretValues[$secretName]
    }
    return [Environment]::GetEnvironmentVariable($secretName, 'User')
}

function Test-TrackedFiles {
    $patterns = @(
        '(^|/)\.env[^/]*$',
        '(^|/)local\.properties$',
        '\.(jks|keystore|p12)$',
        '(?i)\.(db|sqlite|sqlite3)(-(wal|shm))?$',
        '(?i)(^|/)(captures|notification-captures|captured-notifications)(/|$)',
        '(?i)(^|/)[^/]*(real[-_]?notification|notification[-_]?capture|captured[-_]?notification)[^/]*\.(json|txt|xml|csv|log)$',
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

    $secretNames = @(
        'NFA_COLLECTOR_BEARER',
        'NFA_INGEST_BEARER',
        'NFA_INGEST_DEVICE_BEARER_CURRENT',
        'NFA_INGEST_TOKEN'
    )
    $sourceFiles = $trackedFiles | Where-Object { $_ -notmatch '\.(jar|png|jpg|jpeg|gif|webp|ico)$' }
    foreach ($secretName in $secretNames) {
        $secret = Get-SecretValue $secretName
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
    $manifest = Read-XmlFile $_.FullName $relativePath
    if ($null -ne $manifest -and $relativePath -notmatch '(^|/)(build|\.gradle)/' -and
        (Get-QueryAllPackagesPermissionCount $manifest) -gt 0 -and $relativePath -ne $expectedDebugManifest) {
        Add-Error $relativePath 'query_all_packages_outside_debug'
    }
}

$mainManifestPath = 'app/src/main/AndroidManifest.xml'
$mainManifest = Read-RepositoryXml $mainManifestPath
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
$debugManifest = Read-RepositoryXml $debugManifestPath
if ($null -ne $debugManifest -and (Get-QueryAllPackagesPermissionCount $debugManifest) -ne 1) {
    Add-Error $debugManifestPath 'query_all_packages_count'
}

$backupRulesPath = 'app/src/main/res/xml/backup_rules.xml'
$backupRules = Read-RepositoryXml $backupRulesPath
if ($null -ne $backupRules) {
    Test-ExcludeDomains $backupRules.DocumentElement $backupRulesPath 'legacy'
}

$extractionRulesPath = 'app/src/main/res/xml/data_extraction_rules.xml'
$extractionRules = Read-RepositoryXml $extractionRulesPath
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
