#requires -Version 7.0
[CmdletBinding()]
param([string]$PrivateEnvFile)

$ErrorActionPreference = 'Stop'
$backendRoot = Split-Path -Parent $PSScriptRoot
if (-not $PrivateEnvFile) {
    $PrivateEnvFile = Join-Path (Split-Path -Parent $backendRoot) 'runtime/phase0/baseline.env'
}
$PrivateEnvFile = [System.IO.Path]::GetFullPath($PrivateEnvFile)
if (-not (Test-Path -LiteralPath $PrivateEnvFile)) {
    $null = New-Item -ItemType Directory -Path (Split-Path -Parent $PrivateEnvFile) -Force
    $randomBytes = [byte[]]::new(24)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $dbPassword = [Convert]::ToBase64String($randomBytes)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $rootPassword = [Convert]::ToBase64String($randomBytes)
    [System.IO.File]::WriteAllLines($PrivateEnvFile,
        @("BASELINE_DB_PASSWORD=$dbPassword", "BASELINE_MYSQL_ROOT_PASSWORD=$rootPassword"),
        [System.Text.UTF8Encoding]::new($false))
}
$privateSettings = Get-Content -LiteralPath $PrivateEnvFile -Raw
$passwordMatch = [regex]::Match($privateSettings, '(?m)^BASELINE_DB_PASSWORD=(.*)$')
if (-not $passwordMatch.Success) { throw 'Missing baseline password in private env file.' }
$values = @{
    DB_USERNAME = 'baseline'
    DB_PASSWORD = $passwordMatch.Groups[1].Value.Trim()
    AI_API_KEY = 'baseline-placeholder'
    AI_BASE_URL = 'http://127.0.0.1:18080'
    AI_CHAT_MODEL = 'baseline-chat'
    AI_EMBEDDING_MODEL = 'baseline-embedding'
    ADMIN_ACCOUNT = 'baseline-admin'
    # 仅用于隔离环境的公开合成账号；不得用于真实部署。
    ADMIN_PASSWORD_HASH = '$argon2id$v=19$m=19456,t=2,p=1$Q0/3Z7MCSRiXEbY4OJTvRw$jtolzBoGP5evamacES81VFe4LHJuWN5mblchF6i9yK4'
}
foreach ($module in @('web', 'admin', 'mcp')) {
    $samplePath = Join-Path $backendRoot "config/local/$module.example.yml"
    $localPath = Join-Path $backendRoot "config/local/$module.yml"
    if (Test-Path -LiteralPath $localPath) {
        Write-Host "$module.yml already exists; preserved."
        continue
    }
    $sample = Get-Content -LiteralPath $samplePath -Raw -Encoding UTF8
    $resolved = [regex]::Replace($sample, '\$\{([A-Z_]+)(?::([^}]*))?\}', {
        param($match)
        $key = $match.Groups[1].Value
        if ($values.ContainsKey($key)) { $value = $values[$key] }
        elseif ($match.Groups[2].Success) { $value = $match.Groups[2].Value }
        else { throw "No synthetic baseline value for $key." }
        return "'" + $value.Replace("'", "''") + "'"
    })
    if ($module -eq 'web') {
        # Safe only because the generated configuration selects the dedicated ES instance.
        $resolved = $resolved.Replace('initialize-schema: false', 'initialize-schema: true')
    }
    [System.IO.File]::WriteAllText($localPath, $resolved, [System.Text.UTF8Encoding]::new($false))
    Write-Host "Created ignored $module.yml for isolated baseline infrastructure and local model mock."
}
Write-Host 'No credentials printed. Existing configurations and data were preserved.'
