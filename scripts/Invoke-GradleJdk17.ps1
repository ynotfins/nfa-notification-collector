[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
$requiredMajorVersion = 17
$javaHome = $env:JAVA_HOME

if ([string]::IsNullOrWhiteSpace($javaHome)) {
    throw 'JAVA_HOME must point to JDK 17.'
}

$requiredExecutables = @('java.exe', 'javac.exe')
foreach ($executableName in $requiredExecutables) {
    $executable = Join-Path $javaHome "bin\$executableName"
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
        throw "JAVA_HOME does not contain bin\$executableName."
    }

    $version = (& $executable -version 2>&1 | Out-String).Trim()
    $expectedPattern = if ($executableName -eq 'java.exe') {
        'version "' + $requiredMajorVersion + '(\.|\")'
    } else {
        'javac ' + $requiredMajorVersion + '(\.|\")'
    }
    if ($version -notmatch $expectedPattern) {
        throw "JAVA_HOME bin\$executableName must resolve to JDK $requiredMajorVersion."
    }
}

$wrapper = Join-Path $PSScriptRoot '..\gradlew.bat'
& $wrapper @GradleArgs
if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE."
}
