#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PrivateEnvFile,
    [string]$MysqlCommand = 'mysql.exe'
)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
$privateSettings = Get-Content -LiteralPath $PrivateEnvFile -Raw
$passwordMatch = [regex]::Match($privateSettings, '(?m)^BASELINE_DB_PASSWORD=(.*)$')
if (-not $passwordMatch.Success) { throw 'Missing BASELINE_DB_PASSWORD in the private env file.' }
$testPassword = $passwordMatch.Groups[1].Value.Trim()
$mysqlExecutable = (Get-Command $MysqlCommand -ErrorAction Stop).Source

function Invoke-BaselineSql([string]$Sql) {
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $mysqlExecutable
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardInputEncoding = [System.Text.UTF8Encoding]::new($false)
    $startInfo.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
    foreach ($argument in @('--protocol=TCP', '--host=127.0.0.1', '--port=13306',
        '--user=baseline', '--database=easymall_test', '--default-character-set=utf8mb4',
        '--batch', '--skip-column-names', '--connect-timeout=5')) {
        $startInfo.ArgumentList.Add($argument)
    }
    $startInfo.Environment['MYSQL_PWD'] = $testPassword
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        $null = $process.Start()
        $outputTask = $process.StandardOutput.ReadToEndAsync()
        $errorTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.Write($Sql)
        $process.StandardInput.Close()
        $process.WaitForExit()
        $outputText = $outputTask.GetAwaiter().GetResult()
        $errorText = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            # Do not echo connection details or SQL values into verification logs.
            throw "Isolated baseline SQL failed (exit $($process.ExitCode)); no cleanup was attempted."
        }
        return $outputText.Trim()
    }
    finally { $process.Dispose() }
}

$schemaFile = Join-Path $backendRoot 'db/baseline/00-schema.sql'
$fixtureFile = Join-Path $backendRoot 'db/baseline/01-fixture.sql'
if (-not (Test-Path -LiteralPath $fixtureFile)) { throw 'Fixture file is not ready.' }
$selectedDatabase = Invoke-BaselineSql 'SELECT DATABASE();'
if ($selectedDatabase -ne 'easymall_test') { throw 'Refusing a database other than easymall_test.' }
$existingTables = Invoke-BaselineSql 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();'
if ($existingTables -ne '0') { throw 'The isolated database is not empty; refusing to overwrite or reset it.' }

$schemaSql = Get-Content -LiteralPath $schemaFile -Raw -Encoding UTF8
$fixtureSql = Get-Content -LiteralPath $fixtureFile -Raw -Encoding UTF8
$null = Invoke-BaselineSql $schemaSql
$null = Invoke-BaselineSql $fixtureSql
$createdTables = Invoke-BaselineSql 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();'
$fixtureCounts = Invoke-BaselineSql @'
SELECT (SELECT COUNT(*) FROM user_info), (SELECT COUNT(*) FROM sys_category),
       (SELECT COUNT(*) FROM product_info), (SELECT COUNT(*) FROM product_sku),
       (SELECT COUNT(*) FROM user_address),
       (SELECT stock FROM product_sku WHERE product_id='BASELINEPROD001');
'@
if ($createdTables -ne '16' -or $fixtureCounts -ne "1`t2`t1`t1`t1`t10") {
    throw 'Unexpected fixture counts; no cleanup or repeat import was attempted.'
}
Write-Host "Initialized isolated easymall_test on 127.0.0.1:13306 with $createdTables tables and synthetic fixtures."
