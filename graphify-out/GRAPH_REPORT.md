# Graph Report - dwh  (2026-08-29)

## Corpus Check
- 318 files · ~137,710 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2774 nodes · 6541 edges · 159 communities (127 shown, 32 thin omitted)
- Extraction: 87% EXTRACTED · 13% INFERRED · 0% AMBIGUOUS · INFERRED: 850 edges (avg confidence: 0.83)
- Token cost: 635,701 input · 0 output

## Community Hubs (Navigation)
- Backend Test Suite
- Instance Frontend Models
- File Module (mf)
- Search, Palette & i18n
- Profile & HTTP Client
- User Repository (md)
- Role & Assignment API
- Users Screen
- Tasks Screen
- Instance Frontend Dependencies
- Error Code Catalog
- Instance Angular Build Config
- Roles Screen
- Bootstrap & Migrate Runners
- Instance App Shell & Auth Routes
- Permission Materialization
- Instance Initial Schema
- CP Angular Build Config
- CP Frontend Dependencies
- Session & Audit Endpoints
- Security Filter Chain Config
- Task REST API
- Task Events & Membership
- Fleet & Heartbeat API
- Notification & Announcement API
- Project Module
- Cross-Module REST Mappings
- API Token Endpoints
- Telemetry & Repository Wiring
- Auth, Notify & Webhook Flows
- SSE & Audit Controllers
- Deployment State Map
- Onboarding Guide
- SMS/Mail Providers & Outbox Worker
- Task Repository
- ADR-0002 Backend Stack
- Instance Registration & Maintenance
- Security Context & Audit Service
- Rate Limiting & Bootstrap Properties
- Authentication Endpoints
- Custom Fields Module
- Instance TypeScript Config
- CP TypeScript Config
- Notification Frontend Service
- Control Plane Session Auth
- Audit Log Repository
- Instance Bootstrap
- Login Attempt Throttling
- Token & Session Repositories
- Webhook Outbox Repository
- Control Plane Architecture
- Settings Module
- Mail Provider & Health SPI
- Module Boundary Rules
- Onboarding Self-Check & Pitfalls
- Docker & Port Conventions
- Webhook Subscription Repository
- Frontend Core Services
- Contribution & Code Style Rules
- Auth Error Handlers
- Application Service Layer
- Architecture Constraints
- CSRF Token Handling
- API Exception Model
- Global Exception Handling
- SSE Connection Registry
- Task Status Repository
- Control Plane Frontend API
- ADR-0003 RBAC & Audit
- Effective Permissions Mechanism
- File Upload & CI Tasks
- Control Plane Announcements
- Keyset Pagination
- Task Type Repository
- Access Review & Catalog Findings
- CP User Repository
- User REST API
- Messenger Provider SPI
- Notification Outbox Repository
- CP Initial Schema
- Custom Field REST API
- Control Plane API Client
- Modular Monolith Rationale
- Control Plane Auth
- Dev Compose & Migrate Profiles
- Task Comments API
- Searchable Select Component
- Ops Diagnostics & Patching
- Schema Version Gate
- Control Plane App Shell
- Announcements Screen
- Markdown Editor Component
- Storage & Nomad Topology
- Postgres, pgvector & Image Build
- HashiCorp Fleet Stack
- Migration & Release Discipline
- Application Profiles Config
- Heartbeat Worker Tests
- Password Reset Repository
- Assignment REST API
- Projects Screen
- App TypeScript Config
- Users Milestone & Invariants
- Production Compose Stack
- Security Baseline Controls
- CI Pipeline Jobs
- Control Plane Module Wiring
- CP Announcement Repository
- Application Entry Points
- Session Listing & 2FA Result
- Kauth Security Context
- Webhook Subscription API
- Core Technology Choices
- CP Role Interceptor Context
- Password Hashing
- Notification Inbox
- Supply Chain Audit Findings
- Resource Profiles
- Fleet Screen
- Clients Screen
- Markdown View Component
- Audit Partition Maintenance
- Language Enum
- User Channel Repository
- SPA Shells & Jackson Config
- Webhook Module Prefs
- CP Shell Component
- Backups Screen
- Role Permission Assignment
- Announcement Queries
- Client Nomad Job
- Project Governance Rules
- Graphify Agent Rules
- Dynamic Task Types Migration
- Cluster Check Script
- Client Deploy Script
- Migrate Script
- Canary Update Script
- Broken Migration Drill Script
- Spike Setup Script
- ISO 27001 Scope Decision
- Responsive Breakpoints
- Maven Platform BOM
- Control Plane Module
- Core Types Library
- Instance Module
- Provider SPI Library

## God Nodes (most connected - your core abstractions)
1. `RequiresPermission` - 100 edges
2. `ErrorCode` - 72 edges
3. `TasksComponent` - 63 edges
4. `ApiException` - 43 edges
5. `MsTaskService` - 42 edges
6. `UsersComponent` - 42 edges
7. `MdUserRepository` - 41 edges
8. `MdRoleRepository` - 34 edges
9. `MdPermissionService` - 34 edges
10. `MdUserService` - 34 edges

## Surprising Connections (you probably didn't know these)
- `Red-Job-Blocks-Merge Policy (FR-SEC-4, FR-MOD-2)` --semantically_similar_to--> `SchemaVersionGate (fail-closed startup schema check)`  [INFERRED] [semantically similar]
  .github/workflows/ci.yml → MILESTONES.md
- `fazo (in-database runtime library of Biruni)` --semantically_similar_to--> `Technology Stack and Top Dependencies`  [INFERRED] [semantically similar]
  docs/adr/ADR-0001-architecture-model.md → STATS_MAP.md
- `Graphify Knowledge Graph Workflow (agent rules)` --semantically_similar_to--> `Graphify Query-First Rules`  [INFERRED] [semantically similar]
  AGENTS.md → CLAUDE.md
- `PII-Free Structured JSON Logging` --semantically_similar_to--> `M2. Users and Profiles (USR)`  [INFERRED] [semantically similar]
  CODE_STYLE.md → MILESTONES.md
- `Optimization Backlog by Priority (P0-P2)` --references--> `Milestone Catalog M1 to M18`  [INFERRED]
  STATS_MAP.md → MILESTONES.md

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **CI Quality Gate: all jobs must be green to merge** — _github_workflows_ci_backend, _github_workflows_ci_frontend, _github_workflows_ci_security, _github_workflows_ci_merge_block_policy, contributing_ci_quality_gate, contributing_definition_of_done [INFERRED 0.85]
- **Migrations as a separate step guarded by the schema gate (NFR-10)** — apps_instance_src_main_resources_application_migrate_migrate_profile, apps_control_plane_src_main_resources_application_migrate_migrate_profile, docker_compose_migrate, docker_compose_migrate_cp, deploy_compose_docker_compose_prod_migrate, milestones_schema_version_gate, deploy_spike_readme_broken_migration_drill [INFERRED 0.85]
- **Domain concepts inherited from Biruni (model kept, mechanism rewritten)** — docs_adr_adr_0001_architecture_model_form_action_permission_model, docs_adr_adr_0001_architecture_model_effective_permissions_materialization, docs_adr_adr_0001_architecture_model_jsonb_generic_audit, readme_biruni_domain_model_inheritance, milestones_m4_rbac, milestones_m8_audit, code_style_module_prefix_convention [INFERRED 0.85]
- **Fleet Lifecycle Mechanism (orchestrate, migrate, roll out, verify, track drift)** — docs_adr_adr_0007_fleet_strategy_nomad_orchestration, docs_adr_adr_0007_fleet_strategy_migration_batch_job, docs_adr_adr_0007_fleet_strategy_deployment_rings, docs_adr_adr_0007_fleet_strategy_backup_verification, docs_adr_adr_0007_fleet_strategy_version_drift_control, docs_adr_adr_0004_deployment_model_control_plane [EXTRACTED 1.00]
- **Domain Invariant Set Enforced in Aggregates** — docs_adr_adr_0006_modular_monolith_task_aggregate, docs_adr_adr_0006_modular_monolith_effective_permissions_aggregate, docs_adr_adr_0006_modular_monolith_user_aggregate, docs_adr_adr_0006_modular_monolith_notify_queue_invariants, docs_guidelines_testing_strategy_unit_invariant_tests [EXTRACTED 1.00]
- **CI Enforcement Gates (arch, security, migrations, style)** — docs_guidelines_testing_strategy_archunit_rules, docs_adr_adr_0008_security_baseline_sca_sbom_ci, docs_adr_adr_0007_fleet_strategy_migration_linter, docs_adr_adr_0012_ui_foundation_design_tokens, docs_adr_adr_0008_security_baseline_safe_sql [INFERRED 0.85]
- **Контур защиты миграций и старта приложения** — docs_ops_deployment_guide_migration_step, docs_ops_deployment_guide_schema_gate, docs_trd_trd_01_cms_nfr_10_migrations, docs_runbooks_rb_04_migration_failure_triage_rb04, docs_plan_remediation_plan_r4_migration_separation, docs_ops_rollback_expand_contract [INFERRED 0.85]
- **Цепочка бэкап → восстановление → проверка RPO** — docs_trd_trd_01_cms_nfr_7_backups, docs_ops_maintenance_guide_backup_policy, docs_ops_maintenance_guide_restore_verification, docs_ops_rollback_backup_restore_path, docs_runbooks_rb_01_node_failure_recovery_walg_restore, docs_plan_m0_plan_t024_backup_restore [INFERRED 0.85]
- **Жизненный цикл лицензии экземпляра** — docs_trd_trd_01_cms_fr_inst_4, docs_trd_trd_04_api_license_validation, docs_runbooks_rb_03_license_key_emergency_rotation_kid_rotation, docs_trd_trd_03_flows_f11_license_expiry_read_only, docs_trd_trd_01_cms_fr_cp [INFERRED 0.85]

## Communities (159 total, 32 thin omitted)

### Community 0 - "Backend Test Suite"
Cohesion: 0.07
Nodes (16): FlywayControlPlaneScriptIntegrityTest, ModularArchitectureTest, FlywayMigrationScriptIntegrityTest, FlywayMigrationValidationTest, KauthPasswordHasherTest, KwhWebhookServiceTest, RbacSystemRolesIntegrationTest, UserBlockingInvariantTest (+8 more)

### Community 1 - "Instance Frontend Models"
Cohesion: 0.07
Nodes (35): FieldErrorItem, ProblemDetail, CustomField, FormTreeItem, PermissionPair, ProjectMember, ProjectTaskStats, TaskComment (+27 more)

### Community 2 - "File Module (mf)"
Cohesion: 0.07
Nodes (19): GetMapping, RequestMapping, RestController, MfFileController, MfPref, FileRecord, ResultSet, MfFileRepository (+11 more)

### Community 3 - "Search, Palette & i18n"
Cohesion: 0.05
Nodes (18): SearchHit, SearchResult, CommandPaletteService, Injectable, DICTIONARIES, I18nService, Language, TranslatePipe (+10 more)

### Community 5 - "User Repository (md)"
Cohesion: 0.08
Nodes (6): ResultSet, SuppressWarnings, MdUserRepository, UserCreateData, UserRecord, UserUpdateData

### Community 6 - "Role & Assignment API"
Cohesion: 0.09
Nodes (17): CreateRoleDto, PatchMapping, PostMapping, RequestMapping, RestController, MdRoleController, UpdateRoleDto, MdRoleRepository (+9 more)

### Community 7 - "Users Screen"
Cohesion: 0.06
Nodes (9): User, Component, HostListener, UsersComponent, Component, HostListener, Input, Output (+1 more)

### Community 8 - "Tasks Screen"
Cohesion: 0.07
Nodes (4): Task, TasksComponent, Component, SelectOption

### Community 9 - "Instance Frontend Dependencies"
Cohesion: 0.04
Nodes (45): @angular/animations, @angular/cdk, @angular/material, @angular/platform-browser-dynamic, dependencies, @angular/animations, @angular/cdk, @angular/common (+37 more)

### Community 10 - "Error Code Catalog"
Cohesion: 0.04
Nodes (46): ErrorCode, BAD_REQUEST, CODE_ALREADY_EXISTS, CONFLICT, CSRF_TOKEN_INVALID, EMPTY_QUERY, FIELD_IN_USE, FILE_CORRUPTED (+38 more)

### Community 11 - "Instance Angular Build Config"
Cohesion: 0.05
Nodes (43): build, serve, builder, configurations, defaultConfiguration, options, development, production (+35 more)

### Community 12 - "Roles Screen"
Cohesion: 0.06
Nodes (3): Role, RolesComponent, Component

### Community 13 - "Bootstrap & Migrate Runners"
Cohesion: 0.13
Nodes (21): CpBootstrap, CpMigrateModeRunner, Override, CpHeartbeatWorker, Override, MigrateModeRunner, KauthSessionCleanupWorker, KwhOutboxWorker (+13 more)

### Community 14 - "Instance App Shell & Auth Routes"
Cohesion: 0.09
Nodes (16): AppComponent, Component, routes, authGuard(), ApiToken, CreatedTokenResponse, LoginResponse, MeResponse (+8 more)

### Community 15 - "Permission Materialization"
Cohesion: 0.12
Nodes (8): EffectivePermissionItem, FormTreeItem, MdPermissionRepository, PermissionPair, PermissionPair, MdAssignmentService, FormTreeItem, MdAssignmentServiceIntegrationTest

### Community 16 - "Instance Initial Schema"
Cohesion: 0.11
Nodes (36): audit_log, idempotency_keys, kauth_api_tokens, kauth_login_attempts, kauth_otp_codes, kauth_password_reset_codes, kauth_sessions, kauth_user_channels (+28 more)

### Community 17 - "CP Angular Build Config"
Cohesion: 0.06
Nodes (36): build, serve, builder, configurations, defaultConfiguration, options, development, production (+28 more)

### Community 18 - "CP Frontend Dependencies"
Cohesion: 0.05
Nodes (36): dependencies, @angular/common, @angular/compiler, @angular/core, @angular/forms, @angular/platform-browser, @angular/router, rxjs (+28 more)

### Community 19 - "Session & Audit Endpoints"
Cohesion: 0.10
Nodes (15): AuditLogService, RequestMapping, RestController, KauthSessionController, KauthPrincipal, Override, KauthAuthenticationFilter, KauthApiTokenService (+7 more)

### Community 20 - "Security Filter Chain Config"
Cohesion: 0.13
Nodes (19): CpSecurityConfig, FilterRegistrationBean, Override, CpRoleInterceptor, FilterRegistrationBean, SecurityConfig, Override, WebMvcConfig (+11 more)

### Community 21 - "Task REST API"
Cohesion: 0.12
Nodes (16): RequiresPermission, ChangeStatusDto, CreateStatusDto, CreateTaskDto, CreateTypeDto, DeleteMapping, GetMapping, PatchMapping (+8 more)

### Community 22 - "Task Events & Membership"
Cohesion: 0.13
Nodes (10): TaskDetailResponse, MsTaskEvents, TaskAssigned, TaskCommented, TaskStatusChanged, MsTaskMemberRepository, TaskMemberRecord, MsTaskCommentService (+2 more)

### Community 23 - "Fleet & Heartbeat API"
Cohesion: 0.11
Nodes (11): CpFleetController, CreateClientDto, RegisterInstanceDto, BackupReportDto, CpHeartbeatController, HeartbeatDto, CpBackupCheck, CpClient (+3 more)

### Community 24 - "Notification & Announcement API"
Cohesion: 0.10
Nodes (10): RequestMapping, RestController, MsAnnouncementController, RequestMapping, RestController, MsNotificationController, MsTaskNotificationListener, MsNotifyPref (+2 more)

### Community 25 - "Project Module"
Cohesion: 0.12
Nodes (7): MsTaskPref, ResultSet, SuppressWarnings, MsProjectRepository, ProjectMemberRecord, ProjectRecord, MsProjectService

### Community 26 - "Cross-Module REST Mappings"
Cohesion: 0.10
Nodes (16): DeleteMapping, DeleteMapping, DeleteMapping, FormTreeItem, GetMapping, AddMemberDto, CreateProjectDto, DeleteMapping (+8 more)

### Community 27 - "API Token Endpoints"
Cohesion: 0.10
Nodes (13): CreateTokenDto, DeleteMapping, GetMapping, PostMapping, RequestMapping, RestController, KauthApiTokenController, PostMapping (+5 more)

### Community 28 - "Telemetry & Repository Wiring"
Cohesion: 0.13
Nodes (6): CpTelemetryRepository, MsAnnouncementRepository, MsNotificationRepository, MsTaskCommentRepository, org.springframework.jdbc.core.simple.JdbcClient, org.springframework.stereotype.Repository

### Community 29 - "Auth, Notify & Webhook Flows"
Cohesion: 0.09
Nodes (29): Поток входа пользователя (Argon2id → OTP → kauth_sessions), Поток доставки уведомлений через ms_notification_outbox, Поток исходящих вебхуков (kwh_outbox → X-Signature-SHA256), Диагностика: не доставляются уведомления или OTP, Поток C — каркас приложений, T-035 Provider SPI и контрактный тест-кит, S-4: нет словарей i18n, строки зашиты в компонентах, S-5: три провайдера из четырёх — заглушки в лог (+21 more)

### Community 30 - "SSE & Audit Controllers"
Cohesion: 0.14
Nodes (13): AuditLogController, AuditPref, MsSseController, SearchResult, SearchController, SearchPref, SearchHit, SearchResult (+5 more)

### Community 31 - "Deployment State Map"
Cohesion: 0.10
Nodes (28): Карта состояния: что где хранится и что переживает пересоздание, Что развёртывание НЕ обеспечивает (осознанные ограничения), Обязательные действия после первого запуска, Политика бэкапов текущего контура (pg_dump, retention), Ежемесячная проверка восстановления из бэкапа, Формулировки ограничений для информирования клиента, Матрица решения GO/NO-GO, Тип B: коммерческая поставка с SLA — статус NO-GO (+20 more)

### Community 32 - "Onboarding Guide"
Cohesion: 0.12
Nodes (27): Онбординг: 2 часа до контекста, Маршруты чтения по ролям (Backend/Infra/Frontend), Три главных правила работы, Состав экземпляра клиента, Точки интеграции и последствия их отказа, Обзор архитектуры для эксплуатации, I-0 Интеграция и приёмка M0 (сквозная проверка), Веха M0 — «Каркас и платформа» (+19 more)

### Community 33 - "SMS/Mail Providers & Outbox Worker"
Cohesion: 0.14
Nodes (8): ConsoleSmsProvider, Override, MailAttachment, MailMessage, MessengerMessage, SmsMessage, SmsProvider, SmsSendResult

### Community 34 - "Task Repository"
Cohesion: 0.14
Nodes (9): ResultSet, SuppressWarnings, MsTaskRepository, ProjectTaskStats, TaskCreateData, TaskRecord, TaskUpdateData, ApplicationEventPublisher (+1 more)

### Community 35 - "ADR-0002 Backend Stack"
Cohesion: 0.13
Nodes (26): ADR-0002: Backend Stack Decision, Angular 22 Frontend, Hybrid Java Core + Node.js Edge (rejected as premature), Java 25 LTS + Spring Boot 4.1.x Stack, Node.js/NestJS Option (rejected), SQL-First Data Access (JdbcClient/jOOQ over JPA), B2B Version Pinning and Update Policy, ADR-0004: Deployment Model (+18 more)

### Community 36 - "Instance Registration & Maintenance"
Cohesion: 0.11
Nodes (26): Регистрация экземпляра в control plane (heartbeat-токен), Календарь обслуживания (день/неделя/месяц/квартал/год), Ротация секретов по календарю и по инциденту, Ежедневный контроль (5 минут), Диагностика: медленная работа (пул, pg_stat_activity, диск), Сводка готовности 18 модулей, Фаза F — достройка функционала, Ротация ключа по `kid` без простоя флота (+18 more)

### Community 37 - "Security Context & Audit Service"
Cohesion: 0.16
Nodes (5): KauthPrincipal, SecurityContext, KauthPref, Override, MdPref

### Community 38 - "Rate Limiting & Bootstrap Properties"
Cohesion: 0.14
Nodes (9): Override, RateLimitFilter, RateLimitProperties, Entry, RateLimitService, io.github.bucket4j.Bucket, io.github.bucket4j.ConsumptionProbe, org.springframework.boot.context.properties.ConfigurationProperties (+1 more)

### Community 39 - "Authentication Endpoints"
Cohesion: 0.15
Nodes (13): GetMapping, PostMapping, RequestMapping, RestController, KauthAuthController, LoginDto, MeResponse, OtpVerifyDto (+5 more)

### Community 40 - "Custom Fields Module"
Cohesion: 0.17
Nodes (5): CustomFieldRecord, ResultSet, MdCustomFieldRepository, MdCustomFieldService, MdCustomFieldServiceTest

### Community 41 - "Instance TypeScript Config"
Cohesion: 0.09
Nodes (22): angularCompilerOptions, enableI18nLegacyMessageIdFormat, strictInjectionParameters, strictInputAccessModifiers, strictTemplates, compileOnSave, compilerOptions, esModuleInterop (+14 more)

### Community 42 - "CP TypeScript Config"
Cohesion: 0.09
Nodes (21): angularCompilerOptions, strictInjectionParameters, strictTemplates, compileOnSave, compilerOptions, esModuleInterop, importHelpers, isolatedModules (+13 more)

### Community 43 - "Notification Frontend Service"
Cohesion: 0.16
Nodes (7): KeysetPage, Announcement, NotificationItem, NotificationService, Injectable, NotificationsComponent, Component

### Community 44 - "Control Plane Session Auth"
Cohesion: 0.15
Nodes (7): CpSession, CpSessionRepository, CpAuthFilter, Override, CpPrincipal, jakarta.servlet.FilterChain, org.springframework.web.filter.OncePerRequestFilter

### Community 45 - "Audit Log Repository"
Cohesion: 0.15
Nodes (5): AuditLogRepository, AuditRecord, ResultSet, SuppressWarnings, SecurityEventRecord

### Community 46 - "Instance Bootstrap"
Cohesion: 0.20
Nodes (7): InstanceBootstrap, Override, InstanceBootstrapProperties, MigrationGateAndBootstrapTest, MethodOrderer.OrderAnnotation, org.junit.jupiter.api.Order, org.springframework.core.annotation.Order

### Community 47 - "Login Attempt Throttling"
Cohesion: 0.16
Nodes (4): KauthLoginAttemptRepository, KauthOtpCodeRepository, OtpRecord, KauthAuthService

### Community 48 - "Token & Session Repositories"
Cohesion: 0.18
Nodes (6): ApiTokenRecord, KauthApiTokenRepository, KauthSessionRepository, CreatedTokenResult, Override, KauthUserSessionInvalidator

### Community 49 - "Webhook Outbox Repository"
Cohesion: 0.14
Nodes (5): ResultSet, SuppressWarnings, KwhOutboxRecord, KwhOutboxRepository, tools.jackson.databind.ObjectMapper

### Community 50 - "Control Plane Architecture"
Cohesion: 0.12
Nodes (21): Control Plane, Outbound-Only Instance to Control Plane Link, Automated Backup Restore Verification, Version Drift Control via Nomad API, SSRF Protection via Outbound Allow-List, Vault Transit License Signing with kid Rotation, Alert Catalog with Runbook Links, Control Plane vs Grafana Responsibility Split (+13 more)

### Community 51 - "Settings Module"
Cohesion: 0.13
Nodes (6): PatchMapping, RequestMapping, RestController, MdSettingController, MdSettingRepository, MdSettingService

### Community 52 - "Mail Provider & Health SPI"
Cohesion: 0.15
Nodes (5): ConsoleMailProvider, Override, ProviderHealth, MailProvider, MailSendResult

### Community 53 - "Module Boundary Rules"
Cohesion: 0.14
Nodes (20): ArchUnit Enforcement in CI, Reduce libs/ to Stable Utilities Only, Module Dependency Rules (facade-only, no cycles), User Aggregate and Invariants I-U1..I-U3, Provider Contract Test Kit, ProviderRegistry, Provider SPI Pattern (in-tree adapters, config-selected), Angular Material + CDK with Strict Custom Theme (+12 more)

### Community 54 - "Onboarding Self-Check & Pitfalls"
Cohesion: 0.15
Nodes (20): Самопроверка онбординга (7 вопросов), Типичные ошибки развёртывания и их причины, Миграции отдельным шагом (docker compose run --rm migrate), Schema-gate: отказ старта при несовпадении версии схемы, Руководство по обслуживанию экземпляра, Порядок обновления версии приложения, Диагностика: приложение не стартует, Operations Runbook — эксплуатация экземпляра (+12 more)

### Community 55 - "Docker & Port Conventions"
Cohesion: 0.11
Nodes (20): Именование группы и контейнеров Docker (PROJECT_NAME=SmartupCMS), Карта портов (8080 loopback, 9090 actuator, 5432 внутренний), Отличия текущего контура от целевого (фаза P), Руководство по развёртыванию экземпляра клиента, Prerequisites Checklist развёртывания, Аппаратные профили S/M/L для развёртывания, Матрица эскалации P1–P4, Что ещё не автоматизировано (честный список) (+12 more)

### Community 56 - "Webhook Subscription Repository"
Cohesion: 0.19
Nodes (4): ResultSet, KwhSubscriptionRepository, SubscriptionRecord, KwhWebhookService

### Community 57 - "Frontend Core Services"
Cohesion: 0.17
Nodes (8): ApiService, Injectable, PermissionService, Injectable, ToastService, Injectable, Component, UiToastContainerComponent

### Community 58 - "Contribution & Code Style Rules"
Cohesion: 0.15
Nodes (19): Module Prefix Convention (md, kauth, ms, mf, audit, cp), DB Triggers Only for Audit and Integrity, Branching and Merge Policy, Definition of Done (DoD), Definition of Ready (DoR), Area Ownership Matrix, Pull Request Policy (small PRs, review SLA), Biruni DB-Centric (metadata-driven) Architecture (+11 more)

### Community 59 - "Auth Error Handlers"
Cohesion: 0.21
Nodes (10): CpAuthEntryPoint, Override, Logger, Override, ProblemDetailAuthHandlers, jakarta.servlet.http.HttpServletResponse, org.springframework.security.access.AccessDeniedException, org.springframework.security.core.AuthenticationException (+2 more)

### Community 60 - "Application Service Layer"
Cohesion: 0.20
Nodes (4): ProjectTaskStats, java.security.SecureRandom, org.springframework.stereotype.Service, org.springframework.transaction.annotation.Transactional

### Community 61 - "Architecture Constraints"
Cohesion: 0.16
Nodes (18): SSE Config (timeout, heartbeat, max connections per user), ArchUnit Boundary Enforcement in CI, Event-Driven Cross-Module Coupling, No Network I/O Inside DB Transactions, Public Facade Isolation (package-private repositories), Transactional Outbox Pattern (notification_outbox), Typesense Search Env Config (no typesense service declared), Milestone Catalog M1 to M18 (+10 more)

### Community 62 - "CSRF Token Handling"
Cohesion: 0.24
Nodes (8): CpSpaCsrfHandler, Override, Override, SpaCsrfTokenRequestHandler, jakarta.servlet.http.HttpServletRequest, org.springframework.security.web.csrf.CsrfToken, org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler, org.springframework.security.web.csrf.CsrfTokenRequestHandler

### Community 64 - "Global Exception Handling"
Cohesion: 0.27
Nodes (8): GlobalExceptionHandler, com.fasterxml.jackson.annotation.JsonInclude, ProblemDetailRecord, org.springframework.http.converter.HttpMessageNotReadableException, org.springframework.web.bind.annotation.ExceptionHandler, org.springframework.web.bind.annotation.RestControllerAdvice, org.springframework.web.bind.MethodArgumentNotValidException, org.springframework.web.servlet.resource.NoResourceFoundException

### Community 65 - "SSE Connection Registry"
Cohesion: 0.29
Nodes (4): SseEmitter, MsSseRegistry, MsSseRegistryTest, org.springframework.web.servlet.mvc.method.annotation.SseEmitter

### Community 67 - "Control Plane Frontend API"
Cohesion: 0.21
Nodes (10): Announcement, AnnouncementContent, BackupCheck, Client, CpUser, FleetResponse, ago(), DATE_TIME (+2 more)

### Community 68 - "ADR-0003 RBAC & Audit"
Cohesion: 0.15
Nodes (17): ADR-0003: RBAC and Audit Model on PostgreSQL, Tenant Isolation via company_id + Row-Level Security (superseded), Generic Audit Trigger into Single audit_log Table, instance_info Single-Row Table, Instance per Client (Single-Tenant), Events as ML Raw Material (audit_log + notification events), ML/AI as Separate Services outside Java Core, Self-Hosted Inter and Material Symbols (no CDN) (+9 more)

### Community 69 - "Effective Permissions Mechanism"
Cohesion: 0.15
Nodes (17): Materialized effective_permissions Table, has(userId, form, action) Runtime Permission Check, permissions_version Cache Invalidation, RBAC Form/Action Catalog Model, Machine-Friendly API (agent = ordinary user, Idempotency-Key, RFC 9457), EffectivePermissions Aggregate and Invariants I-P1..I-P4, Notify Delivery Queue Invariants I-N1..I-N2, PostgreSQL Table Naming Specification (+9 more)

### Community 70 - "File Upload & CI Tasks"
Cohesion: 0.12
Nodes (17): Поток загрузки файла (SHA-256 дедупликация), Поток A — репозиторий и CI, T-012 ArchUnit-тесты границ модулей, S-6: лимит размера файла не задан (умолчание Spring 1 МБ), FR-FILE: файлы (Garage S3, SHA-256, дедупликация, проверка типа), FR-MOD: модульность, фасады, доменные события, ArchUnit, FR-TASK: мини таск-менеджер, Доступность: WCAG 2.1 AA как планка (+9 more)

### Community 71 - "Control Plane Announcements"
Cohesion: 0.23
Nodes (8): ContentDto, CpAnnouncementController, CreateDto, Announcement, CpRequiresRole, java.lang.annotation.Retention, java.lang.annotation.Target, org.springframework.web.bind.annotation.PostMapping

### Community 72 - "Keyset Pagination"
Cohesion: 0.19
Nodes (3): CursorUtils, KeysetPage, CursorUtilsTest

### Community 74 - "Access Review & Catalog Findings"
Cohesion: 0.21
Nodes (16): Ежеквартальный пересмотр доступа, S-2: каталог форм засеян миграцией, а не зарегистрирован из кода, R6 Тестовый долг: RBAC-матрица ролей и инвариант блокировки, FR-PERM: доступ / RBAC (формы, действия, роли, эффективные права), FR-PERM-7: порог фонового пересчёта прав, FR-USR: пользователи экземпляра, Инвариант I-U1: блокировка закрывает сессии и токены в одной транзакции, Матрица системных ролей экземпляра (admin/manager/user/auditor) (+8 more)

### Community 75 - "CP User Repository"
Cohesion: 0.19
Nodes (4): Override, CpUser, CpUserRepository, ResultSet

### Community 76 - "User REST API"
Cohesion: 0.19
Nodes (9): ChangePasswordDto, CreateUserDto, DeleteMapping, PatchMapping, PostMapping, RequestMapping, RestController, MdUserController (+1 more)

### Community 77 - "Messenger Provider SPI"
Cohesion: 0.20
Nodes (4): Override, TelegramMessengerProvider, MessengerProvider, MessengerSendResult

### Community 78 - "Notification Outbox Repository"
Cohesion: 0.22
Nodes (5): ResultSet, SuppressWarnings, MsOutboxRepository, OutboxRecord, MsOutboxWorker

### Community 79 - "CP Initial Schema"
Cohesion: 0.24
Nodes (12): cp_announcement_contents, cp_announcement_targets, cp_announcements, cp_backup_verifications, cp_clients, cp_instance_heartbeats, cp_instances, cp_licenses (+4 more)

### Community 80 - "Custom Field REST API"
Cohesion: 0.16
Nodes (9): CreateCustomFieldDto, DeleteMapping, GetMapping, PatchMapping, PostMapping, RequestMapping, RestController, MdCustomFieldController (+1 more)

### Community 82 - "Modular Monolith Rationale"
Cohesion: 0.16
Nodes (14): Inter-Module Coupling via Domain Events, Instance Module Map (platform/iam/rbac/tasks/notify/files/audit), Microservices Rejected (6 services x 100 clients = 600 processes), Modular Monolith Architecture Style, Domain Event Naming Convention, Java Class Naming Standards (Controller/Service/Repository/DTO/Event), Module Prefix Catalog (md, kauth, ms, mf, audit, cp), Pref Constant Classes (MdPref, MsTaskPref, KauthPref) (+6 more)

### Community 83 - "Control Plane Auth"
Cohesion: 0.23
Nodes (4): CpAuthController, LoginDto, CpAuthService, LoginResult

### Community 84 - "Dev Compose & Migrate Profiles"
Cohesion: 0.31
Nodes (13): Control Plane migrate Profile (Flyway-only run mode), Instance migrate Profile (single migration entry point, NFR-10), dev service: app (instance backend, ZGC JVM opts), dev service: control-plane (backend 8082 to 8081), dev service: db (instance PostgreSQL 18), dev service: db-cp (control plane PostgreSQL 18), Full Dev Compose Stack (SmartupCMS group), dev service: migrate (instance Flyway one-shot) (+5 more)

### Community 85 - "Task Comments API"
Cohesion: 0.22
Nodes (7): AddCommentDto, GetMapping, PostMapping, RequestMapping, RestController, MsTaskCommentController, CommentRecord

### Community 86 - "Searchable Select Component"
Cohesion: 0.17
Nodes (5): Component, HostListener, Input, Output, UiSearchableSelectComponent

### Community 87 - "Ops Diagnostics & Patching"
Cohesion: 0.22
Nodes (13): Патчинг зависимостей и базовых образов, Диагностика: пользователи не могут войти, S-3: Idempotency-Key не обрабатывается при существующей таблице, Фаза R — ремедиация кода, R1 Версии: Boot 4.1.1, Java 25 LTS, PG 18, Jackson 3, R2 Spring Security: фильтр kauth, CSRF double-submit, заголовки, RFC 9457, R3 Rate limiting: Bucket4j-фильтр, 429 + Retry-After, security_events, R4 Миграции отдельным шагом + SchemaVersionGate + InstanceBootstrap (+5 more)

### Community 88 - "Schema Version Gate"
Cohesion: 0.30
Nodes (5): CpSchemaVersionGate, SchemaVersionGate, jakarta.annotation.PostConstruct, javax.sql.DataSource, org.junit.jupiter.api.TestMethodOrder

### Community 89 - "Control Plane App Shell"
Cohesion: 0.21
Nodes (6): AppComponent, Component, routes, authGuard(), LoginComponent, Component

### Community 91 - "Markdown Editor Component"
Cohesion: 0.21
Nodes (5): Component, Input, Output, UiMarkdownEditorComponent, ViewChild

### Community 92 - "Storage & Nomad Topology"
Cohesion: 0.18
Nodes (12): Garage S3-Compatible Storage (MinIO rejected), One Nomad Job per Client Topology, Stateful Workloads on Host Volumes with Node Constraint, File Upload Magic-Byte Validation and Attachment Delivery, Response Security Headers (CSP, HSTS, nosniff), Fire-and-Forget Telemetry with Local Disk Buffer, Grafana Stack (Alloy, Loki, VictoriaMetrics, Tempo), C-3: Client Files on Local Disk Instead of S3/Garage (+4 more)

### Community 93 - "Postgres, pgvector & Image Build"
Cohesion: 0.18
Nodes (12): PostgreSQL 18, pgvector as Standard PostgreSQL Extension, C-7: Secrets in env with Dev Fallbacks, No Vault, Alpine Base Image Rejected (argon2-jvm needs glibc), Multi-Stage Non-Root Docker Image, docker-compose.prod.yml Production Contour, CREATE INDEX CONCURRENTLY in Non-Transactional Migration, Flyway File Naming Convention (V{NNN}__, R__) (+4 more)

### Community 94 - "HashiCorp Fleet Stack"
Cohesion: 0.21
Nodes (12): Fleet Operations Cost (accepted price of single-tenant), HashiCorp Nomad + Consul + Vault Orchestration, Vault Secret Management per Instance Path, Three-Node Platform Quorum (Nomad/Consul/Vault Raft), deploy/ Layout (nomad, consul, vault, spike), Finding O-1: Docker Compose Inadequate for 30-100 Instances (S1), Finding O-2: Backup Verification Covers 1% of Fleet (S1), Finding SEC-3: Secret Management Undefined (700 secrets) (+4 more)

### Community 95 - "Migration & Release Discipline"
Cohesion: 0.24
Nodes (12): Deployment Rings R0/R1/R2 with Nomad Canary, Expand/Contract Migration Rule, Migrations as Separate Batch Job + Schema Version Gate, Migration Linter for Destructive Operations, Finding A-4: No Migration Rollback Path (S1), Finding O-3: Canary Deployment Undefined, D-5: Flyway Enabled at Startup (regression of A-4), C-4 Closure: SchemaVersionGate and migrate Profile (+4 more)

### Community 96 - "Application Profiles Config"
Cohesion: 0.29
Nodes (11): Control Plane Service Config (port 8081, Hikari, problemdetails), CP First Administrator Bootstrap Properties, Instance dev Profile (local client-code and seed admin), Control Plane Heartbeat Config (dwh.control-plane), Instance Service Config (port 8080, Hikari, actuator 9090, log pattern), End-to-End trace_id Propagation (MDC + SQL comment), Virtual Threads for Blocking Background I/O, M15. Control Plane and Fleet Management (CP) (+3 more)

### Community 97 - "Heartbeat Worker Tests"
Cohesion: 0.29
Nodes (4): CpClientProperties, CpHeartbeatWorkerTest, BuildProperties, org.springframework.beans.factory.ObjectProvider

### Community 99 - "Assignment REST API"
Cohesion: 0.25
Nodes (5): AssignRolesDto, GrantDto, MdAssignmentController, ReplacePermissionsDto, org.springframework.web.bind.annotation.PutMapping

### Community 100 - "Projects Screen"
Cohesion: 0.31
Nodes (3): Project, ProjectsComponent, Component

### Community 101 - "App TypeScript Config"
Cohesion: 0.18
Nodes (10): compilerOptions, outDir, types, extends, files, include, src/**/*.d.ts, src/main.ts (+2 more)

### Community 102 - "Users Milestone & Invariants"
Cohesion: 0.29
Nodes (11): PII-Free Structured JSON Logging, Materialization of Effective Permissions, Invariant I-U1: blocking a user revokes all sessions and tokens, M2. Users and Profiles (USR), M3. Authentication and Authorization (AUTH), M4. Role-Based Access Control (PERM), Live E2E Smoke Suite (scripts/dev/test-api.ps1, 15 scenarios), Delivery Entry M2: Users and Profiles (+3 more)

### Community 103 - "Production Compose Stack"
Cohesion: 0.27
Nodes (11): prod service: app (instance behind reverse proxy), prod service: backup (daily pg_dump, no WAL archive), prod service: migrate (one-shot, profile tools), prod service: postgres (18-alpine, data-checksums), Production Single-Client Compose Stack, Broken Migration and Schema Mismatch Drill, Canary Update with auto_revert Drill, Spike Week S-0 (Nomad + Flyway + canary go/no-go) (+3 more)

### Community 104 - "Security Baseline Controls"
Cohesion: 0.22
Nodes (11): CSRF Double-Submit Token, PII Retention and Deletion by Anonymization, Rate Limiting with Bucket4j, Log Masking of PII and Secrets, Telemetry Discipline (structured JSON logs, low-cardinality labels), End-to-End trace_id Propagation (SPA to SQL comment), Finding SEC-1: CSRF Not Addressed (S1), Finding SEC-2: API Rate Limiting Missing (S1) (+3 more)

### Community 105 - "CI Pipeline Jobs"
Cohesion: 0.24
Nodes (10): CI Job: backend (mvn verify + ArchUnit + SBOM), CI Job: frontend (ng build production), Red-Job-Blocks-Merge Policy (FR-SEC-4, FR-MOD-2), CI Job: security (gitleaks + trivy), Rate Limit Config (ip, user, token, expensive paths), Mandatory SQL Parameterization, CI/CD Quality Gate, M11. Security and Compliance (SEC) (+2 more)

### Community 107 - "CP Announcement Repository"
Cohesion: 0.31
Nodes (4): Announcement, Content, ContentDto, CpAnnouncementRepository

### Community 108 - "Application Entry Points"
Cohesion: 0.31
Nodes (6): ControlPlaneApplication, InstanceApplication, org.springframework.boot.autoconfigure.SpringBootApplication, org.springframework.boot.context.properties.ConfigurationPropertiesScan, org.springframework.scheduling.annotation.EnableAsync, org.springframework.scheduling.annotation.EnableScheduling

### Community 109 - "Session Listing & 2FA Result"
Cohesion: 0.29
Nodes (3): GetMapping, SessionRecord, LoginResult

### Community 111 - "Webhook Subscription API"
Cohesion: 0.22
Nodes (7): CreateSubscriptionDto, GetMapping, PatchMapping, RequestMapping, RestController, KwhSubscriptionController, UpdateSubscriptionDto

### Community 112 - "Core Technology Choices"
Cohesion: 0.20
Nodes (10): Angular Signals State Management (OnPush, no NgRx), Argon2id Password Hashing, Java Records for DTOs, Events and Projections, SQL-First Data Access (JdbcClient/jOOQ, no JPA), fazo (in-database runtime library of Biruni), Option B: Application-Centric Architecture, Architecture Interaction Diagram (mermaid), Optimization Backlog by Priority (P0-P2) (+2 more)

### Community 114 - "Password Hashing"
Cohesion: 0.33
Nodes (3): Override, KauthPasswordHasher, de.mkammerer.argon2.Argon2

### Community 115 - "Notification Inbox"
Cohesion: 0.31
Nodes (3): GetMapping, NotificationRecord, MsNotificationCreatedEvent

### Community 116 - "Supply Chain Audit Findings"
Cohesion: 0.25
Nodes (9): SCA and SBOM Gates in CI (Trivy, CycloneDX, gitleaks), Finding SEC-5: No SCA/SBOM in CI, AUDIT-02: Stage-1 Implementation Review, Verdict: Keep the Code, Remediate Platform Layer, Phase R: Remediation (R1-R6), AUDIT-03: Production Readiness Verification, Verdict NOT READY (7 of 17 outcomes demonstrable), AUDIT-04: DevOps Readiness Assessment (+1 more)

### Community 117 - "Resource Profiles"
Cohesion: 0.22
Nodes (4): ResourceProfile, L, M, S

### Community 118 - "Fleet Screen"
Cohesion: 0.32
Nodes (3): FleetItem, FleetComponent, Component

### Community 119 - "Clients Screen"
Cohesion: 0.39
Nodes (3): errorText(), ClientsComponent, Component

### Community 120 - "Markdown View Component"
Cohesion: 0.32
Nodes (3): Component, Input, UiMarkdownViewComponent

### Community 121 - "Audit Partition Maintenance"
Cohesion: 0.32
Nodes (8): Обслуживание партиций audit_log, Диагностика: кончается место на диске, M8 Аудит и журналы — самый отстающий модуль, Порядок пересмотра: круги 1–4, S-1: аудит не пишется ни из одного бизнес-модуля, FR-AUD: аудит и журналы (audit_log, security-события, retention), F-07. Разбор инцидента «кто изменил права X?», audit.log — журнал аудита и security-событий

### Community 122 - "Language Enum"
Cohesion: 0.29
Nodes (5): fromCode(), Language, EN, RU, UZ

### Community 124 - "SPA Shells & Jackson Config"
Cohesion: 0.33
Nodes (7): Jackson 3 Compatibility Overrides (fail-on-null-for-primitives), Control Panel SPA Shell (cp-root), Instance CMS SPA Shell (app-root, Inter + Material Symbols), Design Token Discipline (ui-* wrappers, no raw hex), M10. API Contract and Idempotency (API), M9. Settings and Localization (SET and I18N), Monorepo Structure and Entry Points

### Community 131 - "Project Governance Rules"
Cohesion: 0.67
Nodes (3): CEO Rule: Deepen, Do Not Expand Scope, Rule: TRD is Source of Truth, Code Follows It, Typesense Deferred, pg_trgm Search Until M/L Client

## Ambiguous Edges - Review These
- `M17. Full-Text Search (SEARCH)` → `Typesense Search Env Config (no typesense service declared)`  [AMBIGUOUS]
  MILESTONES.md · relation: conceptually_related_to
- `Instance-per-Client Deployment (physical isolation, no multi-tenancy)` → `Instance CMS SPA Shell (app-root, Inter + Material Symbols)`  [AMBIGUOUS]
  apps/web-instance/src/index.html · relation: conceptually_related_to
- `Service Access Points and Default Credentials` → `Monorepo Structure and Entry Points`  [AMBIGUOUS]
  STATS_MAP.md · relation: conceptually_related_to
- `Delivery Entry M3: Authentication` → `Test Coverage and Verification`  [AMBIGUOUS]
  STATS_MAP.md · relation: conceptually_related_to
- `Full Dev Compose Stack (SmartupCMS group)` → `Typesense Search Env Config (no typesense service declared)`  [AMBIGUOUS]
  docker-compose.yml · relation: references

## Knowledge Gaps
- **298 isolated node(s):** `control-plane`, `instance`, `md_instance_info`, `md_custom_fields`, `kauth_login_attempts` (+293 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **32 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **What is the exact relationship between `M17. Full-Text Search (SEARCH)` and `Typesense Search Env Config (no typesense service declared)`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Instance-per-Client Deployment (physical isolation, no multi-tenancy)` and `Instance CMS SPA Shell (app-root, Inter + Material Symbols)`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Service Access Points and Default Credentials` and `Monorepo Structure and Entry Points`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Delivery Entry M3: Authentication` and `Test Coverage and Verification`?**
  _Edge tagged AMBIGUOUS (relation: conceptually_related_to) - confidence is low._
- **What is the exact relationship between `Full Dev Compose Stack (SmartupCMS group)` and `Typesense Search Env Config (no typesense service declared)`?**
  _Edge tagged AMBIGUOUS (relation: references) - confidence is low._
- **Why does `RequiresPermission` connect `Task REST API` to `Role Permission Assignment`, `Backend Test Suite`, `File Module (mf)`, `Role & Assignment API`, `Security Filter Chain Config`, `Task Events & Membership`, `Notification & Announcement API`, `Project Module`, `Cross-Module REST Mappings`, `API Token Endpoints`, `Telemetry & Repository Wiring`, `SSE & Audit Controllers`, `Security Context & Audit Service`, `Authentication Endpoints`, `Custom Fields Module`, `Audit Log Repository`, `Settings Module`, `Control Plane Announcements`, `User REST API`, `Custom Field REST API`, `Task Comments API`, `Assignment REST API`, `Session Listing & 2FA Result`, `Webhook Subscription API`, `Notification Inbox`, `Webhook Module Prefs`?**
  _High betweenness centrality (0.025) - this node is a cross-community bridge._
- **Why does `ErrorCode` connect `Error Code Catalog` to `Global Exception Handling`, `File Module (mf)`, `User Repository (md)`, `Rate Limiting & Bootstrap Properties`, `Keyset Pagination`, `Permission Materialization`, `Task Events & Membership`, `Project Module`, `Auth Error Handlers`, `Application Service Layer`, `API Exception Model`?**
  _High betweenness centrality (0.016) - this node is a cross-community bridge._