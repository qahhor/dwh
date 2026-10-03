$ErrorActionPreference = 'Stop'

# Plan 10/10, item 6.3, acceptance "the build fails on an incompatible SPI change without a major version change"
# (ADR-0033, 4.1). The gate itself is japicmp in mvn verify of libs/platform-api; this check proves it bites: it makes a
# public method of the API package-private (a binary incompatible change) and expects verify to fail, then states a
# new major version for the same change and expects verify to pass. Both files are restored afterwards.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = Join-Path $repoRoot 'libs/platform-api/src/main/java/com/smartup24/cms/platform/api/PlatformVersion.java'
$modulePom = Join-Path $repoRoot 'libs/platform-api/pom.xml'
$maven = if (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' } elseif ($IsWindows -or $env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'mvnw.cmd' } else { Join-Path $repoRoot 'mvnw' }
$utf8 = New-Object System.Text.UTF8Encoding($false)

$originalSource = [System.IO.File]::ReadAllText($source)
$originalPom = [System.IO.File]::ReadAllText($modulePom)
$breaking = $originalSource.Replace('    public String feature() {', '    String feature() {')
if ($breaking -eq $originalSource) { throw 'The probe method PlatformVersion.feature() was not found.' }
$versionTag = [regex]::Match($originalPom, '</parent>[\s\S]*?<version>(\d+)\.(\d+)\.(\d+)</version>')
if (-not $versionTag.Success) { throw 'libs/platform-api/pom.xml states no version of its own.' }
$nextMajor = ([int]$versionTag.Groups[1].Value + 1).ToString() + '.0.0'
$current = "$($versionTag.Groups[1].Value).$($versionTag.Groups[2].Value).$($versionTag.Groups[3].Value)"

function Invoke-Verify {
    $log = Join-Path ([System.IO.Path]::GetTempPath()) ("platform-api-compat-" + [guid]::NewGuid() + '.log')
    # Windows PowerShell turns a native program's stderr into errors; the exit code decides here.
    $ErrorActionPreference = 'Continue'
    & $maven -B -q -f (Join-Path $repoRoot 'pom.xml') -pl libs/platform-api -DskipTests '-Djacoco.skip=true' `
        '-Dcheckstyle.skip=true' '-Dspotless.check.skip=true' verify *> $log
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $text = Get-Content -LiteralPath $log -Raw
    Remove-Item -LiteralPath $log -Force
    return @{ Code = $code; Text = $text }
}

$errors = [System.Collections.Generic.List[string]]::new()
try {
    [System.IO.File]::WriteAllText($source, $breaking, $utf8)
    $same = Invoke-Verify
    if ($same.Code -eq 0) {
        $errors.Add("An incompatible change at version $current passed verify: japicmp does not guard the API.")
    }
    elseif ($same.Text -notmatch 'japicmp') {
        $errors.Add("Verify failed for another reason than japicmp:`n$($same.Text)")
    }

    $bumped = $originalPom.Substring(0, $versionTag.Index) +
        $versionTag.Value.Replace("<version>$current</version>", "<version>$nextMajor</version>") +
        $originalPom.Substring($versionTag.Index + $versionTag.Length)
    [System.IO.File]::WriteAllText($modulePom, $bumped, $utf8)
    $major = Invoke-Verify
    if ($major.Code -ne 0) {
        $errors.Add("The same change at the major version $nextMajor failed verify:`n$($major.Text)")
    }
}
finally {
    [System.IO.File]::WriteAllText($source, $originalSource, $utf8)
    [System.IO.File]::WriteAllText($modulePom, $originalPom, $utf8)
}

if ($errors.Count -gt 0) {
    Write-Error ("Platform API compatibility gate:`n  " + ($errors -join "`n  "))
    exit 1
}
Write-Output "Platform API compatibility gate: an incompatible change fails at $current and passes at $nextMajor."
