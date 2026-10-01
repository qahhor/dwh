$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$errors = [System.Collections.Generic.List[string]]::new()

# Removed on purpose: dated audit reports, agent plans, design scratch, a machine-bound start script.
$obsoletePaths = @(
    'REPORT.md',
    'STATS_MAP.md',
    'audit',
    'deploy/spike',
    'docs/audit',
    'docs/superpowers',
    '.superdesign',
    'local-up.cmd'
)

foreach ($relativePath in $obsoletePaths) {
    if (Test-Path -LiteralPath (Join-Path $repoRoot $relativePath)) {
        $errors.Add("Obsolete repository artifact remains: $relativePath")
    }
}

Push-Location $repoRoot
try {
    $tracked = @(git ls-files)
    if ($LASTEXITCODE -ne 0) {
        throw 'git ls-files failed'
    }
}
finally {
    Pop-Location
}

foreach ($relativePath in $tracked) {
    # The knowledge graph is built locally (graphify update .) and never committed.
    if ($relativePath -match '^graphify-out/' -or
        $relativePath -match '^\.playwright-cli/') {
        $errors.Add("Generated artifact is tracked: $relativePath")
    }
}

# Plan 10/10, item 1.8: a check that no workflow runs is a check that silently rots. Every scripts/**/test-* script
# is called by a workflow, directly or through another script a workflow calls.
$workflowText = (Get-ChildItem -LiteralPath (Join-Path $repoRoot '.github/workflows') -Filter '*.yml' |
    ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
$checkScripts = @($tracked | Where-Object { $_ -match '^scripts/.+/test-[^/]+\.(ps1|sh)$' })
$reached = [System.Collections.Generic.HashSet[string]]::new()
$frontier = [System.Collections.Generic.List[string]]::new()
foreach ($script in $checkScripts) {
    if ($workflowText.Contains($script) -or $workflowText.Contains((Split-Path $script -Leaf))) {
        if ($reached.Add($script)) { $frontier.Add($script) }
    }
}
while ($frontier.Count -gt 0) {
    $caller = $frontier[0]
    $frontier.RemoveAt(0)
    $callerText = Get-Content -LiteralPath (Join-Path $repoRoot $caller) -Raw
    foreach ($script in $checkScripts) {
        if (-not $reached.Contains($script) -and $callerText.Contains((Split-Path $script -Leaf))) {
            [void]$reached.Add($script)
            $frontier.Add($script)
        }
    }
}
foreach ($script in $checkScripts) {
    if (-not $reached.Contains($script)) {
        $errors.Add("Check script is not run by any workflow: $script (call it from .github/workflows, e.g. nightly.yml)")
    }
}

# Plan 10/10, item 3.14: a comment points a newcomer to an ADR or a requirement (FR/NFR), never to an item of a
# working brief ("prompt 02 item 18", "(02, item 18)", "extra 12", "extra No 3") that the reader has never seen. The
# words are escaped so this script does not match itself (and stays ASCII: Windows PowerShell 5.1 reads a file without
# a BOM in the ANSI code page).
$briefReference = [regex]::new(
    '\u043f\u0440\u043e\u043c\u043f\u0442|\u0434\u043e\u043f\.\s?\u2116?\s?\d|\b\d{2} \u043f\.\s?\d|\(\d{2},\s?\u043f\.\s?\d',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
# The pattern itself is checked: the forms above match, a plan item or a date does not.
foreach ($sample in @('\u043f\u0440\u043e\u043c\u043f\u0442 02 \u043f.18', '(02, \u043f.18)', '(02, \u043f. 18)', '\u0434\u043e\u043f.12', '\u0434\u043e\u043f. 12', '\u0434\u043e\u043f.\u21163', '\u0434\u043e\u043f. \u2116 3', 'see 02 \u043f. 18')) {
    if (-not $briefReference.IsMatch([regex]::Unescape($sample))) {
        $errors.Add("Brief-reference pattern misses a known form: $sample")
    }
}
foreach ($sample in @('\u043f\u043b\u0430\u043d 10/10, \u043f. 3.9', '(2026-09-26, \u043f. 53)', '\u0434\u043e\u043f. \u043f\u043e\u043b\u044f', 'ADR-0025 \u00a74')) {
    if ($briefReference.IsMatch([regex]::Unescape($sample))) {
        $errors.Add("Brief-reference pattern matches a legitimate reference: $sample")
    }
}
# Released migrations that keep their old references: they are frozen by checksum (MigrationManifestTest) and cannot
# be edited. The list is closed; any other file, a new migration included, is checked.
$frozenBriefReferences = [System.Collections.Generic.HashSet[string]]::new([string[]]@(
    'apps/server/src/main/resources/db/dwh/V001__dwh_schemas.sql',
    'apps/server/src/main/resources/db/dwh/V002__raw_source_file_uuid.sql',
    'apps/server/src/main/resources/db/migration/V100__fnd_core.sql',
    'apps/server/src/main/resources/db/migration/V102__fnd_versioning.sql',
    'apps/server/src/main/resources/db/migration/V103__fnd_units.sql',
    'apps/server/src/main/resources/db/migration/V104__fnd_loads.sql',
    'apps/server/src/main/resources/db/migration/V109__fnd_draft_unique.sql'
))
foreach ($relativePath in $frozenBriefReferences) {
    if ($tracked -notcontains $relativePath) {
        $errors.Add("Frozen brief-reference exemption names a file that is not tracked: $relativePath")
    }
}
# Plan 10/10, item 4.7: "dwh" names the warehouse (the second database, pg-dwh) and nothing else. The product is
# SmartupCMS: its names are smc / SMC / smartupcms. A new occurrence of the old name (the case forms dwh, DWH and Dwh;
# a form such as "dWh" only comes from words like "goodWhen") fails unless the file is exempt as a whole or every
# occurrence on the line is one of the allowed tokens below. Both lists are closed: each entry says why it stays.
$oldName = [regex]::new('dwh|DWH|Dwh')
$oldNameFiles = @(
    # The warehouse module (item 4.2, ADR-0030): "dwh" is the name of its database (pg-dwh), its qualifier, pool and
    # health component. fnd keeps only the old migrate entry point, an alias until 2026-12-31.
    [pscustomobject]@{ Path = '^apps/server/src/(main|test)/java/com/smartup24/cms/instance/warehouse/'; Reason = 'the warehouse module' },
    [pscustomobject]@{ Path = '^apps/server/src/(main|test)/java/com/smartup24/cms/instance/fnd/'; Reason = 'old migrate entry point, an alias until 2026-12-31' },
    [pscustomobject]@{ Path = '^docs/adr/ADR-0030-fnd-split\.md$'; Reason = 'names the warehouse identifiers kept by the split' },
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/support/TestDatabases\.java$'; Reason = 'test databases of OLTP and the warehouse, shared with the fnd tests until item 4.2' },
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/support/fixtures/DwhQualifierViolator\.java$'; Reason = 'fixture of the fnd architecture test: the warehouse qualifier outside fnd' },
    # Warehouse migrations and the scripts of the warehouse database and its archive.
    [pscustomobject]@{ Path = '^apps/server/src/main/resources/db/dwh/'; Reason = 'warehouse migrations' },
    [pscustomobject]@{ Path = '^deploy/images/postgres/init-dwh\.sh$'; Reason = 'creates the warehouse database' },
    [pscustomobject]@{ Path = '^deploy/images/backup/(backup-loop|bootstrap-role)\.sh$'; Reason = 'backs up and grants the warehouse database' },
    [pscustomobject]@{ Path = '^scripts/prod/(restore|restore-combined)\.ps1$|^scripts/prod/(restore|test-backup-databases)\.sh$'; Reason = 'restores the warehouse archive' },
    # Released migrations are frozen by checksum (MigrationManifestTest): their old headers and seed values stay.
    [pscustomobject]@{ Path = '^apps/server/src/main/resources/db/migration/V(001|002|007|017|029|102|104|111|121)__[a-z0-9_]+\.sql$'; Reason = 'released migration, frozen by checksum' },
    # The transition of ADR-0027 and item 4.7: old names are read until 2026-12-31 and these files name them.
    [pscustomobject]@{ Path = '^apps/server/src/main/java/com/smartup24/cms/instance/(config|common)/env/(LegacyConfigNames|DefaultSecretsGuard|package-info)\.java$'; Reason = 'old configuration names and published secrets (ADR-0027)' },
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/(config|common)/env/(LegacyConfigAliasesTest|ConfigurationNamesTest|DefaultSecretsGuardTest)\.java$'; Reason = 'tests of the old configuration names (ADR-0027)' },
    [pscustomobject]@{ Path = '^apps/server/src/main/java/com/smartup24/cms/instance/kauth/pref/KauthPref\.java$'; Reason = 'old session cookie and token prefix, accepted until 2026-12-31' },
    [pscustomobject]@{ Path = '^docs/ops/configuration-reference\.md$|^docs/adr/ADR-0027-configuration-names\.md$|^docs/plan-10-10\.md$'; Reason = 'documents every old name and its replacement' },
    [pscustomobject]@{ Path = '^\.gitleaks\.toml$'; Reason = 'leaked tokens of the old prefix are still live secrets until 2026-12-31' },
    # History that cannot change: the changelog and fingerprints of past commits.
    [pscustomobject]@{ Path = '^CHANGELOG\.md$|^\.gitleaksignore$'; Reason = 'history of past releases and commits' },
    [pscustomobject]@{ Path = '(^|/)package-lock\.json$'; Reason = 'integrity hashes, not names' },
    [pscustomobject]@{ Path = '^scripts/docs/test-repository-hygiene\.ps1$'; Reason = 'this check' }
)
$oldNameTokens = @(
    # The warehouse.
    [pscustomobject]@{ Path = '.'; Token = 'pg-dwh|smartupcms_dwh|db/dwh|V001__dwh_schemas|init-dwh'; Reason = 'the warehouse database, its migrations and its init script' },
    [pscustomobject]@{ Path = '^(docs|deploy|scripts/prod)/|^\.env\.example$|(^|/)docker-compose[^/]*\.yml$'; Token = '\bDWH\b|DwhBackupFile|dwh-backup'; Reason = 'the warehouse in operations documents and scripts' },
    [pscustomobject]@{ Path = '^docs/adr/ADR-00(0[1-9]|1[0-9])-'; Token = '\bDWH\b|smartup5x_dwh'; Reason = 'the warehouse stage in early ADRs and its Biruni node' },
    # fnd identifiers used outside fnd until item 4.2.
    [pscustomobject]@{ Path = '^apps/server/src/|^docs/|^deploy/|(^|/)docker-compose[^/]*\.yml$'; Token = 'FndDwhConfig|FndDwhMaintenance|DwhSchemaVersionGate|DwhDataSourceProperties|DwhUnavailableException|DwhQualifierViolator|FndPref\.DWH\w*|fnd\.dwh|migrateDwh|TestDatabases\.(DWH_DB|dwh\(\))|dwh\.maintenance|`dwh`'; Reason = 'fnd identifiers, the warehouse session setting and the repository slug until item 4.2' },
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/config/system/ReadinessGroupIntegrationTest\.java$'; Token = '"dwh"'; Reason = 'health component of the warehouse, declared in fnd' },
    # i18n keys and texts about the warehouse.
    [pscustomobject]@{ Path = '^apps/server/src/main/resources/i18n/|^apps/web/src/app/core/i18n/|^apps/web/src/app/features/(iam/roles|tasks/projects)/'; Token = 'error\.fnd\.dwh_(read_forbidden|unavailable)|\bDWH\b'; Reason = 'the warehouse (and a sample project name) in UI texts' },
    [pscustomobject]@{ Path = '^apps/web/scripts/i18n-key-renames\.json$|^apps/server/src/main/resources/db/migration/V156__[a-z0-9_]+\.sql$'; Token = '\b[a-z]+\.[a-z_]*dwh[a-z_]*\b'; Reason = 'old translation keys renamed by item 4.5: the mapping and its migration' },
    # Old names accepted until 2026-12-31 (ADR-0027, item 4.7).
    [pscustomobject]@{ Path = '.'; Token = '\b(APP_)?DWH_[A-Z0-9_]*'; Reason = 'old environment names and the old session cookie, read until 2026-12-31' },
    [pscustomobject]@{ Path = '^docs/|^apps/server/src/test/'; Token = '\b(app\.)?dwh\.[a-z][a-z.-]*|`dwh_`|api\.dwh\.internal'; Reason = 'old property names, token prefix and problem type in transition notes and tests' },
    [pscustomobject]@{ Path = '^docs/api/openapi\.json$'; Token = 'dwh_\.\.\.'; Reason = 'old token prefix in the security scheme description' },
    [pscustomobject]@{ Path = '^apps/web/src/|^e2e/|^docs/'; Token = 'dwh_(theme|lang|custom_languages)|dwh\.table-columns\.v1\.'; Reason = 'old browser storage keys, moved on first read' },
    # Not ours to rename.
    [pscustomobject]@{ Path = '.'; Token = 'qahhor/dwh'; Reason = 'the GitHub repository slug' }
)
foreach ($entry in $oldNameFiles) {
    if (-not ($tracked | Where-Object { $_ -match $entry.Path } | Select-Object -First 1)) {
        $errors.Add("Old-name exemption matches no tracked file: $($entry.Path)")
    }
}
# The check itself is checked on a main source file: product forms fail, warehouse and transition forms pass.
$sampleTokens = @($oldNameTokens | Where-Object { 'apps/server/src/main/java/X.java' -match $_.Path })
function Test-OldNameRemains([string]$text) {
    $rest = $text
    foreach ($token in $sampleTokens) { $rest = [regex]::Replace($rest, $token.Token, '') }
    return $oldName.IsMatch($rest)
}
foreach ($sample in @('DWH Platform', 'Bearer dwh_xyz', 'DwhInfoContributor', 'dwh.search.query.duration')) {
    if (-not (Test-OldNameRemains $sample)) { $errors.Add("Old-name check misses a product form: $sample") }
}
foreach ($sample in @('pg-dwh is away', 'goodWhen', 'DWH_TYPESENSE_URL', 'FndDwhConfig')) {
    if (Test-OldNameRemains $sample) { $errors.Add("Old-name check flags a warehouse or transition form: $sample") }
}
# Every tracked text file is read. Binaries are skipped by extension and by a NUL byte in their first 8 KB.
$binaryExtension = '\.(png|jpe?g|gif|ico|webp|avif|bmp|tiff?|woff2?|ttf|otf|eot|pdf|zip|gz|tgz|bz2|xz|7z|rar|jar|war|class|xlsx?|docx?|pptx?|odt|ods|mp3|mp4|webm|wav|ogg|wasm|jks|p12|pfx|keystore|der|exe|dll|so|dylib|bin|dat)$'
function Test-BinaryContent([string]$path) {
    $stream = [System.IO.File]::OpenRead($path)
    try {
        $buffer = New-Object byte[] 8192
        $read = $stream.Read($buffer, 0, $buffer.Length)
        return ([Array]::IndexOf($buffer, [byte]0, 0, $read) -ge 0)
    }
    finally {
        $stream.Dispose()
    }
}
foreach ($relativePath in $tracked) {
    if ($relativePath -match $binaryExtension) {
        continue
    }
    $checkBrief = -not $frozenBriefReferences.Contains($relativePath)
    $checkOldName = -not ($oldNameFiles | Where-Object { $relativePath -match $_.Path } | Select-Object -First 1)
    if (-not $checkBrief -and -not $checkOldName) {
        continue
    }
    $fullPath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf) -or (Test-BinaryContent $fullPath)) {
        continue
    }
    $tokens = @($oldNameTokens | Where-Object { $relativePath -match $_.Path })
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($fullPath)) {
        $lineNumber++
        if ($checkBrief -and $briefReference.IsMatch($line)) {
            $errors.Add("Reference to a working brief instead of an ADR or FR: ${relativePath}:$lineNumber")
        }
        if ($checkOldName -and $oldName.IsMatch($line)) {
            $rest = $line
            foreach ($token in $tokens) { $rest = [regex]::Replace($rest, $token.Token, '') }
            if ($oldName.IsMatch($rest)) {
                $errors.Add("Old product name 'dwh' (the warehouse only, plan 10/10, item 4.7): ${relativePath}:$lineNumber")
            }
        }
    }
}

if ($errors.Count -gt 0) {
    throw "Repository hygiene contract failed:`n - $($errors -join "`n - ")"
}

Write-Host 'Repository hygiene contract passed.'
