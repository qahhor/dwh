param(
    # The web origin of a running stack (the web container, not the dev server).
    [string]$BaseUrl = $(if ($env:SECURITY_HEADERS_BASE_URL) { $env:SECURITY_HEADERS_BASE_URL } else { 'http://localhost:4200' })
)

# Plan 10/10, item 7.4 (ADR-0034): the security headers of the web origin, checked on a running container.
# Requests: the SPA document, a real hashed bundle, /healthz, a public API call, an API error and an API
# path ending in .js (it must reach the server, not the asset location). The header rules follow the
# Mozilla HTTP Observatory tests that can run offline (CSP, HSTS, nosniff, framing, referrer, CORS);
# the online Observatory grade (A+) is a release step, docs/ops/production-launch-checklist.md.
# scripts/security/test-security-headers.sh is the same check for hosts without PowerShell.

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')
$curl = if ($env:OS -eq 'Windows_NT') { 'curl.exe' } else { 'curl' }
$failures = New-Object System.Collections.Generic.List[string]
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('smc-headers-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null

function Get-Response([string]$Path) {
    $headerFile = Join-Path $tmp 'headers.txt'
    $bodyFile = Join-Path $tmp 'body.txt'
    $code = & $curl -sS -o $bodyFile -D $headerFile -w '%{http_code}' --max-time 30 "$BaseUrl$Path"
    if ($LASTEXITCODE -ne 0) { throw "Request failed: $BaseUrl$Path" }
    $headers = @()
    foreach ($line in (Get-Content -LiteralPath $headerFile)) {
        $index = $line.IndexOf(':')
        if ($index -gt 0) {
            $headers += [pscustomobject]@{ Name = $line.Substring(0, $index).Trim().ToLowerInvariant(); Value = $line.Substring($index + 1).Trim() }
        }
    }
    [pscustomobject]@{ Path = $Path; Status = [int]$code; Headers = $headers; Body = (Get-Content -LiteralPath $bodyFile -Raw) }
}

function Fail([object]$Response, [string]$Message) {
    $failures.Add("$($Response.Path): $Message")
}

function Get-Single([object]$Response, [string]$Name) {
    $values = @($Response.Headers | Where-Object { $_.Name -eq $Name } | ForEach-Object { $_.Value })
    if ($values.Count -eq 0) { Fail $Response "missing header $Name"; return $null }
    if ($values.Count -gt 1) { Fail $Response "header $Name is sent $($values.Count) times" }
    return $values[0]
}

function Get-Directives([string]$Policy) {
    $directives = @{}
    foreach ($part in ($Policy -split ';')) {
        $tokens = @($part.Trim() -split '\s+' | Where-Object { $_ })
        if ($tokens.Count -gt 0) { $directives[$tokens[0].ToLowerInvariant()] = @($tokens | Select-Object -Skip 1) }
    }
    return $directives
}

function Test-CommonHeaders([object]$Response) {
    $hsts = Get-Single $Response 'strict-transport-security'
    if ($hsts) {
        if ($hsts -notmatch 'max-age=(\d+)' -or [long]$Matches[1] -lt 31536000) { Fail $Response "HSTS max-age below one year: $hsts" }
        if ($hsts -notmatch '(?i)includeSubDomains') { Fail $Response "HSTS without includeSubDomains: $hsts" }
    }
    $nosniff = Get-Single $Response 'x-content-type-options'
    if ($nosniff -and $nosniff -ne 'nosniff') { Fail $Response "X-Content-Type-Options is '$nosniff'" }
    $frame = Get-Single $Response 'x-frame-options'
    if ($frame -and $frame -notin @('DENY', 'SAMEORIGIN')) { Fail $Response "X-Frame-Options is '$frame'" }
    $referrer = Get-Single $Response 'referrer-policy'
    if ($referrer -and $referrer -notin @('no-referrer', 'same-origin', 'strict-origin', 'strict-origin-when-cross-origin')) {
        Fail $Response "Referrer-Policy leaks the address: '$referrer'"
    }
    $permissions = Get-Single $Response 'permissions-policy'
    if ($permissions -and $permissions -notmatch 'camera=\(\)') { Fail $Response "Permissions-Policy does not deny the camera: $permissions" }
    foreach ($server in @($Response.Headers | Where-Object { $_.Name -eq 'server' })) {
        if ($server.Value -match '\d') { Fail $Response "Server header reveals a version: $($server.Value)" }
    }
    if (@($Response.Headers | Where-Object { $_.Name -eq 'x-powered-by' }).Count -gt 0) { Fail $Response 'X-Powered-By is sent' }
    if (@($Response.Headers | Where-Object { $_.Name -eq 'access-control-allow-origin' -and $_.Value -eq '*' }).Count -gt 0) {
        Fail $Response 'CORS allows every origin'
    }
    $policy = Get-Single $Response 'content-security-policy'
    if ($policy) {
        $directives = Get-Directives $policy
        $frameAncestors = $directives['frame-ancestors']
        if (-not $frameAncestors -or ($frameAncestors -join ' ') -notin @("'none'", "'self'")) {
            Fail $Response "CSP frame-ancestors is not 'none'/'self': $policy"
        }
        if (-not $directives.ContainsKey('default-src')) { Fail $Response "CSP without default-src: $policy" }
    }
    return $policy
}

function Test-DocumentPolicy([object]$Response, [string]$Policy) {
    if (-not $Policy) { return }
    $directives = Get-Directives $Policy
    $wide = @('*', 'http:', 'https:', 'data:', 'blob:', 'ws:', 'wss:')
    $scripts = if ($directives.ContainsKey('script-src')) { $directives['script-src'] } else { $directives['default-src'] }
    foreach ($source in @($scripts)) {
        if ($source -in @("'unsafe-inline'", "'unsafe-eval'") -or $source -in $wide) { Fail $Response "CSP script-src allows $source" }
    }
    foreach ($source in @($directives['default-src'])) {
        if ($source -in $wide) { Fail $Response "CSP default-src allows $source" }
    }
    if (($directives['object-src'] -join ' ') -ne "'none'") { Fail $Response "CSP object-src is not 'none'" }
    foreach ($required in @('base-uri', 'form-action', 'frame-src')) {
        if (-not $directives.ContainsKey($required)) { Fail $Response "CSP without $required" }
    }
    foreach ($source in @($directives['frame-src'])) {
        if ($source -in $wide) { Fail $Response "CSP frame-src allows every $source source" }
    }
    if ($Policy -match 'fonts\.googleapis\.com|fonts\.gstatic\.com') { Fail $Response 'CSP allows third-party font hosts; fonts are local' }
    foreach ($directive in $directives.Keys) {
        foreach ($source in @($directives[$directive])) {
            if ($source -like 'http://*') { Fail $Response "CSP $directive loads over plain http: $source" }
        }
    }
}

try {
    $document = Get-Response '/'
    if ($document.Status -ne 200) { Fail $document "status $($document.Status)" }
    Test-DocumentPolicy $document (Test-CommonHeaders $document)

    if ($document.Body -notmatch 'src="(main-[A-Za-z0-9]+\.js)"') { throw 'The SPA document names no main-*.js bundle.' }
    $bundle = Get-Response ('/' + $Matches[1])
    if ($bundle.Status -ne 200) { Fail $bundle "status $($bundle.Status)" }
    $type = Get-Single $bundle 'content-type'
    if ($type -and $type -notmatch 'javascript') { Fail $bundle "served as $type" }
    Test-DocumentPolicy $bundle (Test-CommonHeaders $bundle)

    $health = Get-Response '/healthz'
    if ($health.Status -ne 200) { Fail $health "status $($health.Status)" }
    Test-DocumentPolicy $health (Test-CommonHeaders $health)

    foreach ($path in @('/api/v1/i18n/languages', '/api/v1/auth/me', '/api/v1/edge-probe.js')) {
        $api = Get-Response $path
        [void](Test-CommonHeaders $api)
        $apiType = Get-Single $api 'content-type'
        if ($path -eq '/api/v1/i18n/languages' -and $api.Status -ne 200) { Fail $api "status $($api.Status)" }
        if ($path -ne '/api/v1/i18n/languages') {
            # Unauthenticated: the server answers with its problem document; nginx's own 404 page means the
            # request never left the web container.
            if ($api.Status -ne 401) { Fail $api "status $($api.Status), expected the server's 401" }
            if ($apiType -notmatch 'application/problem\+json') { Fail $api "answered by nginx, not the server ($apiType)" }
        }
    }
}
finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

if ($failures.Count -gt 0) {
    $failures | ForEach-Object { Write-Host "FAIL $_" -ForegroundColor Red }
    throw "Security headers of $BaseUrl failed $($failures.Count) check(s)."
}
Write-Host "Security headers of $BaseUrl passed." -ForegroundColor Green
