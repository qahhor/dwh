$ErrorActionPreference = 'Stop'

# Plan 10/10, item 6.3, acceptance "the build fails on an incompatible SPI change without a major version change"
# (ADR-0033, 4.1). The gate itself is japicmp in mvn verify of libs/platform-api and libs/provider-spi; this check
# proves it bites in both: it makes a public method of the artifact package-private (a binary incompatible change)
# and expects verify to fail, then states a new major version for the same change and expects verify to pass. Every
# file is restored afterwards.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$maven = if (Get-Command mvn -ErrorAction SilentlyContinue) { 'mvn' } elseif ($IsWindows -or $env:OS -eq 'Windows_NT') { Join-Path $repoRoot 'mvnw.cmd' } else { Join-Path $repoRoot 'mvnw' }
$utf8 = New-Object System.Text.UTF8Encoding($false)

# Each artifact of the public API with a probe: a public method whose narrowing breaks binary compatibility.
$artifacts = @(
    @{
        Module = 'libs/platform-api'
        Source = 'libs/platform-api/src/main/java/com/smartup24/cms/platform/api/PlatformVersion.java'
        From = '    public String feature() {'
        To = '    String feature() {'
    },
    @{
        Module = 'libs/provider-spi'
        Source = 'libs/provider-spi/src/main/java/com/smartup24/cms/spi/common/ProviderHealth.java'
        From = '    public static ProviderHealth healthy(String providerName, long latencyMs) {'
        To = '    static ProviderHealth healthy(String providerName, long latencyMs) {'
    }
)

function Invoke-Verify([string]$module) {
    $log = Join-Path ([System.IO.Path]::GetTempPath()) ("platform-api-compat-" + [guid]::NewGuid() + '.log')
    # Windows PowerShell turns a native program's stderr into errors; the exit code decides here. The libraries an
    # artifact depends on are built with it (-am): verify does not install them.
    $ErrorActionPreference = 'Continue'
    & $maven -B -q -f (Join-Path $repoRoot 'pom.xml') -pl $module -am -DskipTests '-Djacoco.skip=true' `
        '-Dcheckstyle.skip=true' '-Dspotless.check.skip=true' verify *> $log
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    $text = Get-Content -LiteralPath $log -Raw
    Remove-Item -LiteralPath $log -Force
    return @{ Code = $code; Text = $text }
}

$errors = [System.Collections.Generic.List[string]]::new()
$passed = [System.Collections.Generic.List[string]]::new()
foreach ($artifact in $artifacts) {
    $source = Join-Path $repoRoot $artifact.Source
    $modulePom = Join-Path $repoRoot (Join-Path $artifact.Module 'pom.xml')
    $originalSource = [System.IO.File]::ReadAllText($source)
    $originalPom = [System.IO.File]::ReadAllText($modulePom)
    $breaking = $originalSource.Replace($artifact.From, $artifact.To)
    if ($breaking -eq $originalSource) { throw "The probe method of $($artifact.Module) was not found: $($artifact.From.Trim())" }
    $versionTag = [regex]::Match($originalPom, '</parent>[\s\S]*?<version>(\d+)\.(\d+)\.(\d+)</version>')
    if (-not $versionTag.Success) { throw "$($artifact.Module)/pom.xml states no version of its own." }
    $nextMajor = ([int]$versionTag.Groups[1].Value + 1).ToString() + '.0.0'
    $current = "$($versionTag.Groups[1].Value).$($versionTag.Groups[2].Value).$($versionTag.Groups[3].Value)"
    try {
        [System.IO.File]::WriteAllText($source, $breaking, $utf8)
        $same = Invoke-Verify $artifact.Module
        if ($same.Code -eq 0) {
            $errors.Add("$($artifact.Module): an incompatible change at version $current passed verify: japicmp does not guard it.")
        }
        elseif ($same.Text -notmatch 'japicmp') {
            $errors.Add("$($artifact.Module): verify failed for another reason than japicmp:`n$($same.Text)")
        }

        $bumped = $originalPom.Substring(0, $versionTag.Index) +
            $versionTag.Value.Replace("<version>$current</version>", "<version>$nextMajor</version>") +
            $originalPom.Substring($versionTag.Index + $versionTag.Length)
        [System.IO.File]::WriteAllText($modulePom, $bumped, $utf8)
        $major = Invoke-Verify $artifact.Module
        if ($major.Code -ne 0) {
            $errors.Add("$($artifact.Module): the same change at the major version $nextMajor failed verify:`n$($major.Text)")
        }
        else {
            $passed.Add("$($artifact.Module) fails at $current and passes at $nextMajor")
        }
    }
    finally {
        [System.IO.File]::WriteAllText($source, $originalSource, $utf8)
        [System.IO.File]::WriteAllText($modulePom, $originalPom, $utf8)
    }
}

if ($errors.Count -gt 0) {
    Write-Error ("Platform API compatibility gate:`n  " + ($errors -join "`n  "))
    exit 1
}
Write-Output ("Platform API compatibility gate: an incompatible change " + ($passed -join '; ') + '.')
