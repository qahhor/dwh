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
    if ($relativePath -match $binaryExtension -or $frozenBriefReferences.Contains($relativePath)) {
        continue
    }
    $fullPath = Join-Path $repoRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf) -or (Test-BinaryContent $fullPath)) {
        continue
    }
    $lineNumber = 0
    foreach ($line in [System.IO.File]::ReadLines($fullPath)) {
        $lineNumber++
        if ($briefReference.IsMatch($line)) {
            $errors.Add("Reference to a working brief instead of an ADR or FR: ${relativePath}:$lineNumber")
        }
    }
}

if ($errors.Count -gt 0) {
    throw "Repository hygiene contract failed:`n - $($errors -join "`n - ")"
}

Write-Host 'Repository hygiene contract passed.'
