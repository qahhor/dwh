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

$runtimeUid = 10001
$runtimeGid = 10001
$runtimeUser = "${runtimeUid}:${runtimeGid}"
$testVolume = "smartupcms-backup-status-" + [guid]::NewGuid().ToString('N')
$volumeCreated = $false

docker volume create $testVolume | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated backup-status volume.' }
$volumeCreated = $true

try {
    # The production backup and server images intentionally share UID/GID 10001.
    # Use the same ownership model here so Linux CI does not create a root-owned
    # 0600 bind-mounted file that the non-root runner cannot inspect.
    docker run --rm `
        -v "${testVolume}:/status" `
        alpine:3.22 chown "${runtimeUser}" /status
    if ($LASTEXITCODE -ne 0) { throw 'Could not initialize backup-status volume ownership.' }

    docker run --rm --user $runtimeUser `
        -e BACKUP_STATUS_FILE=/status/status.json `
        -v "${backupScripts}:/scripts:ro" `
        -v "${testVolume}:/status" `
        alpine:3.22 sh /scripts/write-status.sh SUCCESS 2026-09-02T08:00:00Z
    if ($LASTEXITCODE -ne 0) { throw 'write-status.sh failed.' }

    $successRaw = docker run --rm --user $runtimeUser `
        -v "${testVolume}:/status:ro" `
        alpine:3.22 cat /status/status.json
    if ($LASTEXITCODE -ne 0) { throw 'Runtime user could not read successful backup status.' }
    $successRaw = $successRaw -join "`n"
    $status = $successRaw | ConvertFrom-Json
    if ($status.status -ne 'SUCCESS') { throw 'Status must be SUCCESS.' }
    if ($successRaw -notmatch '"completedAt":"2026-09-02T08:00:00Z"') { throw 'Completion timestamp was not preserved.' }
    if ($null -ne $status.failureCode) { throw 'Successful status must not contain a failure code.' }

    $mode = docker run --rm --user $runtimeUser `
        -v "${testVolume}:/status:ro" `
        alpine:3.22 stat -c '%a' /status/status.json
    if ($LASTEXITCODE -ne 0 -or $mode.Trim() -ne '600') {
        throw "Backup status permissions must be 0600; found '$($mode.Trim())'."
    }

    docker run --rm --user $runtimeUser `
        -e BACKUP_STATUS_FILE=/status/status.json `
        -v "${backupScripts}:/scripts:ro" `
        -v "${testVolume}:/status" `
        alpine:3.22 sh /scripts/write-status.sh FAILED 2026-09-02T08:01:00Z CONFIGURATION_MISSING
    if ($LASTEXITCODE -ne 0) { throw 'Failed status could not be written.' }

    $rawStatus = docker run --rm --user $runtimeUser `
        -v "${testVolume}:/status:ro" `
        alpine:3.22 cat /status/status.json
    if ($LASTEXITCODE -ne 0) { throw 'Runtime user could not read failed backup status.' }
    $rawStatus = $rawStatus -join "`n"
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
    if ($volumeCreated) {
        docker volume rm --force $testVolume | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Could not remove isolated Docker volume '$testVolume'."
        }
    }
}

Write-Host 'Backup status and encryption contract passed.' -ForegroundColor Green
