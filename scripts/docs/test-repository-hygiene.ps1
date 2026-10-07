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
    $staged = @(git ls-files -s)
    if ($LASTEXITCODE -ne 0) {
        throw 'git ls-files -s failed'
    }
}
finally {
    Pop-Location
}

# A shell entry point checked out on Linux or macOS runs only with the executable bit stored in git (a Windows
# checkout cannot set it): mvnw and every *.sh are 100755.
foreach ($entry in $staged) {
    if ($entry -notmatch '^(\d{6}) [0-9a-f]+ \d\t(.+)$') { continue }
    $mode = $Matches[1]
    $path = $Matches[2]
    if (($path -eq 'mvnw' -or $path.EndsWith('.sh')) -and $mode -ne '100755') {
        $errors.Add("Shell script is not executable in git: $path (git update-index --chmod=+x)")
    }
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
$checkScripts = @($tracked | Where-Object { $_ -match '^scripts/.+/test-[^/]+\.(ps1|sh|mjs)$' })
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
# a BOM in the ANSI code page). The Latin forms are the ids of work items of old briefs and audits, case-sensitive:
# a capital D, I, P or F with a dash and two digits; M with a dash and one or two digits; M with two digits. An SVG
# path (M, a number, then a space or a dot and a digit) and the ids of the specification (ADR-0025, FR-IAM-05,
# NFR-PERF-02, AC-31) do not match.
$briefReference = [regex]::new(
    '\u043f\u0440\u043e\u043c\u043f\u0442|\u0434\u043e\u043f\.\s?\u2116?\s?\d|\b\d{2} \u043f\.\s?\d|\(\d{2},\s?\u043f\.\s?\d' +
    '|(?-i:\b[DIPF]-\d{2}\b|\bM-\d{1,2}\b|\bM\d{2}\b(?![.,]?\s?\d))',
    [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
# The pattern itself is checked: the forms above match, a plan item or a date does not.
foreach ($sample in @('\u043f\u0440\u043e\u043c\u043f\u0442 02 \u043f.18', '(02, \u043f.18)', '(02, \u043f. 18)', '\u0434\u043e\u043f.12', '\u0434\u043e\u043f. 12', '\u0434\u043e\u043f.\u21163', '\u0434\u043e\u043f. \u2116 3', 'see 02 \u043f. 18',
        '(least privilege, \u0049-02)', 'Drill (\u0049-10)', '(\u0044-04)', '\u0050-03: export', '\u0046-04: role', '\u004d-4: log', 'AC-31 / \u004d-14:', '(\u004d17)', '(\u004d10 API)', '(\u004d18, module webhook)')) {
    if (-not $briefReference.IsMatch([regex]::Unescape($sample))) {
        $errors.Add("Brief-reference pattern misses a known form: $sample")
    }
}
foreach ($sample in @('\u043f\u043b\u0430\u043d 10/10, \u043f. 3.9', '(2026-09-26, \u043f. 53)', '\u0434\u043e\u043f. \u043f\u043e\u043b\u044f', 'ADR-0025 \u00a74',
        'FR-IAM-05', 'NFR-PERF-02', 'FR-COMM-03', 'AC-31', 'd="M43.5 19.5C40.1', 'd="M12 4L8 8"', 'UTF-8', 'code A-1', 'i-02', 'm10')) {
    if ($briefReference.IsMatch([regex]::Unescape($sample))) {
        $errors.Add("Brief-reference pattern matches a legitimate reference: $sample")
    }
}
# Released migrations that keep their old references: they are frozen by checksum (MigrationManifestTest) and cannot
# be edited. The list is closed; any other file, a new migration included, is checked.
$frozenBriefReferences = [System.Collections.Generic.HashSet[string]]::new([string[]]@(
    'apps/server/src/main/resources/db/dwh/V001__dwh_schemas.sql',
    'apps/server/src/main/resources/db/dwh/V002__raw_source_file_uuid.sql',
    'apps/server/src/main/resources/db/dwh/V003__raw_rows_source_file_idx.sql',
    'apps/server/src/main/resources/db/migration/V033__audit_partition_maintenance_functions.sql',
    'apps/server/src/main/resources/db/migration/V034__task_revision_and_drop_duplicate_indexes.sql',
    'apps/server/src/main/resources/db/migration/V100__fnd_core.sql',
    'apps/server/src/main/resources/db/migration/V102__fnd_versioning.sql',
    'apps/server/src/main/resources/db/migration/V103__fnd_units.sql',
    'apps/server/src/main/resources/db/migration/V104__fnd_loads.sql',
    'apps/server/src/main/resources/db/migration/V108__fnd_log_time_and_version_index.sql',
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
    # health component.
    [pscustomobject]@{ Path = '^apps/server/src/(main|test)/java/com/smartup24/cms/instance/warehouse/'; Reason = 'the warehouse module' },
    [pscustomobject]@{ Path = '^docs/adr/ADR-0030-fnd-split\.md$'; Reason = 'names the warehouse identifiers kept by the split' },
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/support/TestDatabases\.java$'; Reason = 'test databases of OLTP and the warehouse, shared by the warehouse and units tests' },
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/support/fixtures/DwhQualifierViolator\.java$'; Reason = 'fixture of the warehouse architecture test: the warehouse qualifier outside the warehouse module' },
    # Warehouse migrations and the scripts of the warehouse database and its archive.
    [pscustomobject]@{ Path = '^apps/server/src/main/resources/db/dwh/'; Reason = 'warehouse migrations' },
    [pscustomobject]@{ Path = '^deploy/images/postgres/init-dwh\.sh$'; Reason = 'creates the warehouse database' },
    [pscustomobject]@{ Path = '^deploy/images/backup/(backup-loop|bootstrap-role)\.sh$'; Reason = 'backs up and grants the warehouse database' },
    [pscustomobject]@{ Path = '^scripts/prod/(restore|restore-combined)\.ps1$|^scripts/prod/(restore|test-backup-databases)\.sh$'; Reason = 'restores the warehouse archive' },
    # Released migrations are frozen by checksum (MigrationManifestTest): their old headers and seed values stay.
    [pscustomobject]@{ Path = '^apps/server/src/main/resources/db/migration/V(001|002|007|017|029|102|104|111|121)__[a-z0-9_]+\.sql$'; Reason = 'released migration, frozen by checksum' },
    # Tests that prove an old name is not read any more (ADR-0027 section 4, item 4.7): they have to name it.
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/config/env/(ConfigurationNamesTest|DefaultSecretsGuardTest)\.java$'; Reason = 'tests that the configuration names before ADR-0027 are not read' },
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/(config/security/SecurityConfigTest|kauth/AuthenticationGenerationHttpTest|kauth/security/KauthSessionCookiesTest|kauth/service/KauthApiTokenServiceTest)\.java$'; Reason = 'tests that the old session cookie and token prefix are not accepted' },
    # Documents that describe the history of the old names (the transition was cancelled on 2026-10-01).
    [pscustomobject]@{ Path = '^docs/ops/configuration-reference\.md$|^docs/adr/ADR-0027-configuration-names\.md$|^docs/plan-10-10\.md$'; Reason = 'history of the old names and their replacements' },
    # History that cannot change: the changelog and fingerprints of past commits.
    [pscustomobject]@{ Path = '^CHANGELOG\.md$|^\.gitleaksignore$'; Reason = 'history of past releases and commits' },
    [pscustomobject]@{ Path = '^\.gitleaks\.toml$'; Reason = 'allow-lists false positives of past commits that still hold the old environment names' },
    # Checks that fail when an old name comes back: they have to name it.
    [pscustomobject]@{ Path = '^scripts/prod/test-release-config\.ps1$|^scripts/docs/test-public-docs\.ps1$'; Reason = 'checks that reject the old environment names and retired terms' },
    [pscustomobject]@{ Path = '(^|/)package-lock\.json$'; Reason = 'integrity hashes, not names' },
    [pscustomobject]@{ Path = '^scripts/docs/test-repository-hygiene\.ps1$'; Reason = 'this check' }
)
$oldNameTokens = @(
    # The warehouse.
    [pscustomobject]@{ Path = '.'; Token = 'pg-dwh|smartupcms_dwh|db/dwh|V001__dwh_schemas|init-dwh'; Reason = 'the warehouse database, its migrations and its init script' },
    [pscustomobject]@{ Path = '^(docs|deploy|scripts/prod)/|^\.env\.example$|(^|/)docker-compose[^/]*\.yml$'; Token = '\bDWH\b|DwhBackupFile'; Reason = 'the warehouse in operations documents and scripts' },
    [pscustomobject]@{ Path = '^docs/adr/ADR-00(0[1-9]|1[0-9])-'; Token = '\bDWH\b|smartup5x_dwh'; Reason = 'the warehouse stage in early ADRs and its Biruni node' },
    # Warehouse identifiers used outside the warehouse module (item 4.2 moved them there; their users stay elsewhere).
    [pscustomobject]@{ Path = '^apps/server/src/|^docs/|^deploy/|(^|/)docker-compose[^/]*\.yml$'; Token = 'fnd\.dwh|TestDatabases\.(DWH_DB|dwh\(\))|dwh\.maintenance|`dwh`'; Reason = 'warehouse identifiers, the warehouse session setting and the database name used outside the warehouse module' },
    [pscustomobject]@{ Path = '^apps/server/src/test/java/com/smartup24/cms/instance/config/system/ReadinessGroupIntegrationTest\.java$'; Token = '"dwh"'; Reason = 'health component of the warehouse database' },
    [pscustomobject]@{ Path = '^apps/server/src/'; Token = 'WarehouseError\.DWH_(READ_FORBIDDEN|UNAVAILABLE)'; Reason = 'error codes of the warehouse module' },
    # i18n keys and texts about the warehouse.
    [pscustomobject]@{ Path = '^apps/server/src/main/resources/i18n/|^apps/web/src/app/core/i18n/|^apps/web/src/app/features/(iam/roles|tasks/projects)/'; Token = 'error\.warehouse\.dwh_(read_forbidden|unavailable)|\bDWH\b'; Reason = 'the warehouse (and a sample project name) in UI texts' },
    [pscustomobject]@{ Path = '^apps/web/scripts/i18n-key-renames\.json$|^apps/server/src/main/resources/db/migration/V156__[a-z0-9_]+\.sql$'; Token = '\b[a-z]+\.[a-z_]*dwh[a-z_]*\b'; Reason = 'old translation keys renamed by item 4.5: the mapping and its migration' },
    # Documents that describe the history of a renamed property or problem type.
    [pscustomobject]@{ Path = '^docs/'; Token = '\b(app\.)?dwh\.[a-z][a-z.-]*|api\.dwh\.internal'; Reason = 'old property names and problem type in documents about their history' },
    # Not ours to rename.
    [pscustomobject]@{ Path = '.'; Token = 'qahhor/dwh'; Reason = 'the GitHub repository slug' }
)
foreach ($entry in $oldNameFiles) {
    if (-not ($tracked | Where-Object { $_ -match $entry.Path } | Select-Object -First 1)) {
        $errors.Add("Old-name exemption matches no tracked file: $($entry.Path)")
    }
}
# A token is dead when no checked line needs it: each top-level alternative of each entry is checked on its own, so a
# renamed identifier leaves the list together with the code (the same rule as the file list above).
function Split-TopLevelAlternatives([string]$pattern) {
    $parts = [System.Collections.Generic.List[string]]::new()
    $depth = 0
    $start = 0
    for ($i = 0; $i -lt $pattern.Length; $i++) {
        $c = $pattern[$i]
        if ($c -eq '\') { $i++; continue }
        if ($c -eq '(') { $depth++ }
        elseif ($c -eq ')') { $depth-- }
        elseif ($c -eq '|' -and $depth -eq 0) {
            $parts.Add($pattern.Substring($start, $i - $start))
            $start = $i + 1
        }
    }
    $parts.Add($pattern.Substring($start))
    return $parts
}
$tokenAlternatives = [System.Collections.Generic.List[object]]::new()
foreach ($entry in $oldNameTokens) {
    foreach ($alternative in (Split-TopLevelAlternatives $entry.Token)) {
        $tokenAlternatives.Add([pscustomobject]@{ Path = $entry.Path; Regex = [regex]::new($alternative); Text = $alternative; Used = $false })
    }
}
# The check itself is checked on a main source file: product forms and old names fail, warehouse forms pass.
$sampleTokens = @($oldNameTokens | Where-Object { 'apps/server/src/main/java/X.java' -match $_.Path })
function Test-OldNameRemains([string]$text) {
    $rest = $text
    foreach ($token in $sampleTokens) { $rest = [regex]::Replace($rest, $token.Token, '') }
    return $oldName.IsMatch($rest)
}
foreach ($sample in @('DWH Platform', 'Bearer dwh_xyz', 'DwhInfoContributor', 'dwh.search.query.duration', 'DWH_TYPESENSE_URL', 'DWH_SESSION', 'dwh_theme')) {
    if (-not (Test-OldNameRemains $sample)) { $errors.Add("Old-name check misses a product form: $sample") }
}
foreach ($sample in @('pg-dwh is away', 'goodWhen', 'WarehouseError.DWH_UNAVAILABLE')) {
    if (Test-OldNameRemains $sample) { $errors.Add("Old-name check flags a warehouse form: $sample") }
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
    $alternatives = @($tokenAlternatives | Where-Object { $relativePath -match $_.Path })
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($fullPath)) {
        $lineNumber++
        if ($checkBrief -and $briefReference.IsMatch($line)) {
            $errors.Add("Reference to a working brief instead of an ADR or FR: ${relativePath}:$lineNumber")
        }
        if ($checkOldName -and $oldName.IsMatch($line)) {
            foreach ($alternative in $alternatives) {
                if (-not $alternative.Used -and $alternative.Regex.IsMatch($line)) { $alternative.Used = $true }
            }
            $rest = $line
            foreach ($token in $tokens) { $rest = [regex]::Replace($rest, $token.Token, '') }
            if ($oldName.IsMatch($rest)) {
                $errors.Add("Old product name 'dwh' (the warehouse only, plan 10/10, item 4.7): ${relativePath}:$lineNumber")
            }
        }
    }
}
foreach ($alternative in $tokenAlternatives) {
    if (-not $alternative.Used) {
        $errors.Add("Old-name token matches no checked line: $($alternative.Text) (files $($alternative.Path))")
    }
}

if ($errors.Count -gt 0) {
    throw "Repository hygiene contract failed:`n - $($errors -join "`n - ")"
}

Write-Host 'Repository hygiene contract passed.'
