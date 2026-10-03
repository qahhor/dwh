param(
    # Builds the jars itself; pass -SkipBuild when target/ holds the jars of this version already.
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'

# ADR-0033, 4.1: after a version of the platform's API is released (merged with platform-api.version raised and the
# SPI changelog written), its jars become the baseline japicmp compares the next changes with. The baseline lives in
# git because there is no release repository; this script is the only thing that changes it.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$pomPath = Join-Path $repoRoot 'pom.xml'
[xml]$pom = Get-Content -LiteralPath $pomPath -Raw
$version = $pom.project.properties.'platform-api.version'
if (-not $version) { throw 'pom.xml has no platform-api.version property.' }
$artifacts = @('platform-api', 'provider-spi')

if (-not $SkipBuild) {
    $maven = if (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' } elseif ($IsWindows -or $env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'mvnw.cmd' } else { Join-Path $repoRoot 'mvnw' }
    & $maven -B -q -f $pomPath -pl 'libs/platform-api,libs/provider-spi' -am -DskipTests '-Djapicmp.skip=true' package
    if ($LASTEXITCODE -ne 0) { throw "The API jars did not build (exit $LASTEXITCODE)." }
}

foreach ($artifact in $artifacts) {
    $built = Join-Path $repoRoot "libs/$artifact/target/$artifact-$version.jar"
    if (-not (Test-Path -LiteralPath $built)) { throw "No jar at ${built}: build the API first." }
    $folder = Join-Path $repoRoot "libs/$artifact/baseline"
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    Get-ChildItem -LiteralPath $folder -Filter "$artifact-*.jar" | Remove-Item -Force
    Copy-Item -LiteralPath $built -Destination (Join-Path $folder "$artifact-$version.jar")
    Write-Output "Baseline of ${artifact}: $version"
}

$text = Get-Content -LiteralPath $pomPath -Raw
$updated = [regex]::Replace($text, '<platform-api\.baseline\.version>[^<]*</platform-api\.baseline\.version>',
    "<platform-api.baseline.version>$version</platform-api.baseline.version>")
if ($updated -ne $text) {
    [System.IO.File]::WriteAllText($pomPath, $updated, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output "platform-api.baseline.version is now $version"
}
