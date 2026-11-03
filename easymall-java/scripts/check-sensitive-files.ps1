#requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$repositoryRoot = (& git -C $PSScriptRoot rev-parse --show-toplevel).Trim()
$trackedFiles = @(& git -C $repositoryRoot -c core.quotepath=false ls-files --cached --others --exclude-standard)
$findings = @()
foreach ($relativePath in $trackedFiles) {
    if ($relativePath -notmatch '\.(java|ya?ml|properties|json|js|vue|env|pem|key|p12|pfx)$') {
        continue
    }
    $absolutePath = Join-Path $repositoryRoot $relativePath
    if (-not (Test-Path -LiteralPath $absolutePath -PathType Leaf)) {
        continue
    }
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($absolutePath)) {
        $lineNumber++
        if ($line -match '-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|\bsk-[A-Za-z0-9_-]{24,}\b|\bAKIA[A-Z0-9]{16}\b') {
            # 报告位置，不把发现的凭据值复制到终端或报告。
            $findings += [pscustomobject]@{ file=$relativePath; line=$lineNumber; type='possible-private-credential' }
        }
    }
}
[pscustomobject]@{ candidateFileCount=$trackedFiles.Count; findingCount=$findings.Count; findings=$findings;
    scope='Git跟踪与未忽略的待提交文件中的已知凭据特征；不包含私有配置、Git历史或供应商侧轮换检查' } | ConvertTo-Json -Depth 4
if ($findings.Count -gt 0) {
    exit 1
}
