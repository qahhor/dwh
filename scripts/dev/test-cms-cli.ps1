[CmdletBinding()]
param(
    # Keep the temporary copy of the repository for inspection instead of deleting it.
    [switch]$KeepWorkDir,
    # Generate and check the files only, without the Maven build (a minute instead of a quarter of an hour).
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

# The developer CLI (tools/cms-cli, plan 10/10, item 6.1) is what the module guide tells a newcomer to run, so what it
# writes must pass the same build as hand-written code. This check runs the CLI's own tests (node:test), then the
# cross-platform smoke tools/cms-cli/scripts/smoke.mjs: in a temporary copy of the repository it creates a module, an
# entity with hooks and fields, checks re-runs and hand edits, lets cms migration diff write a migration, and builds
# the server with Spotless, Error Prone, Checkstyle, the architecture and migration tests, the entity contract kit
# and the schema comparison. Nightly in CI on Linux and Windows (.github/workflows/nightly.yml); run it after
# changing the CLI. Needs Node.js 22+ and JDK 25 (cms doctor tells what is missing).
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$cli = Join-Path $repoRoot 'tools/cms-cli'

Push-Location $cli
try {
    & node --test 'test/**/*.test.mjs'
    if ($LASTEXITCODE -ne 0) { throw "cms-cli unit tests failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

$smokeArgs = @((Join-Path $cli 'scripts/smoke.mjs'))
if ($KeepWorkDir) { $smokeArgs += '--keep' }
if ($SkipBuild) { $smokeArgs += '--skip-build' }
# Windows PowerShell turns a native program's stderr (JVM warnings) into a terminating error under 'Stop'.
$ErrorActionPreference = 'Continue'
& node @smokeArgs
$smokeExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($smokeExit -ne 0) { throw "cms-cli smoke failed (exit $smokeExit)" }
Write-Output 'cms-cli check passed.'
