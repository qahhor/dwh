param()

$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$backupScripts = Join-Path $repoRoot 'deploy/images/backup'
$writeStatus = Join-Path $backupScripts 'write-status.sh'
$backupLoop = Join-Path $backupScripts 'backup-loop.sh'
$bootstrapRole = Join-Path $backupScripts 'bootstrap-role.sh'

foreach ($requiredPath in @($writeStatus, $backupLoop, $bootstrapRole)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        throw "Required backup script is missing: $requiredPath"
    }
}

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("smartupcms-backup-status-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot | Out-Null

try {
    docker run --rm `
        -e BACKUP_STATUS_FILE=/status/status.json `
        -v "${backupScripts}:/scripts:ro" `
        -v "${testRoot}:/status" `
        alpine:3.22 sh /scripts/write-status.sh SUCCESS 2026-09-02T08:00:00Z
    if ($LASTEXITCODE -ne 0) { throw 'write-status.sh failed.' }

    $statusPath = Join-Path $testRoot 'status.json'
    $successRaw = Get-Content -LiteralPath $statusPath -Raw
    $status = $successRaw | ConvertFrom-Json
    if ($status.status -ne 'SUCCESS') { throw 'Status must be SUCCESS.' }
    if ($successRaw -notmatch '"completedAt":"2026-09-02T08:00:00Z"') { throw 'Completion timestamp was not preserved.' }
    if ($null -ne $status.failureCode) { throw 'Successful status must not contain a failure code.' }

    $mode = docker run --rm -v "${testRoot}:/status:ro" alpine:3.22 stat -c '%a' /status/status.json
    if ($LASTEXITCODE -ne 0 -or $mode.Trim() -ne '600') {
        throw "Backup status permissions must be 0600; found '$($mode.Trim())'."
    }

    docker run --rm `
        -e BACKUP_STATUS_FILE=/status/status.json `
        -v "${backupScripts}:/scripts:ro" `
        -v "${testRoot}:/status" `
        alpine:3.22 sh /scripts/write-status.sh FAILED 2026-09-02T08:01:00Z CONFIGURATION_MISSING
    if ($LASTEXITCODE -ne 0) { throw 'Failed status could not be written.' }

    $rawStatus = Get-Content -LiteralPath $statusPath -Raw
    $failed = $rawStatus | ConvertFrom-Json
    if ($failed.status -ne 'FAILED' -or $failed.failureCode -ne 'CONFIGURATION_MISSING') {
        throw 'Failed status is missing its sanitized failure code.'
    }
    foreach ($forbidden in @('password', 'postgresql://', 'jdbc:', '.dump', '/backups/')) {
        if ($rawStatus -match [regex]::Escape($forbidden)) {
            throw "Backup status leaks forbidden detail: $forbidden"
        }
    }

    $loop = Get-Content -LiteralPath $backupLoop -Raw
    if ($loop -notmatch 'AGE_RECIPIENT') { throw 'Backup loop must require an age recipient.' }
    if ($loop -notmatch 'pg_dump[\s\S]*\|[\s\S]*age') { throw 'Database dump must stream directly into age.' }
    if ($loop -match 'pg_dump[\s\S]*-f\s+[^|\r\n]+\.dump(?:\s|"|\x27)') {
        throw 'Backup loop must never persist an unencrypted dump.'
    }

    $bootstrap = Get-Content -LiteralPath $bootstrapRole -Raw
    if ($bootstrap -notmatch 'default_transaction_read_only') {
        throw 'Dedicated backup role must be read-only by default.'
    }
    if ($bootstrap -notmatch 'NOSUPERUSER') {
        throw 'Dedicated backup role must not be a superuser.'
    }
}
finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}

Write-Host 'Backup status and encryption contract passed.' -ForegroundColor Green
