<#
.SYNOPSIS
    SmartupCMS Module Scaffolding Generator
.DESCRIPTION
    Автоматически генерирует стандартизированный каркас нового доменного модуля в архитектуре модульного монолита:
    1. Flyway SQL-миграцию со стандартными полями аудита, версионирования и JSONB-атрибутами.
    2. Java-модуль из пяти файлов как объявленная сущность (ADR-0019): Repository, Service, Controller,
       список реестра полей (Query) и объявление сущности (Entity) с бином записей. Из объявления
       платформа даёт форму (form-meta), проверку сохранения, названия прав, пункт меню, историю,
       экспорт, сохранённые виды и массовое удаление.
    3. Регистрацию в реестре модулей md_installed_modules и выдачу прав системным ролям.
    Экран и ключи переводов — по чек-листу docs/guidelines/module-development-guide.md.
.PARAMETER ModuleName
    Кодовое имя модуля (латиница в нижнем регистре, например: inventory, crm, wiki)
.PARAMETER ModuleTitle
    Читаемое название модуля на русском (например: "Управление складом")
.PARAMETER ModuleDescription
    Краткое описание назначения модуля
.PARAMETER Icon
    Иконка интерфейса (например: package, bookmark, briefcase, box)
.EXAMPLE
    .\scripts\dev\create-module.ps1 -ModuleName "inventory" -ModuleTitle "Складской учет" -ModuleDescription "Управление остатками и номенклатурой" -Icon "package"
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [string]$ModuleName,

    [Parameter(Mandatory=$true)]
    [string]$ModuleTitle,

    [Parameter(Mandatory=$false)]
    [string]$ModuleDescription = "Пользовательский модуль системы",

    [Parameter(Mandatory=$false)]
    [string]$Icon = "box",

    [Parameter(Mandatory=$false)]
    [string]$Prefix = "ms"
)

$ErrorActionPreference = "Stop"

# UTF-8 without a BOM: javac refuses one, and Set-Content -Encoding UTF8 in Windows PowerShell writes it.
function Write-Utf8([string]$Path, [string]$Content) {
    [System.IO.File]::WriteAllText($Path, $Content, (New-Object System.Text.UTF8Encoding $false))
}
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")

$cleanCode = $ModuleName.ToLower().Trim()
$capitalName = (Get-Culture).TextInfo.ToTitleCase($cleanCode)
$prefixUpper = (Get-Culture).TextInfo.ToTitleCase($Prefix.ToLower())
$prefixLower = $Prefix.ToLower()
$tableName = "${prefixLower}_${cleanCode}"

Write-Host "=== Генератор модулей SmartupCMS ===" -ForegroundColor Cyan
Write-Host "Модуль: $cleanCode ($capitalName)"
Write-Host "Заголовок: $ModuleTitle"
Write-Host "Таблица БД: $tableName"
Write-Host "Директория проекта: $Root"

# 1. Определение следующего номера миграции Flyway
$migrationDir = Join-Path $Root "apps\server\src\main\resources\db\migration"
$existingMigrations = Get-ChildItem -Path $migrationDir -Filter "V*.sql" | Sort-Object Name
$lastNum = 0
foreach ($m in $existingMigrations) {
    if ($m.Name -match "^V0*(\d+)__") {
        $num = [int]$matches[1]
        if ($num -gt $lastNum) { $lastNum = $num }
    }
}
$nextNum = $lastNum + 1
$nextNumStr = "V" + $nextNum.ToString("D3")
$migrationFile = Join-Path $migrationDir "${nextNumStr}__${cleanCode}_module.sql"

Write-Host "-> Создание миграции: $migrationFile" -ForegroundColor Yellow

$sqlContent = @"
-- ============================================================================
-- ${nextNumStr}: Модуль ${ModuleTitle} (${cleanCode})
-- ============================================================================

create table if not exists ${tableName} (
    id bigserial primary key,
    name varchar(255) not null,
    code varchar(64) not null,
    status varchar(32) not null default 'ACTIVE',
    attributes jsonb not null default '{}'::jsonb,
    created_by bigint not null references md_users(id),
    modified_by bigint not null references md_users(id),
    created_at timestamptz not null default clock_timestamp(),
    modified_at timestamptz not null default clock_timestamp()
);

create unique index if not exists idx_${tableName}_code on ${tableName}(lower(code));
create index if not exists idx_${tableName}_owner on ${tableName}(created_by, id desc);

-- Регистрация формы в каталоге RBAC
insert into md_forms (code, module, name) values
('${cleanCode}', '${prefixLower}', '${ModuleTitle}')
on conflict (code) do nothing;

insert into md_form_actions (form_code, action, name) values
('${cleanCode}', 'view', 'Просмотр записей'),
('${cleanCode}', 'create', 'Создание записей'),
('${cleanCode}', 'update', 'Редактирование записей'),
('${cleanCode}', 'delete', 'Удаление записей')
on conflict (form_code, action) do nothing;

-- Права системным ролям
insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, fa.action
from md_roles r
cross join md_form_actions fa
where r.pcode in ('admin', 'manager') and fa.form_code = '${cleanCode}'
on conflict do nothing;

insert into md_role_permissions (role_id, form_code, action)
select r.id, fa.form_code, 'view'
from md_roles r
cross join md_form_actions fa
where r.pcode in ('user', 'auditor') and fa.form_code = '${cleanCode}' and fa.action = 'view'
on conflict do nothing;

-- Регистрация в реестре модулей
insert into md_installed_modules (code, name, description, version, icon, route, is_system, status, sort_order) values
('${cleanCode}', '${ModuleTitle}', '${ModuleDescription}', '1.0.0', '${Icon}', '/${cleanCode}', false, 'ACTIVE', 100)
on conflict (code) do update set
    name = excluded.name,
    description = excluded.description,
    icon = excluded.icon,
    route = excluded.route;
"@

Write-Utf8 $migrationFile $sqlContent

# 2. Java: five files, as a declared entity (ADR-0019, roadmap item 57)
#    repository, service, controller, the list (QueryList) and the entity declaration with its records bean.
#    The declaration gives the form (form-meta), the rules the service checks, the right's names in the
#    permission matrix, the menu item, history, export, saved views and bulk delete.
$javaBase = Join-Path $Root "apps\server\src\main\java\com\greenwhite\dwh\instance\${prefixLower}\${cleanCode}"
$repoDir = Join-Path $javaBase "repository"
$serviceDir = Join-Path $javaBase "service"
$ctrlDir = Join-Path $javaBase "controller"

New-Item -ItemType Directory -Force -Path $repoDir | Out-Null
New-Item -ItemType Directory -Force -Path $serviceDir | Out-Null
New-Item -ItemType Directory -Force -Path $ctrlDir | Out-Null

$repoClass = "${prefixUpper}${capitalName}Repository"
$serviceClass = "${prefixUpper}${capitalName}Service"
$ctrlClass = "${prefixUpper}${capitalName}Controller"
$queryClass = "${prefixUpper}${capitalName}Query"
$entityClass = "${prefixUpper}${capitalName}Entity"
$listCode = "${prefixLower}.${cleanCode}"
$pkg = "com.greenwhite.dwh.instance.${prefixLower}.${cleanCode}"

# Repository
$repoFile = Join-Path $repoDir "${repoClass}.java"
$repoContent = @"
package ${pkg}.repository;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.query.QueryListRepository;
import com.greenwhite.dwh.instance.common.query.QueryPlan;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Repository
public class ${repoClass} {

    public static final String COLUMNS = """
            t.id, t.name, t.code, t.status, t.attributes::text as attributes_str,
            t.created_by, t.modified_by, t.created_at, t.modified_at""";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public ${repoClass}(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public record ItemRecord(Long id, String name, String code, String status, Map<String, Object> attributes,
                             Long createdBy, Long modifiedBy, Instant createdAt, Instant modifiedAt) {
    }

    /** A page of the list through the field registry (${listCode}). */
    public KeysetPage<ItemRecord> page(QueryPlan plan) {
        return new QueryListRepository(jdbcClient).page(plan, this::mapItem);
    }

    public Optional<ItemRecord> findById(Long id) {
        return jdbcClient.sql("select " + COLUMNS + " from ${tableName} t where t.id = :id")
                .param("id", id)
                .query(this::mapItem)
                .optional();
    }

    public ItemRecord create(String name, String code, Map<String, Object> attributes, Long userId) {
        return jdbcClient.sql("""
                insert into ${tableName} as t (name, code, status, attributes, created_by, modified_by, created_at, modified_at)
                values (:name, :code, 'ACTIVE', cast(:attributes as jsonb), :userId, :userId, clock_timestamp(), clock_timestamp())
                returning\s""" + COLUMNS)
                .param("name", name)
                .param("code", code.toLowerCase().trim())
                .param("attributes", toJson(attributes))
                .param("userId", userId)
                .query(this::mapItem)
                .single();
    }

    /** Changes the fields given; a null one keeps its value. */
    public ItemRecord update(Long id, String name, String status, Map<String, Object> attributes, Long userId) {
        return jdbcClient.sql("""
                update ${tableName} t
                set name = coalesce(:name, t.name),
                    status = coalesce(:status, t.status),
                    attributes = coalesce(cast(:attributes as jsonb), t.attributes),
                    modified_by = :userId,
                    modified_at = clock_timestamp()
                where t.id = :id
                returning\s""" + COLUMNS)
                .param("id", id)
                .param("name", name)
                .param("status", status)
                .param("attributes", attributes == null ? null : toJson(attributes))
                .param("userId", userId)
                .query(this::mapItem)
                .single();
    }

    public boolean delete(Long id) {
        return jdbcClient.sql("delete from ${tableName} where id = :id").param("id", id).update() > 0;
    }

    private ItemRecord mapItem(ResultSet rs, int rowNum) throws SQLException {
        return new ItemRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("status"),
                parseJson(rs.getString("attributes_str")),
                rs.getLong("created_by"),
                rs.getLong("modified_by"),
                rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant() : null,
                rs.getTimestamp("modified_at") != null ? rs.getTimestamp("modified_at").toInstant() : null);
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try { return objectMapper.writeValueAsString(map); } catch (Exception e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json, Map.class); } catch (Exception e) { return Map.of(); }
    }
}
"@
Write-Utf8 $repoFile $repoContent

# The list in the field registry
$queryFile = Join-Path $serviceDir "${queryClass}.java"
$queryContent = @"
package ${pkg}.service;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import ${pkg}.repository.${repoClass};
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** The ${cleanCode} list in the field registry (ADR-0016): GET /api/v1/${cleanCode} and /api/v1/query-meta/${listCode}. */
@Configuration
public class ${queryClass} {

    public static final QueryList LIST = new QueryList(
            "${listCode}",
            "${cleanCode}",
            "view",
            ${repoClass}.COLUMNS,
            "${tableName} t",
            "t.id",
            List.of(
                    QueryField.of("name", "${cleanCode}.col.name", QueryFieldType.TEXT, "t.name").asSortable().asSearchable(),
                    QueryField.of("code", "${cleanCode}.col.code", QueryFieldType.TEXT, "t.code").asSortable().asSearchable(),
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
Write-Utf8 $queryFile $queryContent

# The entity declaration and its records
$entityFile = Join-Path $serviceDir "${entityClass}.java"
$entityContent = @"
package ${pkg}.service;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.entity.EntityCapability;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.EntityAction;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.EntityMenu;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.EntityRights;
import com.greenwhite.dwh.instance.common.entity.EntityDefinition.FormSection;
import com.greenwhite.dwh.instance.common.entity.EntityRecords;
import com.greenwhite.dwh.instance.common.entity.FormField;
import com.greenwhite.dwh.instance.common.entity.FormFieldType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ${cleanCode} entity, declared once (ADR-0019): its form and rules (GET /api/v1/form-meta/${listCode}),
 * the names of its right, its menu item, and history, export, saved views and bulk delete. Add a field here
 * and in the list and the repository; add custom fields with CUSTOM_FIELDS and an entity type.
 */
@Configuration
public class ${entityClass} {

    public static final List<String> STATUSES = List.of("ACTIVE", "ARCHIVED");

    public static final EntityDefinition DEFINITION = new EntityDefinition(
            ${queryClass}.LIST.code(),
            "${cleanCode}",
            ${queryClass}.LIST.code(),
            null,
            "${tableName}",
            new EntityRights("${prefixLower}", "${ModuleTitle}", Map.of(
                    "view", "View",
                    "create", "Create",
                    "update", "Edit",
                    "delete", "Delete")),
            new EntityMenu("/${cleanCode}", "nav.${cleanCode}", "${Icon}", "workspace", 100, "${cleanCode}"),
            List.of(
                    FormField.of("name", "${cleanCode}.col.name", FormFieldType.TEXT).asRequired().length(1, 255),
                    FormField.of("code", "${cleanCode}.col.code", FormFieldType.TEXT).asRequired().length(1, 64)
                            .matching("[a-z0-9_-]+"),
                    FormField.select("status", "${cleanCode}.col.status", STATUSES, "${cleanCode}.status_")),
            List.of(new FormSection("main", "entity.section.main", List.of("name", "code", "status"))),
            List.of(
                    new EntityAction("create", "create"),
                    new EntityAction("update", "update"),
                    new EntityAction("delete", "delete")),
            Set.of(EntityCapability.SAVED_VIEWS, EntityCapability.EXPORT, EntityCapability.HISTORY, EntityCapability.BULK));

    @Bean
    public EntityDefinition ${prefixLower}${capitalName}Entity() {
        return DEFINITION;
    }

    /** What only the module knows: who sees a record, the list page and the single delete. */
    @Bean
    public EntityRecords ${prefixLower}${capitalName}Records(${serviceClass} service) {
        return new EntityRecords() {
            public String entity() { return DEFINITION.code(); }

            public void requireVisible(long id) {
                service.getItem(id);
            }

            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search) {
                return service.page(limit, cursor, filter, sort, search);
            }

            public void delete(long id) {
                service.deleteItem(id);
            }
        };
    }
}
"@
Write-Utf8 $entityFile $entityContent

# Service
$serviceFile = Join-Path $serviceDir "${serviceClass}.java"
$serviceContent = @"
package ${pkg}.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.common.entity.EntityValidator;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import ${pkg}.repository.${repoClass};
import ${pkg}.repository.${repoClass}.ItemRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Saves are checked by the entity declaration (${entityClass}); every change is audited under ${tableName}. */
@Service
public class ${serviceClass} {

    private final ${repoClass} repository;
    private final AuditLogService auditLogService;

    public ${serviceClass}(${repoClass} repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    public record ItemView(Long id, String name, String code, String status, Map<String, Object> attributes,
                           Long createdBy, Instant createdAt, Instant modifiedAt) {
        public static ItemView from(ItemRecord r) {
            return new ItemView(r.id(), r.name(), r.code(), r.status(), r.attributes(), r.createdBy(), r.createdAt(),
                    r.modifiedAt());
        }
    }

    @Transactional(readOnly = true)
    public KeysetPage<ItemView> page(Integer limit, String cursor, String filter, String sort, String search) {
        var page = repository.page(QueryCompiler.compile(${queryClass}.LIST, filter, sort, limit, cursor, search));
        return new KeysetPage<>(page.items().stream().map(ItemView::from).toList(), page.nextCursor(), page.hasMore(),
                page.totalEstimated());
    }

    @Transactional(readOnly = true)
    public ItemView getItem(Long id) {
        return repository.findById(id).map(ItemView::from)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Record not found: " + id));
    }

    @Transactional
    public ItemView createItem(String name, String code, Map<String, Object> attributes, Long userId) {
        EntityValidator.check(${entityClass}.DEFINITION, values(name, code, null), false);
        var item = repository.create(name.trim(), code, attributes, userId);
        auditLogService.logChange("${tableName}", String.valueOf(item.id()), "I", List.of("name", "code", "status"),
                null, Map.of("name", item.name(), "code", item.code(), "status", item.status()));
        return ItemView.from(item);
    }

    @Transactional
    public ItemView updateItem(Long id, String name, String status, Map<String, Object> attributes, Long userId) {
        var existing = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Record not found: " + id));
        EntityValidator.check(${entityClass}.DEFINITION, values(name, null, status), true);
        var updated = repository.update(id, name == null ? null : name.trim(), status, attributes, userId);
        auditLogService.logChange("${tableName}", String.valueOf(id), "U", List.of("name", "status"),
                Map.of("name", existing.name(), "status", existing.status()),
                Map.of("name", updated.name(), "status", updated.status()));
        return ItemView.from(updated);
    }

    @Transactional
    public void deleteItem(Long id) {
        var item = repository.findById(id)
                .orElseThrow(() -> ApiException.notFound(ErrorCode.NOT_FOUND, "Record not found: " + id));
        repository.delete(id);
        auditLogService.logChange("${tableName}", String.valueOf(id), "D", List.of("name", "code"),
                Map.of("name", item.name(), "code", item.code()), null);
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
Write-Utf8 $serviceFile $serviceContent

# Controller
$ctrlFile = Join-Path $ctrlDir "${ctrlClass}.java"
$ctrlContent = @"
package ${pkg}.controller;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import ${pkg}.service.${serviceClass};
import ${pkg}.service.${serviceClass}.ItemView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/${cleanCode}")
public class ${ctrlClass} {

    private final ${serviceClass} service;

    public ${ctrlClass}(${serviceClass} service) {
        this.service = service;
    }

    public record CreateRequest(String name, String code, Map<String, Object> attributes) {}

    public record UpdateRequest(String name, String status, Map<String, Object> attributes) {}

    /** The list through the field registry: filter (JSON DSL), sort, search q and the keyset cursor. */
    @GetMapping
    @RequiresPermission(form = "${cleanCode}", action = "view")
    public ResponseEntity<KeysetPage<ItemView>> page(@RequestParam(required = false) Integer limit,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(required = false) String filter,
                                                     @RequestParam(required = false) String sort,
                                                     @RequestParam(required = false) String q) {
        return ResponseEntity.ok(service.page(limit, cursor, filter, sort, q));
    }

    @GetMapping("/{id}")
    @RequiresPermission(form = "${cleanCode}", action = "view")
    public ResponseEntity<ItemView> getItem(@PathVariable Long id) {
        return ResponseEntity.ok(service.getItem(id));
    }

    @PostMapping
    @RequiresPermission(form = "${cleanCode}", action = "create")
    public ResponseEntity<ItemView> createItem(@RequestBody CreateRequest body) {
        return ResponseEntity.ok(service.createItem(body.name(), body.code(), body.attributes(),
                SecurityContext.getCurrentUserId()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(form = "${cleanCode}", action = "update")
    public ResponseEntity<ItemView> updateItem(@PathVariable Long id, @RequestBody UpdateRequest body) {
        return ResponseEntity.ok(service.updateItem(id, body.name(), body.status(), body.attributes(),
                SecurityContext.getCurrentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(form = "${cleanCode}", action = "delete")
    public ResponseEntity<Void> deleteItem(@PathVariable Long id) {
        service.deleteItem(id);
        return ResponseEntity.noContent().build();
    }
}
"@
Write-Utf8 $ctrlFile $ctrlContent

Write-Host "-> Created: ${pkg} (5 files: repository, service, controller, ${queryClass}, ${entityClass})" -ForegroundColor Green
Write-Host "Next (docs/guidelines/module-development-guide.md, checklist):" -ForegroundColor Cyan
Write-Host "  1. Label keys in apps/server/src/main/resources/i18n (ru, en): nav.${cleanCode}, ${cleanCode}.col.*, ${cleanCode}.status_*"
Write-Host "  2. Screen: a route to /${cleanCode} with smt-entity-form, smt-entity-card and smt-entity-toolbar"
Write-Host "  3. mvn verify: the entity contract tests check the declaration's rights against @RequiresPermission"
