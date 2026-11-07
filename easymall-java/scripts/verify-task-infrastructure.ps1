#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = 'mvn.cmd',
    [string]$MavenRepository,
    [string]$PrivateEnvFile,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $backendRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Pass -JavaHome with a JDK 21 directory.'
}
if ((Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw) -notmatch '(?m)^JAVA_VERSION="21(?:\.|\+|")') {
    throw 'Task tests require JDK 21.'
}
if (-not $PrivateEnvFile) { $PrivateEnvFile = Join-Path $workspaceRoot 'runtime/phase0/baseline.env' }
$privateFile = (Resolve-Path -LiteralPath $PrivateEnvFile).Path
if (-not $privateFile.Replace('\', '/').EndsWith('/runtime/phase0/baseline.env')) {
    throw 'Only the isolated baseline private env file is supported.'
}
if (-not $MavenRepository) { $MavenRepository = Join-Path $workspaceRoot 'runtime/phase0/maven-repository' }
$MavenRepository = [System.IO.Path]::GetFullPath($MavenRepository)
$mavenExecutable = (Get-Command $MavenCommand -ErrorAction Stop).Source
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
try {
    $env:JAVA_HOME = $JavaHome
    $env:Path = "$(Join-Path $JavaHome 'bin');$originalPath"
    Push-Location -LiteralPath $backendRoot
    try {
        $arguments = @('-B', '-ntp', "-Dmaven.repo.local=$MavenRepository", '-Dmaven.test.skip=false', '-DskipTests=false',
            '-Dtest=BusinessTaskUnitTest,TaskWorkerBootstrapTest,BusinessTaskMySqlIT', '-Dsurefire.failIfNoSpecifiedTests=false',
            '-Dtask.mysql.integration=true', "-Dtask.mysql.env-file=$privateFile", '-pl', 'easymall-common', '-am', 'test')
        if ($Offline) { $arguments = @('-o') + $arguments }
        $started = [DateTime]::UtcNow
        & $mavenExecutable @arguments
        if ($LASTEXITCODE -ne 0) { throw "Task verification failed with exit code $LASTEXITCODE." }
        $executed = 0
        foreach ($name in @('BusinessTaskUnitTest', 'TaskWorkerBootstrapTest', 'BusinessTaskMySqlIT')) {
            $report = Get-Item -LiteralPath (Join-Path $backendRoot "easymall-common/target/surefire-reports/TEST-com.easymall.task.$name.xml")
            if ($report.LastWriteTimeUtc -lt $started) { throw "Stale report for $name." }
            [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
            if ([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.tests -le 0) {
                throw "Incomplete task verification for $name."
            }
            $executed += [int]$xml.testsuite.tests
        }
        Write-Host "Task infrastructure passed: $executed tests executed against isolated MySQL. Synthetic rows retained."
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
}
