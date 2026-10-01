[CmdletBinding()]
param(
    # Keep the temporary copy for inspection instead of deleting it.
    [switch]$KeepWorkDir
)

$ErrorActionPreference = 'Stop'

# The module generator (scripts/dev/create-module.ps1) is what the module guide tells a newcomer to run, so what it
# writes must pass the same build as hand-written code. This check runs it in a temporary copy of the repository,
# compiles the server with the generated module, runs Spotless, Checkstyle and the architecture tests of phase 3
# (errors, paging, statuses, revisions, migrations, boundaries), applies the migrations and starts the application on
# an embedded PostgreSQL, and deletes the copy. Nightly in CI (.github/workflows/nightly.yml); run it after changing
# the generator.
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$tempRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [System.IO.Path]::GetTempPath() }
$work = Join-Path $tempRoot ("create-module-check-" + [guid]::NewGuid().ToString('N').Substring(0, 8))

$moduleCode = 'probe'
$javaRelative = 'apps/server/src/main/java/com/smartup24/cms/instance/ms/probe'
$tests = @(
    'ErrorTextsTest',
    'ErrorModelTest',
    'NoSwallowedErrorsTest',
    'ModuleBoundariesTest',
    'ModularArchitectureTest',
    'ChangesNameTheirRevisionTest',
    'ResponseStatusDeclaredTest',
    'CollectionsArePagedTest',
    'CommentLanguageTest',
    'MigrationLintTest',
    'MigrationFileRulesTest',
    'EntityActionPermissionContractTest',
    # Plan 10/10, item 5.0: form and list fields agree, and the right names are translated keys.
    'EntityFieldContractTest',
    'MdPermissionEntityNamesTest',
    'MdFormCatalogTest',
    # Embedded PostgreSQL, no Docker: the migrations apply, the declared rights are in the catalog, the role
    # grants stay as the tests of the system and instance roles expect, and the application context starts.
    'RbacSystemRolesIntegrationTest',
    'A1InstanceRolesTest'
)

function Assert-That([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "create-module check: $Message" }
}

try {
    # 1. A copy of the working tree: tracked and untracked files, without what .gitignore excludes.
    Write-Output "Copying the repository to $work"
    $files = & git -C $repoRoot ls-files --cached --others --exclude-standard
    Assert-That ($LASTEXITCODE -eq 0) 'git ls-files failed'
    foreach ($file in $files) {
        $source = Join-Path $repoRoot $file
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
        $target = Join-Path $work $file
        $directory = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
        Copy-Item -LiteralPath $source -Destination $target
    }

    # 2. The generator, with a title that needs escaping in SQL, JSON and Java.
    & (Join-Path $work 'scripts/dev/create-module.ps1') -ModuleName $moduleCode -ModuleTitle "Probe's `"items`"" `
        -TitleEn 'Probe items' -TitleUz 'Sinov yozuvlari' -Icon 'box' | Out-Host

    $refused = $false
    try {
        & (Join-Path $work 'scripts/dev/create-module.ps1') -ModuleName $moduleCode -ModuleTitle 'Again' | Out-Null
    } catch {
        $refused = $_.Exception.Message -like '*already exists*'
    }
    Assert-That $refused 'a second run over an existing module must be refused'

    # 3. What the phase 3 rules require of the generated code, beyond what the tests below see.
    $java = (Get-ChildItem -LiteralPath (Join-Path $work $javaRelative) -Recurse -Filter '*.java' |
        ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n"
    Assert-That (Test-Path -LiteralPath (Join-Path $work "$javaRelative/api/MsProbeView.java")) 'DTOs live in the api package'
    Assert-That ($java.Contains('Created.at(') -and $java.Contains('@ResponseStatus(HttpStatus.CREATED)')) 'create answers 201 with Location'
    Assert-That ($java.Contains('Revisions.required(ifMatch)') -and $java.Contains('implements Revisioned')) 'a change takes If-Match and the answer carries the ETag'
    Assert-That ($java.Contains('new JsonColumns(') -and -not ($java -match 'toJson\(|parseJson\(')) 'JSON columns go through JsonColumns'
    Assert-That ($java.Contains('"error.probe.not_found"')) 'not found is an ApiException with a catalog key'
    Assert-That (-not ($java -match '[Ѐ-ӿ]')) 'the generated Java has no Cyrillic outside the title'

    $migrations = @(Get-ChildItem -LiteralPath (Join-Path $work 'apps/server/src/main/resources/db/migration') -Filter 'V*__ms_probe_*.sql')
    Assert-That ($migrations.Count -eq 2) "two migrations (table and seed), found $($migrations.Count)"
    foreach ($migration in $migrations) {
        $comments = (Get-Content -LiteralPath $migration.FullName -Encoding UTF8 | Where-Object { $_ -match '^\s*--' }) -join "`n"
        Assert-That (-not ($comments -match '[Ѐ-ӿ]')) "$($migration.Name): comments are in English"
    }

    foreach ($language in @('ru', 'en', 'uz')) {
        $catalogPath = Join-Path $work "apps/server/src/main/resources/i18n/$language.json"
        $catalog = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($key in @('error.probe.not_found', 'nav.probe', 'probe.col.name', 'probe.status.active', 'probe.rights.view')) {
            Assert-That ($null -ne $catalog.$key) "$language.json has $key"
        }
    }

    # 4. The build: Spotless, compile with Error Prone, the architecture tests, Checkstyle.
    $maven = if ($IsWindows -or $env:OS -eq 'Windows_NT') { Join-Path $work 'mvnw.cmd' } else { Join-Path $work 'mvnw' }
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) { & chmod +x $maven }
    Push-Location $work
    # Windows PowerShell turns a native program's stderr (JVM warnings) into a terminating error under 'Stop'.
    $ErrorActionPreference = 'Continue'
    try {
        & $maven -B -ntp -pl apps/server -am spotless:check test checkstyle:check "-Dtest=$($tests -join ',')" `
            '-Dsurefire.failIfNoSpecifiedTests=false' | Out-Host
        $mavenExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    Assert-That ($mavenExit -eq 0) "the server with the generated module fails the build (exit $mavenExit)"

    Write-Output "Module generator check passed: the generated module builds, passes $($tests.Count) test classes and starts."
} finally {
    if ($KeepWorkDir) {
        Write-Output "Kept $work"
    } elseif (Test-Path -LiteralPath $work) {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}
