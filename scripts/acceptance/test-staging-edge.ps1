$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$temporary = Join-Path $repoRoot ('.local/staging-edge-test-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path (Join-Path $temporary 'tls') -Force
$prometheus = 'prom/prometheus:v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e'
$openssl = Get-Command openssl -ErrorAction SilentlyContinue
$opensslPath = if ($openssl) { $openssl.Source } elseif ($IsWindows -and
    (Test-Path 'C:/Program Files/Git/usr/bin/openssl.exe')) { 'C:/Program Files/Git/usr/bin/openssl.exe' } else { $null }

function Write-Fixture([string]$Name, [string]$Value) {
    [IO.File]::WriteAllText((Join-Path $temporary $Name), $Value.Replace("`r`n", "`n"), [Text.UTF8Encoding]::new($false))
}
function Check-Exit([string]$Name) {
    if ($LASTEXITCODE -ne 0) { throw "$Name failed; inspect the private test log." }
}

try {
    if (-not $opensslPath) { throw 'OpenSSL is required to generate disposable TLS fixtures.' }
    & $opensslPath req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=staging.example.invalid `
        -keyout (Join-Path $temporary 'tls/origin-key.pem') -out (Join-Path $temporary 'tls/origin.pem') `
        *> (Join-Path $temporary 'openssl.log')
    Check-Exit 'Disposable certificate generation'
    foreach ($name in @('zone-aop-ca.pem', 'ops.pem', 'collector-ca.pem', 'ca.pem', 'client.pem')) {
        Copy-Item -LiteralPath (Join-Path $temporary 'tls/origin.pem') -Destination (Join-Path $temporary "tls/$name")
    }
    foreach ($name in @('ops-key.pem', 'client-key.pem')) {
        Copy-Item -LiteralPath (Join-Path $temporary 'tls/origin-key.pem') -Destination (Join-Path $temporary "tls/$name")
    }
    Write-Fixture 'cloudflare-realip.conf' 'set_real_ip_from 127.0.0.1;'
    foreach ($name in @('origin', 'ops')) {
        $template = [IO.File]::ReadAllText((Join-Path $repoRoot "deploy/staging/$name.nginx.conf.example"))
        $template = $template.Replace('CHANGE_ME_STAGING_HOST', 'staging.example.invalid').
            Replace('CHANGE_ME_PRIVATE_OPS_HOST', 'ops.example.invalid').
            Replace('CHANGE_ME_OPS_PRIVATE_IP', '127.0.0.1').Replace('CHANGE_ME_APP_PRIVATE_IP', '127.0.0.2')
        Write-Fixture "$name.conf" $template
        & docker run --rm --network none -v "${temporary}:/etc/smartupcms:ro" `
            -v "${temporary}/${name}.conf:/etc/nginx/conf.d/default.conf:ro" `
            --entrypoint nginx nginx:1.28-alpine -t *> (Join-Path $temporary "$name-nginx.log")
        Check-Exit "$name NGINX template"
    }
    Copy-Item -LiteralPath (Join-Path $repoRoot 'deploy/observability/prometheus/rules') -Destination $temporary -Recurse
    foreach ($name in @('collector', 'ops')) {
        $template = [IO.File]::ReadAllText((Join-Path $repoRoot "deploy/staging/prometheus-$name.yml.example"))
        $template = $template.Replace('CHANGE_ME_PRIVATE_OPS_HOST', 'ops.example.invalid').
            Replace('CHANGE_ME_PRIVATE_ALERTMANAGER_HOST', 'alertmanager.example.invalid')
        Write-Fixture "$name.yml" $template
        & docker run --rm --network none --user 0:0 -v "${temporary}:/etc/prometheus:ro" `
            --entrypoint promtool $prometheus check config "/etc/prometheus/$name.yml" `
            *> (Join-Path $temporary "$name-promtool.log")
        Check-Exit "$name Prometheus template"
    }
    Write-Host 'Staging edge passed: two TLS NGINX templates and both Prometheus configs; no target contacted.'
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot '.local')) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
