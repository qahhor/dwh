param(
    # The branch the pull request merges into: its docs/api/openapi.json is the contract clients rely on today.
    [string]$BaseRef = 'origin/main',
    # A deliberate breaking change (the pull request carries the api-breaking label): report it, do not fail.
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
$diff = Invoke-Docker @('run', '--rm', '-v', "${work}:/specs", 'openapitools/openapi-diff:2.1.2',
    '/specs/base-openapi.json', '/specs/openapi.json', '--fail-on-incompatible')
if ($diff -ne 0) {
    if ($AllowBreaking) {
        Write-Warning 'The API breaks clients of the base branch; allowed by the api-breaking label.'
    } else {
        throw ('The API breaks clients of the base branch. Keep the old shape (deprecate, add an alias), or label ' +
            'the pull request api-breaking and record the change in CHANGELOG.md.')
    }
}
