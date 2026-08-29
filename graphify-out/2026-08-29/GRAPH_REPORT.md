# Graph Report - dwh  (2026-08-29)

## Corpus Check
- 336 files · ~157,475 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3103 nodes · 7546 edges · 174 communities (134 shown, 40 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 1027 edges (avg confidence: 0.82)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `00b1b4d8`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- org.junit.jupiter.api.Test
- tasks.component.ts
- MfFileRepository
- ToastService
- UsersComponent
- UserRecord
- CommandPaletteService
- UiUserMultiSelectComponent
- TasksComponent
- dependencies
- ErrorCode
- options
- RolesComponent
- org.springframework.stereotype.Component
- AppShellComponent
- MdAssignmentServiceIntegrationTest
- V001__init_schema.sql
- options
- dependencies
- MdUserRepository
- CpSecurityConfig.java
- org.springframework.http.ResponseEntity
- org.springframework.transaction.annotation.Transactional
- CpFleetRepository
- MsNotificationService
- MsProjectRepository
- MsProjectController
- KauthApiTokenController
- org.springframework.jdbc.core.simple.JdbcClient
- FR-NOTIF: оповещения (in-app, email, SMS, Telegram, объявления)
- KwhOutboxRepository
- Поток B — платформа (критический путь)
- Матрица точных результатов Этапа 1 (разд. 8.2)
- SmsMessage
- MsTaskRepository
- ADR-0006: Modular Monolith
- FR-CP: control plane (реестр, heartbeat, лицензии, объявления, бэкапы)
- ApiException
- RateLimitFilter
- KauthAuthController
- MdCustomFieldRepository
- compilerOptions
- compilerOptions
- NotificationService
- CpSessionRepository
- AuditLogRepository
- MigrationGateAndBootstrapTest.java
- .login
- KauthSessionRepository
- ProviderHealth
- Control Plane
- MdSettingService
- .deliverItem
- Provider SPI Pattern (in-tree adapters, config-selected)
- RB-04: Диагностика и устранение сбоев миграций Flyway
- Production Launch Checklist — критерии go/no-go
- KwhWebhookService
- RbacSystemRolesIntegrationTest.java
- DWH Platform (product overview)
- ProblemDetailAuthHandlers
- .notFound
- Milestone Catalog M1 to M18
- jakarta.servlet.http.HttpServletRequest
- MfFileController.java
- GlobalExceptionHandler.java
- MsSseRegistry
- MsTaskStatusRepository
- cp-api.service.ts
- Generic Audit Trigger into Single audit_log Table
- Materialized effective_permissions Table
- FR-TASK: мини таск-менеджер
- Override
- .listUsers
- MsTaskService
- FR-PERM: доступ / RBAC (формы, действия, роли, эффективные права)
- CpUserRepository
- MdUserController
- MessengerMessage
- MsOutboxRepository
- V001__init_cp_schema.sql
- IdempotencyService
- CpApiService
- Module Prefix Catalog (md, kauth, ms, mf, audit, cp)
- CustomFieldsComponent
- Full Dev Compose Stack (SmartupCMS group)
- MsTaskCommentRepository
- UiSearchableSelectComponent
- FR-SEC: безопасность (CSRF, лимиты, Vault, SCA/SBOM, заголовки)
- PlatformMetrics
- web-cp/src/app/app.routes.ts
- AnnouncementsComponent
- UiMarkdownEditorComponent
- Garage S3-Compatible Storage (MinIO rejected)
- PostgreSQL 18
- HashiCorp Nomad + Consul + Vault Orchestration
- Migrations as Separate Batch Job + Schema Version Gate
- Instance Service Config (port 8080, Hikari, actuator 9090, log pattern)
- CpClientProperties
- .confirmPasswordReset
- org.springframework.web.bind.annotation.GetMapping
- ProjectsComponent
- tsconfig.app.json
- Delivery Entry M3: Authentication
- Production Single-Client Compose Stack
- Divergence Register D-1..D-15
- CI Job: backend (mvn verify + ArchUnit + SBOM)
- CpPasswordHasher
- org.springframework.web.bind.annotation.PostMapping
- InstanceApplication
- SessionRecord
- KauthSecurityContext
- TypesenseClient
- Project Statistics Map
- CpAuthController.java
- KauthPasswordHasher
- AuditComponent
- AUDIT-03: Production Readiness Verification
- ResourceProfile.java
- FleetComponent
- errorText
- UiMarkdownViewComponent
- FR-AUD: аудит и журналы (audit_log, security-события, retention)
- Language
- KauthChannelRepository
- Monorepo Structure and Entry Points
- MdCustomFieldController
- shell.component.ts
- BackupsComponent
- MdRoleRepository
- FilesComponent
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
- TypesenseProperties
- CpHeartbeatWorker.java
- jakarta.servlet.http.HttpServletResponse
- LocalStorageProvider
- .run
- .changeStatus
- MsTaskServiceTest
- CachedBodyHttpServletRequest
- .mapUser
- .verifyOtp
- .createUser
- KauthSessionController
- .hasPermission

## God Nodes (most connected - your core abstractions)
1. `RequiresPermission` - 107 edges
2. `ErrorCode` - 78 edges
3. `TasksComponent` - 65 edges
4. `MsTaskService` - 47 edges
5. `ApiException` - 43 edges
6. `UsersComponent` - 42 edges
7. `MdUserRepository` - 41 edges
8. `ToastService` - 40 edges
9. `ApiService` - 37 edges
10. `MdUserService` - 36 edges

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

## Communities (174 total, 40 thin omitted)

### Community 0 - "org.junit.jupiter.api.Test"
Cohesion: 0.09
Nodes (11): FlywayControlPlaneScriptIntegrityTest, ModularArchitectureTest, ProviderRegistryTest, IdempotencyServiceTest, TraceparentFilterTest, FlywayMigrationScriptIntegrityTest, RbacSystemRolesIntegrationTest, com.tngtech.archunit.core.domain.JavaClasses (+3 more)

### Community 1 - "tasks.component.ts"
Cohesion: 0.06
Nodes (42): ApiToken, CreatedTokenResponse, UserSession, FieldErrorItem, ProblemDetail, CustomField, PermissionPair, ProjectMember (+34 more)

### Community 2 - "MfFileRepository"
Cohesion: 0.11
Nodes (9): FileDetailRecord, FileRecord, ResultSet, MfFileRepository, MfFileService, StorageStats, MfFileServiceTest, StorageProvider (+1 more)

### Community 3 - "ToastService"
Cohesion: 0.05
Nodes (25): AppComponent, Component, routes, authGuard(), LoginResponse, MeResponse, ApiService, Injectable (+17 more)

### Community 4 - "UsersComponent"
Cohesion: 0.05
Nodes (6): User, ProfileComponent, Component, Component, HostListener, UsersComponent

### Community 6 - "CommandPaletteService"
Cohesion: 0.15
Nodes (7): SearchHit, SearchResult, CommandPaletteService, Injectable, CommandPaletteComponent, Component, HostListener

### Community 7 - "UiUserMultiSelectComponent"
Cohesion: 0.13
Nodes (5): Component, HostListener, Input, Output, UiUserMultiSelectComponent

### Community 8 - "TasksComponent"
Cohesion: 0.07
Nodes (4): Task, TasksComponent, Component, SelectOption

### Community 9 - "dependencies"
Cohesion: 0.04
Nodes (45): @angular/animations, @angular/cdk, @angular/material, @angular/platform-browser-dynamic, dependencies, @angular/animations, @angular/cdk, @angular/common (+37 more)

### Community 10 - "ErrorCode"
Cohesion: 0.04
Nodes (50): ErrorCode, BAD_REQUEST, CODE_ALREADY_EXISTS, CONFLICT, CSRF_TOKEN_INVALID, EMPTY_QUERY, FIELD_IN_USE, FILE_CORRUPTED (+42 more)

### Community 11 - "options"
Cohesion: 0.05
Nodes (43): build, serve, builder, configurations, defaultConfiguration, options, development, production (+35 more)

### Community 12 - "RolesComponent"
Cohesion: 0.10
Nodes (4): FormTreeItem, Role, RolesComponent, Component

### Community 13 - "org.springframework.stereotype.Component"
Cohesion: 0.15
Nodes (19): CpBootstrap, CpMigrateModeRunner, Override, CpSchemaVersionGate, InstanceBootstrap, Override, Override, MigrateModeRunner (+11 more)

### Community 15 - "MdAssignmentServiceIntegrationTest"
Cohesion: 0.17
Nodes (5): EffectivePermissionItem, PermissionPair, PermissionPair, FormTreeItem, MdAssignmentServiceIntegrationTest

### Community 16 - "V001__init_schema.sql"
Cohesion: 0.10
Nodes (37): audit_log, idempotency_keys, kauth_api_tokens, kauth_login_attempts, kauth_otp_codes, kauth_password_reset_codes, kauth_sessions, kauth_user_channels (+29 more)

### Community 17 - "options"
Cohesion: 0.06
Nodes (36): build, serve, builder, configurations, defaultConfiguration, options, development, production (+28 more)

### Community 18 - "dependencies"
Cohesion: 0.05
Nodes (36): dependencies, @angular/common, @angular/compiler, @angular/core, @angular/forms, @angular/platform-browser, @angular/router, rxjs (+28 more)

### Community 19 - "MdUserRepository"
Cohesion: 0.10
Nodes (23): AuditLogService, KauthPref, KauthAuthenticationFilter, KauthApiTokenService, KauthAuthService, KauthSessionService, MdPref, MdUserRepository (+15 more)

### Community 20 - "CpSecurityConfig.java"
Cohesion: 0.13
Nodes (19): CpSecurityConfig, FilterRegistrationBean, Override, CpRoleInterceptor, FilterRegistrationBean, SecurityConfig, Override, WebMvcConfig (+11 more)

### Community 21 - "org.springframework.http.ResponseEntity"
Cohesion: 0.06
Nodes (40): AuditStats, RequiresPermission, DeleteMapping, GetMapping, GetMapping, DeleteMapping, PostMapping, DeleteMapping (+32 more)

### Community 22 - "org.springframework.transaction.annotation.Transactional"
Cohesion: 0.17
Nodes (4): UpdateTaskDto, MsTaskMemberRepository, TaskMemberRecord, org.springframework.transaction.annotation.Transactional

### Community 23 - "CpFleetRepository"
Cohesion: 0.12
Nodes (11): CpFleetController, CreateClientDto, RegisterInstanceDto, BackupReportDto, CpHeartbeatController, HeartbeatDto, CpBackupCheck, CpClient (+3 more)

### Community 24 - "MsNotificationService"
Cohesion: 0.11
Nodes (9): RequestMapping, RestController, MsAnnouncementController, AnnouncementRecord, MsAnnouncementRepository, MsNotificationRepository, NotificationRecord, MsNotificationService (+1 more)

### Community 25 - "MsProjectRepository"
Cohesion: 0.15
Nodes (6): ResultSet, SuppressWarnings, MsProjectRepository, ProjectMemberRecord, ProjectRecord, MsProjectService

### Community 26 - "MsProjectController"
Cohesion: 0.15
Nodes (10): AddMemberDto, CreateProjectDto, DeleteMapping, GetMapping, PatchMapping, PostMapping, RequestMapping, RestController (+2 more)

### Community 27 - "KauthApiTokenController"
Cohesion: 0.28
Nodes (6): CreateTokenDto, PostMapping, RequestMapping, RestController, KauthApiTokenController, CreatedTokenResult

### Community 28 - "org.springframework.jdbc.core.simple.JdbcClient"
Cohesion: 0.10
Nodes (4): CpTelemetryRepository, org.springframework.jdbc.core.simple.JdbcClient, org.springframework.stereotype.Repository, tools.jackson.databind.ObjectMapper

### Community 29 - "FR-NOTIF: оповещения (in-app, email, SMS, Telegram, объявления)"
Cohesion: 0.09
Nodes (29): Поток входа пользователя (Argon2id → OTP → kauth_sessions), Поток доставки уведомлений через ms_notification_outbox, Поток исходящих вебхуков (kwh_outbox → X-Signature-SHA256), Диагностика: не доставляются уведомления или OTP, Поток C — каркас приложений, T-035 Provider SPI и контрактный тест-кит, S-4: нет словарей i18n, строки зашиты в компонентах, S-5: три провайдера из четырёх — заглушки в лог (+21 more)

### Community 30 - "KwhOutboxRepository"
Cohesion: 0.21
Nodes (5): ResultSet, SuppressWarnings, KwhOutboxRecord, KwhOutboxRepository, KwhOutboxWorker

### Community 31 - "Поток B — платформа (критический путь)"
Cohesion: 0.10
Nodes (28): Карта состояния: что где хранится и что переживает пересоздание, Что развёртывание НЕ обеспечивает (осознанные ограничения), Обязательные действия после первого запуска, Политика бэкапов текущего контура (pg_dump, retention), Ежемесячная проверка восстановления из бэкапа, Формулировки ограничений для информирования клиента, Матрица решения GO/NO-GO, Тип B: коммерческая поставка с SLA — статус NO-GO (+20 more)

### Community 32 - "Матрица точных результатов Этапа 1 (разд. 8.2)"
Cohesion: 0.12
Nodes (27): Онбординг: 2 часа до контекста, Маршруты чтения по ролям (Backend/Infra/Frontend), Три главных правила работы, Состав экземпляра клиента, Точки интеграции и последствия их отказа, Обзор архитектуры для эксплуатации, I-0 Интеграция и приёмка M0 (сквозная проверка), Веха M0 — «Каркас и платформа» (+19 more)

### Community 33 - "SmsMessage"
Cohesion: 0.24
Nodes (4): ConsoleSmsProvider, Override, SmsMessage, SmsSendResult

### Community 34 - "MsTaskRepository"
Cohesion: 0.12
Nodes (8): ResultSet, SuppressWarnings, MsTaskRepository, ProjectTaskStats, TaskCreateData, TaskFileRecord, TaskRecord, TaskUpdateData

### Community 35 - "ADR-0006: Modular Monolith"
Cohesion: 0.13
Nodes (26): ADR-0002: Backend Stack Decision, Angular 22 Frontend, Hybrid Java Core + Node.js Edge (rejected as premature), Java 25 LTS + Spring Boot 4.1.x Stack, Node.js/NestJS Option (rejected), SQL-First Data Access (JdbcClient/jOOQ over JPA), B2B Version Pinning and Update Policy, ADR-0004: Deployment Model (+18 more)

### Community 36 - "FR-CP: control plane (реестр, heartbeat, лицензии, объявления, бэкапы)"
Cohesion: 0.11
Nodes (26): Регистрация экземпляра в control plane (heartbeat-токен), Календарь обслуживания (день/неделя/месяц/квартал/год), Ротация секретов по календарю и по инциденту, Ежедневный контроль (5 минут), Диагностика: медленная работа (пул, pg_stat_activity, диск), Сводка готовности 18 модулей, Фаза F — достройка функционала, Ротация ключа по `kid` без простоя флота (+18 more)

### Community 37 - "ApiException"
Cohesion: 0.15
Nodes (6): ApiException, KauthPrincipal, SecurityContext, KwhPref, MsNotifyPref, MsTaskPref

### Community 38 - "RateLimitFilter"
Cohesion: 0.14
Nodes (9): Override, RateLimitFilter, RateLimitProperties, Entry, RateLimitService, io.github.bucket4j.Bucket, io.github.bucket4j.ConsumptionProbe, org.springframework.boot.context.properties.ConfigurationProperties (+1 more)

### Community 39 - "KauthAuthController"
Cohesion: 0.25
Nodes (8): PostMapping, RequestMapping, RestController, KauthAuthController, LoginDto, OtpVerifyDto, PasswordResetConfirmDto, PasswordResetRequestDto

### Community 40 - "MdCustomFieldRepository"
Cohesion: 0.15
Nodes (6): GetMapping, PostMapping, CustomFieldRecord, ResultSet, MdCustomFieldRepository, MdCustomFieldServiceTest

### Community 41 - "compilerOptions"
Cohesion: 0.09
Nodes (22): angularCompilerOptions, enableI18nLegacyMessageIdFormat, strictInjectionParameters, strictInputAccessModifiers, strictTemplates, compileOnSave, compilerOptions, esModuleInterop (+14 more)

### Community 42 - "compilerOptions"
Cohesion: 0.09
Nodes (21): angularCompilerOptions, strictInjectionParameters, strictTemplates, compileOnSave, compilerOptions, esModuleInterop, importHelpers, isolatedModules (+13 more)

### Community 43 - "NotificationService"
Cohesion: 0.18
Nodes (7): KeysetPage, Announcement, NotificationItem, NotificationService, Injectable, NotificationsComponent, Component

### Community 44 - "CpSessionRepository"
Cohesion: 0.16
Nodes (5): CpSession, CpSessionRepository, CpAuthFilter, Override, CpPrincipal

### Community 45 - "AuditLogRepository"
Cohesion: 0.15
Nodes (8): AuditLogRepository, AuditRecord, AuditStats, ResultSet, SuppressWarnings, SecurityEventRecord, AuditStats, AuditLogServiceTest

### Community 46 - "MigrationGateAndBootstrapTest.java"
Cohesion: 0.20
Nodes (8): InstanceBootstrapProperties, SchemaVersionGate, MigrationGateAndBootstrapTest, jakarta.annotation.PostConstruct, javax.sql.DataSource, MethodOrderer.OrderAnnotation, org.junit.jupiter.api.Order, org.junit.jupiter.api.TestMethodOrder

### Community 48 - "KauthSessionRepository"
Cohesion: 0.13
Nodes (5): ApiTokenRecord, KauthApiTokenRepository, KauthSessionRepository, Override, KauthUserSessionInvalidator

### Community 49 - "ProviderHealth"
Cohesion: 0.14
Nodes (6): ProviderRegistry, MsOutboxWorker, ProviderHealth, MailProvider, MessengerProvider, SmsProvider

### Community 50 - "Control Plane"
Cohesion: 0.12
Nodes (21): Control Plane, Outbound-Only Instance to Control Plane Link, Automated Backup Restore Verification, Version Drift Control via Nomad API, SSRF Protection via Outbound Allow-List, Vault Transit License Signing with kid Rotation, Alert Catalog with Runbook Links, Control Plane vs Grafana Responsibility Split (+13 more)

### Community 51 - "MdSettingService"
Cohesion: 0.17
Nodes (3): MdSettingRepository, MdSettingService, MdSettingServiceTest

### Community 52 - ".deliverItem"
Cohesion: 0.21
Nodes (5): ConsoleMailProvider, Override, MailAttachment, MailMessage, MailSendResult

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
Cohesion: 0.11
Nodes (12): CreateSubscriptionDto, GetMapping, PatchMapping, RequestMapping, RestController, KwhSubscriptionController, UpdateSubscriptionDto, ResultSet (+4 more)

### Community 57 - "RbacSystemRolesIntegrationTest.java"
Cohesion: 0.24
Nodes (5): FlywayMigrationValidationTest, UserBlockingInvariantTest, org.junit.jupiter.api.BeforeAll, org.testcontainers.containers.PostgreSQLContainer, org.testcontainers.junit.jupiter.Testcontainers

### Community 58 - "DWH Platform (product overview)"
Cohesion: 0.15
Nodes (19): Module Prefix Convention (md, kauth, ms, mf, audit, cp), DB Triggers Only for Audit and Integrity, Branching and Merge Policy, Definition of Done (DoD), Definition of Ready (DoR), Area Ownership Matrix, Pull Request Policy (small PRs, review SLA), Biruni DB-Centric (metadata-driven) Architecture (+11 more)

### Community 59 - "ProblemDetailAuthHandlers"
Cohesion: 0.23
Nodes (9): CpAuthEntryPoint, Override, Logger, Override, ProblemDetailAuthHandlers, org.springframework.security.access.AccessDeniedException, org.springframework.security.core.AuthenticationException, org.springframework.security.web.access.AccessDeniedHandler (+1 more)

### Community 61 - "Milestone Catalog M1 to M18"
Cohesion: 0.16
Nodes (18): SSE Config (timeout, heartbeat, max connections per user), ArchUnit Boundary Enforcement in CI, Event-Driven Cross-Module Coupling, No Network I/O Inside DB Transactions, Public Facade Isolation (package-private repositories), Transactional Outbox Pattern (notification_outbox), Typesense Search Env Config (no typesense service declared), Milestone Catalog M1 to M18 (+10 more)

### Community 62 - "jakarta.servlet.http.HttpServletRequest"
Cohesion: 0.18
Nodes (10): CpAuthController, LoginDto, CpSpaCsrfHandler, Override, Override, SpaCsrfTokenRequestHandler, jakarta.servlet.http.HttpServletRequest, org.springframework.security.web.csrf.CsrfToken (+2 more)

### Community 63 - "MfFileController.java"
Cohesion: 0.15
Nodes (9): DeleteMapping, PostMapping, RequestMapping, RestController, MfFileController, MfPref, InputStreamResource, org.springframework.core.io.InputStreamResource (+1 more)

### Community 64 - "GlobalExceptionHandler.java"
Cohesion: 0.19
Nodes (9): GlobalExceptionHandler, com.fasterxml.jackson.annotation.JsonInclude, FieldErrorItem, ProblemDetailRecord, org.springframework.http.converter.HttpMessageNotReadableException, org.springframework.web.bind.annotation.ExceptionHandler, org.springframework.web.bind.annotation.RestControllerAdvice, org.springframework.web.bind.MethodArgumentNotValidException (+1 more)

### Community 65 - "MsSseRegistry"
Cohesion: 0.23
Nodes (4): SseEmitter, MsSseRegistry, MsSseRegistryTest, org.springframework.web.servlet.mvc.method.annotation.SseEmitter

### Community 67 - "cp-api.service.ts"
Cohesion: 0.21
Nodes (10): Announcement, AnnouncementContent, BackupCheck, Client, CpUser, FleetResponse, ago(), DATE_TIME (+2 more)

### Community 68 - "Generic Audit Trigger into Single audit_log Table"
Cohesion: 0.15
Nodes (17): ADR-0003: RBAC and Audit Model on PostgreSQL, Tenant Isolation via company_id + Row-Level Security (superseded), Generic Audit Trigger into Single audit_log Table, instance_info Single-Row Table, Instance per Client (Single-Tenant), Events as ML Raw Material (audit_log + notification events), ML/AI as Separate Services outside Java Core, Self-Hosted Inter and Material Symbols (no CDN) (+9 more)

### Community 69 - "Materialized effective_permissions Table"
Cohesion: 0.15
Nodes (17): Materialized effective_permissions Table, has(userId, form, action) Runtime Permission Check, permissions_version Cache Invalidation, RBAC Form/Action Catalog Model, Machine-Friendly API (agent = ordinary user, Idempotency-Key, RFC 9457), EffectivePermissions Aggregate and Invariants I-P1..I-P4, Notify Delivery Queue Invariants I-N1..I-N2, PostgreSQL Table Naming Specification (+9 more)

### Community 70 - "FR-TASK: мини таск-менеджер"
Cohesion: 0.12
Nodes (17): Поток загрузки файла (SHA-256 дедупликация), Поток A — репозиторий и CI, T-012 ArchUnit-тесты границ модулей, S-6: лимит размера файла не задан (умолчание Spring 1 МБ), FR-FILE: файлы (Garage S3, SHA-256, дедупликация, проверка типа), FR-MOD: модульность, фасады, доменные события, ArchUnit, FR-TASK: мини таск-менеджер, Доступность: WCAG 2.1 AA как планка (+9 more)

### Community 71 - "Override"
Cohesion: 0.14
Nodes (5): DummyMailProvider, DummyMessengerProvider, DummySmsProvider, DummyStorageProvider, Override

### Community 73 - "MsTaskService"
Cohesion: 0.16
Nodes (4): MsTaskTypeRepository, TypeRecord, ProjectTaskStats, MsTaskService

### Community 74 - "FR-PERM: доступ / RBAC (формы, действия, роли, эффективные права)"
Cohesion: 0.21
Nodes (16): Ежеквартальный пересмотр доступа, S-2: каталог форм засеян миграцией, а не зарегистрирован из кода, R6 Тестовый долг: RBAC-матрица ролей и инвариант блокировки, FR-PERM: доступ / RBAC (формы, действия, роли, эффективные права), FR-PERM-7: порог фонового пересчёта прав, FR-USR: пользователи экземпляра, Инвариант I-U1: блокировка закрывает сессии и токены в одной транзакции, Матрица системных ролей экземпляра (admin/manager/user/auditor) (+8 more)

### Community 75 - "CpUserRepository"
Cohesion: 0.24
Nodes (6): CpUser, CpUserRepository, ResultSet, CpAuthService, LoginResult, CpAuthServiceTest

### Community 76 - "MdUserController"
Cohesion: 0.11
Nodes (13): GetMapping, MeResponse, ChangePasswordDto, CreateUserDto, GetMapping, KeysetPage, PatchMapping, PostMapping (+5 more)

### Community 77 - "MessengerMessage"
Cohesion: 0.24
Nodes (4): Override, TelegramMessengerProvider, MessengerMessage, MessengerSendResult

### Community 78 - "MsOutboxRepository"
Cohesion: 0.24
Nodes (4): ResultSet, SuppressWarnings, MsOutboxRepository, OutboxRecord

### Community 79 - "V001__init_cp_schema.sql"
Cohesion: 0.24
Nodes (12): cp_announcement_contents, cp_announcement_targets, cp_announcements, cp_backup_verifications, cp_clients, cp_instance_heartbeats, cp_instances, cp_licenses (+4 more)

### Community 80 - "IdempotencyService"
Cohesion: 0.19
Nodes (5): IdempotencyFilter, Override, IdempotencyRecord, IdempotencyRepository, IdempotencyService

### Community 82 - "Module Prefix Catalog (md, kauth, ms, mf, audit, cp)"
Cohesion: 0.16
Nodes (14): Inter-Module Coupling via Domain Events, Instance Module Map (platform/iam/rbac/tasks/notify/files/audit), Microservices Rejected (6 services x 100 clients = 600 processes), Modular Monolith Architecture Style, Domain Event Naming Convention, Java Class Naming Standards (Controller/Service/Repository/DTO/Event), Module Prefix Catalog (md, kauth, ms, mf, audit, cp), Pref Constant Classes (MdPref, MsTaskPref, KauthPref) (+6 more)

### Community 84 - "Full Dev Compose Stack (SmartupCMS group)"
Cohesion: 0.31
Nodes (13): Control Plane migrate Profile (Flyway-only run mode), Instance migrate Profile (single migration entry point, NFR-10), dev service: app (instance backend, ZGC JVM opts), dev service: control-plane (backend 8082 to 8081), dev service: db (instance PostgreSQL 18), dev service: db-cp (control plane PostgreSQL 18), Full Dev Compose Stack (SmartupCMS group), dev service: migrate (instance Flyway one-shot) (+5 more)

### Community 85 - "MsTaskCommentRepository"
Cohesion: 0.17
Nodes (10): AddCommentDto, GetMapping, PostMapping, RequestMapping, RestController, MsTaskCommentController, CommentRecord, MsTaskCommentRepository (+2 more)

### Community 86 - "UiSearchableSelectComponent"
Cohesion: 0.17
Nodes (5): Component, HostListener, Input, Output, UiSearchableSelectComponent

### Community 87 - "FR-SEC: безопасность (CSRF, лимиты, Vault, SCA/SBOM, заголовки)"
Cohesion: 0.22
Nodes (13): Патчинг зависимостей и базовых образов, Диагностика: пользователи не могут войти, S-3: Idempotency-Key не обрабатывается при существующей таблице, Фаза R — ремедиация кода, R1 Версии: Boot 4.1.1, Java 25 LTS, PG 18, Jackson 3, R2 Spring Security: фильтр kauth, CSRF double-submit, заголовки, RFC 9457, R3 Rate limiting: Bucket4j-фильтр, 429 + Retry-After, security_events, R4 Миграции отдельным шагом + SchemaVersionGate + InstanceBootstrap (+5 more)

### Community 88 - "PlatformMetrics"
Cohesion: 0.20
Nodes (3): PlatformMetrics, io.micrometer.core.instrument.Counter, io.micrometer.core.instrument.MeterRegistry

### Community 89 - "web-cp/src/app/app.routes.ts"
Cohesion: 0.21
Nodes (6): AppComponent, Component, routes, authGuard(), LoginComponent, Component

### Community 91 - "UiMarkdownEditorComponent"
Cohesion: 0.21
Nodes (5): Component, Input, Output, UiMarkdownEditorComponent, ViewChild

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

### Community 97 - "CpClientProperties"
Cohesion: 0.36
Nodes (3): CpClientProperties, CpHeartbeatWorkerTest, BuildProperties

### Community 99 - "org.springframework.web.bind.annotation.GetMapping"
Cohesion: 0.07
Nodes (22): Announcement, AuditLogController, AuditRecord, SecurityEventRecord, AuditPref, AuditRecord, SecurityEventRecord, OpenApiController (+14 more)

### Community 100 - "ProjectsComponent"
Cohesion: 0.14
Nodes (8): Project, ProjectTaskStats, ProjectsComponent, Component, Component, Input, Output, UiPaginationComponent

### Community 101 - "tsconfig.app.json"
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

### Community 107 - "org.springframework.web.bind.annotation.PostMapping"
Cohesion: 0.16
Nodes (11): ContentDto, CpAnnouncementController, CreateDto, Announcement, Content, ContentDto, CpAnnouncementRepository, CpRequiresRole (+3 more)

### Community 108 - "InstanceApplication"
Cohesion: 0.31
Nodes (6): ControlPlaneApplication, InstanceApplication, org.springframework.boot.autoconfigure.SpringBootApplication, org.springframework.boot.context.properties.ConfigurationPropertiesScan, org.springframework.scheduling.annotation.EnableAsync, org.springframework.scheduling.annotation.EnableScheduling

### Community 111 - "TypesenseClient"
Cohesion: 0.13
Nodes (9): SearchHit, SearchResult, SearchService, SearchHit, TypesenseClient, TypesenseIndexer, SearchServiceTest, org.springframework.scheduling.annotation.Async (+1 more)

### Community 112 - "Project Statistics Map"
Cohesion: 0.20
Nodes (10): Angular Signals State Management (OnPush, no NgRx), Argon2id Password Hashing, Java Records for DTOs, Events and Projections, SQL-First Data Access (JdbcClient/jOOQ, no JPA), fazo (in-database runtime library of Biruni), Option B: Application-Centric Architecture, Architecture Interaction Diagram (mermaid), Optimization Backlog by Priority (P0-P2) (+2 more)

### Community 114 - "KauthPasswordHasher"
Cohesion: 0.20
Nodes (5): Override, KauthPasswordHasher, PasswordHasher, KauthPasswordHasherTest, de.mkammerer.argon2.Argon2

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
Cohesion: 0.39
Nodes (3): errorText(), ClientsComponent, Component

### Community 120 - "UiMarkdownViewComponent"
Cohesion: 0.32
Nodes (3): Component, Input, UiMarkdownViewComponent

### Community 121 - "FR-AUD: аудит и журналы (audit_log, security-события, retention)"
Cohesion: 0.32
Nodes (8): Обслуживание партиций audit_log, Диагностика: кончается место на диске, M8 Аудит и журналы — самый отстающий модуль, Порядок пересмотра: круги 1–4, S-1: аудит не пишется ни из одного бизнес-модуля, FR-AUD: аудит и журналы (audit_log, security-события, retention), F-07. Разбор инцидента «кто изменил права X?», audit.log — журнал аудита и security-событий

### Community 122 - "Language"
Cohesion: 0.29
Nodes (5): fromCode(), Language, EN, RU, UZ

### Community 124 - "Monorepo Structure and Entry Points"
Cohesion: 0.33
Nodes (7): Jackson 3 Compatibility Overrides (fail-on-null-for-primitives), Control Panel SPA Shell (cp-root), Instance CMS SPA Shell (app-root, Inter + Material Symbols), Design Token Discipline (ui-* wrappers, no raw hex), M10. API Contract and Idempotency (API), M9. Settings and Localization (SET and I18N), Monorepo Structure and Entry Points

### Community 125 - "MdCustomFieldController"
Cohesion: 0.18
Nodes (7): CreateCustomFieldDto, DeleteMapping, PatchMapping, RequestMapping, RestController, MdCustomFieldController, UpdateCustomFieldDto

### Community 128 - "MdRoleRepository"
Cohesion: 0.08
Nodes (18): CreateRoleDto, FormTreeItem, GetMapping, PatchMapping, PermissionPair, PostMapping, PutMapping, RequestMapping (+10 more)

### Community 129 - "FilesComponent"
Cohesion: 0.08
Nodes (7): TaskFile, FilesComponent, Component, Component, Input, Output, UiFileUploadComponent

### Community 131 - "CEO Rule: Deepen, Do Not Expand Scope"
Cohesion: 0.67
Nodes (3): CEO Rule: Deepen, Do Not Expand Scope, Rule: TRD is Source of Truth, Code Follows It, Typesense Deferred, pg_trgm Search Until M/L Client

### Community 159 - "TypesenseProperties"
Cohesion: 0.25
Nodes (6): DwhInfoContributor, Override, TypesenseProperties, Builder, java.net.http.HttpClient, org.springframework.boot.actuate.info.InfoContributor

### Community 160 - "CpHeartbeatWorker.java"
Cohesion: 0.17
Nodes (8): CpHeartbeatWorker, MsSsePublisher, org.springframework.beans.factory.ObjectProvider, org.springframework.boot.info.BuildProperties, org.springframework.http.client.ClientHttpRequestFactory, org.springframework.scheduling.annotation.Scheduled, org.springframework.transaction.event.TransactionalEventListener, org.springframework.web.client.RestClient

### Community 161 - "jakarta.servlet.http.HttpServletResponse"
Cohesion: 0.12
Nodes (8): Override, TraceparentFilter, KauthPrincipal, Override, Override, jakarta.servlet.FilterChain, jakarta.servlet.http.HttpServletResponse, org.springframework.web.filter.OncePerRequestFilter

### Community 162 - "LocalStorageProvider"
Cohesion: 0.18
Nodes (4): Override, LocalStorageProvider, FileDownloadStream, Override

### Community 164 - ".changeStatus"
Cohesion: 0.21
Nodes (6): MsTaskNotificationListener, MsTaskEvents, TaskAssigned, TaskCommented, TaskStatusChanged, org.springframework.context.event.EventListener

### Community 166 - "CachedBodyHttpServletRequest"
Cohesion: 0.29
Nodes (5): CachedBodyHttpServletRequest, Override, jakarta.servlet.http.HttpServletRequestWrapper, jakarta.servlet.ServletInputStream, ServletInputStream

### Community 171 - "KauthSessionController"
Cohesion: 0.25
Nodes (4): DeleteMapping, RequestMapping, RestController, KauthSessionController

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
- **307 isolated node(s):** `control-plane`, `instance`, `md_instance_info`, `md_custom_fields`, `kauth_login_attempts` (+302 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **40 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

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
- **Why does `RequiresPermission` connect `org.springframework.http.ResponseEntity` to `MdRoleRepository`, `org.springframework.web.bind.annotation.GetMapping`, `ApiException`, `MdCustomFieldRepository`, `org.springframework.web.bind.annotation.PostMapping`, `MdUserController`, `KauthSessionController`, `MdUserRepository`, `MsTaskCommentRepository`, `org.springframework.transaction.annotation.Transactional`, `KwhWebhookService`, `RbacSystemRolesIntegrationTest.java`, `MsProjectController`, `KauthApiTokenController`, `MdCustomFieldController`, `MfFileController.java`?**
  _High betweenness centrality (0.028) - this node is a cross-community bridge._
- **Why does `ErrorCode` connect `ErrorCode` to `GlobalExceptionHandler.java`, `jakarta.servlet.http.HttpServletResponse`, `MdRoleRepository`, `MfFileRepository`, `UserRecord`, `ApiException`, `MsTaskService`, `TypesenseClient`, `MdUserRepository`, `ProblemDetailAuthHandlers`, `.notFound`?**
  _High betweenness centrality (0.016) - this node is a cross-community bridge._