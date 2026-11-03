#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ConfigPath,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$NewConfig
)
$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw '请通过 -JavaHome 指定 JDK 21 目录。'
}
$jar = Join-Path $backendRoot 'easymall-admin/target/easymall-admin-1.0.0.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    throw '请先完成后端 package，生成管理员 JAR。'
}
$mode = if ($NewConfig) { '--new-config' } else { '--migrate-config' }
& (Join-Path $JavaHome 'bin/java.exe') '-Dfile.encoding=UTF-8' `
    '-Dloader.main=com.easymall.tools.AdminPasswordHashTool' '-cp' $jar `
    'org.springframework.boot.loader.launch.PropertiesLauncher' $mode ([IO.Path]::GetFullPath($ConfigPath))
if ($LASTEXITCODE -ne 0) {
    throw '管理员密码配置未完成，请检查文件；没有输出原密码。'
}
