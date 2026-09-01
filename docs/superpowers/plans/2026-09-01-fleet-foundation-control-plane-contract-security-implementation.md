# Fleet Foundation Control Plane Contract and Security Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** построить безопасный Control Plane contract для enrollment, instance authentication, typed telemetry, backup ownership, immutable releases, desired state и проверяемой deployment history.

**Architecture:** существующий Control Plane остаётся Spring Boot модульным приложением с PostgreSQL и JDBC. Instance trust boundary выносится из контроллера в отдельный credential service и security filter; release, target и deployment capabilities получают собственные repository/service/controller boundaries. Изменения выполняются expand-only миграцией V006 и сохраняют старые данные, но новые API больше не доверяют `clientCode` или произвольному JSON из instance request.

**Tech Stack:** Java 25, Spring Boot 4.1.1, Spring Security, Spring JDBC, Flyway, PostgreSQL 18, JUnit 5, Mockito, Testcontainers 1.21.3, Angular, Vitest, Playwright, PowerShell.

**Spec:** `docs/superpowers/specs/2026-09-01-fleet-foundation-design.md`

## Global Constraints

- Scope ограничен первым delivery slice: Control Plane contract/security; Terraform, Ansible, runner execution и production observability не реализуются этим планом.
- Существующий модульный монолит и PostgreSQL 18 сохраняются.
- Instance identity определяется credential; `clientCode` не принимается из heartbeat или backup-report body.
- Raw credential имеет 256 бит энтропии, показывается один раз и хранится в PostgreSQL только как SHA-256 hash.
- Enrollment token действует 15 минут и может быть использован только один раз.
- При rotation старый credential действует не более 24 часов; revoke действует немедленно.
- `X-Instance-Token` сохраняется в этом срезе как wire header, чтобы не смешивать credential hardening с transport migration.
- Heartbeat payload не превышает 16 KiB и принимается не чаще двух раз в минуту на instance.
- Raw heartbeat хранится 30 дней; дневные агрегаты — 13 месяцев.
- Release после перехода в `READY` неизменяем; runtime references используют только `image@sha256:<64 hex>`.
- Каждое изменение desired state увеличивает generation; `(instance_id, generation)` уникально.
- Control Plane не принимает shell command, executable path или произвольный script payload.
- Все новые API используют Jakarta Validation и RFC 9457 responses со стабильным `errorCode` и `traceId`.
- Все schema changes являются expand-only; `heartbeat_token_hash` удаляется только отдельной contract migration после доказанного fleet upgrade.
- Каждый task выполняется TDD-циклом и заканчивается отдельным commit.

---

## File Structure

### Existing files to modify

| File | Responsibility after this slice |
|---|---|
| `apps/control-plane/pom.xml` | Testcontainers, Spring Security/WebMVC test dependencies and Bucket4j runtime dependency |
| `apps/control-plane/src/main/resources/application.yml` | Strict JSON contract, heartbeat limits and retention configuration |
| `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/config/CpSecurityConfig.java` | Separate operator-session and instance-credential authorization rules |
| `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpFleetController.java` | Operator client/instance lifecycle only; no credential implementation |
| `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpHeartbeatController.java` | Removed after instance endpoints move to the dedicated boundary |
| `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/repository/CpFleetRepository.java` | Existing client CRUD, fleet/status and legacy restore-check read model; instance creation, heartbeat, backup artifact and credential writes move out |
| `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/CpHeartbeatWorker.java` | Build and send the typed heartbeat contract |
| `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/CpTelemetryRepository.java` | Return a typed anonymous capacity snapshot instead of an arbitrary map |
| `apps/web-cp/src/app/core/cp-api.service.ts` | Enrollment response and release/target read contracts |
| `apps/web-cp/src/app/pages/clients.component.ts` | Display one-time enrollment token, not a ready-to-use heartbeat credential |
| `scripts/dev/test-cp-api.ps1` | Live enrollment, heartbeat, backup ownership and desired-state smoke |

### New backend boundaries

| Package/file | Single responsibility |
|---|---|
| `cp/instance/api/*` | Strict instance wire request/response records |
| `cp/instance/CpInstancePrincipal.java` | Authenticated instance/client identity |
| `cp/instance/CpTokenGenerator.java` | 256-bit Base64URL token generation |
| `cp/instance/CpInstanceCredentialRepository.java` | Enrollment and credential persistence |
| `cp/instance/CpInstanceCredentialService.java` | Issue, exchange, authenticate, rotate and revoke rules |
| `cp/instance/CpInstanceRepository.java` | Instance identity/placement metadata persistence |
| `cp/instance/CpInstanceRegistrationService.java` | Validate deployment mode and atomically create instance + enrollment |
| `cp/instance/CpInstanceAuthFilter.java` | Authenticate protected instance paths before Spring authorization |
| `cp/instance/CpInstanceApiController.java` | Enrollment, heartbeat, backup, desired-state and rotation endpoints |
| `cp/error/CpApiException.java` | Stable HTTP status and `errorCode` for domain/API failures |
| `cp/error/CpProblemDetailsHandler.java` | RFC 9457 mapping for validation, JSON and domain failures |
| `cp/error/CpRequestTraceFilter.java` | Request `traceId`, MDC correlation and `X-Trace-Id` response header |
| `cp/instance/CpHeartbeatRepository.java` | Typed heartbeat persistence |
| `cp/instance/CpHeartbeatService.java` | Bind heartbeat writes to `CpInstancePrincipal` and validate telemetry invariants |
| `cp/instance/CpBackupReportRepository.java` | Principal-bound backup artifact report persistence |
| `cp/instance/CpBackupReportService.java` | Backup status/checksum invariants; instance cannot self-assert `VERIFIED` |
| `cp/instance/CpInstanceRequestGuardFilter.java` | 16 KiB request limit and per-instance heartbeat rate limit |
| `cp/instance/CpHeartbeatRetentionJob.java` | Daily aggregation and bounded retention deletion |
| `cp/release/*` | Immutable release catalog and signed-manifest metadata |
| `cp/deployment/*` | Desired generation, deployment state machine, events and read model |
| `cp/audit/CpAuditRepository.java` | Append-only non-secret security/release audit events |

### New tests

All PostgreSQL integration tests extend `CpPostgresIntegrationSupport`, which owns one `postgres:18-alpine` Testcontainer, Flyway migration and per-test table cleanup.

---

### Task 1: Add the PostgreSQL test harness and expand-only V006 schema

**Files:**
- Modify: `apps/control-plane/pom.xml:74-79`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/support/CpPostgresIntegrationSupport.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/db/CpFleetFoundationMigrationTest.java`
- Create: `apps/control-plane/src/main/resources/db/migration/V006__fleet_foundation_control_plane.sql`
- Modify: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/FlywayControlPlaneScriptIntegrityTest.java:14-35`

**Interfaces:**
- Consumes: existing Flyway location `classpath:db/migration` and PostgreSQL 18.
- Produces: migrated tables `cp_releases`, `cp_release_components`, `cp_instance_enrollment_tokens`, `cp_instance_credentials`, `cp_instance_backup_reports`, `cp_instance_targets`, `cp_deployments`, `cp_deployment_events`, `cp_heartbeat_daily`, `cp_audit_events`; reusable `dataSource()` and `jdbc()` test helpers.

- [ ] **Step 1: Add failing migration assertions**

Create `CpFleetFoundationMigrationTest` with exact table and invariant checks:

```java
package com.greenwhite.dwh.cp.db;

import com.greenwhite.dwh.cp.support.CpPostgresIntegrationSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CpFleetFoundationMigrationTest extends CpPostgresIntegrationSupport {

    @Test
    void createsFleetFoundationTablesAndBackfillsLegacyCredential() {
        cleanAndMigrateTo("5");
        long clientId = jdbc().sql("""
                insert into cp_clients(code, name, resource_profile)
                values ('migration_client', 'Migration Client', 'S') returning id
                """).query(Long.class).single();
        long instanceId = jdbc().sql("""
                insert into cp_instances(client_id, environment, url, heartbeat_token_hash)
                values (:clientId, 'production', 'https://migration.invalid', 'legacy-hash') returning id
                """).param("clientId", clientId).query(Long.class).single();

        migrateLatest();

        assertThat(tableExists("cp_releases")).isTrue();
        assertThat(tableExists("cp_instance_credentials")).isTrue();
        assertThat(tableExists("cp_instance_targets")).isTrue();
        assertThat(tableExists("cp_deployments")).isTrue();
        assertThat(tableExists("cp_deployment_events")).isTrue();
        assertThat(tableExists("cp_instance_backup_reports")).isTrue();
        assertThat(tableExists("cp_heartbeat_daily")).isTrue();
        assertThat(tableExists("cp_audit_events")).isTrue();
        assertThat(jdbc().sql("select count(*) from cp_instance_credentials where instance_id=:id")
                .param("id", instanceId).query(Long.class).single()).isEqualTo(1L);
    }
}
```

`CpPostgresIntegrationSupport` exposes:

```java
protected static DataSource dataSource();
protected static JdbcClient jdbc();
protected static void cleanAndMigrateTo(String version);
protected static void migrateLatest();
protected boolean tableExists(String tableName);
```

- [ ] **Step 2: Add test dependencies and run the test to prove it fails**

Add `spring-boot-security-test`, `spring-boot-webmvc-test`, `org.testcontainers:postgresql` and `org.testcontainers:junit-jupiter` with `test` scope to `apps/control-plane/pom.xml`.

Run:

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace -v /var/run/docker.sock:/var/run/docker.sock maven:3.9.11-eclipse-temurin-25 mvn -pl apps/control-plane -am -Dtest=CpFleetFoundationMigrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: FAIL because the V006 tables do not exist.

- [ ] **Step 3: Implement V006 with explicit constraints**

The migration must create the following core shape; use these exact status values and keys:

```sql
create table cp_releases (
    id uuid primary key default gen_random_uuid(),
    version text not null unique,
    source_commit char(40) not null,
    manifest_digest text not null unique,
    manifest_location text not null,
    verification_bundle_digest text not null,
    config_schema_version text not null,
    minimum_agent_version text not null,
    deployment_modes text[] not null,
    status text not null default 'DRAFT'
        check (status in ('DRAFT', 'READY', 'REVOKED')),
    created_by_user_id bigint references cp_users(id),
    created_by_identity text not null,
    created_at timestamptz not null default now(),
    ready_at timestamptz,
    revoked_at timestamptz
);

create table cp_release_components (
    release_id uuid not null references cp_releases(id) on delete cascade,
    component_name text not null,
    image_reference text not null,
    image_digest text not null,
    sbom_digest text not null,
    provenance_digest text not null,
    minimum_schema_version text,
    maximum_rollback_schema_version text,
    primary key (release_id, component_name),
    check (image_reference like '%@sha256:%')
);

create table cp_instance_enrollment_tokens (
    id bigint generated always as identity primary key,
    instance_id bigint not null references cp_instances(id) on delete cascade,
    token_hash char(64) not null unique,
    expires_at timestamptz not null,
    consumed_at timestamptz,
    created_by bigint not null references cp_users(id),
    created_at timestamptz not null default now()
);

create table cp_instance_credentials (
    id bigint generated always as identity primary key,
    instance_id bigint not null references cp_instances(id) on delete cascade,
    credential_hash char(64) not null unique,
    activated_at timestamptz not null default now(),
    expires_at timestamptz,
    revoked_at timestamptz,
    predecessor_id bigint references cp_instance_credentials(id),
    successor_id bigint references cp_instance_credentials(id),
    last_used_at timestamptz,
    created_at timestamptz not null default now()
);

insert into cp_instance_credentials(instance_id, credential_hash)
select id, heartbeat_token_hash from cp_instances
where heartbeat_token_hash is not null
on conflict (credential_hash) do nothing;

create table cp_instance_backup_reports (
    id bigint generated always as identity primary key,
    backup_id uuid not null unique,
    instance_id bigint not null references cp_instances(id) on delete cascade,
    artifact_status text not null check (artifact_status in ('UPLOADED', 'VERIFIED', 'FAILED')),
    checksum_sha256 char(64),
    duration_sec int not null check (duration_sec between 0 and 86400),
    reason_code text,
    completed_at timestamptz not null,
    received_at timestamptz not null default now(),
    verified_at timestamptz,
    check ((artifact_status = 'UPLOADED' and checksum_sha256 is not null)
        or artifact_status in ('VERIFIED', 'FAILED'))
);
create index cp_instance_backup_reports_instance_time_idx
    on cp_instance_backup_reports(instance_id, received_at desc);

alter table cp_instances
    add column if not exists deployment_mode text not null default 'MANAGED_CLOUD'
        check (deployment_mode in ('MANAGED_CLOUD', 'CUSTOMER_HOSTED')),
    add column if not exists jurisdiction text,
    add column if not exists cloud_provider text,
    add column if not exists storage_provider text,
    add column if not exists edge_provider text,
    add column if not exists support_tier text not null default 'MANAGED_995',
    add column if not exists current_release_id uuid references cp_releases(id),
    add column if not exists current_config_version text,
    add column if not exists current_generation bigint not null default 0,
    add column if not exists lifecycle_status text not null default 'REGISTERED'
        check (lifecycle_status in ('REGISTERED', 'ENROLLING', 'ACTIVE', 'SUSPENDED', 'OFFBOARDING', 'DELETED'));

alter table cp_instance_heartbeats
    add column if not exists release_version text,
    add column if not exists config_version text,
    add column if not exists component_health jsonb not null default '{}'::jsonb,
    add column if not exists storage_used_bytes bigint check (storage_used_bytes >= 0),
    add column if not exists storage_quota_bytes bigint check (storage_quota_bytes >= 0),
    add column if not exists last_backup_at timestamptz,
    add column if not exists backup_status text
        check (backup_status in ('UNKNOWN', 'UPLOADED', 'VERIFIED', 'FAILED')),
    add column if not exists tunnel_status text
        check (tunnel_status in ('UP', 'DEGRADED', 'DOWN', 'UNKNOWN')),
    add column if not exists agent_status text
        check (agent_status in ('UP', 'DEGRADED', 'DOWN', 'UNKNOWN')),
    add column if not exists deployment_state text,
    add column if not exists active_users bigint check (active_users >= 0),
    add column if not exists outbox_pending bigint check (outbox_pending >= 0),
    add column if not exists outbox_dead_letter bigint check (outbox_dead_letter >= 0);

create table cp_instance_targets (
    instance_id bigint primary key references cp_instances(id) on delete cascade,
    generation bigint not null check (generation > 0),
    desired_release_id uuid not null references cp_releases(id),
    config_version text not null,
    rollout_ring text not null check (rollout_ring in ('R0', 'R1', 'R2', 'R3')),
    maintenance_week_of_month smallint not null check (maintenance_week_of_month between 1 and 5),
    maintenance_day_of_week smallint not null check (maintenance_day_of_week between 1 and 7),
    maintenance_start time not null,
    maintenance_duration_minutes smallint not null check (maintenance_duration_minutes between 15 and 240),
    maintenance_timezone text not null,
    requested_by bigint not null references cp_users(id),
    requested_at timestamptz not null default now(),
    unique(instance_id, generation)
);

create table cp_deployments (
    id uuid primary key default gen_random_uuid(),
    instance_id bigint not null references cp_instances(id) on delete cascade,
    release_id uuid not null references cp_releases(id),
    generation bigint not null,
    previous_release_id uuid references cp_releases(id),
    runner_identity text,
    status text not null check (status in (
        'REQUESTED', 'PREFLIGHT', 'PREFLIGHT_FAILED', 'BACKUP_VERIFIED', 'BACKUP_FAILED',
        'MIGRATING', 'DEPLOYING', 'VERIFYING', 'SUCCEEDED', 'ROLLING_BACK',
        'ROLLED_BACK', 'RECOVERY_REQUIRED', 'CANCELLED')),
    reason_code text,
    technical_log_reference text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    unique(instance_id, generation)
);

create table cp_deployment_events (
    deployment_id uuid not null references cp_deployments(id) on delete cascade,
    sequence_no bigint not null check (sequence_no > 0),
    idempotency_key text not null unique,
    status text not null,
    reason_code text,
    details text check (char_length(details) <= 4000),
    occurred_at timestamptz not null default now(),
    primary key (deployment_id, sequence_no)
);

create table cp_heartbeat_daily (
    instance_id bigint not null references cp_instances(id) on delete cascade,
    day date not null,
    sample_count bigint not null check (sample_count >= 0),
    max_storage_used_bytes bigint,
    max_active_users bigint,
    max_outbox_pending bigint,
    last_app_version text,
    last_schema_version text,
    primary key (instance_id, day)
);

create table cp_audit_events (
    id bigint generated always as identity primary key,
    actor_type text not null check (actor_type in ('OPERATOR', 'INSTANCE', 'BUILD_IDENTITY', 'SYSTEM')),
    actor_id text not null,
    action text not null,
    entity_type text not null,
    entity_id text not null,
    trace_id char(32),
    details jsonb not null default '{}'::jsonb,
    occurred_at timestamptz not null default now()
);
create index cp_audit_events_entity_time_idx
    on cp_audit_events(entity_type, entity_id, occurred_at desc);
```

Add an index for active credential lookup on `credential_hash` where `revoked_at is null`, and indexes on deployment status/time and raw heartbeat retention time. Keep legacy `cp_instances.heartbeat_token_hash` and `cp_instance_heartbeats.metrics` untouched but stop writing them after the application change.

- [ ] **Step 4: Run migration and legacy integrity tests**

Run the Task 1 Maven command with `-Dtest=CpFleetFoundationMigrationTest,FlywayControlPlaneScriptIntegrityTest`.

Expected: PASS; V001–V006 apply on an empty PostgreSQL 18 database and the legacy hash is copied exactly once.

- [ ] **Step 5: Commit the schema foundation**

```powershell
git add apps/control-plane/pom.xml apps/control-plane/src/main/resources/db/migration/V006__fleet_foundation_control_plane.sql apps/control-plane/src/test/java/com/greenwhite/dwh/cp/support/CpPostgresIntegrationSupport.java apps/control-plane/src/test/java/com/greenwhite/dwh/cp/db/CpFleetFoundationMigrationTest.java apps/control-plane/src/test/java/com/greenwhite/dwh/cp/FlywayControlPlaneScriptIntegrityTest.java
git commit -m "feat(cp): add Fleet Foundation schema"
```

### Task 2: Implement one-time enrollment and credential lifecycle

**Files:**
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstancePrincipal.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpTokenGenerator.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceCredentialRepository.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceCredentialService.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/audit/CpAuditRepository.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/error/CpApiException.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpInstanceCredentialServiceTest.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpInstanceCredentialRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: V006 credential tables and `CpPasswordHasher.sha256`.
- Produces: `issueEnrollment`, `exchange`, `authenticate`, `rotate`, `revoke` and immutable `CpInstancePrincipal(instanceId, clientId, clientCode, credentialId)`.

- [ ] **Step 1: Write failing credential lifecycle unit tests**

The service contract is:

```java
public IssuedEnrollment issueEnrollment(long instanceId, long actorUserId);
public IssuedCredential exchange(String rawEnrollmentToken);
public Optional<CpInstancePrincipal> authenticate(String rawCredential);
public IssuedCredential rotate(CpInstancePrincipal principal);
public void revoke(long instanceId, long credentialId, long actorUserId);

public record IssuedEnrollment(long instanceId, String enrollmentToken, Instant expiresAt) {}
public record IssuedCredential(long instanceId, String credential, Instant previousValidUntil) {}
```

Use `Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC)` and a deterministic token generator returning 43-character Base64URL fixtures. Tests must assert:

```java
assertThat(service.exchange("enroll-raw").credential()).isEqualTo("credential-raw");
assertThatThrownBy(() -> service.exchange("enroll-raw"))
        .isInstanceOfSatisfying(CpApiException.class, error -> {
            assertThat(error.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(error.errorCode()).isEqualTo("instance_enrollment_invalid");
        });
assertThat(service.rotate(principal).previousValidUntil())
        .isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
```

Also verify expired enrollment, revoked credential and credential past the overlap window are rejected.

- [ ] **Step 2: Run the credential unit test and verify failure**

Run the Maven container command with `-Dtest=CpInstanceCredentialServiceTest -Dsurefire.failIfNoSpecifiedTests=false`.

Expected: FAIL because the credential classes do not exist.

- [ ] **Step 3: Implement secure generation and transactional lifecycle**

`CpTokenGenerator` must generate exactly 32 random bytes:

```java
@Component
public final class CpTokenGenerator {
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

Repository exchange must lock the enrollment row with `for update`, require `consumed_at is null and expires_at > now`, mark it consumed, and insert one credential in the same transaction. Rotation inserts a successor and sets predecessor `expires_at = now + interval '24 hours'`. Revoke updates only credentials belonging to the requested instance and returns the affected-row count.
Successful authentication updates `last_used_at` server-side without changing expiry or activation timestamps.

`CpApiException` is the only domain-to-HTTP exception used by new Control Plane capabilities:

```java
public final class CpApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public CpApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus status() { return status; }
    public String errorCode() { return errorCode; }
}
```

- [ ] **Step 4: Add PostgreSQL race/idempotency tests**

The integration test launches two concurrent `exchange("same-token")` calls with a `CountDownLatch`. Assert exactly one returns `IssuedCredential`, one returns 401, and the database contains one active credential. Add repository assertions that a credential for client A resolves only to A's `instance_id`, `client_id` and `client_code`.

- [ ] **Step 5: Prove credential persistence never exposes raw values**

Add PostgreSQL assertions that enrollment/credential tables contain only 64-character lowercase hashes, `last_used_at` changes on authentication, revoke is scoped by both instance and credential ID, and no repository read model has a raw-token field.

- [ ] **Step 6: Run unit and PostgreSQL integration tests**

Expected: all Task 2 tests PASS; plaintext tokens are absent from repository arguments captured by Mockito and from database queries.

- [ ] **Step 7: Commit credential lifecycle**

```powershell
git add apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance apps/control-plane/src/main/java/com/greenwhite/dwh/cp/audit apps/control-plane/src/main/java/com/greenwhite/dwh/cp/error/CpApiException.java apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance
git commit -m "feat(cp): add instance credential lifecycle"
```

### Task 3: Enforce the instance trust boundary in Spring Security

**Files:**
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceAuthFilter.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceAuthenticationEntryPoint.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceApiController.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceDeploymentMode.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceRepository.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceRegistrationService.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/api/CpEnrollmentRequest.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/api/CpEnrollmentResponse.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/api/CpCredentialRotationResponse.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/error/CpProblemDetailsHandler.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/error/CpRequestTraceFilter.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpInstanceAuthFilterTest.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpInstanceRegistrationServiceTest.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/config/CpSecurityConfigTest.java`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/config/CpSecurityConfig.java:34-79`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpFleetController.java:63-81`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/repository/CpFleetRepository.java:63-82`
- Modify: `apps/web-cp/src/app/core/cp-api.service.ts:75-79`
- Modify: `apps/web-cp/src/app/pages/clients.component.ts:41-59,280-330,662-668`
- Modify: `apps/web-cp/src/app/pages/operational-pages.spec.ts`
- Modify: `e2e/tests/browser/control-plane/fleet-registration.spec.ts`

**Interfaces:**
- Consumes: `CpInstanceCredentialService.authenticate(String)` and `CpInstancePrincipal`.
- Produces: Spring `Authentication` with principal `CpInstancePrincipal` and authority `ROLE_INSTANCE`, explicit mode-aware instance registration, one-time enrollment/rotation endpoints, and correlated RFC 9457 errors.

- [ ] **Step 1: Write failing filter tests**

Cover these exact cases:

```java
@Test
void validCredentialCreatesInstanceAuthentication() throws Exception {
    when(credentials.authenticate("raw-token")).thenReturn(Optional.of(
            new CpInstancePrincipal(11L, 7L, "alpha", 31L)));
    request.addHeader("X-Instance-Token", "raw-token");
    AtomicReference<Authentication> captured = new AtomicReference<>();
    FilterChain chain = (req, res) -> captured.set(SecurityContextHolder.getContext().getAuthentication());
    filter.doFilter(request, response, chain);
    assertThat(captured.get().getPrincipal())
            .isEqualTo(new CpInstancePrincipal(11L, 7L, "alpha", 31L));
}
```

Missing, blank, unknown, expired and revoked credentials return RFC 9457 `401` with `errorCode=instance_credential_invalid`; raw credential never appears in response or logs.

- [ ] **Step 2: Run the filter test to verify it fails**

Expected: FAIL because `CpInstanceAuthFilter` does not exist.

- [ ] **Step 3: Implement path-scoped authentication and switch registration atomically**

`POST /api/v1/instances` returns:

```json
{
  "instanceId": 42,
  "enrollmentToken": "one-time-base64url",
  "expiresAt": "2026-09-01T00:15:00Z"
}
```

The registration request is explicit about responsibility boundaries:

```java
public record RegisterInstanceDto(
        @NotBlank String clientCode,
        @Pattern(regexp = "production|staging|dev") String environment,
        @NotNull URI url,
        @NotNull CpInstanceDeploymentMode deploymentMode,
        @NotBlank @Size(max = 64) String jurisdiction,
        @NotBlank @Size(max = 64) String cloudProvider,
        @NotBlank @Size(max = 64) String storageProvider,
        @Size(max = 64) String edgeProvider,
        @NotBlank @Size(max = 64) String supportTier) {}
```

For `MANAGED_CLOUD`, `CpInstanceRegistrationService` requires `EU`, `HETZNER`, `CLOUDFLARE_R2`, `CLOUDFLARE` and `MANAGED_995`. For `CUSTOMER_HOSTED`, provider/jurisdiction values remain client-selected but nonblank, `edgeProvider` may be absent, and `supportTier` must be `CUSTOMER_HOSTED_SUPPORT`. `CpInstanceRepository.create` writes these fields and no raw/hashed heartbeat token. The service creates instance, enrollment and audit event in one transaction; `CpFleetController` only maps the DTO, calls `register(command, requireOperatorId())` and never returns `credential`. Remove create-instance writes from `CpFleetRepository`, leaving it as the fleet read model.

The filter authenticates only:

```text
POST /api/v1/instances/heartbeat
POST /api/v1/instances/backup-reports
GET  /api/v1/instances/desired-state
POST /api/v1/instances/credentials/rotate
```

It explicitly skips `POST /api/v1/instances/enroll`. Add the filter before `AuthorizationFilter`; configure enroll as `permitAll`, protected paths as `hasRole("INSTANCE")`, operator paths as authenticated. Remove heartbeat and backup paths from `PUBLIC_PATHS`.

`CpInstanceApiController` exposes enrollment and rotation now, and receives heartbeat/backup/desired methods in later tasks:

```text
POST /api/v1/instances/enroll
POST /api/v1/instances/credentials/rotate
```

`CpEnrollmentRequest` contains only `@NotBlank @Size(max=128) String enrollmentToken`. The response contains `instanceId` and one-time `credential`; rotation returns `instanceId`, one-time `credential` and `previousValidUntil`. Add operator-only `POST /api/v1/instances/{instanceId}/credentials/{credentialId}/revoke` to `CpFleetController`. In the same commit, replace operator instance registration with the `RegisterInstanceDto` defined above and call `issueEnrollment(id, requireOperatorId())`.

Update the Angular API type/modal and E2E registration flow in the same task: require explicit managed/customer-hosted mode, populate the approved managed provider constants, label the secret `Одноразовый enrollment-токен`, display expiry, and remove it from the DOM on close. The UI never writes it to URL, storage, analytics or console.

```typescript
export interface InstanceRegistrationRequest {
  clientCode: string;
  environment: 'production' | 'staging' | 'dev';
  url: string;
  deploymentMode: 'MANAGED_CLOUD' | 'CUSTOMER_HOSTED';
  jurisdiction: string;
  cloudProvider: string;
  storageProvider: string;
  edgeProvider: string | null;
  supportTier: 'MANAGED_995' | 'CUSTOMER_HOSTED_SUPPORT';
}

export interface InstanceEnrollment {
  instanceId: number;
  enrollmentToken: string;
  expiresAt: string;
}
```

`CpRequestTraceFilter` accepts a valid W3C `traceparent` trace ID or generates 32 lowercase hex characters, writes it to MDC/request attribute, adds `X-Trace-Id`, and clears MDC in `finally`. `CpProblemDetailsHandler` maps `CpApiException`, bean validation, malformed/unknown JSON and unhandled errors to `application/problem+json` with `status`, `errorCode`, `detail`, `instance` and `traceId`; it never returns a stack trace.

- [ ] **Step 4: Add security-chain regression tests**

Use `@SpringBootTest` + `@AutoConfigureMockMvc` with PostgreSQL Testcontainers. Assert protected instance paths return 401 without a credential, operator cookie cannot impersonate an instance, valid instance credential cannot call `/api/v1/clients`, enrollment replay returns `instance_enrollment_invalid`, and every error carries the same trace ID in body/header. `CpInstanceRegistrationServiceTest` covers every managed/customer provider combination and proves a failed enrollment insert rolls back instance creation. Run the Angular component test and Playwright registration spec so the backend contract cannot leave the operator UI broken.

- [ ] **Step 5: Run Control Plane security tests**

Run focused Maven tests, then:

```powershell
docker run --rm -v "${PWD}/apps/web-cp:/workspace" -w /workspace node:24.15.0-alpine sh -lc "npm ci && npm test && npm run typecheck && npm run build"
Set-Location e2e
npm ci
npx playwright test tests/browser/control-plane/fleet-registration.spec.ts
Set-Location ..
```

Expected: filter tests, `CpSecurityConfigTest`, web-cp unit/typecheck/build and Playwright fleet-registration PASS.

- [ ] **Step 6: Commit the trust boundary**

```powershell
git add apps/control-plane/src/main/java/com/greenwhite/dwh/cp/config/CpSecurityConfig.java apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance apps/control-plane/src/main/java/com/greenwhite/dwh/cp/error apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpFleetController.java apps/control-plane/src/main/java/com/greenwhite/dwh/cp/repository/CpFleetRepository.java apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpInstanceAuthFilterTest.java apps/control-plane/src/test/java/com/greenwhite/dwh/cp/config/CpSecurityConfigTest.java apps/web-cp/src/app/core/cp-api.service.ts apps/web-cp/src/app/pages/clients.component.ts apps/web-cp/src/app/pages/operational-pages.spec.ts e2e/tests/browser/control-plane/fleet-registration.spec.ts
git commit -m "fix(cp): enforce instance trust boundary"
```

### Task 4: Close backup-report IDOR with authenticated ownership

**Files:**
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/api/CpBackupReportRequest.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpBackupReportRepository.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpBackupReportService.java`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceApiController.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpBackupOwnershipIntegrationTest.java`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpFleetController.java`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/repository/CpFleetRepository.java:177-209`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpHeartbeatController.java` (remove only legacy backup endpoint; keep heartbeat operational until Task 5)

**Interfaces:**
- Consumes: authenticated `CpInstancePrincipal` from Task 3.
- Produces: `POST /api/v1/instances/backup-reports` whose persistence key is `principal.instanceId()`; request has no client identifier, plus an operator projection at `GET /api/v1/backup-reports`.

- [ ] **Step 1: Write the failing cross-client attack test**

The accepted request is exactly:

```java
public record CpBackupReportRequest(
        @NotNull UUID backupId,
        @NotNull ArtifactStatus status,
        @Pattern(regexp = "[0-9a-f]{64}") String checksumSha256,
        @Min(0) @Max(86_400) Integer durationSec,
        @NotNull @PastOrPresent Instant completedAt,
        @Pattern(regexp = "[a-z0-9_]{1,64}") String reasonCode) {
    public enum ArtifactStatus { UPLOADED, FAILED }
}
```

Operators read the principal-derived projection through `GET /api/v1/backup-reports` (`cp-engineer` or `cp-admin`). Each row contains `backupId`, `instanceId`, derived `clientCode`, artifact status, checksum, duration, reason code, completed/received/verified timestamps; it never contains a credential or object URL.

Send JSON containing `"clientCode":"victim"` while authenticated as attacker. With strict unknown-field handling, assert `400` and zero victim rows. Send the valid body without `clientCode`, assert `202` and one row joined through the authenticated attacker's instance. The instance request enum deliberately excludes `VERIFIED`.

- [ ] **Step 2: Run the ownership test and verify the current vulnerability**

Before implementation, the test must fail because the current DTO accepts `clientCode` and resolves the target by body value.

- [ ] **Step 3: Implement principal-bound persistence**

The service signature is:

```java
@Transactional
public void recordBackup(CpInstancePrincipal principal, CpBackupReportRequest request) {
    repository.recordArtifact(
            principal.instanceId(),
            request.backupId(),
            request.status(),
            request.checksumSha256(),
            request.durationSec() == null ? 0 : request.durationSec(),
            request.completedAt(),
            request.reasonCode());
}
```

Set `spring.jackson.deserialization.fail-on-unknown-properties: true`. Add `@Valid` and `@AuthenticationPrincipal CpInstancePrincipal` to the controller method. Repository SQL stores only `principal.instanceId()`; client ownership is derived by joining `cp_instances.client_id`. A duplicate `backupId` with identical fields is idempotent; conflicting content returns `backup_report_conflict`. `UPLOADED` requires checksum, `FAILED` forbids checksum, and only a future Storage verification service may transition the stored status to `VERIFIED`.

- [ ] **Step 4: Add negative validation cases**

Assert 400 for negative duration, duration above 86,400, future completion time, malformed checksum, `UPLOADED` without checksum, `FAILED` with checksum, `VERIFIED` from an instance, unknown property and missing status. Assert 401 for a revoked credential.

- [ ] **Step 5: Run the focused security suite**

Expected: `CpBackupOwnershipIntegrationTest`, `CpSecurityConfigTest` and credential tests PASS.

- [ ] **Step 6: Commit the IDOR fix**

```powershell
git add apps/control-plane/src/main/resources/application.yml apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpFleetController.java apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpHeartbeatController.java apps/control-plane/src/main/java/com/greenwhite/dwh/cp/repository/CpFleetRepository.java apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance
git commit -m "fix(cp): bind backup reports to instance identity"
```

### Task 5: Replace arbitrary heartbeat JSON with a typed contract

**Files:**
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/api/CpHeartbeatRequest.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/api/CpHeartbeatResponse.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpHeartbeatRepository.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpHeartbeatService.java`
- Delete: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpHeartbeatController.java` after its heartbeat method is moved to `CpInstanceApiController`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpHeartbeatContractIntegrationTest.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/CpTelemetrySnapshot.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/CpHeartbeatPayload.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/CpHeartbeatReply.java`
- Create: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/CpControlPlaneClient.java`
- Modify: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/CpTelemetryRepository.java:40-53`
- Modify: `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp/CpHeartbeatWorker.java:74-102`
- Modify: `apps/instance/src/test/java/com/greenwhite/dwh/instance/config/cp/CpHeartbeatWorkerTest.java`

**Interfaces:**
- Consumes: `CpHeartbeatService` and principal-bound controller from Task 4.
- Produces: fixed heartbeat fields and an instance-side typed producer with no `Map<String,Object>` wire payload.

- [ ] **Step 1: Define the failing strict-contract matrix**

`CpHeartbeatRequest` has this exact public shape:

```java
public record CpHeartbeatRequest(
        @NotBlank @Size(max = 64) String appVersion,
        @NotBlank @Size(max = 32) String schemaVersion,
        @Size(max = 64) String releaseVersion,
        @Size(max = 64) String configVersion,
        @Valid ComponentHealth components,
        @Valid StorageTelemetry storage,
        @Valid BackupTelemetry backup,
        @Valid AgentTelemetry agents,
        @Pattern(regexp = "IDLE|REQUESTED|PREFLIGHT|PREFLIGHT_FAILED|BACKUP_VERIFIED|BACKUP_FAILED|MIGRATING|DEPLOYING|VERIFYING|SUCCEEDED|ROLLING_BACK|ROLLED_BACK|RECOVERY_REQUIRED|CANCELLED") String deploymentState,
        @Valid CapacityTelemetry capacity) {

    public record ComponentHealth(Health app, Health database, Health typesense, Health objectStorage) {}
    public record StorageTelemetry(@PositiveOrZero long usedBytes, @PositiveOrZero long quotaBytes) {}
    public record BackupTelemetry(Instant lastCompletedAt, BackupStatus status) {}
    public record AgentTelemetry(Health tunnel, Health telemetry) {}
    public record CapacityTelemetry(@PositiveOrZero long activeUsers,
                                    @PositiveOrZero long outboxPending,
                                    @PositiveOrZero long outboxDeadLetter) {}
    public enum Health { UP, DEGRADED, DOWN, UNKNOWN }
    public enum BackupStatus { UNKNOWN, UPLOADED, VERIFIED, FAILED }
}
```

The integration test rejects `users`, `emails`, `fileNames`, arbitrary `metrics`, negative counters and unknown nested properties. It accepts only the declared shape and records the authenticated instance ID.

- [ ] **Step 2: Run the contract test and verify failure**

Expected: FAIL because the current endpoint accepts `Map<String,Object> metrics`.

- [ ] **Step 3: Implement typed server persistence**

Map each fixed field to V006 columns. `ComponentHealth` is serialized to JSON only after Jackson has materialized the fixed record; no request-owned map is stored. Serialization failure throws a stable `telemetry_serialization_failed` server error instead of silently writing `{}`.

Return:

```java
public record CpHeartbeatResponse(
        boolean accepted,
        long instanceId,
        String licenseStatus,
        String resourceProfile,
        long desiredGeneration) {}
```

The response does not repeat `clientCode`.

- [ ] **Step 4: Refactor the instance producer**

`CpTelemetryRepository.snapshot()` returns:

```java
public record CpTelemetrySnapshot(
        long activeUsers,
        long outboxPending,
        long outboxDeadLetter,
        long storageUsedBytes,
        long storageQuotaBytes) {}
```

The instance-side wire type mirrors the server contract without importing the Control Plane module:

```java
public record CpHeartbeatPayload(
        String appVersion,
        String schemaVersion,
        String releaseVersion,
        String configVersion,
        ComponentHealth components,
        StorageTelemetry storage,
        BackupTelemetry backup,
        AgentTelemetry agents,
        String deploymentState,
        CapacityTelemetry capacity) {
    public record ComponentHealth(String app, String database, String typesense, String objectStorage) {}
    public record StorageTelemetry(long usedBytes, long quotaBytes) {}
    public record BackupTelemetry(Instant lastCompletedAt, String status) {}
    public record AgentTelemetry(String tunnel, String telemetry) {}
    public record CapacityTelemetry(long activeUsers, long outboxPending, long outboxDeadLetter) {}
}

public record CpHeartbeatReply(
        boolean accepted,
        long instanceId,
        String licenseStatus,
        String resourceProfile,
        long desiredGeneration) {}
```

`CpControlPlaneClient.sendHeartbeat(CpHeartbeatPayload payload)` returns `CpHeartbeatReply` and owns HTTP timeout, URL and `X-Instance-Token`. `CpHeartbeatWorker` only builds the payload, calls the client and applies the returned license status. Do not send user counts other than the anonymous aggregate `activeUsers`; do not send task count, filenames, emails or stack traces.

- [ ] **Step 5: Add instance producer tests**

Capture `CpHeartbeatPayload` passed to a mocked client and assert exact values. Assert a delivery exception remains fail-open for tenant business functions and the token is absent from the log message.

- [ ] **Step 6: Run both modules' focused tests**

Run Maven with `-pl apps/control-plane,apps/instance -am` and the Task 5 test classes. Expected: PASS.

- [ ] **Step 7: Commit typed telemetry**

```powershell
git add apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance apps/instance/src/main/java/com/greenwhite/dwh/instance/config/cp apps/instance/src/test/java/com/greenwhite/dwh/instance/config/cp
git rm apps/control-plane/src/main/java/com/greenwhite/dwh/cp/controller/CpHeartbeatController.java
git commit -m "feat(cp): enforce typed instance telemetry"
```

### Task 6: Add payload, rate and retention controls

**Files:**
- Modify: `apps/control-plane/pom.xml`
- Modify: `apps/control-plane/src/main/resources/application.yml`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceRequestGuardFilter.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpHeartbeatRetentionJob.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpInstanceRequestGuardFilterTest.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance/CpHeartbeatRetentionIntegrationTest.java`

**Interfaces:**
- Consumes: `ROLE_INSTANCE` authentication and typed heartbeat rows.
- Produces: hard 16 KiB limit, two-heartbeats/minute token bucket, daily aggregates and bounded deletion.

- [ ] **Step 1: Write failing request guard tests**

Assert:

```java
mvc.perform(post("/api/v1/instances/heartbeat")
        .header("X-Instance-Token", credential)
        .contentType(MediaType.APPLICATION_JSON)
        .content(bodyOfBytes(16_384)))
        .andExpect(status().isOk());
mvc.perform(post("/api/v1/instances/heartbeat")
        .header("X-Instance-Token", credential)
        .contentType(MediaType.APPLICATION_JSON)
        .content(bodyOfBytes(16_385)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.errorCode").value("instance_payload_too_large"));
mvc.perform(post("/api/v1/instances/heartbeat")
        .header("X-Instance-Token", credential)
        .contentType(MediaType.APPLICATION_JSON)
        .content(validBody))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"));
```

For the 429 assertion, first consume two tokens with two successful requests in the same test clock minute. `bodyOfBytes(int)` is a test helper that returns a syntactically valid heartbeat JSON padded by a fixed string field; it asserts the resulting UTF-8 byte length before returning. Include a chunked request without `Content-Length`; it must still stop after byte 16,385. The 413 and 429 bodies must be RFC 9457 with `errorCode=instance_payload_too_large` and `instance_rate_limited`.

- [ ] **Step 2: Run guard tests and verify failure**

Expected: FAIL because no request-size/rate guard exists.

- [ ] **Step 3: Implement the guard**

Add Bucket4j to Control Plane. Key buckets by authenticated `instanceId`, capacity two, refill two tokens every 60 seconds. Wrap `ServletInputStream` with a counting stream so chunked transfer cannot bypass the limit. Apply size limiting to all instance POST requests and rate limiting only to heartbeat.

Configure exact values:

```yaml
dwh:
  cp:
    instance-api:
      max-body-bytes: 16384
      heartbeat-capacity: 2
      heartbeat-refill: 1m
      raw-retention: 30d
      aggregate-retention: 395d
```

- [ ] **Step 4: Write retention tests with fixed time**

Insert heartbeats at 31 days, 30 days and one day old. Run `aggregateAndPrune()`. Assert the 31-day row is deleted, the boundary and newer rows remain, the daily aggregate is upserted idempotently, and aggregates older than 395 days are deleted in batches of 10,000.

- [ ] **Step 5: Implement observable retention**

`CpHeartbeatRetentionJob` runs daily at `02:15 GMT+5`, uses a DB advisory lock, aggregates completed UTC days, deletes bounded batches, records Micrometer counters `dwh_cp_heartbeat_retention_deleted_total` and `dwh_cp_heartbeat_retention_failures_total`, and logs IDs/counts but no telemetry payload.

- [ ] **Step 6: Run guard and retention tests**

Expected: PASS, including duplicate job execution and advisory-lock contention.

- [ ] **Step 7: Commit API resource controls**

```powershell
git add apps/control-plane/pom.xml apps/control-plane/src/main/resources/application.yml apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceRequestGuardFilter.java apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpHeartbeatRetentionJob.java apps/control-plane/src/test/java/com/greenwhite/dwh/cp/instance
git commit -m "feat(cp): bound heartbeat ingestion and retention"
```

### Task 7: Implement the immutable release catalog

**Files:**
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/release/CpRelease.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/release/CpReleaseRepository.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/release/CpReleaseService.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/release/CpReleaseController.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/release/CpReleaseServiceTest.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/release/CpReleaseRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: V006 release tables, verified metadata from the future supply-chain adapter, and operator roles for read/revoke.
- Produces: immutable `READY` release metadata used by desired state in Task 8; this slice deliberately exposes no operator endpoint that can claim an unverified release is ready.

- [ ] **Step 1: Write failing release invariant tests**

Define:

```java
public enum ReleaseStatus { DRAFT, READY, REVOKED }
public enum DeploymentMode { MANAGED_CLOUD, CUSTOMER_HOSTED }

public record ReleaseComponent(
        String name,
        String imageReference,
        String imageDigest,
        String sbomDigest,
        String provenanceDigest,
        String minimumSchemaVersion,
        String maximumRollbackSchemaVersion) {}

public record VerifiedReleaseCommand(
        String version,
        String sourceCommit,
        String manifestDigest,
        URI manifestLocation,
        String verificationBundleDigest,
        String configSchemaVersion,
        String minimumAgentVersion,
        Set<DeploymentMode> deploymentModes,
        List<ReleaseComponent> components) {}

public record CpRelease(
        UUID id,
        String version,
        String sourceCommit,
        String manifestDigest,
        URI manifestLocation,
        String verificationBundleDigest,
        String configSchemaVersion,
        String minimumAgentVersion,
        Set<DeploymentMode> deploymentModes,
        ReleaseStatus status,
        List<ReleaseComponent> components,
        Instant createdAt) {}
```

Tests reject mutable tags, non-SHA digests, mismatched `imageReference`/`imageDigest`, duplicate component names, invalid 40-hex source commit, empty deployment modes and a second release with the same semver. Tests also prove `READY` cannot be edited and `REVOKED` cannot return to `READY`.

- [ ] **Step 2: Run the release test and verify failure**

Expected: FAIL because the release package does not exist.

- [ ] **Step 3: Implement transactional catalog operations**

Service signatures:

```java
public UUID registerVerified(VerifiedReleaseCommand command, String buildIdentity);
public CpRelease revoke(UUID releaseId, String reason, long actorUserId);
public List<CpRelease> list();
public CpRelease requireReady(UUID releaseId);
```

Use regex `^[0-9a-f]{40}$` for commit and `^sha256:[0-9a-f]{64}$` for digests. `registerVerified` requires a `verificationBundleDigest`, checks at least `instance`, `web`, `postgres`, `typesense` and `proxy` components, writes a `READY` release and one audit event atomically, and is idempotent only when version, manifest digest and every component are equal. The future supply-chain slice owns the authenticated adapter and cryptographic verification before this method is called.

- [ ] **Step 4: Add operator APIs**

Expose:

```text
GET  /api/v1/releases                 cp-engineer or cp-admin
POST /api/v1/releases/{id}/revoke     cp-admin
```

No operator HTTP endpoint creates or marks a release `READY`. Revoke accepts only a bounded reason; no registry credential, private key or raw signature key is accepted.

- [ ] **Step 5: Run unit and PostgreSQL release tests**

Expected: PASS; an attempted update after `READY` leaves every release/component column unchanged.

- [ ] **Step 6: Commit release catalog**

```powershell
git add apps/control-plane/src/main/java/com/greenwhite/dwh/cp/release apps/control-plane/src/test/java/com/greenwhite/dwh/cp/release
git commit -m "feat(cp): add immutable release catalog"
```

### Task 8: Implement desired generation and instance reconciliation contract

**Files:**
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpTarget.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpTargetRepository.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpTargetService.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpTargetController.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/api/CpDesiredStateResponse.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/deployment/CpTargetServiceIntegrationTest.java`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance/CpInstanceApiController.java`

**Interfaces:**
- Consumes: `CpReleaseService.requireReady`, principal identity and V006 target table.
- Produces: monotonically increasing desired generation and authenticated instance response.

- [ ] **Step 1: Write failing generation and isolation tests**

Define:

```java
public enum RolloutRing { R0, R1, R2, R3 }

public record MaintenanceWindow(
        @Min(1) @Max(5) int weekOfMonth,
        @Min(1) @Max(7) int dayOfWeek,
        @NotNull LocalTime start,
        @Min(15) @Max(240) int durationMinutes,
        @NotBlank String timezone) {}

public record AssignTargetCommand(
        @NotNull UUID releaseId,
        @NotBlank @Size(max = 64) String configVersion,
        @NotNull RolloutRing ring,
        @Valid @NotNull MaintenanceWindow maintenanceWindow) {}
```

Tests assign generation 1, then 2 to the same instance; two concurrent assignments produce distinct generations. Instance A cannot retrieve instance B's target because `desiredState` takes only `CpInstancePrincipal`.

- [ ] **Step 2: Run target tests and verify failure**

Expected: FAIL because target classes do not exist.

- [ ] **Step 3: Implement serial generation assignment**

Lock `cp_instances` by ID before reading/upserting target. Reject unknown instance, non-`READY` or `REVOKED` release and a release whose `deployment_modes` excludes the instance mode. Write `requested_by`, `requested_at`, generation and audit event in one transaction.

- [ ] **Step 4: Implement operator and instance APIs**

Expose:

```text
PUT /api/v1/instances/{id}/target      cp-admin
GET /api/v1/instances/desired-state   ROLE_INSTANCE
```

The instance response is:

```java
public record CpDesiredStateResponse(
        long generation,
        UUID releaseId,
        String releaseVersion,
        String manifestDigest,
        URI manifestLocation,
        String configVersion,
        MaintenanceWindow maintenanceWindow,
        AllowedAction allowedAction) {
    public enum AllowedAction { NONE, APPLY_RELEASE }
}
```

Return `NONE` only when `cp_instances.current_generation`, current release and current config all equal the target.
If the authenticated instance has no target, return `204 No Content`; never synthesize generation zero or expose another instance's target.

- [ ] **Step 5: Run target integration and security tests**

Expected: PASS; revoked release assignment returns stable `release_not_assignable`, cross-instance retrieval is impossible by API shape.

- [ ] **Step 6: Commit desired state**

```powershell
git add apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment apps/control-plane/src/main/java/com/greenwhite/dwh/cp/instance apps/control-plane/src/test/java/com/greenwhite/dwh/cp/deployment
git commit -m "feat(cp): add declarative desired state"
```

### Task 9: Implement deployment history and state-machine invariants

**Files:**
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpDeploymentStatus.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpDeploymentStateMachine.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpDeploymentRepository.java`
- Create: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpDeploymentService.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/deployment/CpDeploymentStateMachineTest.java`
- Create: `apps/control-plane/src/test/java/com/greenwhite/dwh/cp/deployment/CpDeploymentRepositoryIntegrationTest.java`
- Modify: `apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment/CpTargetService.java`

**Interfaces:**
- Consumes: target `(instanceId, generation, releaseId)` from Task 8.
- Produces: one idempotent `REQUESTED` deployment per generation, append-only sequenced events and reusable transition service for the runner slice.

- [ ] **Step 1: Write the failing state transition matrix**

Use this enum:

```java
public enum CpDeploymentStatus {
    REQUESTED, PREFLIGHT, PREFLIGHT_FAILED, BACKUP_VERIFIED, BACKUP_FAILED,
    MIGRATING, DEPLOYING, VERIFYING, SUCCEEDED,
    ROLLING_BACK, ROLLED_BACK, RECOVERY_REQUIRED, CANCELLED
}

public record CpDeployment(
        UUID id,
        long instanceId,
        UUID releaseId,
        long generation,
        UUID previousReleaseId,
        String runnerIdentity,
        CpDeploymentStatus status,
        String reasonCode,
        String technicalLogReference,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt) {}
```

Tests permit the exact normal and failure paths from design section 12. They reject `REQUESTED -> DEPLOYING`, every transition out of `SUCCEEDED`, rollback without previous release, and `CANCELLED` after `MIGRATING`.

- [ ] **Step 2: Run state-machine tests and verify failure**

Expected: FAIL because the state machine does not exist.

- [ ] **Step 3: Implement pure transition rules**

The state machine API is:

```java
public void requireTransition(CpDeploymentStatus current,
                              CpDeploymentStatus next,
                              boolean previousReleaseAvailable);
```

It has no repository or Spring dependency and throws `CpDeploymentTransitionException(current, next)` with stable code `deployment_transition_invalid`.

- [ ] **Step 4: Implement idempotent persistence**

Repository methods:

```java
public UUID createRequested(long instanceId, UUID releaseId, long generation, UUID previousReleaseId);
public Optional<CpDeployment> findByInstanceAndGeneration(long instanceId, long generation);
public CpDeployment lock(UUID deploymentId);
public boolean appendEvent(UUID deploymentId, long sequence, String idempotencyKey,
                           CpDeploymentStatus status, String reasonCode, String details);
public void updateStatus(UUID deploymentId, CpDeploymentStatus expected,
                         CpDeploymentStatus next, String reasonCode);
```

`createRequested` uses `on conflict(instance_id, generation) do nothing` and returns the existing row. Event duplicate `(deployment_id, sequence_no)` or `idempotency_key` is a no-op only when its stored content is byte-for-byte equal; conflicting replay returns `deployment_event_conflict`.

- [ ] **Step 5: Link target assignment to deployment request**

After assigning a target, `CpTargetService` calls `createRequested` inside the same transaction and appends sequence 1 with status `REQUESTED`. No job claim or shell execution is added in this slice.

- [ ] **Step 6: Run concurrency and idempotency tests**

Two concurrent assignments/requests for one generation must produce one deployment row and one sequence-1 event. Invalid transition leaves current status and events unchanged.

- [ ] **Step 7: Commit deployment history**

```powershell
git add apps/control-plane/src/main/java/com/greenwhite/dwh/cp/deployment apps/control-plane/src/test/java/com/greenwhite/dwh/cp/deployment
git commit -m "feat(cp): add deployment state history"
```

### Task 10: Complete operator backup UI, live smoke and API documentation

**Files:**
- Modify: `apps/web-cp/src/app/core/cp-api.service.ts:75-79`
- Modify: `apps/web-cp/src/app/pages/backups.component.ts`
- Modify: `apps/web-cp/src/app/pages/operational-pages.spec.ts`
- Modify: `scripts/dev/test-cp-api.ps1:82-118`
- Create: `docs/api/control-plane-instance-v1.md`
- Modify: `docs/ops/deployment-guide.md:180-200`
- Modify: `docs/ops/architecture-overview.md`

**Interfaces:**
- Consumes: enrollment, heartbeat, backup, release and desired-state endpoints from Tasks 2–9.
- Produces: operator-visible backup artifact state, executable live contract smoke and source-of-truth API documentation.

- [ ] **Step 1: Add failing backup-report UI expectations**

Add this UI contract:

```typescript
export interface InstanceBackupReport {
  backupId: string;
  instanceId: number;
  clientCode: string;
  artifactStatus: 'UPLOADED' | 'VERIFIED' | 'FAILED';
  checksumSha256: string | null;
  durationSec: number;
  reasonCode: string | null;
  completedAt: string;
  receivedAt: string;
  verifiedAt: string | null;
}
```

Add `backupReports()` to `CpApiService` and show artifact status separately from legacy restore verification in the backups page. Unit tests distinguish `UPLOADED` from `VERIFIED`, display `FAILED` reason code, and never render checksum as an object URL or clickable credential.

- [ ] **Step 2: Run web-cp unit and E2E tests to verify failure**

Run:

```powershell
docker run --rm -v "${PWD}/apps/web-cp:/workspace" -w /workspace node:24.15.0-alpine sh -lc "npm ci && npm test && npm run typecheck && npm run build"
```

Expected: FAIL until the backup API type and component projection are updated.

- [ ] **Step 3: Implement the backup artifact projection**

Keep the existing restore-verification table and add a clearly separate artifact-report section. `UPLOADED` means artifact presence only; only `VERIFIED` uses the verified badge. Do not render an object key, presigned URL or raw technical payload.

- [ ] **Step 4: Replace the live API smoke flow**

`test-cp-api.ps1` must:

1. create client A and instance A;
2. exchange `enrollmentToken` through `/api/v1/instances/enroll`;
3. prove the same enrollment token returns 401 on second exchange;
4. send a typed heartbeat with the returned credential;
5. create client B and instance B;
6. submit a valid backup report as A and verify it appears under A;
7. submit a body containing B's `clientCode` as A and require 400;
8. call desired-state before assignment and require `204 No Content`; assignment/reconciliation with a verified release remains covered by PostgreSQL integration tests until the release supply-chain adapter exists.

All generated tokens stay in variables and are never printed.

- [ ] **Step 5: Document exact API schemas and errors**

`docs/api/control-plane-instance-v1.md` includes every request/response field, size limit, rate limit, token lifecycle, status code, `errorCode`, example without real secrets, and N/N-1 compatibility note. Update deployment guide to distinguish enrollment token from runtime credential.

- [ ] **Step 6: Run frontend, browser and live CP tests**

Expected: web-cp tests/build PASS, Playwright fleet registration PASS, PowerShell live API suite PASS, and no token appears in Playwright artifacts.

- [ ] **Step 7: Commit clients and documentation**

```powershell
git add apps/web-cp/src/app scripts/dev/test-cp-api.ps1 docs/api/control-plane-instance-v1.md docs/ops/deployment-guide.md docs/ops/architecture-overview.md
git commit -m "docs(cp): publish secure instance enrollment contract"
```

### Task 11: Run full release gates and record evidence

**Files:**
- Modify: `audit/fixes/00-implementation-tracker.md`
- Create: `audit/evidence/fleet-foundation-cp-contract-2026-09-01.md`
- Modify: `graphify-out/*` only when `graphify update .` produces semantically relevant graph changes with the project-compatible Graphify version.

**Interfaces:**
- Consumes: every prior task and existing CI gates.
- Produces: immutable verification evidence for the first Fleet Foundation slice.

- [ ] **Step 1: Run the full backend gate**

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace -v /var/run/docker.sock:/var/run/docker.sock maven:3.9.11-eclipse-temurin-25 mvn -B verify
```

Expected: all modules PASS; no skipped Docker integration test on the release workstation.

- [ ] **Step 2: Run both frontend gates**

Run `npm ci`, `npm test`, `npm run typecheck` and `npm run build` in Node 24.15.0 containers for `apps/web-instance` and `apps/web-cp`.

Expected: all commands PASS.

- [ ] **Step 3: Run clean Compose, security and Playwright gates**

```powershell
./scripts/prod/test-release-config.ps1
./scripts/security/scan-runtime-images.ps1
./scripts/dev/test-e2e.ps1
```

Expected: production config, runtime image policy and all critical browser flows PASS; failure artifacts contain no credential.

- [ ] **Step 4: Verify schema upgrade and negative security paths**

Run V001–V005 fixture → V006 migration, clean V001–V006 migration, one-time enrollment replay, revoked credential, cross-client backup body, oversized/chunked heartbeat, third heartbeat in one minute, revoked release assignment and conflicting deployment-event replay.

Expected: migrations succeed; every negative request fails closed with the documented status/error code and no cross-client write.

- [ ] **Step 5: Refresh the graph safely**

Run:

```powershell
graphify update .
graphify query "Control Plane instance credential release desired state deployment history"
```

If Graphify reports a project-skill/package version mismatch and rewrites unrelated community labels, exclude those mechanical changes and record the mismatch in evidence. Remove `graphify-out/cache/last_query_stamp` before commit.

- [ ] **Step 6: Write verification evidence and update tracker**

Evidence records command, exit code, test counts, migration versions, security-negative cases, commit SHA and known remaining scope. Mark only the Control Plane contract/security slice verified; release supply chain, runtime separation, IaC, runner and observability remain open.

- [ ] **Step 7: Inspect and commit the final evidence**

```powershell
git diff --check
git status --short
git add audit/fixes/00-implementation-tracker.md audit/evidence/fleet-foundation-cp-contract-2026-09-01.md
# Add only explicitly reviewed semantic graph files when the project-compatible Graphify version changed them.
git commit -m "test(cp): verify Fleet Foundation contracts"
```

- [ ] **Step 8: Push and verify remote CI**

```powershell
git push origin main
git ls-remote origin refs/heads/main
```

Expected: remote SHA equals local SHA and required GitHub CI checks are green before this slice is reported complete.

---

## Plan Acceptance Criteria

- Backup-report IDOR has a PostgreSQL-backed cross-client regression test.
- Enrollment is one-time and short-lived; credentials support overlap rotation and immediate revoke.
- Protected instance endpoints cannot be reached with an operator cookie or another instance's identity.
- Heartbeat accepts only the fixed telemetry allowlist, is bounded to 16 KiB and two requests/minute, and has verified retention.
- Releases are immutable after `READY`, digest-addressed and cannot be assigned after revoke.
- Desired state is principal-bound and generation is monotonic under concurrency.
- Deployment requests/events are idempotent and transition-safe without adding arbitrary command execution.
- UI, scripts and docs distinguish enrollment token from runtime credential and do not leak either.
- Full backend/frontend/E2E/security gates and remote CI pass on one immutable SHA.
