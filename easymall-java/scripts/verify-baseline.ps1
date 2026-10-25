#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = 'mvn.cmd',
    [string]$MavenRepository,
    [switch]$Package
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Set JAVA_HOME or pass -JavaHome with a JDK 21 directory.'
}
$javaRelease = Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
if ($javaRelease -notmatch '(?m)^JAVA_VERSION="21(?:\.|\+|")') {
    throw 'This baseline requires JDK 21; the selected JDK has a different major version.'
}
if (-not $MavenRepository) {
    $MavenRepository = Join-Path (Split-Path -Parent $backendRoot) 'runtime/phase0/maven-repository'
}
$MavenRepository = [System.IO.Path]::GetFullPath($MavenRepository)
$mavenExecutable = (Get-Command $MavenCommand -ErrorAction Stop).Source
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$(Join-Path $JavaHome 'bin');$originalPath"
    Push-Location -LiteralPath $backendRoot
    try {
        $mavenArguments = @('-B', '-ntp', "-Dmaven.repo.local=$MavenRepository",
            '-Dmaven.test.skip=false', '-DskipTests=false')
        if ($Package) { $mavenArguments += 'package' } else { $mavenArguments += 'test' }
        $verificationStartedUtc = [DateTime]::UtcNow
        & $mavenExecutable @mavenArguments
        if ($LASTEXITCODE -ne 0) { throw "Maven failed with exit code $LASTEXITCODE." }

        $executed = 0
        foreach ($module in @('easymall-common', 'easymall-web')) {
            $reportDirectory = Join-Path $backendRoot "$module/target/surefire-reports"
            if (-not (Test-Path -LiteralPath $reportDirectory)) {
                throw "Missing test reports for $module."
            }
            $currentReports = @(Get-ChildItem -LiteralPath $reportDirectory -Filter 'TEST-*.xml' -File |
                Where-Object { $_.LastWriteTimeUtc -ge $verificationStartedUtc })
            if ($currentReports.Count -eq 0) {
                throw "No test reports from this invocation for $module; historical reports are not evidence."
            }
            $moduleExecuted = 0
            foreach ($report in $currentReports) {
                [xml]$testReport = Get-Content -LiteralPath $report.FullName -Raw
                $suite = $testReport.testsuite
                if ([int]$suite.failures -gt 0 -or [int]$suite.errors -gt 0) {
                    throw "A test failed in $($report.Name)."
                }
                $moduleExecuted += [int]$suite.tests - [int]$suite.skipped
            }
            if ($moduleExecuted -le 0) { throw "No tests actually executed for $module." }
            $executed += $moduleExecuted
        }
        if ($executed -le 0) { throw 'No tests actually executed.' }
        Write-Host "Baseline passed: $executed tests executed."
    }
    finally { Pop-Location }
}
finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
}
