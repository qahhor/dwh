# Graph Report - dwh  (2026-08-31)

## Corpus Check
- 435 files · ~206,259 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3772 nodes · 9539 edges · 201 communities (166 shown, 35 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 1265 edges (avg confidence: 0.82)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `724630c2`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- org.junit.jupiter.api.Test
- ToastService
- MfFileRepository
- .checkSession
- auth.ts
- AuditLogService
- app-shell.component.ts
- UsersComponent
- TasksComponent
- dependencies
- ErrorCode
- options
- RolesComponent
- org.springframework.stereotype.Component
- AppShellComponent
- MdRoleRepository
- V001__init_schema.sql
- options
- dependencies
- UiUserMultiSelectComponent
- MsTaskService.java
- .getCurrentUserId
- CustomFieldRecord
- CpFleetRepository
- MsNotificationService
- MdScopeService
- jakarta.servlet.http.HttpServletResponse
- MdCustomFieldController
- KauthOtpLoginIntegrationTest.java
- FR-NOTIF: оповещения (in-app, email, SMS, Telegram, объявления)
- IdempotencyService
- Поток B — платформа (критический путь)
- Матрица точных результатов Этапа 1 (разд. 8.2)
- .doFilterInternal
- MdOrgUnitRepository
- ADR-0006: Modular Monolith
- FR-CP: control plane (реестр, heartbeat, лицензии, объявления, бэкапы)
- e2e/package.json
- org.springframework.http.ResponseEntity
- KauthPasswordHasher
- FilesComponent
- compilerOptions
- compilerOptions
- CpSecurityContext
- .success
- CpSecurityConfig.java
- UI/UX hardening for DWH web applications
- MdPermissionRepository
- jakarta.servlet.http.HttpServletRequest
- RequiresPermission
- Control Plane
- tasks.component.ts
- org.springframework.jdbc.core.simple.JdbcClient
- Provider SPI Pattern (in-tree adapters, config-selected)
- RB-04: Диагностика и устранение сбоев миграций Flyway
- Production Launch Checklist — критерии go/no-go
- KwhWebhookService
- TypesenseProperties
- DWH Platform (product overview)
- MsProjectController
- MsOutboxRepository
- Milestone Catalog M1 to M18
- InstanceBootstrap
- Override
- GlobalExceptionHandler.java
- MsSseRegistry
- RateLimitService
- cp-api.service.ts
- Generic Audit Trigger into Single audit_log Table
- Materialized effective_permissions Table
- FR-TASK: мини таск-менеджер
- ProviderHealth
- TypesenseClient
- UiPaginationComponent
- FR-PERM: доступ / RBAC (формы, действия, роли, эффективные права)
- CpUserRepository
- 4. Дефекты
- KauthSessionController
- MdFormCatalogTest.java
- V001__init_cp_schema.sql
- KwhSubscriptionController
- CpApiService
- Module Prefix Catalog (md, kauth, ms, mf, audit, cp)
- MdUserController
- Full Dev Compose Stack (SmartupCMS group)
- MdPermissionService
- UiSearchableSelectComponent
- FR-SEC: безопасность (CSRF, лимиты, Vault, SCA/SBOM, заголовки)
- V014__audit_log_immutable.sql
- web-cp/src/app/app.routes.ts
- AnnouncementsComponent
- Garage S3-Compatible Storage (MinIO rejected)
- PostgreSQL 18
- HashiCorp Nomad + Consul + Vault Orchestration
- Migrations as Separate Batch Job + Schema Version Gate
- Instance Service Config (port 8080, Hikari, actuator 9090, log pattern)
- AuditPartitionRepository
- NotificationChannelTest.java
- org.springframework.web.bind.annotation.GetMapping
- KauthAuthController
- web-instance/tsconfig.app.json
- Delivery Entry M3: Authentication
- Production Single-Client Compose Stack
- Divergence Register D-1..D-15
- CI Job: backend (mvn verify + ArchUnit + SBOM)
- KauthSessionRepository
- CpAnnouncementRepository
- InstanceApplication
- 2. Решение
- KauthSecurityContext
- org.springframework.transaction.annotation.Transactional
- Project Statistics Map
- MsTaskService
- ui-toast.component.ts
- AuditComponent
- AUDIT-03: Production Readiness Verification
- ResourceProfile.java
- FleetComponent
- errorText
- SmsMessage
- FR-AUD: аудит и журналы (audit_log, security-события, retention)
- Language
- MfFileController.java
- Monorepo Structure and Entry Points
- NotificationService
- operational-pages.spec.ts
- UiMarkdownEditorComponent
- client-demo.nomad.hcl
- CEO Rule: Deepen, Do Not Expand Scope
- Graphify Knowledge Graph Workflow (agent rules)
- V007__dynamic_task_statuses_and_types.sql
- 01-check-cluster.sh
- 02-deploy-client.sh
- 03-migrate.sh
- 04-canary-update.sh
- 05-drill-broken.sh
- setup.sh
- ISO 27001 Certification Deliberately Out of Scope
- Мобильная адаптивность и touch-интерфейс (360–768px)
- com.greenwhite.dwh:dwh-platform
- control-plane
- core-types
- instance
- provider-spi
- Browser E2E implementation plan
- File structure locked by this plan
- CpHeartbeatWorker.java
- Browser E2E testing design
- AUDIT-05: Финальный DevOps-аудит и Production Readiness Assessment
- MdSettingService
- backup.sh script
- CachedBodyHttpServletRequest
- deploy.sh script
- restore.sh script
- devDependencies
- verify-artifact-security.mjs
- KauthPasswordController
- SmartupCMS browser E2E
- AUDIT-06. Пересмотр модуля M3 «Авторизация и аутентификация»
- env.d.mts
- web-cp/tsconfig.app.json
- ProjectsComponent
- .preHandle
- UI Feature Screens Hardening Plan
- UI/UX audit — 2026-08-30
- fleet.component.ts
- AuditLogRepository
- pages/login.component.ts
- scripts
- web-instance/package.json
- @angular/animations
- @angular/material
- @angular/common
- @angular/compiler
- @angular/core
- rxjs
- UiMarkdownViewComponent

## God Nodes (most connected - your core abstractions)
1. `RequiresPermission` - 129 edges
2. `ErrorCode` - 85 edges
3. `AuditLogService` - 71 edges
4. `TasksComponent` - 70 edges
5. `ToastService` - 53 edges
6. `ApiException` - 52 edges
7. `ApiService` - 48 edges
8. `MdPermissionService` - 47 edges
9. `MsTaskService` - 47 edges
10. `MdUserRepository` - 45 edges

## Surprising Connections (you probably didn't know these)
- `Red-Job-Blocks-Merge Policy (FR-SEC-4, FR-MOD-2)` --semantically_similar_to--> `SchemaVersionGate (fail-closed startup schema check)`  [INFERRED] [semantically similar]
  .github/workflows/ci.yml → MILESTONES.md
- `fazo (in-database runtime library of Biruni)` --semantically_similar_to--> `Technology Stack and Top Dependencies`  [INFERRED] [semantically similar]
  docs/adr/ADR-0001-architecture-model.md → STATS_MAP.md
- `Live E2E Smoke Suite (scripts/dev/test-api.ps1, 15 scenarios)` --references--> `dev service: app (instance backend, ZGC JVM opts)`  [INFERRED]
  REPORT.md → docker-compose.yml
- `PII-Free Structured JSON Logging` --semantically_similar_to--> `M2. Users and Profiles (USR)`  [INFERRED] [semantically similar]
  CODE_STYLE.md → MILESTONES.md
- `Test Coverage and Verification` --conceptually_related_to--> `Delivery Entry M3: Authentication`  [AMBIGUOUS]
  STATS_MAP.md → REPORT.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **Domain Invariant Set Enforced in Aggregates** — docs_adr_adr_0006_modular_monolith_task_aggregate, docs_adr_adr_0006_modular_monolith_effective_permissions_aggregate, docs_adr_adr_0006_modular_monolith_user_aggregate, docs_adr_adr_0006_modular_monolith_notify_queue_invariants, docs_guidelines_testing_strategy_unit_invariant_tests [EXTRACTED 1.00]
- **Fleet Lifecycle Mechanism (orchestrate, migrate, roll out, verify, track drift)** — docs_adr_adr_0007_fleet_strategy_nomad_orchestration, docs_adr_adr_0007_fleet_strategy_migration_batch_job, docs_adr_adr_0007_fleet_strategy_deployment_rings, docs_adr_adr_0007_fleet_strategy_backup_verification, docs_adr_adr_0007_fleet_strategy_version_drift_control, docs_adr_adr_0004_deployment_model_control_plane [EXTRACTED 1.00]
- **Цепочка бэкап → восстановление → проверка RPO** — docs_trd_trd_01_cms_nfr_7_backups, docs_ops_maintenance_guide_backup_policy, docs_ops_maintenance_guide_restore_verification, docs_ops_rollback_backup_restore_path, docs_runbooks_rb_01_node_failure_recovery_walg_restore, docs_plan_m0_plan_t024_backup_restore [INFERRED 0.85]
- **Domain concepts inherited from Biruni (model kept, mechanism rewritten)** — docs_adr_adr_0001_architecture_model_form_action_permission_model, docs_adr_adr_0001_architecture_model_effective_permissions_materialization, docs_adr_adr_0001_architecture_model_jsonb_generic_audit, readme_biruni_domain_model_inheritance, milestones_m4_rbac, milestones_m8_audit, code_style_module_prefix_convention [INFERRED 0.85]
- **CI Enforcement Gates (arch, security, migrations, style)** — docs_guidelines_testing_strategy_archunit_rules, docs_adr_adr_0008_security_baseline_sca_sbom_ci, docs_adr_adr_0007_fleet_strategy_migration_linter, docs_adr_adr_0012_ui_foundation_design_tokens, docs_adr_adr_0008_security_baseline_safe_sql [INFERRED 0.85]
- **CI Quality Gate: all jobs must be green to merge** — _github_workflows_ci_backend, _github_workflows_ci_frontend, _github_workflows_ci_security, _github_workflows_ci_merge_block_policy, contributing_ci_quality_gate, contributing_definition_of_done [INFERRED 0.85]
- **Жизненный цикл лицензии экземпляра** — docs_trd_trd_01_cms_fr_inst_4, docs_trd_trd_04_api_license_validation, docs_runbooks_rb_03_license_key_emergency_rotation_kid_rotation, docs_trd_trd_03_flows_f11_license_expiry_read_only, docs_trd_trd_01_cms_fr_cp [INFERRED 0.85]
- **Контур защиты миграций и старта приложения** — docs_ops_deployment_guide_migration_step, docs_ops_deployment_guide_schema_gate, docs_trd_trd_01_cms_nfr_10_migrations, docs_runbooks_rb_04_migration_failure_triage_rb04, docs_plan_remediation_plan_r4_migration_separation, docs_ops_rollback_expand_contract [INFERRED 0.85]
- **Migrations as a separate step guarded by the schema gate (NFR-10)** — apps_instance_src_main_resources_application_migrate_migrate_profile, apps_control_plane_src_main_resources_application_migrate_migrate_profile, docker_compose_migrate, docker_compose_migrate_cp, deploy_compose_docker_compose_prod_migrate, milestones_schema_version_gate, deploy_spike_readme_broken_migration_drill [INFERRED 0.85]

## Communities (201 total, 35 thin omitted)

### Community 0 - "org.junit.jupiter.api.Test"
Cohesion: 0.05
Nodes (21): FlywayControlPlaneScriptIntegrityTest, CpClientProperties, ModularArchitectureTest, AuditCoverageTest, AuditLogServiceTest, CpHeartbeatWorkerTest, BuildProperties, GlobalExceptionHandlerTest (+13 more)

### Community 1 - "ToastService"
Cohesion: 0.06
Nodes (33): authGuard(), FormTreeItem, PermissionPair, ApiService, Injectable, AuthService, Injectable, PermissionService (+25 more)

### Community 2 - "MfFileRepository"
Cohesion: 0.07
Nodes (13): FileDetailRecord, FileRecord, ResultSet, MfFileRepository, MfFileService, StorageStats, Override, LocalStorageProvider (+5 more)

### Community 3 - ".checkSession"
Cohesion: 0.18
Nodes (4): AppComponent, Component, routes, MeResponse

### Community 4 - "auth.ts"
Cohesion: 0.09
Nodes (31): environment, environment, loginToControlPlane(), loginToInstance(), collectPageErrors(), uniqueRunName(), currentDirectory, defaultEnvFilePath (+23 more)

### Community 5 - "AuditLogService"
Cohesion: 0.14
Nodes (9): AuditLogService, SecurityEventRecord, ApiException, KauthPref, MdCustomFieldService, ApplicationEventPublisher, MsTaskServiceTest, java.security.SecureRandom (+1 more)

### Community 6 - "app-shell.component.ts"
Cohesion: 0.06
Nodes (19): SearchHit, SearchResult, CommandPaletteService, Injectable, DICTIONARIES, I18nService, Language, TranslatePipe (+11 more)

### Community 7 - "UsersComponent"
Cohesion: 0.05
Nodes (5): User, Component, HostListener, ViewChild, UsersComponent

### Community 8 - "TasksComponent"
Cohesion: 0.06
Nodes (6): Task, TaskStatus, TaskType, TasksComponent, Component, SelectOption

### Community 9 - "dependencies"
Cohesion: 0.13
Nodes (15): @angular/cdk, @angular/platform-browser-dynamic, dependencies, @angular/cdk, @angular/forms, @angular/platform-browser, @angular/platform-browser-dynamic, @angular/router (+7 more)

### Community 10 - "ErrorCode"
Cohesion: 0.04
Nodes (53): ErrorCode, BAD_REQUEST, CODE_ALREADY_EXISTS, CONFLICT, CSRF_TOKEN_INVALID, EMPTY_QUERY, FIELD_IN_USE, FILE_CORRUPTED (+45 more)

### Community 11 - "options"
Cohesion: 0.04
Nodes (48): build, serve, test, builder, configurations, defaultConfiguration, options, development (+40 more)

### Community 12 - "RolesComponent"
Cohesion: 0.12
Nodes (3): Role, RolesComponent, Component

### Community 13 - "org.springframework.stereotype.Component"
Cohesion: 0.10
Nodes (28): CpBootstrap, CpMigrateModeRunner, Override, CpSchemaVersionGate, AuditPartitionWorker, Override, MigrateModeRunner, SchemaVersionGate (+20 more)

### Community 15 - "MdRoleRepository"
Cohesion: 0.12
Nodes (6): MdRoleRepository, PermissionPair, RoleRecord, PermissionPair, MdAssignmentService, MdAssignmentServiceIntegrationTest

### Community 16 - "V001__init_schema.sql"
Cohesion: 0.09
Nodes (42): audit_log, idempotency_keys, kauth_api_tokens, kauth_login_attempts, kauth_otp_codes, kauth_password_reset_codes, kauth_sessions, kauth_user_channels (+34 more)

### Community 17 - "options"
Cohesion: 0.05
Nodes (43): build, serve, test, builder, configurations, defaultConfiguration, options, cli (+35 more)

### Community 18 - "dependencies"
Cohesion: 0.05
Nodes (43): dependencies, @angular/common, @angular/compiler, @angular/core, @angular/forms, @angular/platform-browser, @angular/router, rxjs (+35 more)

### Community 19 - "UiUserMultiSelectComponent"
Cohesion: 0.08
Nodes (12): ApiToken, CreatedTokenResponse, UserSession, ProfileComponent, Component, user, Component, HostListener (+4 more)

### Community 20 - "MsTaskService.java"
Cohesion: 0.11
Nodes (15): AddCommentDto, GetMapping, PostMapping, RequestMapping, RestController, MsTaskCommentController, MsTaskEvents, TaskCommented (+7 more)

### Community 21 - ".getCurrentUserId"
Cohesion: 0.10
Nodes (17): CreateTokenDto, DeleteMapping, GetMapping, PostMapping, RequestMapping, RestController, KauthApiTokenController, BindChannelDto (+9 more)

### Community 22 - "CustomFieldRecord"
Cohesion: 0.33
Nodes (3): GetMapping, CustomFieldRecord, MdCustomFieldServiceTest

### Community 23 - "CpFleetRepository"
Cohesion: 0.09
Nodes (14): CpAuthController, LoginDto, CpFleetController, CreateClientDto, RegisterInstanceDto, BackupReportDto, CpHeartbeatController, HeartbeatDto (+6 more)

### Community 24 - "MsNotificationService"
Cohesion: 0.08
Nodes (11): RequestMapping, RestController, MsAnnouncementController, MsTaskNotificationListener, AnnouncementRecord, MsAnnouncementRepository, MsNotificationRepository, NotificationRecord (+3 more)

### Community 25 - "MdScopeService"
Cohesion: 0.10
Nodes (5): ScopeFilter, MdScopeRepository, MdScopeService, UserScope, MdScopeServiceIntegrationTest

### Community 26 - "jakarta.servlet.http.HttpServletResponse"
Cohesion: 0.10
Nodes (18): CpAuthEntryPoint, Override, Logger, Override, ProblemDetailAuthHandlers, Override, RateLimitFilter, RateLimitProperties (+10 more)

### Community 27 - "MdCustomFieldController"
Cohesion: 0.20
Nodes (8): CreateCustomFieldDto, DeleteMapping, PatchMapping, PostMapping, RequestMapping, RestController, MdCustomFieldController, UpdateCustomFieldDto

### Community 28 - "KauthOtpLoginIntegrationTest.java"
Cohesion: 0.09
Nodes (11): ChannelRecord, KauthChannelRepository, KauthLoginAttemptRepository, KauthOtpCodeRepository, OtpRecord, KauthAuthService, KauthChannelService, KauthOtpSender (+3 more)

### Community 29 - "FR-NOTIF: оповещения (in-app, email, SMS, Telegram, объявления)"
Cohesion: 0.09
Nodes (29): Поток входа пользователя (Argon2id → OTP → kauth_sessions), Поток доставки уведомлений через ms_notification_outbox, Поток исходящих вебхуков (kwh_outbox → X-Signature-SHA256), Диагностика: не доставляются уведомления или OTP, Поток C — каркас приложений, T-035 Provider SPI и контрактный тест-кит, S-4: нет словарей i18n, строки зашиты в компонентах, S-5: три провайдера из четырёх — заглушки в лог (+21 more)

### Community 30 - "IdempotencyService"
Cohesion: 0.18
Nodes (5): IdempotencyFilter, Override, IdempotencyRecord, IdempotencyRepository, IdempotencyService

### Community 31 - "Поток B — платформа (критический путь)"
Cohesion: 0.10
Nodes (28): Карта состояния: что где хранится и что переживает пересоздание, Что развёртывание НЕ обеспечивает (осознанные ограничения), Обязательные действия после первого запуска, Политика бэкапов текущего контура (pg_dump, retention), Ежемесячная проверка восстановления из бэкапа, Формулировки ограничений для информирования клиента, Матрица решения GO/NO-GO, Тип B: коммерческая поставка с SLA — статус NO-GO (+20 more)

### Community 32 - "Матрица точных результатов Этапа 1 (разд. 8.2)"
Cohesion: 0.12
Nodes (27): Онбординг: 2 часа до контекста, Маршруты чтения по ролям (Backend/Infra/Frontend), Три главных правила работы, Состав экземпляра клиента, Точки интеграции и последствия их отказа, Обзор архитектуры для эксплуатации, I-0 Интеграция и приёмка M0 (сквозная проверка), Веха M0 — «Каркас и платформа» (+19 more)

### Community 33 - ".doFilterInternal"
Cohesion: 0.23
Nodes (3): SessionRecord, KauthPrincipal, Override

### Community 34 - "MdOrgUnitRepository"
Cohesion: 0.13
Nodes (8): AssignUnitsDto, CreateOrgUnitDto, MdOrgUnitController, ScopeRuleDto, ResultSet, MdOrgUnitRepository, OrgUnitRecord, MdOrgUnitService

### Community 35 - "ADR-0006: Modular Monolith"
Cohesion: 0.13
Nodes (26): ADR-0002: Backend Stack Decision, Angular 22 Frontend, Hybrid Java Core + Node.js Edge (rejected as premature), Java 25 LTS + Spring Boot 4.1.x Stack, Node.js/NestJS Option (rejected), SQL-First Data Access (JdbcClient/jOOQ over JPA), B2B Version Pinning and Update Policy, ADR-0004: Deployment Model (+18 more)

### Community 36 - "FR-CP: control plane (реестр, heartbeat, лицензии, объявления, бэкапы)"
Cohesion: 0.11
Nodes (26): Регистрация экземпляра в control plane (heartbeat-токен), Календарь обслуживания (день/неделя/месяц/квартал/год), Ротация секретов по календарю и по инциденту, Ежедневный контроль (5 минут), Диагностика: медленная работа (пул, pg_stat_activity, диск), Сводка готовности 18 модулей, Фаза F — достройка функционала, Ротация ключа по `kid` без простоя флота (+18 more)

### Community 37 - "e2e/package.json"
Cohesion: 0.10
Nodes (20): devDependencies, @playwright/test, @types/node, typescript, engines, node, typescript, name (+12 more)

### Community 38 - "org.springframework.http.ResponseEntity"
Cohesion: 0.09
Nodes (20): AssignRolesDto, GrantDto, MdAssignmentController, ReplacePermissionsDto, UpdateOrgUnitDto, CreateRoleDto, DeleteMapping, FormTreeItem (+12 more)

### Community 39 - "KauthPasswordHasher"
Cohesion: 0.13
Nodes (5): ApiTokenRecord, CreatedTokenResult, Override, KauthPasswordHasher, KauthPasswordHasherTest

### Community 40 - "FilesComponent"
Cohesion: 0.08
Nodes (7): TaskFile, FilesComponent, Component, Component, Input, Output, UiFileUploadComponent

### Community 41 - "compilerOptions"
Cohesion: 0.09
Nodes (22): angularCompilerOptions, enableI18nLegacyMessageIdFormat, strictInjectionParameters, strictInputAccessModifiers, strictTemplates, compileOnSave, compilerOptions, esModuleInterop (+14 more)

### Community 42 - "compilerOptions"
Cohesion: 0.11
Nodes (17): angularCompilerOptions, strictInjectionParameters, strictTemplates, compileOnSave, compilerOptions, esModuleInterop, importHelpers, isolatedModules (+9 more)

### Community 43 - "CpSecurityContext"
Cohesion: 0.20
Nodes (3): Override, CpPrincipal, CpSecurityContext

### Community 44 - ".success"
Cohesion: 0.09
Nodes (3): LoginResponse, LoginComponent, Component

### Community 45 - "CpSecurityConfig.java"
Cohesion: 0.13
Nodes (19): CpSecurityConfig, FilterRegistrationBean, Override, CpRoleInterceptor, FilterRegistrationBean, SecurityConfig, Override, WebMvcConfig (+11 more)

### Community 46 - "UI/UX hardening for DWH web applications"
Cohesion: 0.08
Nodes (25): 10. Audit artifacts and traceability, 11. Non-goals, 12. Acceptance criteria, 1. Objective, 2. Product and viewport boundaries, 3. Current-state findings that define the work, 4.1. Why foundation-first, 4.2. Delivery sequence (+17 more)

### Community 47 - "MdPermissionRepository"
Cohesion: 0.09
Nodes (11): EffectivePermissionItem, FormTreeItem, MdPermissionRepository, CatalogSyncResult, FormTreeItem, FlywayMigrationValidationTest, MdFormCatalogIntegrationTest, UserBlockingInvariantTest (+3 more)

### Community 48 - "jakarta.servlet.http.HttpServletRequest"
Cohesion: 0.24
Nodes (8): CpSpaCsrfHandler, Override, Override, SpaCsrfTokenRequestHandler, jakarta.servlet.http.HttpServletRequest, org.springframework.security.web.csrf.CsrfToken, org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler, org.springframework.security.web.csrf.CsrfTokenRequestHandler

### Community 49 - "RequiresPermission"
Cohesion: 0.06
Nodes (26): RequiresPermission, GetMapping, PatchMapping, RequestMapping, RestController, MdSettingController, AttachFileDto, ChangeStatusDto (+18 more)

### Community 50 - "Control Plane"
Cohesion: 0.12
Nodes (21): Control Plane, Outbound-Only Instance to Control Plane Link, Automated Backup Restore Verification, Version Drift Control via Nomad API, SSRF Protection via Outbound Allow-List, Vault Transit License Signing with kid Rotation, Alert Catalog with Runbook Links, Control Plane vs Grafana Responsibility Split (+13 more)

### Community 51 - "tasks.component.ts"
Cohesion: 0.10
Nodes (13): CustomField, ProjectMember, ProjectTaskStats, TaskComment, TaskDetailResponse, TaskMember, CustomFieldsComponent, Component (+5 more)

### Community 52 - "org.springframework.jdbc.core.simple.JdbcClient"
Cohesion: 0.05
Nodes (16): CpTelemetryRepository, KauthApiTokenRepository, KauthPasswordResetRepository, ResetRecord, Override, KauthUserSessionInvalidator, ResultSet, SuppressWarnings (+8 more)

### Community 53 - "Provider SPI Pattern (in-tree adapters, config-selected)"
Cohesion: 0.14
Nodes (20): ArchUnit Enforcement in CI, Reduce libs/ to Stable Utilities Only, Module Dependency Rules (facade-only, no cycles), User Aggregate and Invariants I-U1..I-U3, Provider Contract Test Kit, ProviderRegistry, Provider SPI Pattern (in-tree adapters, config-selected), Angular Material + CDK with Strict Custom Theme (+12 more)

### Community 54 - "RB-04: Диагностика и устранение сбоев миграций Flyway"
Cohesion: 0.15
Nodes (20): Самопроверка онбординга (7 вопросов), Типичные ошибки развёртывания и их причины, Миграции отдельным шагом (docker compose run --rm migrate), Schema-gate: отказ старта при несовпадении версии схемы, Руководство по обслуживанию экземпляра, Порядок обновления версии приложения, Диагностика: приложение не стартует, Operations Runbook — эксплуатация экземпляра (+12 more)

### Community 55 - "Production Launch Checklist — критерии go/no-go"
Cohesion: 0.11
Nodes (20): Именование группы и контейнеров Docker (PROJECT_NAME=SmartupCMS), Карта портов (8080 loopback, 9090 actuator, 5432 внутренний), Отличия текущего контура от целевого (фаза P), Руководство по развёртыванию экземпляра клиента, Prerequisites Checklist развёртывания, Аппаратные профили S/M/L для развёртывания, Матрица эскалации P1–P4, Что ещё не автоматизировано (честный список) (+12 more)

### Community 56 - "KwhWebhookService"
Cohesion: 0.17
Nodes (5): GetMapping, ResultSet, KwhSubscriptionRepository, SubscriptionRecord, KwhWebhookService

### Community 57 - "TypesenseProperties"
Cohesion: 0.18
Nodes (7): DwhInfoContributor, Override, TypesenseProperties, Builder, java.net.http.HttpClient, org.springframework.boot.actuate.info.InfoContributor, org.springframework.boot.context.properties.ConfigurationProperties

### Community 58 - "DWH Platform (product overview)"
Cohesion: 0.15
Nodes (19): Module Prefix Convention (md, kauth, ms, mf, audit, cp), DB Triggers Only for Audit and Integrity, Branching and Merge Policy, Definition of Done (DoD), Definition of Ready (DoR), Area Ownership Matrix, Pull Request Policy (small PRs, review SLA), Biruni DB-Centric (metadata-driven) Architecture (+11 more)

### Community 59 - "MsProjectController"
Cohesion: 0.17
Nodes (9): AddMemberDto, CreateProjectDto, DeleteMapping, PatchMapping, PostMapping, RequestMapping, RestController, MsProjectController (+1 more)

### Community 60 - "MsOutboxRepository"
Cohesion: 0.27
Nodes (4): ResultSet, SuppressWarnings, MsOutboxRepository, OutboxRecord

### Community 61 - "Milestone Catalog M1 to M18"
Cohesion: 0.16
Nodes (18): SSE Config (timeout, heartbeat, max connections per user), ArchUnit Boundary Enforcement in CI, Event-Driven Cross-Module Coupling, No Network I/O Inside DB Transactions, Public Facade Isolation (package-private repositories), Transactional Outbox Pattern (notification_outbox), Typesense Search Env Config (no typesense service declared), Milestone Catalog M1 to M18 (+10 more)

### Community 62 - "InstanceBootstrap"
Cohesion: 0.22
Nodes (7): InstanceBootstrap, Override, InstanceBootstrapProperties, MigrationGateAndBootstrapTest, MethodOrderer.OrderAnnotation, org.junit.jupiter.api.Order, org.junit.jupiter.api.TestMethodOrder

### Community 63 - "Override"
Cohesion: 0.14
Nodes (6): DummyMailProvider, DummyMessengerProvider, DummySmsProvider, DummyStorageProvider, Override, ProviderRegistryTest

### Community 64 - "GlobalExceptionHandler.java"
Cohesion: 0.12
Nodes (16): GlobalExceptionHandler, com.fasterxml.jackson.annotation.JsonInclude, FieldErrorItem, ProblemDetailRecord, MethodArgumentTypeMismatchException, MissingServletRequestParameterException, org.springframework.dao.DataIntegrityViolationException, org.springframework.http.converter.HttpMessageNotReadableException (+8 more)

### Community 65 - "MsSseRegistry"
Cohesion: 0.23
Nodes (4): SseEmitter, MsSseRegistry, MsSseRegistryTest, org.springframework.web.servlet.mvc.method.annotation.SseEmitter

### Community 66 - "RateLimitService"
Cohesion: 0.36
Nodes (4): Entry, RateLimitService, io.github.bucket4j.Bucket, io.github.bucket4j.ConsumptionProbe

### Community 67 - "cp-api.service.ts"
Cohesion: 0.20
Nodes (7): Announcement, AnnouncementContent, Client, CpUser, FleetResponse, ShellComponent, Component

### Community 68 - "Generic Audit Trigger into Single audit_log Table"
Cohesion: 0.15
Nodes (17): ADR-0003: RBAC and Audit Model on PostgreSQL, Tenant Isolation via company_id + Row-Level Security (superseded), Generic Audit Trigger into Single audit_log Table, instance_info Single-Row Table, Instance per Client (Single-Tenant), Events as ML Raw Material (audit_log + notification events), ML/AI as Separate Services outside Java Core, Self-Hosted Inter and Material Symbols (no CDN) (+9 more)

### Community 69 - "Materialized effective_permissions Table"
Cohesion: 0.15
Nodes (17): Materialized effective_permissions Table, has(userId, form, action) Runtime Permission Check, permissions_version Cache Invalidation, RBAC Form/Action Catalog Model, Machine-Friendly API (agent = ordinary user, Idempotency-Key, RFC 9457), EffectivePermissions Aggregate and Invariants I-P1..I-P4, Notify Delivery Queue Invariants I-N1..I-N2, PostgreSQL Table Naming Specification (+9 more)

### Community 70 - "FR-TASK: мини таск-менеджер"
Cohesion: 0.12
Nodes (17): Поток загрузки файла (SHA-256 дедупликация), Поток A — репозиторий и CI, T-012 ArchUnit-тесты границ модулей, S-6: лимит размера файла не задан (умолчание Spring 1 МБ), FR-FILE: файлы (Garage S3, SHA-256, дедупликация, проверка типа), FR-MOD: модульность, фасады, доменные события, ArchUnit, FR-TASK: мини таск-менеджер, Доступность: WCAG 2.1 AA как планка (+9 more)

### Community 71 - "ProviderHealth"
Cohesion: 0.07
Nodes (15): NotificationChannelStartupCheck, ProviderRegistry, ConsoleMessengerProvider, Override, Override, TelegramBotMessengerProvider, MsOutboxWorker, CapturingMessenger (+7 more)

### Community 72 - "TypesenseClient"
Cohesion: 0.13
Nodes (9): SearchHit, SearchResult, SearchService, SearchHit, TypesenseClient, TypesenseIndexer, SearchServiceTest, org.springframework.scheduling.annotation.Async (+1 more)

### Community 73 - "UiPaginationComponent"
Cohesion: 0.29
Nodes (4): Component, Input, Output, UiPaginationComponent

### Community 74 - "FR-PERM: доступ / RBAC (формы, действия, роли, эффективные права)"
Cohesion: 0.21
Nodes (16): Ежеквартальный пересмотр доступа, S-2: каталог форм засеян миграцией, а не зарегистрирован из кода, R6 Тестовый долг: RBAC-матрица ролей и инвариант блокировки, FR-PERM: доступ / RBAC (формы, действия, роли, эффективные права), FR-PERM-7: порог фонового пересчёта прав, FR-USR: пользователи экземпляра, Инвариант I-U1: блокировка закрывает сессии и токены в одной транзакции, Матрица системных ролей экземпляра (admin/manager/user/auditor) (+8 more)

### Community 75 - "CpUserRepository"
Cohesion: 0.08
Nodes (14): Override, CpPref, CpSession, CpSessionRepository, CpUser, CpUserRepository, ResultSet, CpAuthFilter (+6 more)

### Community 76 - "4. Дефекты"
Cohesion: 0.12
Nodes (16): 1. Что изменилось, 2. Что действительно сделано, 3. Главное расхождение: `main` красная, 4. Дефекты, 5. Что делать, 6. Общая оценка, AUDIT-05. Глубокая ревизия проекта после вех M1–M18, Д-1. Дедупликация файлов отдаёт чужую запись и обходит квоту (+8 more)

### Community 77 - "KauthSessionController"
Cohesion: 0.28
Nodes (5): DeleteMapping, GetMapping, RequestMapping, RestController, KauthSessionController

### Community 78 - "MdFormCatalogTest.java"
Cohesion: 0.16
Nodes (3): FormMeta, MdFormCatalog, MdFormCatalogTest

### Community 79 - "V001__init_cp_schema.sql"
Cohesion: 0.24
Nodes (12): cp_announcement_contents, cp_announcement_targets, cp_announcements, cp_backup_verifications, cp_clients, cp_instance_heartbeats, cp_instances, cp_licenses (+4 more)

### Community 80 - "KwhSubscriptionController"
Cohesion: 0.15
Nodes (9): CreateSubscriptionDto, DeleteMapping, PatchMapping, PostMapping, RequestMapping, RestController, KwhSubscriptionController, UpdateSubscriptionDto (+1 more)

### Community 82 - "Module Prefix Catalog (md, kauth, ms, mf, audit, cp)"
Cohesion: 0.16
Nodes (14): Inter-Module Coupling via Domain Events, Instance Module Map (platform/iam/rbac/tasks/notify/files/audit), Microservices Rejected (6 services x 100 clients = 600 processes), Modular Monolith Architecture Style, Domain Event Naming Convention, Java Class Naming Standards (Controller/Service/Repository/DTO/Event), Module Prefix Catalog (md, kauth, ms, mf, audit, cp), Pref Constant Classes (MdPref, MsTaskPref, KauthPref) (+6 more)

### Community 83 - "MdUserController"
Cohesion: 0.13
Nodes (13): GetMapping, MeResponse, CreateUserDto, DeleteMapping, GetMapping, KeysetPage, PatchMapping, PostMapping (+5 more)

### Community 84 - "Full Dev Compose Stack (SmartupCMS group)"
Cohesion: 0.31
Nodes (13): Control Plane migrate Profile (Flyway-only run mode), Instance migrate Profile (single migration entry point, NFR-10), dev service: app (instance backend, ZGC JVM opts), dev service: control-plane (backend 8082 to 8081), dev service: db (instance PostgreSQL 18), dev service: db-cp (control plane PostgreSQL 18), Full Dev Compose Stack (SmartupCMS group), dev service: migrate (instance Flyway one-shot) (+5 more)

### Community 85 - "MdPermissionService"
Cohesion: 0.07
Nodes (21): KauthAuthenticationFilter, KauthApiTokenService, LoginResult, ResultSet, SuppressWarnings, MdUserRepository, UserCreateData, UserRecord (+13 more)

### Community 86 - "UiSearchableSelectComponent"
Cohesion: 0.14
Nodes (6): Component, HostListener, Input, Output, ViewChild, UiSearchableSelectComponent

### Community 87 - "FR-SEC: безопасность (CSRF, лимиты, Vault, SCA/SBOM, заголовки)"
Cohesion: 0.22
Nodes (13): Патчинг зависимостей и базовых образов, Диагностика: пользователи не могут войти, S-3: Idempotency-Key не обрабатывается при существующей таблице, Фаза R — ремедиация кода, R1 Версии: Boot 4.1.1, Java 25 LTS, PG 18, Jackson 3, R2 Spring Security: фильтр kauth, CSRF double-submit, заголовки, RFC 9457, R3 Rate limiting: Bucket4j-фильтр, 429 + Retry-After, security_events, R4 Миграции отдельным шагом + SchemaVersionGate + InstanceBootstrap (+5 more)

### Community 88 - "V014__audit_log_immutable.sql"
Cohesion: 0.83
Nodes (3): audit_log_immutable(), audit_log_no_delete, audit_log_no_update

### Community 89 - "web-cp/src/app/app.routes.ts"
Cohesion: 0.36
Nodes (4): AppComponent, Component, routes, authGuard()

### Community 92 - "Garage S3-Compatible Storage (MinIO rejected)"
Cohesion: 0.18
Nodes (12): Garage S3-Compatible Storage (MinIO rejected), One Nomad Job per Client Topology, Stateful Workloads on Host Volumes with Node Constraint, File Upload Magic-Byte Validation and Attachment Delivery, Response Security Headers (CSP, HSTS, nosniff), Fire-and-Forget Telemetry with Local Disk Buffer, Grafana Stack (Alloy, Loki, VictoriaMetrics, Tempo), C-3: Client Files on Local Disk Instead of S3/Garage (+4 more)

### Community 93 - "PostgreSQL 18"
Cohesion: 0.18
Nodes (12): PostgreSQL 18, pgvector as Standard PostgreSQL Extension, C-7: Secrets in env with Dev Fallbacks, No Vault, Alpine Base Image Rejected (argon2-jvm needs glibc), Multi-Stage Non-Root Docker Image, docker-compose.prod.yml Production Contour, CREATE INDEX CONCURRENTLY in Non-Transactional Migration, Flyway File Naming Convention (V{NNN}__, R__) (+4 more)

### Community 94 - "HashiCorp Nomad + Consul + Vault Orchestration"
Cohesion: 0.21
Nodes (12): Fleet Operations Cost (accepted price of single-tenant), HashiCorp Nomad + Consul + Vault Orchestration, Vault Secret Management per Instance Path, Three-Node Platform Quorum (Nomad/Consul/Vault Raft), deploy/ Layout (nomad, consul, vault, spike), Finding O-1: Docker Compose Inadequate for 30-100 Instances (S1), Finding O-2: Backup Verification Covers 1% of Fleet (S1), Finding SEC-3: Secret Management Undefined (700 secrets) (+4 more)

### Community 95 - "Migrations as Separate Batch Job + Schema Version Gate"
Cohesion: 0.24
Nodes (12): Deployment Rings R0/R1/R2 with Nomad Canary, Expand/Contract Migration Rule, Migrations as Separate Batch Job + Schema Version Gate, Migration Linter for Destructive Operations, Finding A-4: No Migration Rollback Path (S1), Finding O-3: Canary Deployment Undefined, D-5: Flyway Enabled at Startup (regression of A-4), C-4 Closure: SchemaVersionGate and migrate Profile (+4 more)

### Community 96 - "Instance Service Config (port 8080, Hikari, actuator 9090, log pattern)"
Cohesion: 0.29
Nodes (11): Control Plane Service Config (port 8081, Hikari, problemdetails), CP First Administrator Bootstrap Properties, Instance dev Profile (local client-code and seed admin), Control Plane Heartbeat Config (dwh.control-plane), Instance Service Config (port 8080, Hikari, actuator 9090, log pattern), End-to-End trace_id Propagation (MDC + SQL comment), Virtual Threads for Blocking Background I/O, M15. Control Plane and Fleet Management (CP) (+3 more)

### Community 97 - "AuditPartitionRepository"
Cohesion: 0.15
Nodes (4): AuditPartitionRepository, AuditPartitionRepositoryIntegrationTest, AuditPartitionWorkerTest, java.time.YearMonth

### Community 98 - "NotificationChannelTest.java"
Cohesion: 0.13
Nodes (12): ConsoleMailProvider, Override, Override, SmtpMailProvider, Override, StubRealMailProvider, jakarta.mail.internet.MimeMessage, MailAttachment (+4 more)

### Community 99 - "org.springframework.web.bind.annotation.GetMapping"
Cohesion: 0.06
Nodes (23): AuditLogController, AuditRecord, AuditStats, SecurityEventRecord, AuditPref, KauthPrincipal, SecurityContext, OpenApiController (+15 more)

### Community 100 - "KauthAuthController"
Cohesion: 0.25
Nodes (8): PostMapping, RequestMapping, RestController, KauthAuthController, LoginDto, OtpVerifyDto, PasswordResetConfirmDto, PasswordResetRequestDto

### Community 101 - "web-instance/tsconfig.app.json"
Cohesion: 0.18
Nodes (10): compilerOptions, outDir, types, extends, files, include, src/**/*.d.ts, src/main.ts (+2 more)

### Community 102 - "Delivery Entry M3: Authentication"
Cohesion: 0.29
Nodes (11): PII-Free Structured JSON Logging, Materialization of Effective Permissions, Invariant I-U1: blocking a user revokes all sessions and tokens, M2. Users and Profiles (USR), M3. Authentication and Authorization (AUTH), M4. Role-Based Access Control (PERM), Live E2E Smoke Suite (scripts/dev/test-api.ps1, 15 scenarios), Delivery Entry M2: Users and Profiles (+3 more)

### Community 103 - "Production Single-Client Compose Stack"
Cohesion: 0.27
Nodes (11): prod service: app (instance behind reverse proxy), prod service: backup (daily pg_dump, no WAL archive), prod service: migrate (one-shot, profile tools), prod service: postgres (18-alpine, data-checksums), Production Single-Client Compose Stack, Broken Migration and Schema Mismatch Drill, Canary Update with auto_revert Drill, Spike Week S-0 (Nomad + Flyway + canary go/no-go) (+3 more)

### Community 104 - "Divergence Register D-1..D-15"
Cohesion: 0.22
Nodes (11): CSRF Double-Submit Token, PII Retention and Deletion by Anonymization, Rate Limiting with Bucket4j, Log Masking of PII and Secrets, Telemetry Discipline (structured JSON logs, low-cardinality labels), End-to-End trace_id Propagation (SPA to SQL comment), Finding SEC-1: CSRF Not Addressed (S1), Finding SEC-2: API Rate Limiting Missing (S1) (+3 more)

### Community 105 - "CI Job: backend (mvn verify + ArchUnit + SBOM)"
Cohesion: 0.24
Nodes (10): CI Job: backend (mvn verify + ArchUnit + SBOM), CI Job: frontend (ng build production), Red-Job-Blocks-Merge Policy (FR-SEC-4, FR-MOD-2), CI Job: security (gitleaks + trivy), Rate Limit Config (ip, user, token, expensive paths), Mandatory SQL Parameterization, CI/CD Quality Gate, M11. Security and Compliance (SEC) (+2 more)

### Community 107 - "CpAnnouncementRepository"
Cohesion: 0.15
Nodes (11): ContentDto, CpAnnouncementController, CreateDto, Announcement, Announcement, Content, ContentDto, CpAnnouncementRepository (+3 more)

### Community 108 - "InstanceApplication"
Cohesion: 0.31
Nodes (6): ControlPlaneApplication, InstanceApplication, org.springframework.boot.autoconfigure.SpringBootApplication, org.springframework.boot.context.properties.ConfigurationPropertiesScan, org.springframework.scheduling.annotation.EnableAsync, org.springframework.scheduling.annotation.EnableScheduling

### Community 109 - "2. Решение"
Cohesion: 0.18
Nodes (10): 1. Контекст, 2.1. Оргструктура — дерево, а не плоский справочник, 2.2. Правило видимости — у роли, позиция — у пользователя, 2.3. Эффективный скоуп материализуется, 2.4. Применение — предикат, а не фильтрация в приложении, 2.5. Что осталось за рамками этого решения, 2. Решение, 3. Последствия (+2 more)

### Community 111 - "org.springframework.transaction.annotation.Transactional"
Cohesion: 0.08
Nodes (11): AuditRecord, PermissionPair, MdRoleService, GetMapping, ResultSet, SuppressWarnings, MsProjectRepository, ProjectMemberRecord (+3 more)

### Community 112 - "Project Statistics Map"
Cohesion: 0.20
Nodes (10): Angular Signals State Management (OnPush, no NgRx), Argon2id Password Hashing, Java Records for DTOs, Events and Projections, SQL-First Data Access (JdbcClient/jOOQ, no JPA), fazo (in-database runtime library of Biruni), Option B: Application-Centric Architecture, Architecture Interaction Diagram (mermaid), Optimization Backlog by Priority (P0-P2) (+2 more)

### Community 113 - "MsTaskService"
Cohesion: 0.05
Nodes (15): TaskAssigned, MsTaskMemberRepository, TaskMemberRecord, ResultSet, SuppressWarnings, MsTaskRepository, ProjectTaskStats, TaskCreateData (+7 more)

### Community 114 - "ui-toast.component.ts"
Cohesion: 0.38
Nodes (3): ToastMessage, Component, UiToastContainerComponent

### Community 116 - "AUDIT-03: Production Readiness Verification"
Cohesion: 0.25
Nodes (9): SCA and SBOM Gates in CI (Trivy, CycloneDX, gitleaks), Finding SEC-5: No SCA/SBOM in CI, AUDIT-02: Stage-1 Implementation Review, Verdict: Keep the Code, Remediate Platform Layer, Phase R: Remediation (R1-R6), AUDIT-03: Production Readiness Verification, Verdict NOT READY (7 of 17 outcomes demonstrable), AUDIT-04: DevOps Readiness Assessment (+1 more)

### Community 117 - "ResourceProfile.java"
Cohesion: 0.22
Nodes (4): ResourceProfile, L, M, S

### Community 118 - "FleetComponent"
Cohesion: 0.32
Nodes (3): FleetItem, FleetComponent, Component

### Community 119 - "errorText"
Cohesion: 0.36
Nodes (3): errorText(), ClientsComponent, Component

### Community 120 - "SmsMessage"
Cohesion: 0.24
Nodes (4): ConsoleSmsProvider, Override, SmsMessage, SmsSendResult

### Community 121 - "FR-AUD: аудит и журналы (audit_log, security-события, retention)"
Cohesion: 0.32
Nodes (8): Обслуживание партиций audit_log, Диагностика: кончается место на диске, M8 Аудит и журналы — самый отстающий модуль, Порядок пересмотра: круги 1–4, S-1: аудит не пишется ни из одного бизнес-модуля, FR-AUD: аудит и журналы (audit_log, security-события, retention), F-07. Разбор инцидента «кто изменил права X?», audit.log — журнал аудита и security-событий

### Community 122 - "Language"
Cohesion: 0.29
Nodes (5): fromCode(), Language, EN, RU, UZ

### Community 123 - "MfFileController.java"
Cohesion: 0.12
Nodes (11): DeleteMapping, GetMapping, PostMapping, RequestMapping, RestController, StorageStats, MfFileController, MfPref (+3 more)

### Community 124 - "Monorepo Structure and Entry Points"
Cohesion: 0.33
Nodes (7): Jackson 3 Compatibility Overrides (fail-on-null-for-primitives), Control Panel SPA Shell (cp-root), Instance CMS SPA Shell (app-root, Inter + Material Symbols), Design Token Discipline (ui-* wrappers, no raw hex), M10. API Contract and Idempotency (API), M9. Settings and Localization (SET and I18N), Monorepo Structure and Entry Points

### Community 126 - "NotificationService"
Cohesion: 0.14
Nodes (9): FieldErrorItem, KeysetPage, ProblemDetail, Announcement, NotificationItem, NotificationService, Injectable, NotificationsComponent (+1 more)

### Community 129 - "UiMarkdownEditorComponent"
Cohesion: 0.21
Nodes (5): Component, Input, Output, ViewChild, UiMarkdownEditorComponent

### Community 131 - "CEO Rule: Deepen, Do Not Expand Scope"
Cohesion: 0.67
Nodes (3): CEO Rule: Deepen, Do Not Expand Scope, Rule: TRD is Source of Truth, Code Follows It, Typesense Deferred, pg_trgm Search Until M/L Client

### Community 159 - "Browser E2E implementation plan"
Cohesion: 0.25
Nodes (7): Browser E2E implementation plan, Task 1: Bootstrap the runner, Task 2: Instance browser contracts, Task 3: Control Plane browser contracts, Task 4: Repair the legacy CP live suite, Task 5: CI and operational entry points, Task 6: Verification and delivery

### Community 160 - "File structure locked by this plan"
Cohesion: 0.13
Nodes (14): File structure locked by this plan, Follow-on plans, Global Constraints, Task 10: Foundation verification and first audit checkpoint, Task 1: Native Angular test targets and button contract, Task 2: Global focus, motion, and repeated design tokens, Task 3: Modal dialog focus and naming contract, Task 4: Toast live-region contract (+6 more)

### Community 161 - "CpHeartbeatWorker.java"
Cohesion: 0.31
Nodes (4): CpHeartbeatWorker, org.springframework.boot.info.BuildProperties, org.springframework.http.client.ClientHttpRequestFactory, org.springframework.web.client.RestClient

### Community 162 - "Browser E2E testing design"
Cohesion: 0.25
Nodes (7): Acceptance criteria, Browser E2E testing design, CI lifecycle, Configuration and secrets, Decision, Stability rules, Test layers

### Community 163 - "AUDIT-05: Финальный DevOps-аудит и Production Readiness Assessment"
Cohesion: 0.50
Nodes (3): 1. Сводка результатов аудита, 2. Production Launch Checklist (Go / No-Go Decision), AUDIT-05: Финальный DevOps-аудит и Production Readiness Assessment

### Community 164 - "MdSettingService"
Cohesion: 0.17
Nodes (3): MdSettingRepository, MdSettingService, MdSettingServiceTest

### Community 166 - "CachedBodyHttpServletRequest"
Cohesion: 0.29
Nodes (5): CachedBodyHttpServletRequest, Override, jakarta.servlet.http.HttpServletRequestWrapper, jakarta.servlet.ServletInputStream, ServletInputStream

### Community 169 - "devDependencies"
Cohesion: 0.15
Nodes (13): devDependencies, @angular/cli, @angular/compiler-cli, @angular-devkit/build-angular, jsdom, typescript, vitest, @angular/cli (+5 more)

### Community 170 - "verify-artifact-security.mjs"
Cohesion: 0.29
Nodes (5): artifactsDirectory, e2eDirectory, playwrightCli, sentinels, unexpectedReportDirectories

### Community 177 - "SmartupCMS browser E2E"
Cohesion: 0.50
Nodes (3): Coverage, Local run, SmartupCMS browser E2E

### Community 178 - "AUDIT-06. Пересмотр модуля M3 «Авторизация и аутентификация»"
Cohesion: 0.20
Nodes (9): 1. Что проверялось, 2. Дефекты, 3. Что сделано, 4. Проверка, 5. Что остаётся открытым по M3, AUDIT-06. Пересмотр модуля M3 «Авторизация и аутентификация», Д-10. Одноразовый код никуда не отправлялся, Д-11. Токен второго фактора не был связан ни с чем (+1 more)

### Community 182 - "web-cp/tsconfig.app.json"
Cohesion: 0.18
Nodes (10): compilerOptions, outDir, types, extends, files, include, src/**/*.d.ts, src/main.ts (+2 more)

### Community 186 - "ProjectsComponent"
Cohesion: 0.24
Nodes (3): Project, ProjectsComponent, Component

### Community 187 - ".preHandle"
Cohesion: 0.25
Nodes (4): Override, HandlerMethod, RequiresPermissionInterceptorTest, SecuredController

### Community 188 - "UI Feature Screens Hardening Plan"
Cohesion: 0.18
Nodes (10): Goal, Task 1 — Command palette interaction model, Task 2 — Tasks and projects forms, Task 3 — Users and roles administration, Task 4 — Audit and files operational screens, Task 5 — Settings and profile forms, Task 6 — Cross-screen regression scan, Task 7 — Final verification and audit evidence (+2 more)

### Community 190 - "UI/UX audit — 2026-08-30"
Cohesion: 0.20
Nodes (9): UI/UX audit — 2026-08-30, Доказательства, Исправленные классы несоответствий, Итог, Область проверки, Ограничения и остаточный риск, Проверенные состояния, Решение (+1 more)

### Community 191 - "fleet.component.ts"
Cohesion: 0.36
Nodes (5): BackupCheck, ago(), DATE_TIME, dt(), RFC-9457

### Community 192 - "AuditLogRepository"
Cohesion: 0.09
Nodes (10): AuditLogRepository, AuditRecord, AuditStats, ResultSet, SuppressWarnings, SecurityEventRecord, AuditStats, PlatformMetrics (+2 more)

### Community 195 - "scripts"
Cohesion: 0.33
Nodes (6): scripts, build, ng, start, test, watch

### Community 197 - "web-instance/package.json"
Cohesion: 0.50
Nodes (3): name, private, version

### Community 208 - "UiMarkdownViewComponent"
Cohesion: 0.32
Nodes (3): Component, Input, UiMarkdownViewComponent

## Ambiguous Edges - Review These
- `Delivery Entry M3: Authentication` → `Test Coverage and Verification`  [AMBIGUOUS]
  STATS_MAP.md · relation: conceptually_related_to
- `Instance-per-Client Deployment (physical isolation, no multi-tenancy)` → `Instance CMS SPA Shell (app-root, Inter + Material Symbols)`  [AMBIGUOUS]
  apps/web-instance/src/index.html · relation: conceptually_related_to
- `Monorepo Structure and Entry Points` → `Service Access Points and Default Credentials`  [AMBIGUOUS]
  STATS_MAP.md · relation: conceptually_related_to
- `Typesense Search Env Config (no typesense service declared)` → `Full Dev Compose Stack (SmartupCMS group)`  [AMBIGUOUS]
  docker-compose.yml · relation: references
- `Typesense Search Env Config (no typesense service declared)` → `M17. Full-Text Search (SEARCH)`  [AMBIGUOUS]
  MILESTONES.md · relation: conceptually_related_to

## Knowledge Gaps
- **458 isolated node(s):** `control-plane`, `instance`, `md_instance_info`, `md_custom_fields`, `kauth_login_attempts` (+453 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **35 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `Delivery Entry M3: Authentication` and `Test Coverage and Verification`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Instance-per-Client Deployment (physical isolation, no multi-tenancy)` and `Instance CMS SPA Shell (app-root, Inter + Material Symbols)`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Monorepo Structure and Entry Points` and `Service Access Points and Default Credentials`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Typesense Search Env Config (no typesense service declared)` and `Full Dev Compose Stack (SmartupCMS group)`?**
  _Edge tagged AMBIGUOUS (relation: references) - confidence is low._
- **What is the exact relationship between `Typesense Search Env Config (no typesense service declared)` and `M17. Full-Text Search (SEARCH)`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **Why does `ErrorCode` connect `ErrorCode` to `GlobalExceptionHandler.java`, `AuditLogService`, `TypesenseClient`, `org.springframework.transaction.annotation.Transactional`, `MsTaskService`, `MsTaskService.java`, `jakarta.servlet.http.HttpServletResponse`, `KauthOtpLoginIntegrationTest.java`?**
  _High betweenness centrality (0.024) - this node is a cross-community bridge._
- **Why does `RequiresPermission` connect `RequiresPermission` to `org.springframework.stereotype.Component`, `MsTaskService.java`, `.getCurrentUserId`, `CustomFieldRecord`, `MdCustomFieldController`, `MdOrgUnitRepository`, `org.springframework.http.ResponseEntity`, `MdPermissionRepository`, `KwhWebhookService`, `MsProjectController`, `.preHandle`, `KauthSessionController`, `MdFormCatalogTest.java`, `KwhSubscriptionController`, `MdUserController`, `org.springframework.web.bind.annotation.GetMapping`, `CpAnnouncementRepository`, `org.springframework.transaction.annotation.Transactional`, `MsTaskService`, `MfFileController.java`?**
  _High betweenness centrality (0.017) - this node is a cross-community bridge._