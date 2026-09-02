$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$failures = [System.Collections.Generic.List[string]]::new()

Push-Location $repositoryRoot
try {
    [xml]$pom = Get-Content -LiteralPath 'pom.xml' -Raw
    $modules = @($pom.project.modules.module | ForEach-Object { $_.Trim() })

    if ($modules -notcontains 'apps/instance') {
        $failures.Add('Required Maven module is missing: apps/instance')
    }
    if ($modules -contains 'apps/control-plane') {
        $failures.Add('Control Plane Maven module remains: apps/control-plane')
    }

    $services = @(& docker compose --profile tools config --services 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose config failed: $($services -join [Environment]::NewLine)"
    }

    $requiredServices = @('db', 'migrate', 'app', 'web', 'typesense')
    $missingServices = @($requiredServices | Where-Object { $services -notcontains $_ })
    if ($missingServices.Count -gt 0) {
        $failures.Add("Required Compose services are missing: $($missingServices -join ', ')")
    }

    $forbiddenServices = @('db-cp', 'migrate-cp', 'control-plane', 'web-cp')
    $foundServices = @($forbiddenServices | Where-Object { $services -contains $_ })
    if ($foundServices.Count -gt 0) {
        $failures.Add("Control Plane services remain: $($foundServices -join ', ')")
    }

    foreach ($path in @('apps/control-plane', 'apps/web-cp')) {
        if (Test-Path -LiteralPath $path) {
            $failures.Add("Control Plane application directory remains: $path")
        }
    }
}
finally {
    Pop-Location
}

if ($failures.Count -gt 0) {
    throw "Unified architecture boundary failed:`n - $($failures -join "`n - ")"
}

Write-Host 'Unified architecture boundary passed.'
