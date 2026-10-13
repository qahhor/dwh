$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$temporary = Join-Path $repoRoot ('.local/staging-config-test-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $temporary
$compose = @('compose', '--env-file', (Join-Path $temporary 'test.env'),
    '-f', (Join-Path $repoRoot 'deploy/compose/docker-compose.prod.yml'),
    '-f', (Join-Path $repoRoot 'deploy/observability/docker-compose.observability.yml'),
    '-f', (Join-Path $repoRoot 'deploy/staging/docker-compose.staging.yml'))

function Assert-Value($Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Render([string[]]$Arguments) {
    $raw = & docker @Arguments 2> (Join-Path $temporary 'compose-errors.log')
    Assert-Value ($LASTEXITCODE -eq 0) 'Staging Compose render failed; inspect the private test log.'
    return ($raw | ConvertFrom-Json)
}
function Write-Fixture([hashtable]$Values) {
    $lines = $Values.Keys | Sort-Object | ForEach-Object { "$_=$($Values[$_])" }
    [IO.File]::WriteAllLines((Join-Path $temporary 'test.env'), $lines, [Text.UTF8Encoding]::new($false))
}

try {
    # Synthetic inputs render only: no containers, registry access or real credentials.
    $nonce = [guid]::NewGuid().ToString('N')
    $fixture = @{
        APP_VERSION = 'local-validation'; PROJECT_NAME = 'smartupcms-staging-config-test'
        DB_USER = 'fixture'; DB_PASSWORD = $nonce; ORGANIZATION_NAME = 'Fixture'
        POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N')
        MIGRATE_DB_PASSWORD = [guid]::NewGuid().ToString('N')
        BACKUP_DB_PASSWORD = [guid]::NewGuid().ToString('N')
        SMC_PUBLIC_URL = 'https://staging.example.invalid'; SMC_SECRETS_KEY = $nonce
        TYPESENSE_API_KEY = $nonce; BACKUP_AGE_RECIPIENT = 'age1fixture'
        SMC_S3_ENDPOINT = 'https://storage.example.invalid'; SMC_S3_BUCKET = 'fixture-app'
        SMC_S3_ACCESS_KEY = $nonce; SMC_S3_SECRET_KEY = $nonce
        BACKUP_S3_ENDPOINT = 'https://storage.example.invalid'; BACKUP_S3_BUCKET = 'fixture-recovery'
        STAGING_CLAMAV_SCRATCH_PATH = '/staging/clamav'; STAGING_PROMETHEUS_CONFIG = '/staging/collector.yml'
        STAGING_MONITORING_TLS_DIR = '/staging/tls'
        STAGING_OPS_PROMETHEUS_CONFIG = '/staging/ops.yml'; STAGING_PROMETHEUS_RULES_DIR = '/staging/rules'
    }
    foreach ($name in @('POSTGRES_PASSWORD_FILE', 'MIGRATE_DB_PASSWORD_FILE', 'DB_PASSWORD_FILE', 'BACKUP_DB_PASSWORD_FILE', 'BACKUP_S3_ACCESS_KEY_ID_FILE',
            'BACKUP_S3_SECRET_ACCESS_KEY_FILE', 'MONITOR_DB_PASSWORD_FILE')) {
        $fixture[$name] = '/staging/unused-secret'
    }
    foreach ($name in @('SERVER', 'WEB', 'BACKUP', 'POSTGRES', 'TYPESENSE', 'CLAMAV')) {
        $fixture["${name}_IMAGE"] = "fixture.invalid/$($name.ToLower())@sha256:$('a' * 64)"
    }
    Write-Fixture $fixture
    $config = Render ($compose + @('config', '--format', 'json'))
    $expected = @{
        server = @(3, 10GB); postgres = @(1.5, 6GB); clamav = @(1.5, 4GB)
        web = @(0.25, 2GB); typesense = @(0.5, 2GB); backup = @(0.25, 1GB)
        prometheus = @(0.25, 1GB); 'node-exporter' = @(0.1, 64MB); 'postgres-exporter' = @(0.1, 64MB)
    }
    $cpuTotal = 0.0; $memoryTotal = 0L
    foreach ($name in $expected.Keys) {
        $service = $config.services.$name
        Assert-Value ($null -ne $service) "Missing service: $name"
        Assert-Value ([double]$service.deploy.resources.limits.cpus -eq $expected[$name][0]) "Incorrect CPU: $name"
        Assert-Value ([long]$service.deploy.resources.limits.memory -eq $expected[$name][1]) "Incorrect memory: $name"
        $cpuTotal += [double]$service.deploy.resources.limits.cpus
        $memoryTotal += [long]$service.deploy.resources.limits.memory
        if ($name -eq 'web') {
            Assert-Value (@($service.ports).Count -eq 1 -and $service.ports[0].host_ip -eq '127.0.0.1' -and
                $service.ports[0].published -eq '8080') 'Only the loopback web port may be published.'
        } else { Assert-Value (-not $service.ports) "Internal service published a port: $name" }
    }
    Assert-Value ([Math]::Abs($cpuTotal - 7.45) -lt 0.001 -and $memoryTotal -eq 26.125GB) 'Staging resource budget changed.'
    Assert-Value ($config.services.server.environment.SMC_FILES_MAX_CONCURRENT_UPLOADS -eq '20') 'Upload admission must reach the server.'
    Assert-Value ($config.services.server.environment.JAVA_TOOL_OPTIONS -match '-Xmx4g' -and
        $config.services.server.environment.JAVA_TOOL_OPTIONS -match '-Djna.tmpdir=/opt/smartupcms/jna') 'Heap/JNA options were lost.'
    Assert-Value (@($config.services.server.tmpfs).Count -eq 2 -and
        @($config.services.server.tmpfs | Where-Object { $_ -match '^/tmp:.*size=3072m' }).Count -eq 1) 'Server tmpfs merged incorrectly.'
    Assert-Value (@($config.services.web.tmpfs | Where-Object { $_ -match '^/var/lib/nginx/tmp:.*size=1536m' }).Count -eq 1) 'The actual NGINX client body temp mount must hold 20 near-limit uploads.'
    Assert-Value ($config.services.server.environment.SMC_FILE_SCANNER_REQUIRED -eq 'true' -and
        $config.services.server.environment.SMC_BACKUP_MAX_AGE -eq '20h') 'Scanner or backup freshness weakened.'
    $scratch = @($config.services.clamav.volumes | Where-Object target -eq '/tmp')
    Assert-Value ($scratch.Count -eq 1 -and $scratch[0].type -eq 'bind' -and
        -not $scratch[0].bind.create_host_path) 'ClamAV scratch must require an existing quota-backed path.'
    Assert-Value ($config.networks.backend.internal -and $config.networks.monitoring.internal) 'Private networks must remain internal.'

    # The existing host/deploy runners accept one Compose file: prove a merged template preserves the overlay.
    $mergedPath = Join-Path $temporary 'merged.yml'
    $merged = & docker @compose config --no-interpolate --no-path-resolution --no-normalize 2> (Join-Path $temporary 'compose-errors.log')
    Assert-Value ($LASTEXITCODE -eq 0) 'Merged staging template failed.'
    [IO.File]::WriteAllLines($mergedPath, $merged, [Text.UTF8Encoding]::new($false))
    $again = Render @('compose', '--env-file', (Join-Path $temporary 'test.env'), '-f', $mergedPath, 'config', '--format', 'json')
    foreach ($service in $config.services.PSObject.Properties) {
        foreach ($property in $service.Value.PSObject.Properties) {
            if (($property.Value | ConvertTo-Json -Depth 40 -Compress) -ne
                ($again.services.($service.Name).($property.Name) | ConvertTo-Json -Depth 40 -Compress)) {
                Write-Host "Merged template differs at $($service.Name).$($property.Name)"
            }
        }
    }
    Assert-Value (($config.services | ConvertTo-Json -Depth 40 -Compress) -eq
        ($again.services | ConvertTo-Json -Depth 40 -Compress)) 'Single-file runner input changed the staged services.'
    foreach ($key in @('name', 'volumes', 'networks', 'secrets')) {
        Assert-Value (($config.$key | ConvertTo-Json -Depth 40 -Compress) -eq
            ($again.$key | ConvertTo-Json -Depth 40 -Compress)) "Single-file runner input changed $key."
    }
    $isolated = Render @('compose', '--env-file', (Join-Path $temporary 'test.env'), '-f', $mergedPath,
        '-p', 'smartupcms-restore-fixture', 'config', '--format', 'json')
    foreach ($kind in @('volumes', 'networks')) {
        foreach ($resource in $config.$kind.PSObject.Properties) {
            Assert-Value ($isolated.$kind.($resource.Name).name -ne $resource.Value.name -and
                $isolated.$kind.($resource.Name).name.StartsWith('smartupcms-restore-fixture_')) "Restore shares a $kind resource with staging."
        }
    }

    foreach ($key in @('SERVER_IMAGE', 'WEB_IMAGE', 'BACKUP_IMAGE', 'POSTGRES_IMAGE', 'TYPESENSE_IMAGE',
            'CLAMAV_IMAGE', 'STAGING_CLAMAV_SCRATCH_PATH', 'STAGING_PROMETHEUS_CONFIG', 'STAGING_MONITORING_TLS_DIR')) {
        $saved = $fixture[$key]; $fixture[$key] = ''; Write-Fixture $fixture
        $null = & docker @compose config --quiet 2> (Join-Path $temporary 'compose-errors.log')
        Assert-Value ($LASTEXITCODE -ne 0) "Missing $key must fail closed."
        $fixture[$key] = $saved
    }
    Write-Fixture $fixture
    $ops = Render @('compose', '--env-file', (Join-Path $temporary 'test.env'),
        '-f', (Join-Path $repoRoot 'deploy/staging/docker-compose.ops.yml'), 'config', '--format', 'json')
    Assert-Value ($ops.services.prometheus.deploy.resources.limits.cpus -eq '0.5' -and
        [long]$ops.services.prometheus.deploy.resources.limits.memory -eq 2GB -and
        $ops.services.prometheus.ports[0].host_ip -eq '127.0.0.1' -and
        $ops.services.prometheus.ports[0].published -eq '19090') 'Ops receiver limits or private port changed.'
    $fixture.STAGING_ALERTMANAGER_CONFIG = '/staging/alertmanager.yml'
    $fixture.STAGING_BLACKBOX_CONFIG = '/staging/blackbox.yml'
    $fixture.STAGING_ALERT_WEBHOOK_URL_FILE = '/staging/private-receiver'
    Write-Fixture $fixture
    $completeOps = Render @('compose', '--env-file', (Join-Path $temporary 'test.env'),
        '-f', (Join-Path $repoRoot 'deploy/staging/docker-compose.ops.yml'),
        '-f', (Join-Path $repoRoot 'deploy/staging/docker-compose.alerting.yml'), 'config', '--format', 'json')
    $opsCpu = 0.0
    $opsMemory = 0L
    foreach ($service in $completeOps.services.PSObject.Properties.Value) {
        $opsCpu += [double]::Parse([string]$service.deploy.resources.limits.cpus, [Globalization.CultureInfo]::InvariantCulture)
        $opsMemory += [long]$service.deploy.resources.limits.memory
    }
    Assert-Value ($opsCpu -le 0.5 -and $opsMemory -le 2GB) 'Complete ops stack exceeds the approved budget.'
    foreach ($name in @('alertmanager', 'blackbox', 'recovery-metrics')) {
        Assert-Value (-not $completeOps.services.$name.ports) 'Internal ops services must not publish ports.'
        Assert-Value ($completeOps.services.$name.image -match '@sha256:[a-f0-9]{64}$') 'Ops image must be immutable.'
    }
    Write-Host 'Staging config passed: resources, private ports, upload admission, mounts, merged runner input and nine missing-input failures.'
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot '.local')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
