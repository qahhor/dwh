<#
.SYNOPSIS
    SmartupCMS module generator: a declared entity that follows the server rules of phase 3.
.DESCRIPTION
    Generates a new domain module the way the reference module (notes, ms/note) is built:
    1. Two Flyway migrations: the table (ADR-0020 naming and types, a revision column for If-Match) and the seed
       (the right's catalog, grants to the system roles, the module registry). DDL and seed data never share a file.
    2. Java: two files, the entity declaration (Entity) and its hooks (EntityHooks). The general entity runtime serves
       the records at /api/v1/entities/<prefix>.<code> (ADR-0032, 6; plan 10/10, item 5.4): list, read, create,
       change with If-Match, delete, scope, field rules, audit and events; no controller, service or repository is
       written. Every field is declared once, as an EntityField: the form and the list in the field registry are
       derived from it (ADR-0032, plan 10/10, item 5.1).
    The area of the module's right is registered in PermissionAreas, so the right has its owning module (ADR-0028).
    The test sources get the entity's contract test, one subclass of EntityContractTestKit (plan 10/10, item 6.2).
    3. Catalog keys in ru/uz/en (apps/server/src/main/resources/i18n): the menu item and the labels.
    What is left to do by hand is printed at the end; scripts/dev/test-create-module.ps1 checks the output compiles
    and passes the architecture tests.
.PARAMETER ModuleName
    Module code: lower-case Latin letters and digits, starting with a letter (inventory, crm, wiki).
.PARAMETER ModuleTitle
    Module title in Russian, the canonical language (for example "Склад").
.PARAMETER TitleEn
    Module title in English; the code in title case when omitted.
.PARAMETER TitleUz
    Module title in Uzbek; the English title when omitted.
.PARAMETER ModuleDescription
    Short description for the module registry.
.PARAMETER Icon
    Interface icon (package, bookmark, briefcase, box).
.PARAMETER Prefix
    The parent package and table prefix (ms by default).
.EXAMPLE
    .\scripts\dev\create-module.ps1 -ModuleName inventory -ModuleTitle "Склад" -TitleEn "Inventory" -TitleUz "Ombor" -Icon package
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ModuleName,

    [Parameter(Mandatory = $true)]
    [string]$ModuleTitle,

    [Parameter(Mandatory = $false)]
    [string]$TitleEn = "",

    [Parameter(Mandatory = $false)]
    [string]$TitleUz = "",

    [Parameter(Mandatory = $false)]
    [string]$ModuleDescription = "",

    [Parameter(Mandatory = $false)]
    [string]$Icon = "box",

    [Parameter(Mandatory = $false)]
    [string]$Prefix = "ms"
)

$ErrorActionPreference = "Stop"

# UTF-8 without a BOM (javac refuses one, and Set-Content -Encoding UTF8 in Windows PowerShell writes it), with the
# line ending of the platform, which Spotless expects of a checkout (git core.autocrlf), and a final newline.
function Write-Utf8([string]$Path, [string]$Content) {
    $text = $Content.Replace("`r`n", "`n")
    if (-not $text.EndsWith("`n")) { $text += "`n" }
    $text = $text.Replace("`n", [Environment]::NewLine)
    [System.IO.File]::WriteAllText($Path, $text, (New-Object System.Text.UTF8Encoding $false))
}

function Get-SqlText([string]$Value) { return $Value.Replace("'", "''") }

function Get-JsonText([string]$Value) { return $Value.Replace('\', '\\').Replace('"', '\"') }

$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")

$cleanCode = $ModuleName.ToLower().Trim()
$prefixLower = $Prefix.ToLower().Trim()
if ($cleanCode -notmatch '^[a-z][a-z0-9]{1,30}$') { throw "ModuleName must be lower-case Latin letters and digits: $ModuleName" }
if ($prefixLower -notmatch '^[a-z][a-z0-9]{1,10}$') { throw "Prefix must be lower-case Latin letters and digits: $Prefix" }

$textInfo = (Get-Culture).TextInfo
$capitalName = $textInfo.ToTitleCase($cleanCode)
$prefixUpper = $textInfo.ToTitleCase($prefixLower)
if (-not $TitleEn) { $TitleEn = $capitalName }
if (-not $TitleUz) { $TitleUz = $TitleEn }
if (-not $ModuleDescription) { $ModuleDescription = $ModuleTitle }
$tableName = "${prefixLower}_${cleanCode}"

$pkg = "com.smartup24.cms.instance.${prefixLower}.${cleanCode}"
$javaBase = Join-Path $Root "apps\server\src\main\java\com\smartup24\cms\instance\${prefixLower}\${cleanCode}"
if (Test-Path $javaBase) { throw "The module already exists: $javaBase" }

# The right's form is the module code, so the code must not be a permission area already (ADR-0028).
$areasFile = Join-Path $Root "apps\server\src\main\java\com\smartup24\cms\instance\md\pref\PermissionAreas.java"
$areasText = [System.IO.File]::ReadAllText($areasFile, (New-Object System.Text.UTF8Encoding $false))
if ($areasText.Contains("`"$cleanCode`"")) { throw "The permission area $cleanCode is taken (PermissionAreas)" }

Write-Host "=== SmartupCMS module generator ===" -ForegroundColor Cyan
Write-Host "Module: $cleanCode ($pkg), table $tableName"

# 1. Migrations: the next two free numbers, the table and the seed in files of their own (MigrationFileRulesTest)
$migrationDir = Join-Path $Root "apps\server\src\main\resources\db\migration"
$lastNum = 0
foreach ($m in Get-ChildItem -Path $migrationDir -Filter "V*.sql") {
    if ($m.Name -match "^V0*(\d+)__") {
        $num = [int]$matches[1]
        if ($num -gt $lastNum) { $lastNum = $num }
    }
}
$tableVersion = "V" + ($lastNum + 1).ToString("D3")
$seedVersion = "V" + ($lastNum + 2).ToString("D3")
$tableMigration = Join-Path $migrationDir "${tableVersion}__${tableName}_table.sql"
$seedMigration = Join-Path $migrationDir "${seedVersion}__${tableName}_seed.sql"

$titleSql = Get-SqlText $ModuleTitle
$descriptionSql = Get-SqlText $ModuleDescription

Write-Utf8 $tableMigration @"
set lock_timeout = '2s';
set statement_timeout = '60s';
-- The table of the $cleanCode module (scripts/dev/create-module.ps1): names and types by ADR-0020, a revision that
-- every change raises and If-Match names (ADR-0024), custom field values in attributes.
create table $tableName (
    id bigint generated always as identity primary key,
    name text not null,
    code text not null,
    status text not null default 'active',
    attributes jsonb not null default '{}'::jsonb,
    created_by bigint not null references md_users (id),
    modified_by bigint not null references md_users (id),
    created_at timestamptz not null default clock_timestamp(),
    modified_at timestamptz not null default clock_timestamp(),
    revision bigint not null default 1,
    constraint ${tableName}_ck_name check (char_length(name) between 1 and 255),
    constraint ${tableName}_ck_code check (code ~ '^[a-z0-9_-]{1,64}$'),
    constraint ${tableName}_ck_status check (status in ('active', 'archived'))
);

create unique index ${tableName}_code_uq on $tableName (lower(code));
create index ${tableName}_created_by_idx on $tableName (created_by, id desc);
"@

Write-Utf8 $seedMigration @"
set lock_timeout = '2s';
set statement_timeout = '60s';
-- The $cleanCode module (scripts/dev/create-module.ps1): its right in the catalog, grants to the system roles and
-- its entry in the module registry. Seed data only: the table is created by $tableVersion.
insert into md_forms (code, module, name) values
('$cleanCode', '$prefixLower', '$titleSql')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
('$cleanCode', 'view', 'View'),
('$cleanCode', 'create', 'Create'),
('$cleanCode', 'update', 'Edit'),
('$cleanCode', 'delete', 'Delete')
on conflict (form_code, action) do nothing;

-- Administrators and managers get every action
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('chief_admin', 'admin', 'manager') and fa.form_code = '$cleanCode'
on conflict do nothing;

-- The user and auditor roles only view; analyst (V110) gets a new module's rights from an administrator
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('user', 'auditor') and fa.form_code = '$cleanCode' and fa.action = 'view'
on conflict do nothing;

insert into md_installed_modules (code, name, description, icon, route, is_system, status, sort_order) values
('$cleanCode', '$titleSql', '$descriptionSql', '$Icon', '/e/${prefixLower}.$cleanCode', false, 'ACTIVE', 100)
on conflict (code) do nothing;
"@

Write-Host "-> Migrations: $tableMigration, $seedMigration" -ForegroundColor Yellow

# The manifest of the module (ADR-0033, 6.4): the registry shows its version, the start checks its platform and
# dependencies. The build writes the application's and the API's versions in place of the placeholders.
$manifestFile = Join-Path $Root "apps\server\src\main\resources\META-INF\smartupcms\modules\${cleanCode}.json"
$titleJson = Get-JsonText $ModuleTitle
Write-Utf8 $manifestFile @"
{
  "code": "$cleanCode",
  "name": "$titleJson",
  "version": "`${project.version}",
  "minPlatform": "`${platform-api.version}",
  "dependencies": [
    {
      "code": "iam",
      "version": "`${project.version}"
    }
  ]
}
"@
Write-Host "-> Manifest: $manifestFile" -ForegroundColor Yellow

# 2. Java: the declaration and the hooks, the whole server side of an entity on the runtime (ADR-0032, 6; plan 10/10,
# item 5.4): /api/v1/entities/<code> serves the records; there is no controller, service or repository to write.
$serviceDir = Join-Path $javaBase "service"
New-Item -ItemType Directory -Force -Path $serviceDir | Out-Null

# Every module says what it is for (plan 10/10, item 4.3, ModuleMapTest); the English title keeps the comment English.
$titleComment = $TitleEn.Replace('*/', '').Trim()
Write-Utf8 (Join-Path $javaBase "package-info.java") @"
/**
 * Module {@code ${prefixLower}.${cleanCode}}: $titleComment.
 *
 * <p>Declared with {@code Entity.define} by scripts/dev/create-module.ps1 (ADR-0019, ADR-0032): the general entity
 * runtime serves its records at {@code /api/v1/entities/${prefixLower}.${cleanCode}}. Describe here what the module is
 * for, and give it a row in docs/architecture/module-map.md (plan 10/10, item 4.3).
 */
package ${pkg};
"@

$entityClass = "${prefixUpper}${capitalName}Entity"
$hooksClass = "${prefixUpper}${capitalName}Hooks"
$listCode = "${prefixLower}.${cleanCode}"

Write-Utf8 (Join-Path $serviceDir "${entityClass}.java") @"
package ${pkg}.service;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.instant;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.select;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;

import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.platform.api.entity.field.FieldDefault;
import com.smartup24.cms.platform.api.entity.field.FieldSource.SystemColumn;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The $cleanCode entity, every field declared once (ADR-0019, ADR-0032): the general runtime serves its records at
 * /api/v1/entities/$listCode with its scope, field rules, revision, audit and events; its form (GET
 * /api/v1/form-meta/$listCode) and its list (GET /api/v1/query-meta/$listCode) are derived from the fields, with the
 * names of its right, its menu item, and history, export, saved views and bulk delete. A new field goes here and into
 * a migration of the table; what the declaration cannot say goes into ${hooksClass}.
 */
@Configuration
public class ${entityClass} {

    public static final List<String> STATUSES = List.of("active", "archived");

    public static final EntityDefinition DEFINITION = Entity.define("$listCode", "$cleanCode")
            .table("$tableName", "t")
            // Every row for whoever holds the right (ADR-0032, 5.1): a personal or org-unit record declares
            // EntityScope.owner(...) or EntityScope.orgUnit(...).
            .scope(EntityScope.all())
            .rights(
                    "${prefixLower}.${cleanCode}",
                    "${cleanCode}.rights.form",
                    Map.of(
                            "view", "${cleanCode}.rights.view",
                            "create", "${cleanCode}.rights.create",
                            "update", "${cleanCode}.rights.update",
                            "delete", "${cleanCode}.rights.delete"))
            // No route of its own: the item leads to the general screen /e/$listCode (ADR-0032, 7.1).
            .menu(new EntityMenu("nav.$cleanCode", "$Icon", "workspace", 100, "$cleanCode"))
            .field(text("name", "${cleanCode}.col.name")
                    .column("name")
                    .required()
                    .length(1, 255)
                    .list(sortable().searchable()))
            .field(text("code", "${cleanCode}.col.code")
                    .column("code")
                    .required()
                    .length(1, 64)
                    .matching("[a-z0-9_-]+")
                    .list(sortable().searchable()))
            .field(select("status", "${cleanCode}.col.status", STATUSES, "${cleanCode}.status.")
                    .column("status")
                    .required()
                    .defaultValue(FieldDefault.fixed("active")))
            .field(instant("modifiedAt", "${cleanCode}.col.modified_at")
                    .system(SystemColumn.MODIFIED_AT)
                    .list(sortable()))
            .section("main", "entity.section.main", "name", "code", "status")
            .actions("create", "update", "delete")
            .defaultSort("modifiedAt", Entity.Sort.DESC)
            .capabilities(
                    EntityCapability.SAVED_VIEWS,
                    EntityCapability.EXPORT,
                    EntityCapability.HISTORY,
                    EntityCapability.BULK)
            .build();

    /** Named apart from the bean of this configuration class itself (${prefixLower}${capitalName}Entity). */
    @Bean
    public EntityDefinition ${prefixLower}${capitalName}Definition() {
        return DEFINITION;
    }
}
"@

Write-Utf8 (Join-Path $serviceDir "${hooksClass}.java") @"
package ${pkg}.service;

import com.smartup24.cms.platform.api.entity.hook.EntityHooks;
import com.smartup24.cms.platform.api.entity.hook.EntitySave;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * What the $cleanCode declaration cannot say (ADR-0032, 6.5): the runtime calls these hooks at fixed steps of every
 * save, after the field rules and before the write. The code is kept in lower case, as its unique index compares it.
 * Data of this module goes through a repository of the module, another module's through its service.
 */
@Component
public class ${hooksClass} implements EntityHooks {

    @Override
    public String entity() {
        return ${entityClass}.DEFINITION.code();
    }

    @Override
    public void beforeSave(EntitySave save) {
        String code = save.values().text("code");
        if (code != null && save.changed("code")) {
            save.values().set("code", code.strip().toLowerCase(Locale.ROOT));
        }
    }
}
"@

Write-Host "-> Java: $pkg (the declaration ${entityClass} and the hooks ${hooksClass})" -ForegroundColor Yellow

# The entity's contract test (plan 10/10, item 6.2; ADR-0032, 11): one subclass of the kit gives the module the checks
# of CRUD, rights, scope, validation, audit and export, and EntityContractCoverageTest finds it.
$testBase = Join-Path $Root "apps\server\src\test\java\com\smartup24\cms\instance\${prefixLower}\${cleanCode}"
New-Item -ItemType Directory -Force -Path $testBase | Out-Null
$contractClass = "${prefixUpper}${capitalName}ContractTest"
Write-Utf8 (Join-Path $testBase "${contractClass}.java") @"
package ${pkg};

import com.smartup24.cms.instance.support.entity.EntityContractTestKit;

/**
 * The $cleanCode entity passes the entity contract (ADR-0032, 11; plan 10/10, item 6.2) on the general runtime
 * /api/v1/entities/$listCode. Values the kit cannot make up (a reference, a file, an enumeration item) go into
 * fixture(...).
 */
class ${contractClass} extends EntityContractTestKit {

    @Override
    protected String entity() {
        return "$listCode";
    }
}
"@
Write-Host "-> Contract test: ${contractClass} (EntityContractTestKit)" -ForegroundColor Yellow

# The area of the right and its owning module (ADR-0028): the form $cleanCode belongs to ${prefixLower}.${cleanCode}, the
# module its EntityRights name (EntityActionPermissionContractTest, PermissionCodesTest).
$anchor = "NAMED_AREAS = Map.of("
$index = $areasText.IndexOf($anchor)
if ($index -lt 0) { throw "PermissionAreas.NAMED_AREAS not found in $areasFile" }
$areasNewline = if ($areasText.Contains("`r`n")) { "`r`n" } else { "`n" }
$areasText = $areasText.Insert($index + $anchor.Length, "$areasNewline            `"$cleanCode`", `"${prefixLower}.${cleanCode}`",")
[System.IO.File]::WriteAllText($areasFile, $areasText, (New-Object System.Text.UTF8Encoding $false))
Write-Host "-> Permission area: $cleanCode -> ${prefixLower}.${cleanCode} (PermissionAreas)" -ForegroundColor Yellow

# 3. Catalog keys in ru/uz/en: the labels of the screen and of the permission matrix
$labels = [ordered]@{
    "nav.$cleanCode"                 = @{ ru = $ModuleTitle; en = $TitleEn; uz = $TitleUz }
    "${cleanCode}.col.name"          = @{ ru = "Название"; en = "Name"; uz = "Nomi" }
    "${cleanCode}.col.code"          = @{ ru = "Код"; en = "Code"; uz = "Kod" }
    "${cleanCode}.col.status"        = @{ ru = "Статус"; en = "Status"; uz = "Holat" }
    "${cleanCode}.col.modified_at"   = @{ ru = "Изменено"; en = "Modified"; uz = "Oʻzgartirilgan" }
    "${cleanCode}.status.active"     = @{ ru = "Действует"; en = "Active"; uz = "Faol" }
    "${cleanCode}.status.archived"   = @{ ru = "В архиве"; en = "Archived"; uz = "Arxivda" }
    "${cleanCode}.rights.form"       = @{ ru = $ModuleTitle; en = $TitleEn; uz = $TitleUz }
    "${cleanCode}.rights.view"       = @{ ru = "Просмотр"; en = "View"; uz = "Koʻrish" }
    "${cleanCode}.rights.create"     = @{ ru = "Создание"; en = "Create"; uz = "Yaratish" }
    "${cleanCode}.rights.update"     = @{ ru = "Редактирование"; en = "Edit"; uz = "Tahrirlash" }
    "${cleanCode}.rights.delete"     = @{ ru = "Удаление"; en = "Delete"; uz = "Oʻchirish" }
}
$catalogDir = Join-Path $Root "apps\server\src\main\resources\i18n"
foreach ($language in @("ru", "en", "uz")) {
    $catalogFile = Join-Path $catalogDir "$language.json"
    $text = [System.IO.File]::ReadAllText($catalogFile, (New-Object System.Text.UTF8Encoding $false))
    $newline = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
    $added = @()
    foreach ($key in $labels.Keys) {
        if (-not $text.Contains("`"$key`":")) {
            $added += "  `"$key`": `"$(Get-JsonText $labels[$key][$language])`""
        }
    }
    if ($added.Count -gt 0) {
        $end = $text.LastIndexOf('}')
        $text = $text.Substring(0, $end).TrimEnd() + "," + $newline + ($added -join ("," + $newline)) + $newline + $text.Substring($end)
        [System.IO.File]::WriteAllText($catalogFile, $text, (New-Object System.Text.UTF8Encoding $false))
    }
}
Write-Host "-> Catalog keys: $($labels.Keys -join ', ')" -ForegroundColor Yellow

Write-Host ""
Write-Host "Left to do (docs/guidelines/module-development-guide.md, 'Server rules'):" -ForegroundColor Cyan
Write-Host "  1. Pin the migrations: mvn -B -pl apps/server test -Dtest=MigrationManifestTest -Dmigrations.manifest.append=true"
Write-Host "     ($tableVersion and $seedVersion are the next free numbers now: renumber if another branch takes them)"
Write-Host "  2. Check the uz/en texts of the added keys; then in apps/web: npm run i18n:sync-ru"
Write-Host "  3. API description: mvn -B -pl apps/server test -Dtest=OpenApiContractTest -Dopenapi.update=true; in apps/web: npm run api:types"
Write-Host "  4. Screen: none to write - the menu item opens the general screen /e/$listCode (list, form, card);"
Write-Host "     a small tweak is provideEntityOverrides('$listCode', ...) in apps/web/src/app/app.config.ts"
Write-Host "  5. The contract test ${contractClass} runs the entity kit on the runtime; test the hooks; a line for"
Write-Host "     $prefixLower.$cleanCode in apps/server/coverage-floors.csv;"
Write-Host "     $prefixLower.$cleanCode in ModuleBoundariesTest.MODULES and its table prefix; a growing table: LARGE_TABLES, a RetentionPolicy"
Write-Host "     the purpose in package-info.java and a row in docs/architecture/module-map.md (ModuleMapTest)"
Write-Host "  6. mvn -B verify (Checkstyle, Spotless, architecture tests); scripts/dev/test-create-module.ps1 checks this generator"
