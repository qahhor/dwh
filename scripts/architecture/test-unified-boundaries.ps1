$ErrorActionPreference = 'Stop'

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$failures = [System.Collections.Generic.List[string]]::new()

Push-Location $repositoryRoot
try {
    [xml]$pom = Get-Content -LiteralPath 'pom.xml' -Raw
    $modules = @($pom.project.modules.module | ForEach-Object { $_.Trim() })

    if ($modules -notcontains 'apps/server') {
        $failures.Add('Required Maven module is missing: apps/server')
    }
    foreach ($forbiddenModule in @('apps/instance', 'apps/control-plane')) {
        if ($modules -contains $forbiddenModule) {
            $failures.Add("Retired Maven module remains: $forbiddenModule")
        }
    }

    $services = @(& docker compose --profile tools --profile backup config --services 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose config failed: $($services -join [Environment]::NewLine)"
    }

    $requiredServices = @('postgres', 'migrate', 'server', 'web', 'typesense', 'backup')
    $missingServices = @($requiredServices | Where-Object { $services -notcontains $_ })
    if ($missingServices.Count -gt 0) {
        $failures.Add("Required Compose services are missing: $($missingServices -join ', ')")
    }

    $forbiddenServices = @('db', 'app', 'db-cp', 'migrate-cp', 'control-plane', 'web-cp')
    $foundServices = @($forbiddenServices | Where-Object { $services -contains $_ })
    if ($foundServices.Count -gt 0) {
        $failures.Add("Control Plane services remain: $($foundServices -join ', ')")
    }

    foreach ($path in @('apps/instance', 'apps/web-instance', 'apps/control-plane', 'apps/web-cp')) {
        if (Test-Path -LiteralPath $path) {
            $failures.Add("Retired application directory remains: $path")
        }
    }

    foreach ($path in @('apps/server', 'apps/web')) {
        if (-not (Test-Path -LiteralPath $path -PathType Container)) {
            $failures.Add("Required application directory is missing: $path")
        }
    }

    if (Test-Path -LiteralPath 'deploy/compose/docker-compose.fleet.prod.yml') {
        $failures.Add('Retired fleet production Compose file remains')
    }

    $runtimeFiles = @(
        'docker-compose.yml',
        'deploy/compose/docker-compose.prod.yml',
        'Dockerfile',
        '.github/workflows/ci.yml'
    )
    foreach ($runtimeFile in $runtimeFiles) {
        $content = Get-Content -LiteralPath $runtimeFile -Raw
        foreach ($forbiddenText in @('apps/web-instance', 'apps/instance', 'smartupcms/instance')) {
            if ($content.Contains($forbiddenText)) {
                $failures.Add("Retired runtime identifier '$forbiddenText' remains in $runtimeFile")
            }
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
