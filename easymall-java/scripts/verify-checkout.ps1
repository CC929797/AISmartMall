#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand = 'mvn.cmd',
    [string]$MavenRepository,
    [string]$PrivateEnvFile,
    [switch]$Offline
)
# 仅验证入口：使用固定隔离库和合成记录，不访问支付网关、不清表或删除文件。
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $backendRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Pass a JDK 21 directory using -JavaHome.' }
if ((Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw) -notmatch '(?m)^JAVA_VERSION="21(?:\.|\+|")') { throw 'Checkout verification requires JDK 21.' }
if (-not $PrivateEnvFile) { $PrivateEnvFile = Join-Path $workspaceRoot 'runtime/phase0/baseline.env' }
$privateFile = (Resolve-Path -LiteralPath $PrivateEnvFile).Path
if (-not $privateFile.Replace('\', '/').EndsWith('/runtime/phase0/baseline.env')) { throw 'Only the isolated baseline private env file is supported.' }
if (-not $MavenRepository) { $MavenRepository = Join-Path $workspaceRoot 'runtime/phase0/maven-repository' }
$MavenRepository = [System.IO.Path]::GetFullPath($MavenRepository)
$mavenExecutable = (Get-Command $MavenCommand -ErrorAction Stop).Source
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
try {
    $env:JAVA_HOME = $JavaHome; $env:Path = "$(Join-Path $JavaHome 'bin');$originalPath"
    Push-Location -LiteralPath $backendRoot
    try {
        $names = @('com.easymall.order.CheckoutInputTest', 'com.easymall.service.OrderInputBoundaryTest',
            'com.easymall.entity.dto.PostOrderValidationTest', 'com.easymall.service.BusinessResourceOwnershipTest', 'com.easymall.task.OrderCheckoutMySqlIT')
        $arguments = @('-B', '-ntp', "-Dmaven.repo.local=$MavenRepository", '-Dmaven.test.skip=false', '-DskipTests=false',
            "-Dtest=$($names -join ',')", '-Dsurefire.failIfNoSpecifiedTests=false', '-Dtask.mysql.integration=true', '-Dorder.mysql.integration=true',
            "-Dtask.mysql.env-file=$privateFile", '-pl', 'easymall-common', '-am', 'test')
        if ($Offline) { $arguments = @('-o') + $arguments }
        $started = [DateTime]::UtcNow
        & $mavenExecutable @arguments
        if ($LASTEXITCODE -ne 0) { throw "Checkout verification failed with exit code $LASTEXITCODE." }
        $executed = 0
        foreach ($name in $names) {
            $report = Get-Item -LiteralPath (Join-Path $backendRoot "easymall-common/target/surefire-reports/TEST-$name.xml")
            if ($report.LastWriteTimeUtc -lt $started) { throw "Stale report for $name." }
            [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
            if ([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0 -or [int]$xml.testsuite.tests -le 0) { throw "Incomplete verification for $name." }
            $executed += [int]$xml.testsuite.tests
        }
        Write-Host "Checkout passed: $executed tests executed; isolated MySQL and synthetic records only."
    } finally { Pop-Location }
} finally { $env:JAVA_HOME = $originalJavaHome; $env:Path = $originalPath }
