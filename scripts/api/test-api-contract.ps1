param(
    # The branch the pull request merges into: its docs/api/openapi.json is the contract clients rely on today.
    [string]$BaseRef = 'origin/main',
    # A deliberate breaking change (the pull request carries the api-breaking label): report it, do not fail. A commit
    # trailer "Api-Breaking:" between the base and HEAD declares it too.
    [switch]$AllowBreaking
)

$ErrorActionPreference = 'Stop'

# Plan 10/10, item 3.3: the API description is generated from the controllers and committed as
# docs/api/openapi.json (OpenApiContractTest keeps it current). This check holds what the description promises:
#   1. it is a valid OpenAPI document (Spectral, errors fail);
#   2. the web's TypeScript types are generated from this very description;
#   3. it does not break the clients of the base branch (openapi-diff), unless the change is declared breaking.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$spec = 'docs/api/openapi.json'
$types = 'apps/web/src/app/core/api/openapi.d.ts'
$work = Join-Path $repoRoot 'target/api-contract'
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Invoke-Docker([string[]]$Arguments) {
    & docker @Arguments | Out-Host
    return $LASTEXITCODE
}

# The run's output shown and kept: the verdict of openapi-diff is read from it.
function Invoke-DockerCaptured([string[]]$Arguments) {
    # Windows PowerShell turns a native command's stderr into an error record; the exit code is the answer here.
    $ErrorActionPreference = 'Continue'
    $lines = @(& docker @Arguments 2>&1 | ForEach-Object { "$_" })
    $code = $LASTEXITCODE
    $lines | Out-Host
    return [pscustomobject]@{ ExitCode = $code; Output = ($lines -join "`n") }
}

# openapi-diff --fail-on-incompatible exits 1 and reports "API changes broke backward compatibility" for a break.
# Any other failure - docker not running, the image not pulled, a spec the tool cannot read - is not a verdict on
# the API and must not be allowed by the api-breaking label or trailer.
function Get-DiffVerdict([int]$ExitCode, [string]$Output) {
    if ($ExitCode -eq 0) { return 'compatible' }
    if ($ExitCode -eq 1 -and $Output -match 'broke backward compatibility') { return 'breaking' }
    return 'failed'
}

# Self-check of the verdict on the cases that matter.
if ((Get-DiffVerdict 0 'API changes are backward compatible') -ne 'compatible' -or
    (Get-DiffVerdict 1 'API changes broke backward compatibility') -ne 'breaking' -or
    (Get-DiffVerdict 125 'Unable to find image; pull access denied') -ne 'failed' -or
    (Get-DiffVerdict 1 'Exception in thread "main" java.lang.RuntimeException') -ne 'failed' -or
    (Get-DiffVerdict 2 'Unexpected exception') -ne 'failed') {
    throw 'test-api-contract.ps1: the self-check of the openapi-diff verdict failed.'
}

Write-Host "1/3 Spectral lint of $spec"
if ((Invoke-Docker @('run', '--rm', '-v', "${repoRoot}:/work", '-w', '/work', 'stoplight/spectral:6',
            'lint', $spec, '--fail-severity=error', '--display-only-failures')) -ne 0) {
    throw "Spectral found errors in ${spec}."
}

Write-Host "2/3 Web types are generated from $spec"
$generator = Join-Path $repoRoot 'tools/api-types'
if (-not (Test-Path -LiteralPath (Join-Path $generator 'node_modules/openapi-typescript'))) {
    & npm ci --prefix $generator --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw 'npm ci of tools/api-types failed.' }
}
$fresh = Join-Path $work 'openapi.d.ts'
& node (Join-Path $generator 'node_modules/openapi-typescript/bin/cli.js') (Join-Path $repoRoot $spec) -o $fresh
if ($LASTEXITCODE -ne 0) { throw 'openapi-typescript failed.' }
$committed = (Get-Content -LiteralPath (Join-Path $repoRoot $types) -Raw) -replace "`r`n", "`n"
if (((Get-Content -LiteralPath $fresh -Raw) -replace "`r`n", "`n") -cne $committed) {
    throw "$types is stale: run npm run api:types in apps/web and commit the result."
}

Write-Host "3/3 Breaking changes against $BaseRef"
$baseSpec = Join-Path $work 'base-openapi.json'
# Windows PowerShell turns a native command's stderr into an error record; a missing file is an answer here.
$ErrorActionPreference = 'Continue'
$baseText = & git -C $repoRoot show "${BaseRef}:$spec" 2>$null
$baseFound = $LASTEXITCODE -eq 0
$ErrorActionPreference = 'Stop'
if (-not $baseFound) {
    Write-Host "  $BaseRef has no $spec yet: nothing to compare."
    exit 0
}
[System.IO.File]::WriteAllText($baseSpec, ($baseText -join "`n"), [System.Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $repoRoot $spec) -Destination (Join-Path $work 'openapi.json') -Force
$run = Invoke-DockerCaptured @('run', '--rm', '-v', "${work}:/specs", 'openapitools/openapi-diff:2.1.2',
    '/specs/base-openapi.json', '/specs/openapi.json', '--fail-on-incompatible')
$verdict = Get-DiffVerdict $run.ExitCode $run.Output
if ($verdict -eq 'failed') {
    throw "openapi-diff did not give a verdict (exit $($run.ExitCode)): a failure of docker, the image or the tool."
}
# A commit of the change may declare the break with a trailer "Api-Breaking: <what and why>": it stays in the history
# (a push to main has no label to carry it), and CHANGELOG.md says the same to the clients.
$trailers = @(& git -C $repoRoot log --format='%(trailers:key=Api-Breaking,valueonly)' "$BaseRef..HEAD" |
        Where-Object { $_.Trim() })
if ($verdict -eq 'breaking') {
    if ($AllowBreaking) {
        Write-Warning 'The API breaks clients of the base branch; allowed by the api-breaking label.'
    } elseif ($trailers.Count -gt 0) {
        Write-Warning ("The API breaks clients of the base branch; declared by Api-Breaking: " + ($trailers -join '; '))
    } else {
        throw ('The API breaks clients of the base branch. Keep the old shape (deprecate, add an alias), or declare ' +
            'the break (pull request label api-breaking, or a commit trailer Api-Breaking:) and record it in ' +
            'CHANGELOG.md.')
    }
}
