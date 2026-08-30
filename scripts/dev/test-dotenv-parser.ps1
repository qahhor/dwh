$ErrorActionPreference = "Stop"

$helper = Join-Path $PSScriptRoot "dotenv.ps1"
. $helper

$fixture = Join-Path ([System.IO.Path]::GetTempPath()) ("smartupcms-dotenv-{0}.env" -f [guid]::NewGuid())
try {
    @'
DOUBLE_QUOTED="value with spaces # retained" # comment
SINGLE_QUOTED='single value' # comment
UNQUOTED=value-with-#-hash
COMMENTED=value with spaces # removed comment
'@ | Set-Content -LiteralPath $fixture -Encoding utf8

    $cases = @{
        DOUBLE_QUOTED = "value with spaces # retained"
        SINGLE_QUOTED = "single value"
        UNQUOTED = "value-with-#-hash"
        COMMENTED = "value with spaces"
    }

    foreach ($case in $cases.GetEnumerator()) {
        $actual = Get-DotEnvValue -Path $fixture -Key $case.Key
        if ($actual -cne $case.Value) {
            throw "dotenv parse mismatch for $($case.Key)"
        }
    }

    if ($null -ne (Get-DotEnvValue -Path $fixture -Key "MISSING")) {
        throw "Missing dotenv key must return null"
    }

    Write-Host "PowerShell dotenv parser contract passed." -ForegroundColor Green
} finally {
    Remove-Item -LiteralPath $fixture -Force -ErrorAction SilentlyContinue
}
