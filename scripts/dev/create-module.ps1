<#
.SYNOPSIS
    SmartupCMS module generator: a declared entity that follows the server rules of phase 3.
.DESCRIPTION
    Generates a new domain module the way the reference module (notes, ms/note) is built:
    1. Two Flyway migrations: the table (ADR-0020 naming and types, a revision column for If-Match) and the seed
       (the right's catalog, grants to the system roles, the module registry). DDL and seed data never share a file.
    2. Java: the api package (the view and the request records), repository (JsonColumns, revision), service
       (ApiException with catalog keys, keyset pages), controller (201 + Location, If-Match, 204), the list in the
       field registry (Query) and the entity declaration (Entity) with its records bean (ADR-0019).
    3. Catalog keys in ru/uz/en (apps/server/src/main/resources/i18n): the error, the menu item and the labels.
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

insert into md_installed_modules (code, name, description, version, icon, route, is_system, status, sort_order) values
('$cleanCode', '$titleSql', '$descriptionSql', '1.0.0', '$Icon', '/$cleanCode', false, 'ACTIVE', 100)
on conflict (code) do nothing;
"@

Write-Host "-> Migrations: $tableMigration, $seedMigration" -ForegroundColor Yellow

# 2. Java: api, repository, service (list, entity, service) and controller, as in ms/note
$apiDir = Join-Path $javaBase "api"
$repoDir = Join-Path $javaBase "repository"
$serviceDir = Join-Path $javaBase "service"
$ctrlDir = Join-Path $javaBase "controller"
foreach ($dir in @($apiDir, $repoDir, $serviceDir, $ctrlDir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }

$viewClass = "${prefixUpper}${capitalName}View"
$createClass = "${prefixUpper}${capitalName}CreateRequest"
$updateClass = "${prefixUpper}${capitalName}UpdateRequest"
$repoClass = "${prefixUpper}${capitalName}Repository"
$serviceClass = "${prefixUpper}${capitalName}Service"
$ctrlClass = "${prefixUpper}${capitalName}Controller"
$queryClass = "${prefixUpper}${capitalName}Query"
$entityClass = "${prefixUpper}${capitalName}Entity"
$listCode = "${prefixLower}.${cleanCode}"
$notFoundKey = "error.${cleanCode}.not_found"
$titleJava = $ModuleTitle.Replace('\', '\\').Replace('"', '\"')

Write-Utf8 (Join-Path $apiDir "${viewClass}.java") @"
package ${pkg}.api;

import com.smartup24.cms.instance.common.web.Revisioned;
import java.time.Instant;
import java.util.Map;

/** A $cleanCode record as the API answers it; its revision is the ETag a change names in If-Match (ADR-0024). */
public record ${viewClass}(
        Long id,
        String name,
        String code,
        String status,
        Map<String, Object> attributes,
        Long createdBy,
        Instant createdAt,
        Instant modifiedAt,
        long revision)
        implements Revisioned {}
"@

Write-Utf8 (Join-Path $apiDir "${createClass}.java") @"
package ${pkg}.api;

import java.util.Map;

/** The body of POST /api/v1/$cleanCode. */
public record ${createClass}(String name, String code, Map<String, Object> attributes) {}
"@

Write-Utf8 (Join-Path $apiDir "${updateClass}.java") @"
package ${pkg}.api;

import java.util.Map;

/** The body of PUT /api/v1/$cleanCode/{id}: a null field keeps its value; the revision comes in If-Match. */
public record ${updateClass}(String name, String status, Map<String, Object> attributes) {}
"@

Write-Utf8 (Join-Path $repoDir "${repoClass}.java") @"
package ${pkg}.repository;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.json.JsonColumns;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.web.Revisions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** The rows of ${tableName}: JSON through JsonColumns, and every change raises the revision (ADR-0024). */
@Repository
public class ${repoClass} {

    public static final String COLUMNS = """
            t.id, t.name, t.code, t.status, t.attributes::text as attributes_str,
            t.created_by, t.modified_by, t.created_at, t.modified_at, t.revision""";

    private final JdbcClient jdbcClient;
    private final JsonColumns jsonColumns;

    public ${repoClass}(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.jsonColumns = new JsonColumns(objectMapper, "$tableName");
    }

    public record ItemRecord(
            Long id,
            String name,
            String code,
            String status,
            Map<String, Object> attributes,
            Long createdBy,
            Long modifiedBy,
            Instant createdAt,
            Instant modifiedAt,
            long revision) {}

    /** A page of the list through the field registry ($listCode). */
    public KeysetPage<ItemRecord> page(QueryPlan plan) {
        return new QueryListRepository(jdbcClient).page(plan, this::mapItem);
    }

    public Optional<ItemRecord> findById(Long id) {
        return jdbcClient
                .sql("select " + COLUMNS + " from $tableName t where t.id = :id")
                .param("id", id)
                .query(this::mapItem)
                .optional();
    }

    public ItemRecord create(String name, String code, Map<String, Object> attributes, Long userId) {
        return jdbcClient
                .sql("insert into $tableName as t (name, code, attributes, created_by, modified_by)"
                        + " values (:name, :code, cast(:attributes as jsonb), :userId, :userId) returning "
                        + COLUMNS)
                .param("name", name)
                .param("code", code.toLowerCase().trim())
                .param("attributes", jsonColumns.object(attributes))
                .param("userId", userId)
                .query(this::mapItem)
                .single();
    }

    /** Changes the fields given (a null one keeps its value) if the row is still at the expected revision, else 409. */
    public ItemRecord update(
            Long id, String name, String status, Map<String, Object> attributes, Long userId, long expectedRevision) {
        return jdbcClient
                .sql("update $tableName t set name = coalesce(:name, t.name), status = coalesce(:status, t.status),"
                        + " attributes = coalesce(cast(:attributes as jsonb), t.attributes), modified_by = :userId,"
                        + " modified_at = clock_timestamp(), revision = t.revision + 1"
                        + " where t.id = :id and t.revision = :expectedRevision returning " + COLUMNS)
                .param("id", id)
                .param("name", name)
                .param("status", status)
                .param("attributes", attributes == null ? null : jsonColumns.object(attributes))
                .param("userId", userId)
                .param("expectedRevision", expectedRevision)
                .query(this::mapItem)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    public boolean delete(Long id) {
        return jdbcClient
                        .sql("delete from $tableName where id = :id")
                        .param("id", id)
                        .update()
                > 0;
    }

    private ItemRecord mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new ItemRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("status"),
                jsonColumns.readObject(rs.getString("attributes_str")),
                rs.getLong("created_by"),
                rs.getLong("modified_by"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("modified_at")),
                rs.getLong("revision"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
"@

Write-Utf8 (Join-Path $serviceDir "${queryClass}.java") @"
package ${pkg}.service;

import com.smartup24.cms.instance.common.query.QueryField;
import com.smartup24.cms.instance.common.query.QueryFieldType;
import com.smartup24.cms.instance.common.query.QueryList;
import ${pkg}.repository.${repoClass};
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The $cleanCode list in the field registry (ADR-0016): GET /api/v1/$cleanCode and /api/v1/query-meta/$listCode. A
 * table that grows without bound reports an estimate instead of a count: add withEstimatedTotal() (plan 10/10, item
 * 3.5).
 */
@Configuration
public class ${queryClass} {

    public static final QueryList LIST = new QueryList(
            "$listCode",
            "$cleanCode",
            "view",
            ${repoClass}.COLUMNS,
            "$tableName t",
            "t.id",
            List.of(
                    QueryField.of("name", "${cleanCode}.col.name", QueryFieldType.TEXT, "t.name")
                            .asSortable()
                            .asSearchable(),
                    QueryField.of("code", "${cleanCode}.col.code", QueryFieldType.TEXT, "t.code")
                            .asSortable()
                            .asSearchable(),
                    QueryField.of("status", "${cleanCode}.col.status", QueryFieldType.TEXT, "t.status"),
                    QueryField.of("modifiedAt", "${cleanCode}.col.modified_at", QueryFieldType.INSTANT, "t.modified_at")
                            .asSortable()),
            "modifiedAt",
            true,
            QueryList.DEFAULT_LIMIT,
            QueryList.MAX_LIMIT);

    @Bean
    public QueryList ${prefixLower}${capitalName}QueryList() {
        return LIST;
    }
}
"@

Write-Utf8 (Join-Path $serviceDir "${entityClass}.java") @"
package ${pkg}.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityAction;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityMenu;
import com.smartup24.cms.instance.common.entity.EntityDefinition.EntityRights;
import com.smartup24.cms.instance.common.entity.EntityDefinition.FormSection;
import com.smartup24.cms.instance.common.entity.EntityRecords;
import com.smartup24.cms.instance.common.entity.FormField;
import com.smartup24.cms.instance.common.entity.FormFieldType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The $cleanCode entity, declared once (ADR-0019): its form and rules (GET /api/v1/form-meta/$listCode), the names
 * of its right, its menu item, and history, export, saved views and bulk delete. A new field goes here, into the list
 * and into the repository.
 */
@Configuration
public class ${entityClass} {

    public static final List<String> STATUSES = List.of("active", "archived");

    public static final EntityDefinition DEFINITION = new EntityDefinition(
            ${queryClass}.LIST.code(),
            "$cleanCode",
            ${queryClass}.LIST.code(),
            null,
            "$tableName",
            new EntityRights(
                    "$prefixLower",
                    "$titleJava",
                    Map.of("view", "View", "create", "Create", "update", "Edit", "delete", "Delete")),
            new EntityMenu("/$cleanCode", "nav.$cleanCode", "$Icon", "workspace", 100, "$cleanCode"),
            List.of(
                    FormField.of("name", "${cleanCode}.col.name", FormFieldType.TEXT)
                            .asRequired()
                            .length(1, 255),
                    FormField.of("code", "${cleanCode}.col.code", FormFieldType.TEXT)
                            .asRequired()
                            .length(1, 64)
                            .matching("[a-z0-9_-]+"),
                    FormField.select("status", "${cleanCode}.col.status", STATUSES, "${cleanCode}.status_")),
            List.of(new FormSection("main", "entity.section.main", List.of("name", "code", "status"))),
            List.of(
                    new EntityAction("create", "create"),
                    new EntityAction("update", "update"),
                    new EntityAction("delete", "delete")),
            Set.of(
                    EntityCapability.SAVED_VIEWS,
                    EntityCapability.EXPORT,
                    EntityCapability.HISTORY,
                    EntityCapability.BULK));

    /** Named apart from the bean of this configuration class itself (${prefixLower}${capitalName}Entity). */
    @Bean
    public EntityDefinition ${prefixLower}${capitalName}Definition() {
        return DEFINITION;
    }

    /** What only the module knows: who sees a record, the list page and the single delete. */
    @Bean
    public EntityRecords ${prefixLower}${capitalName}Records(${serviceClass} service) {
        return new EntityRecords() {
            @Override
            public String entity() {
                return DEFINITION.code();
            }

            @Override
            public void requireVisible(long id) {
                service.getItem(id);
            }

            @Override
            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search) {
                return service.page(limit, cursor, filter, sort, search);
            }

            @Override
            public void delete(long id) {
                service.deleteItem(id);
            }
        };
    }
}
"@

Write-Utf8 (Join-Path $serviceDir "${serviceClass}.java") @"
package ${pkg}.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityValidator;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import ${pkg}.api.${viewClass};
import ${pkg}.repository.${repoClass};
import ${pkg}.repository.${repoClass}.ItemRecord;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saves are checked by the entity declaration (${entityClass}); every change is audited under $tableName. Errors are
 * ApiException with a catalog key (ADR-0021); a change names the revision it was made from (ADR-0024).
 */
@Service
public class ${serviceClass} {

    private final ${repoClass} repository;
    private final AuditLogService auditLogService;

    public ${serviceClass}(${repoClass} repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    /** A page of the list ($listCode): filter, sort, search and the keyset cursor; the total as the list reports it. */
    @Transactional(readOnly = true)
    public KeysetPage<${viewClass}> page(Integer limit, String cursor, String filter, String sort, String search) {
        var page = repository.page(QueryCompiler.compile(${queryClass}.LIST, filter, sort, limit, cursor, search));
        return new KeysetPage<>(
                page.items().stream().map(${serviceClass}::view).toList(),
                page.nextCursor(),
                page.hasMore(),
                page.totalEstimated(),
                page.totalExact());
    }

    @Transactional(readOnly = true)
    public ${viewClass} getItem(Long id) {
        return view(find(id));
    }

    @Transactional
    public ${viewClass} createItem(String name, String code, Map<String, Object> attributes, Long userId) {
        EntityValidator.check(${entityClass}.DEFINITION, values(name, code, null), false);
        var item = repository.create(name.trim(), code, attributes, userId);
        auditLogService.logChange(
                "$tableName",
                String.valueOf(item.id()),
                "I",
                List.of("name", "code", "status"),
                null,
                Map.of("name", item.name(), "code", item.code(), "status", item.status()));
        return view(item);
    }

    @Transactional
    public ${viewClass} updateItem(
            Long id, String name, String status, Map<String, Object> attributes, Long userId, long expectedRevision) {
        var existing = find(id);
        EntityValidator.check(${entityClass}.DEFINITION, values(name, null, status), true);
        var updated =
                repository.update(id, name == null ? null : name.trim(), status, attributes, userId, expectedRevision);
        auditLogService.logChange(
                "$tableName",
                String.valueOf(id),
                "U",
                List.of("name", "status"),
                Map.of("name", existing.name(), "status", existing.status()),
                Map.of("name", updated.name(), "status", updated.status()));
        return view(updated);
    }

    @Transactional
    public void deleteItem(Long id) {
        var item = find(id);
        repository.delete(id);
        auditLogService.logChange(
                "$tableName",
                String.valueOf(id),
                "D",
                List.of("name", "code"),
                Map.of("name", item.name(), "code", item.code()),
                null);
    }

    private ItemRecord find(Long id) {
        return repository
                .findById(id)
                .orElseThrow(
                        () -> ApiException.notFound(ErrorCode.NOT_FOUND, "$notFoundKey", Map.of("id", id)));
    }

    private static ${viewClass} view(ItemRecord r) {
        return new ${viewClass}(
                r.id(),
                r.name(),
                r.code(),
                r.status(),
                r.attributes(),
                r.createdBy(),
                r.createdAt(),
                r.modifiedAt(),
                r.revision());
    }

    /** The declared fields a save carries; an absent one (null) is left out, so an update keeps it. */
    private static Map<String, Object> values(String name, String code, String status) {
        Map<String, Object> values = new HashMap<>();
        if (name != null) values.put("name", name);
        if (code != null) values.put("code", code);
        if (status != null) values.put("status", status);
        return values;
    }
}
"@

Write-Utf8 (Join-Path $ctrlDir "${ctrlClass}.java") @"
package ${pkg}.controller;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.annotation.RequiresPermission;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.common.web.Created;
import com.smartup24.cms.instance.common.web.Revisions;
import ${pkg}.api.${createClass};
import ${pkg}.api.${updateClass};
import ${pkg}.api.${viewClass};
import ${pkg}.service.${serviceClass};
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The $cleanCode records (ADR-0023): a keyset list, 201 with Location on create, If-Match on change, 204 on delete. */
@RestController
@RequestMapping("/api/v1/$cleanCode")
public class ${ctrlClass} {

    private final ${serviceClass} service;

    public ${ctrlClass}(${serviceClass} service) {
        this.service = service;
    }

    /** The list through the field registry: filter (JSON DSL), sort, search q and the keyset cursor. */
    @GetMapping
    @RequiresPermission(form = "$cleanCode", action = "view")
    public ResponseEntity<KeysetPage<${viewClass}>> page(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) String filter,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String q) {
        return ResponseEntity.ok(service.page(limit, cursor, filter, sort, q));
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = "$cleanCode", action = "view")
    public ResponseEntity<${viewClass}> getItem(@PathVariable Long id) {
        return ResponseEntity.ok(service.getItem(id));
    }

    @PostMapping
    @RequiresPermission(form = "$cleanCode", action = "create")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<${viewClass}> createItem(@RequestBody ${createClass} body) {
        ${viewClass} item =
                service.createItem(body.name(), body.code(), body.attributes(), SecurityContext.getCurrentUserId());
        return Created.at("/api/v1/$cleanCode/{id}", item.id(), item);
    }

    @PutMapping("/{id}")
    @RequiresPermission(form = "$cleanCode", action = "update")
    public ResponseEntity<${viewClass}> updateItem(
            @PathVariable Long id,
            @RequestHeader(name = Revisions.IF_MATCH, required = false) String ifMatch,
            @RequestBody ${updateClass} body) {
        return ResponseEntity.ok(service.updateItem(
                id,
                body.name(),
                body.status(),
                body.attributes(),
                SecurityContext.getCurrentUserId(),
                Revisions.required(ifMatch)));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = "$cleanCode", action = "delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public ResponseEntity<Void> deleteItem(@PathVariable Long id) {
        service.deleteItem(id);
        return ResponseEntity.noContent().build();
    }
}
"@

Write-Host "-> Java: $pkg (api, repository, service, controller)" -ForegroundColor Yellow

# 3. Catalog keys in ru/uz/en: the error key is required by ErrorTextsTest; the labels by the screen
$labels = [ordered]@{
    "nav.$cleanCode"                 = @{ ru = $ModuleTitle; en = $TitleEn; uz = $TitleUz }
    "$notFoundKey"                   = @{ ru = "Запись не найдена: {id}"; en = "Record not found: {id}"; uz = "Yozuv topilmadi: {id}" }
    "${cleanCode}.col.name"          = @{ ru = "Название"; en = "Name"; uz = "Nomi" }
    "${cleanCode}.col.code"          = @{ ru = "Код"; en = "Code"; uz = "Kod" }
    "${cleanCode}.col.status"        = @{ ru = "Статус"; en = "Status"; uz = "Holat" }
    "${cleanCode}.col.modified_at"   = @{ ru = "Изменено"; en = "Modified"; uz = "Oʻzgartirilgan" }
    "${cleanCode}.status_active"     = @{ ru = "Действует"; en = "Active"; uz = "Faol" }
    "${cleanCode}.status_archived"   = @{ ru = "В архиве"; en = "Archived"; uz = "Arxivda" }
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
Write-Host "  4. Screen: a route to /$cleanCode with smt-entity-form, smt-entity-card and smt-entity-toolbar; PUT sends ifMatch: revision"
Write-Host "  5. Tests as for notes (MsNoteControllerTest); a growing table: MigrationLintTest.LARGE_TABLES, withEstimatedTotal(), a RetentionPolicy"
Write-Host "  6. mvn -B verify (Checkstyle, Spotless, architecture tests); scripts/dev/test-create-module.ps1 checks this generator"
