[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'
$requiredMajorVersion = 17

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw 'JAVA_HOME must point to JDK 17.'
}

$javaExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
    throw 'JAVA_HOME does not contain bin\\java.exe.'
}

$javaVersion = (& $javaExecutable -version 2>&1 | Out-String).Trim()
if ($javaVersion -notmatch ('version "' + $requiredMajorVersion + '(\.|\")')) {
    throw "JAVA_HOME must resolve to JDK $requiredMajorVersion."
}

$wrapper = Join-Path $PSScriptRoot '..\gradlew.bat'
& $wrapper @GradleArgs
if ($LASTEXITCODE -ne 0) {
    throw "Gradle failed with exit code $LASTEXITCODE."
}
