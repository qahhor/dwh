param(
    [Parameter(Mandatory = $true)][string]$ConfigFile,
    [string]$EvidenceDirectory = 'output/managed-acceptance'
)

$ErrorActionPreference = 'Stop'

function Read-DotEnv([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Acceptance configuration was not found: $Path"
    }

    $values = @{}
    foreach ($rawLine in Get-Content -LiteralPath $Path) {
        $line = $rawLine.Trim()
        if (-not $line -or $line.StartsWith('#')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { throw 'Acceptance configuration contains an invalid line.' }
        $key = $line.Substring(0, $separator).Trim()
        if ($values.ContainsKey($key)) { throw "Acceptance configuration contains duplicate key '$key'." }
        $values[$key] = $line.Substring($separator + 1).Trim()
    }
    return $values
}

function Get-RequiredValue([hashtable]$Values, [string]$Name) {
    $value = "$($Values[$Name])".Trim()
    if (-not $value -or $value -eq 'CHANGE_ME' -or $value.Contains('CHANGE_ME')) {
        throw "Required acceptance value '$Name' is not configured."
    }
    return $value
}

function Get-RequiredSecret([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "Required secret environment variable '$Name' is not configured."
    }
    return $value
}

function Get-HeaderValue($Response, [string]$Name) {
    $values = $null
    if (-not $Response.Headers.TryGetValues($Name, [ref]$values)) { return '' }
    return (@($values) -join ',')
}

function Get-RedactedIdentifier([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [System.Security.Cryptography.SHA256]::HashData($bytes)
    return 'sha256:' + ([Convert]::ToHexString($hash).ToLowerInvariant().Substring(0, 16))
}

$checks = [System.Collections.Generic.List[object]]::new()
function Add-Check([string]$Id, [string]$Status, [string]$Summary) {
    $checks.Add([ordered]@{ id = $Id; status = $Status; summary = $Summary })
    $color = if ($Status -eq 'PASS') { 'Green' } elseif ($Status -eq 'NOT_APPLICABLE') { 'Yellow' } else { 'Red' }
    Write-Host "[$Status] $Id - $Summary" -ForegroundColor $color
}

function Invoke-Control([string]$Id, [scriptblock]$Control) {
    try {
        $summary = & $Control
        Add-Check $Id 'PASS' "$summary"
    }
    catch {
        Add-Check $Id 'FAIL' $_.Exception.Message
    }
}

$config = Read-DotEnv $ConfigFile
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$generatedAt = (Get-Date).ToUniversalTime()
$gitSha = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $gitSha -notmatch '^[a-f0-9]{40}$') {
    throw 'git rev-parse HEAD did not return an immutable commit SHA.'
}

$profileId = Get-RequiredValue $config 'ACCEPTANCE_PROFILE_ID'
$publicOriginText = Get-RequiredValue $config 'ACCEPTANCE_PUBLIC_ORIGIN'
$publicOrigin = [uri]$publicOriginText
$originIp = Get-RequiredValue $config 'ACCEPTANCE_ORIGIN_IP'
$expectedImages = [ordered]@{
    server = Get-RequiredValue $config 'ACCEPTANCE_EXPECTED_SERVER_IMAGE'
    web = Get-RequiredValue $config 'ACCEPTANCE_EXPECTED_WEB_IMAGE'
    backup = Get-RequiredValue $config 'ACCEPTANCE_EXPECTED_BACKUP_IMAGE'
    postgres = Get-RequiredValue $config 'ACCEPTANCE_EXPECTED_POSTGRES_IMAGE'
    typesense = Get-RequiredValue $config 'ACCEPTANCE_EXPECTED_TYPESENSE_IMAGE'
}
$accountId = Get-RequiredValue $config 'ACCEPTANCE_CLOUDFLARE_ACCOUNT_ID'
$zoneId = Get-RequiredValue $config 'ACCEPTANCE_CLOUDFLARE_ZONE_ID'
$zoneName = Get-RequiredValue $config 'ACCEPTANCE_CLOUDFLARE_ZONE_NAME'
$bucket = Get-RequiredValue $config 'ACCEPTANCE_R2_BUCKET'
$recoveryBucket = Get-RequiredValue $config 'ACCEPTANCE_R2_RECOVERY_BUCKET'
$r2Endpoint = Get-RequiredValue $config 'ACCEPTANCE_R2_ENDPOINT'
$lifecycleRuleId = Get-RequiredValue $config 'ACCEPTANCE_R2_LIFECYCLE_RULE_ID'
$r2TestPrefix = Get-RequiredValue $config 'ACCEPTANCE_R2_TEST_PREFIX'

Invoke-Control 'configuration' {
    if ($publicOrigin.Scheme -ne 'https' -or -not $publicOrigin.IsDefaultPort) {
        throw 'ACCEPTANCE_PUBLIC_ORIGIN must be an HTTPS origin without a path or custom port.'
    }
    if ($publicOrigin.AbsolutePath -ne '/') { throw 'ACCEPTANCE_PUBLIC_ORIGIN must not contain a path.' }
    foreach ($entry in $expectedImages.GetEnumerator()) {
        if ($entry.Value -notmatch '^ghcr\.io/[a-z0-9_.-]+/smartupcms/[a-z0-9_.-]+@sha256:[a-f0-9]{64}$') {
            throw "Expected $($entry.Key) image is not a complete GHCR digest reference."
        }
    }
    if ($bucket -eq $recoveryBucket) { throw 'Application and recovery R2 buckets must be different.' }
    if ($r2TestPrefix -notmatch '^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$') { throw 'R2 test prefix is unsafe.' }
    'Required non-secret target values are explicit.'
}

$publicResponse = $null
Invoke-Control 'public-health' {
    $healthUri = [uri]::new($publicOrigin, '/healthz')
    $response = Invoke-WebRequest -Uri $healthUri -Method Get -TimeoutSec 20 -SkipHttpErrorCheck
    if ([int]$response.StatusCode -lt 200 -or [int]$response.StatusCode -ge 300) {
        throw "Public health returned HTTP $([int]$response.StatusCode)."
    }
    $script:publicResponse = Invoke-WebRequest -Uri $publicOrigin -Method Get -TimeoutSec 20 -SkipHttpErrorCheck
    if ([int]$script:publicResponse.StatusCode -lt 200 -or [int]$script:publicResponse.StatusCode -ge 400) {
        throw "Public origin returned HTTP $([int]$script:publicResponse.StatusCode)."
    }
    'Public health and application origin are reachable over validated HTTPS.'
}

Invoke-Control 'security-headers' {
    if ($null -eq $script:publicResponse) { throw 'Public origin response is unavailable.' }
    foreach ($header in @(
        'strict-transport-security', 'content-security-policy', 'x-content-type-options',
        'x-frame-options', 'referrer-policy', 'permissions-policy', 'cf-ray'
    )) {
        if ([string]::IsNullOrWhiteSpace((Get-HeaderValue $script:publicResponse $header))) {
            throw "Required response header '$header' is missing."
        }
    }
    'TLS, browser hardening, and Cloudflare edge headers are present.'
}

Invoke-Control 'direct-origin-denied' {
    $curl = Get-Command curl -ErrorAction SilentlyContinue
    if ($null -eq $curl) { $curl = Get-Command curl.exe -ErrorAction SilentlyContinue }
    if ($null -eq $curl) { throw 'curl is required for a DNS-bypass origin check.' }
    $nullDevice = if ($IsWindows) { 'NUL' } else { '/dev/null' }
    $resolve = "$($publicOrigin.DnsSafeHost):443:$originIp"
    $statusCode = & $curl.Source --silent --show-error --insecure --connect-timeout 5 --max-time 10 --noproxy '*' --output $nullDevice --write-out '%{http_code}' --resolve $resolve ([uri]::new($publicOrigin, '/healthz').AbsoluteUri) 2>$null
    if ($LASTEXITCODE -eq 0 -and "$statusCode" -match '^2\d\d$') {
        throw 'The origin served the application directly when Cloudflare DNS was bypassed.'
    }
    'Direct origin request did not receive a successful application response.'
}

Invoke-Control 'private-service-ports' {
    $ports = @(Get-RequiredValue $config 'ACCEPTANCE_ORIGIN_DENIED_PORTS' -split ',' | ForEach-Object { [int]$_.Trim() })
    foreach ($port in $ports) {
        $client = [System.Net.Sockets.TcpClient]::new()
        $reachable = $false
        try {
            $connected = $client.ConnectAsync($originIp, $port).Wait(2500)
            $reachable = $connected -and $client.Connected
        }
        catch {
            $reachable = $false
        }
        finally {
            $client.Dispose()
        }
        if ($reachable) { throw "Origin port $port is reachable from the external runner." }
    }
    "Denied origin ports are unreachable: $($ports -join ',')."
}

$cloudflareToken = $null
try { $cloudflareToken = Get-RequiredSecret 'CLOUDFLARE_API_TOKEN' } catch { Add-Check 'cloudflare-credentials' 'FAIL' $_.Exception.Message }
$cfHeaders = if ($cloudflareToken) { @{ Authorization = "Bearer $cloudflareToken" } } else { @{} }
function Invoke-CloudflareGet([string]$Path) {
    if (-not $cloudflareToken) { throw 'Cloudflare API token is unavailable.' }
    $response = Invoke-RestMethod -Method Get -Uri "https://api.cloudflare.com/client/v4$Path" -Headers $cfHeaders -TimeoutSec 30
    if (-not $response.success) { throw 'Cloudflare API rejected an acceptance query.' }
    return $response.result
}

Invoke-Control 'cloudflare-dns-proxy' {
    $records = Invoke-CloudflareGet "/zones/$zoneId/dns_records?name=$([uri]::EscapeDataString($publicOrigin.DnsSafeHost))"
    $matching = @($records | Where-Object { $_.name -eq $publicOrigin.DnsSafeHost -and $_.proxied -eq $true })
    if ($matching.Count -lt 1) { throw 'No proxied Cloudflare DNS record matches the public hostname.' }
    'The public hostname resolves through a proxied Cloudflare DNS record.'
}

Invoke-Control 'cloudflare-tls-settings' {
    $alwaysHttps = Invoke-CloudflareGet "/zones/$zoneId/settings/always_use_https"
    $ssl = Invoke-CloudflareGet "/zones/$zoneId/settings/ssl"
    $minimumTls = Invoke-CloudflareGet "/zones/$zoneId/settings/min_tls_version"
    if ($alwaysHttps.value -ne 'on') { throw 'Cloudflare Always Use HTTPS is not enabled.' }
    if ($ssl.value -ne 'strict') { throw 'Cloudflare SSL mode is not Full (strict).' }
    if (@('1.2', '1.3') -notcontains "$($minimumTls.value)") { throw 'Cloudflare minimum TLS version is below 1.2.' }
    'Cloudflare enforces HTTPS, Full (strict), and TLS 1.2 or newer.'
}

Invoke-Control 'cloudflare-managed-waf' {
    $ruleset = Invoke-CloudflareGet "/zones/$zoneId/rulesets/phases/http_request_firewall_managed/entrypoint"
    $enabled = @($ruleset.rules | Where-Object { $_.enabled -ne $false })
    if ($enabled.Count -lt 1) { throw 'Managed WAF entrypoint has no enabled rules.' }
    'Cloudflare managed WAF entrypoint contains enabled rules.'
}

Invoke-Control 'cloudflare-rate-limit' {
    $ruleset = Invoke-CloudflareGet "/zones/$zoneId/rulesets/phases/http_ratelimit/entrypoint"
    $enabled = @($ruleset.rules | Where-Object { $_.enabled -ne $false })
    if ($enabled.Count -lt 1) { throw 'Cloudflare rate-limit entrypoint has no enabled rules.' }
    'Cloudflare rate-limit entrypoint contains enabled rules.'
}

$encodedBucket = [uri]::EscapeDataString($bucket)
Invoke-Control 'r2-private-access' {
    $null = Invoke-CloudflareGet "/accounts/$accountId/r2/buckets/$encodedBucket"
    $managed = Invoke-CloudflareGet "/accounts/$accountId/r2/buckets/$encodedBucket/domains/managed"
    if ($managed.enabled -eq $true) { throw 'The R2 r2.dev public domain is enabled.' }
    $custom = Invoke-CloudflareGet "/accounts/$accountId/r2/buckets/$encodedBucket/domains/custom"
    if (@($custom.domains | Where-Object { $_.enabled -eq $true }).Count -gt 0) {
        throw 'The application R2 bucket has an enabled public custom domain.'
    }
    'Application R2 bucket exists and both public-domain paths are disabled.'
}

Invoke-Control 'r2-cors' {
    $cors = Invoke-CloudflareGet "/accounts/$accountId/r2/buckets/$encodedBucket/cors"
    if (@($cors.rules).Count -ne 0) { throw 'Application R2 bucket has browser CORS rules; direct browser access is not approved.' }
    'Application R2 bucket has no browser CORS policy.'
}

Invoke-Control 'r2-lifecycle' {
    $lifecycle = Invoke-CloudflareGet "/accounts/$accountId/r2/buckets/$encodedBucket/lifecycle"
    $matching = @($lifecycle.rules | Where-Object { $_.id -eq $lifecycleRuleId -and $_.enabled -eq $true })
    if ($matching.Count -ne 1) { throw 'The approved R2 lifecycle rule is missing or disabled.' }
    'Approved R2 lifecycle rule is enabled.'
}

Add-Check 'r2-versioning' 'NOT_APPLICABLE' 'Cloudflare R2 does not implement S3 PutBucketVersioning; isolated encrypted object recovery is required instead.'
Invoke-Control 'r2-recovery-boundary' {
    $null = Invoke-CloudflareGet "/accounts/$accountId/r2/buckets/$([uri]::EscapeDataString($recoveryBucket))"
    'A separate recovery bucket exists for object recovery evidence.'
}

Invoke-Control 'r2-private-round-trip' {
    $previousAccessKey = $env:AWS_ACCESS_KEY_ID
    $previousSecretKey = $env:AWS_SECRET_ACCESS_KEY
    $previousRegion = $env:AWS_DEFAULT_REGION
    $temporaryDirectory = $null
    $aws = $null
    $r2CleanupKey = $null
    try {
        $env:AWS_ACCESS_KEY_ID = Get-RequiredSecret 'AWS_ACCESS_KEY_ID'
        $env:AWS_SECRET_ACCESS_KEY = Get-RequiredSecret 'AWS_SECRET_ACCESS_KEY'
        $env:AWS_DEFAULT_REGION = 'auto'
        $aws = Get-Command aws -ErrorAction SilentlyContinue
        if ($null -eq $aws) { throw 'AWS CLI is required for the R2 private round-trip.' }
        $temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('smartupcms-r2-' + [guid]::NewGuid())
        New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
        $source = Join-Path $temporaryDirectory 'source.bin'
        $download = Join-Path $temporaryDirectory 'download.bin'
        $bytes = [byte[]]::new(4096)
        [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
        [System.IO.File]::WriteAllBytes($source, $bytes)
        $key = "$r2TestPrefix/$([guid]::NewGuid().ToString('N')).bin"
        & $aws.Source --endpoint-url $r2Endpoint s3api put-object --bucket $bucket --key $key --body $source --no-cli-pager | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'R2 put-object failed.' }
        $r2CleanupKey = $key
        & $aws.Source --endpoint-url $r2Endpoint s3api head-object --bucket $bucket --key $key --no-cli-pager | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'R2 head-object failed.' }
        & $aws.Source --endpoint-url $r2Endpoint s3api get-object --bucket $bucket --key $key $download --no-cli-pager | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'R2 get-object failed.' }
        if ((Get-FileHash $source -Algorithm SHA256).Hash -ne (Get-FileHash $download -Algorithm SHA256).Hash) {
            throw 'R2 downloaded bytes do not match the uploaded checksum.'
        }
        & $aws.Source --endpoint-url $r2Endpoint s3api delete-object --bucket $bucket --key $key --no-cli-pager | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'R2 delete-object failed.' }
        $r2CleanupKey = $null
        & $aws.Source --endpoint-url $r2Endpoint s3api head-object --bucket $bucket --key $key --no-cli-pager 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { throw 'R2 object still exists after delete-object.' }
    }
    finally {
        if ($r2CleanupKey -and $aws) {
            & $aws.Source --endpoint-url $r2Endpoint s3api delete-object --bucket $bucket --key $r2CleanupKey --no-cli-pager 2>$null | Out-Null
        }
        if ($temporaryDirectory) { Remove-Item -LiteralPath $temporaryDirectory -Recurse -Force -ErrorAction SilentlyContinue }
        $env:AWS_ACCESS_KEY_ID = $previousAccessKey
        $env:AWS_SECRET_ACCESS_KEY = $previousSecretKey
        $env:AWS_DEFAULT_REGION = $previousRegion
    }
    'Private R2 put/head/get/checksum/delete round-trip passed.'
}

foreach ($probe in @(
    @{ Id = 'scanner-health'; Key = 'ACCEPTANCE_SCANNER_HEALTH_URL'; Method = 'Get' },
    @{ Id = 'alert-health'; Key = 'ACCEPTANCE_ALERT_HEALTHCHECK_URL'; Method = 'Get' },
    @{ Id = 'alert-delivery-drill'; Key = 'ACCEPTANCE_ALERT_TEST_URL'; Method = 'Post' }
)) {
    Invoke-Control $probe.Id {
        $uri = [uri](Get-RequiredValue $config $probe.Key)
        if ($uri.Scheme -ne 'https') { throw "$($probe.Key) must use HTTPS." }
        $response = Invoke-WebRequest -Uri $uri -Method $probe.Method -TimeoutSec 20 -SkipHttpErrorCheck
        if ([int]$response.StatusCode -lt 200 -or [int]$response.StatusCode -ge 300) {
            throw "$($probe.Key) returned HTTP $([int]$response.StatusCode)."
        }
        "$($probe.Key) returned a successful response."
    }
}

$failed = @($checks | Where-Object { $_.status -eq 'FAIL' })
$evidence = [ordered]@{
    schemaVersion = 1
    status = if ($failed.Count -eq 0) { 'PASS' } else { 'UNVERIFIED' }
    generatedAt = $generatedAt.ToString('o')
    gitSha = $gitSha
    expectedImages = $expectedImages
    profileId = $profileId
    target = [ordered]@{
        environment = "$($config['ACCEPTANCE_TARGET_ENVIRONMENT'])"
        hostProfile = Get-RedactedIdentifier (Get-RequiredValue $config 'ACCEPTANCE_HOST_PROFILE')
        originHost = Get-RedactedIdentifier $publicOrigin.DnsSafeHost
        originIp = Get-RedactedIdentifier $originIp
        cloudflareAccount = Get-RedactedIdentifier $accountId
        cloudflareZone = Get-RedactedIdentifier "$zoneId/$zoneName"
        r2Bucket = Get-RedactedIdentifier $bucket
        r2RecoveryBucket = Get-RedactedIdentifier $recoveryBucket
    }
    checks = $checks
}

$evidenceRoot = if ([System.IO.Path]::IsPathRooted($EvidenceDirectory)) {
    [System.IO.Path]::GetFullPath($EvidenceDirectory)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $EvidenceDirectory))
}
New-Item -ItemType Directory -Path $evidenceRoot -Force | Out-Null
$evidencePath = Join-Path $evidenceRoot ("managed-preflight-{0}.json" -f $generatedAt.ToString('yyyyMMddTHHmmssZ'))
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $evidencePath -Encoding utf8NoBOM
Write-Host "Evidence: $evidencePath"

if ($failed.Count -gt 0) {
    Write-Error "Managed preflight remains UNVERIFIED: $($failed.Count) control(s) failed."
    exit 1
}

Write-Host 'Managed infrastructure preflight passed.' -ForegroundColor Green
