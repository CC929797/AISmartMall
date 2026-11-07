#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ConfigFile,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Smoke
)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$javaExecutable = Join-Path $JavaHome 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw 'Pass a JDK 21 directory using -JavaHome.' }
$release = Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
if ($release -notmatch '(?m)^JAVA_VERSION="21(?:\.|\+|")') { throw 'Worker requires JDK 21.' }
$workerJar = Join-Path $backendRoot 'easymall-admin/target/easymall-admin-1.0.0.jar'
if (-not (Test-Path -LiteralPath $workerJar)) { throw 'Build the Admin package first; this launcher reuses its dependency archive.' }
$configuration = (Resolve-Path -LiteralPath $ConfigFile).Path.Replace('\', '/')
$arguments = @('-Dloader.main=com.easymall.task.BusinessTaskWorkerApplication', '-cp', $workerJar,
    'org.springframework.boot.loader.launch.PropertiesLauncher', "--spring.config.location=file:$configuration")
if ($Smoke) { $arguments += '--business-task.smoke.enabled=true' }
& $javaExecutable @arguments
if ($LASTEXITCODE -ne 0) { throw "Worker exited with code $LASTEXITCODE." }
