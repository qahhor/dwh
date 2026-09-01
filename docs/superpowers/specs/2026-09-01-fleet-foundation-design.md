# Fleet Foundation A+ — архитектурный дизайн

**Дата:** 2026-09-01

**Статус:** секции 1–6 утверждены; документ подготовлен для итогового письменного review

**Область:** только Fleet Foundation; продуктовые функции CMS не расширяются

## 1. Решение

Fleet Foundation строится по модели **A+**:

- один изолированный runtime stack и одна VM на клиента;
- один Hetzner Project на каждого клиента нашего managed cloud;
- Docker Compose остаётся runtime-оркестратором tenant stack;
- Terraform управляет инфраструктурой;
- Ansible выполняет bootstrap, deploy и restore orchestration;
- Control Plane хранит declarative desired/current state и audit, но не выполняет произвольные команды;
- deployment runner исполняет только типизированные задания;
- релизы являются immutable, адресуются OCI digest и проверяются по подписи;
- managed cloud использует Hetzner для compute/DB и Cloudflare для edge/security и R2;
- customer-hosted работает в выбранной клиентом стране и инфраструктуре через подписанный pull-механизм;
- RTO обеспечивается автоматизированным rebuild/restore, а не HA-кластером.

Nomad, Kubernetes, active-active и переписывание модульного монолита не требуются для GA.

## 2. Исходные ограничения

Дизайн рассчитан на следующие подтверждённые вводные:

- публичный GA через четыре месяца;
- около 100 клиентов;
- около 500 зарегистрированных пользователей;
- до 100 одновременно активных пользователей на всём флоте;
- около 50 GB новых файлов в месяц;
- физическая изоляция stack/database на клиента;
- managed-cloud SLA 99,5% в месяц;
- customer-hosted получает поддержку без инфраструктурного SLA;
- managed-cloud RPO 24 часа для DB и файлов;
- managed-cloud RTO 1 час;
- штатное maintenance window: первое воскресенье месяца, 02:00–03:00 GMT+5;
- P1 support managed cloud: 24/7, первичная реакция не более 30 минут;
- P1 escalation: Telegram и email немедленно, телефон через 10 минут без подтверждения;
- данные включают контактные сведения, данные сотрудников/эффективности, документы/сканы и финансовую информацию;
- завершение договора: 90 дней хранения, затем удаление DB, объектов, логов и backup, кроме документированного legal hold;
- customer-hosted имеет постоянный исходящий HTTPS к Control Plane и не требует входящего соединения со стороны платформы.

## 3. Доказанные проблемы текущего контура

Дизайн заменяет следующие подтверждённые несоответствия:

1. `deploy/compose/docker-compose.fleet.prod.yml` объединяет tenant и Control Plane в одном stack, хотя `docs/ops/architecture-overview.md` определяет их как отдельные контуры.
2. `deploy/compose/docker-compose.prod.yml` не является полным tenant release unit: в нём отсутствуют web, proxy и Typesense.
3. `scripts/prod/deploy.*` загружает mutable tags, строит инфраструктурные образы на целевом узле и не записывает проверяемый deployment result.
4. Автоматического rollback и schema compatibility decision нет.
5. В репозитории отсутствуют Terraform и Ansible production-модули.
6. CI проверяет код, но не выпускает подписанные OCI artifacts, image-bound SBOM и provenance.
7. Control Plane хранит actual version и heartbeat, но не хранит releases, desired generation, rollout и deployment attempts.
8. Backup report принимает `clientCode` из тела после instance authentication; это позволяет одному instance приписать отчёт другому клиенту.
9. Heartbeat принимает произвольный JSON и сохраняет запись каждые пять минут без retention policy.
10. Actuator/Prometheus endpoints существуют, но production metrics/logs/alerts stack отсутствует.
11. Runbooks и ADR одновременно описывают Compose, Nomad, Vault и WAL-G; часть ссылок указывает на отсутствующие файлы.

Существующий модульный монолит, Flyway schema gate, backend/frontend приложения и provider SPI сохраняются.

## 4. Границы системы

Fleet состоит из двух независимых release unit.

### 4.1 Platform Stack

Содержит:

- Control Plane API;
- Control Plane UI;
- Control Plane PostgreSQL;
- deployment runner;
- release registry metadata;
- observability stack;
- notification bridge;
- platform Terraform state и Ansible inventory metadata.

Platform Stack не является data-plane dependency tenant-приложения. Его недоступность не блокирует обычную работу клиента.

### 4.2 Tenant Stack

Содержит:

- internal NGINX;
- Angular web instance;
- Spring Boot instance, ровно одна replica;
- PostgreSQL 18;
- Typesense;
- `cloudflared` для managed cloud;
- telemetry/backup jobs;
- локальные persistent volumes только для DB, Typesense и служебного состояния.

Файлы managed cloud не считаются постоянными данными VM и хранятся в отдельном Cloudflare R2 bucket клиента.

### 4.3 Связи

```text
Platform Control Plane
  ├── desired state + signed manifest ──▶ managed runner
  ├── desired state + signed manifest ──▶ customer-hosted pull agent
  └── принимает heartbeat/backup/deployment events

Managed runner
  └── Terraform + Ansible ──▶ один tenant

Tenant
  └── исходящий HTTPS ──▶ Control Plane
```

Control Plane не хранит shell command, Terraform token, SSH private key, R2 secret, DB password или клиентские файлы.

## 5. Режимы размещения

### 5.1 MANAGED_CLOUD

| Область | Решение |
|---|---|
| Compute и PostgreSQL | Hetzner, Германия |
| Изоляция | отдельный Hetzner Project на клиента |
| Data residency тарифа | EU |
| Object storage | отдельный private R2 bucket с `jurisdiction=eu` |
| Edge | Cloudflare DNS/TLS/Tunnel |
| Security edge | DDoS, Managed WAF, rate limiting |
| Secrets | 1Password.eu |
| Deploy | platform runner, Terraform + Ansible |
| Ответственность за backup/SLO | платформа |

Для запуска 100 клиентов Hetzner account limit должен быть заранее увеличен минимум до 120 projects. Создание Project и первоначального project-scoped API token остаётся контролируемой человеческой операцией, потому что рабочий Cloud API token уже привязан к существующему Project.

Cloudflare plan выбирается по измеримым возможностям: Tunnel, managed WAF, DDoS protection, требуемое число rate-limiting rules, notifications и Terraform/API management. Дизайн не зависит от маркетингового названия тарифа.

### 5.2 CUSTOMER_HOSTED

| Область | Решение |
|---|---|
| Compute и DB | инфраструктура клиента |
| Страна/юрисдикция | выбор и ответственность клиента |
| Object storage | S3-compatible или MinIO клиента |
| Edge/WAF | perimeter клиента; Cloudflare необязателен |
| Secrets/KMS | система клиента |
| Deploy | signed pull-bundle после локального approval |
| Inbound platform access | отсутствует |
| SLA | инфраструктурный SLA не предоставляется |

Поддержка object storage определяется не названием продукта, а прохождением compatibility suite: PUT, GET, HEAD, DELETE, multipart, presigned URL, checksum, lifecycle и согласованные error semantics.

### 5.3 Provider boundary

Бизнес-код работает через существующий storage/provider boundary. Cloudflare-specific endpoint, jurisdiction и token scopes находятся в adapter/configuration layer. Customer-hosted adapter использует тот же S3-контракт.

## 6. Managed Cloud: сеть и Cloudflare

### 6.1 Публичный HTTP path

```text
Browser
  → Cloudflare TLS
  → DDoS protection
  → WAF
  → rate limiting
  → Cloudflare Tunnel
  → internal NGINX
  → web / instance API
```

После bootstrap на VM нет публичных listeners для 22, 80, 443, PostgreSQL, Typesense или Actuator. `cloudflared` устанавливает исходящие QUIC/HTTP2 connections к Cloudflare.

### 6.2 Bootstrap access

Новая VM ещё не имеет Tunnel, поэтому bootstrap является отдельной ограниченной фазой:

1. Terraform создаёт firewall с временным TCP/22 allowlist только для фиксированных runner egress IP.
2. Runner использует уникальный per-tenant Ed25519 bootstrap key.
3. Ansible устанавливает и проверяет Docker, tenant stack, `cloudflared` и Access SSH.
4. Runner проверяет управление через Cloudflare Access.
5. Terraform удаляет временное правило TCP/22.
6. Instance не может перейти в `ACTIVE`, пока прямой SSH остаётся доступен.

Hetzner Console является break-glass каналом. Возврат прямого SSH требует audit-события и ограниченного по времени firewall change.

### 6.3 Source IP

Origin принимает client IP только из заголовка, сформированного доверенным Cloudflare path. NGINX удаляет любые клиентские копии forwarded headers и устанавливает canonical value. Прямой обход Tunnel сетево запрещён.

### 6.4 R2

Для каждого managed tenant создаются:

- private bucket;
- immutable internal bucket identifier;
- `jurisdiction=eu`;
- отдельный object read/write credential;
- отдельный admin credential только для provisioning/lifecycle operations;
- CORS allowlist только для hostname этого tenant;
- lifecycle policy, определяемая Storage & Data Protection;
- public `r2.dev` disabled.

Upload flow:

1. Browser запрашивает upload intent у instance.
2. Instance проверяет session, permission, quota, MIME/size policy и создаёт opaque object key.
3. Instance выдаёт короткоживущий presigned PUT.
4. Browser загружает объект напрямую в R2 S3 endpoint.
5. Browser сообщает completion.
6. Instance выполняет HEAD/checksum/size verification и меняет статус объекта.

Download flow:

1. Instance проверяет permission и ownership.
2. Instance выдаёт короткоживущий presigned GET.
3. URL рассматривается как bearer credential и не журналируется.

Cloudflare-managed AES-256 at-rest encryption является baseline. Ранее утверждённые per-client application key/BYOK и malware quarantine реализуются в отдельном Storage & Data Protection design. До прохождения этого gate чувствительные документы не считаются GA-ready.

## 7. Infrastructure as Code

### 7.1 Terraform ownership

Terraform разделяется на states с единственным владельцем каждого ресурса:

- `platform/hetzner` — platform project resources;
- `platform/cloudflare` — zone, common WAF/DDoS/rate-limit policy, Access baseline;
- `tenants/<client-id>/hetzner` — уникальные ресурсы Hetzner Project клиента;
- `tenants/<client-id>/cloudflare` — tunnel, DNS hostname, R2 bucket и tenant-specific bindings.

Ни один tenant state не управляет общим Cloudflare ruleset. Это исключает lost update между параллельными Terraform applies.

Remote state обязателен: versioning, locking, encryption и отдельный backup. Backend credentials передаются runner через secret store и не записываются в HCL.

Terraform создаёт:

- VM и primary IP;
- firewall;
- delete/rebuild protection;
- labels и inventory outputs;
- Cloudflare tunnel/DNS/R2 resources;
- наблюдаемые non-secret metadata.

Terraform не создаёт application password и не передаёт runtime secrets через user-data.

Одноразово возвращаемые secret-bearing credentials — R2 object token, bootstrap SSH key и instance enrollment token — выпускает типизированный provisioning step после создания ресурсов. Он записывает secret напрямую в tenant vault 1Password.eu, передаёт в Control Plane только non-secret identifier/fingerprint и не включает значение в Terraform state, Ansible inventory, job event или command output.

### 7.2 Ansible ownership

Ansible отвечает за:

- поддерживаемый LTS OS baseline;
- OS hardening и security updates;
- deploy user и filesystem permissions;
- Docker Engine/Compose;
- root-owned runtime directories;
- secret injection;
- immutable Compose release;
- migration, bootstrap, health verification;
- restore orchestration;
- Alloy/cloudflared agents.

Terraform и Ansible повторяемы и идемпотентны. Nightly drift job выполняет Terraform plan и Ansible check mode. Drift создаёт alert; автоматическое исправление не выполняется.

### 7.3 Destruction

Production resources используют delete/rebuild protection. Destroy требует:

- завершённого offboarding workflow;
- истечения 90-дневного retention или legal-hold decision;
- двух approvals;
- отдельного audit event;
- доказательства удаления DB, R2 objects, logs, secrets и backup согласно Data Protection policy.

## 8. Release artifact model

### 8.1 Release

`cp_releases` хранит:

- release ID и уникальную semver;
- source commit;
- OCI manifest digest;
- config schema version;
- minimum agent version;
- поддерживаемые deployment modes;
- status `DRAFT`, `READY` или `REVOKED`;
- creator и timestamps.

`cp_release_components` хранит для каждого компонента:

- component name;
- immutable image reference;
- OCI digest;
- SBOM digest;
- provenance digest;
- schema compatibility metadata.

Релиз после `READY` неизменяем. Исправление создаёт новую версию. `REVOKED` нельзя назначить новому instance.

### 8.2 Signed manifest

Подписанный manifest содержит:

- manifest format version;
- release version и source commit;
- build identity и timestamp;
- компоненты и их OCI digests;
- SBOM/provenance digests;
- instance/CP target schema versions;
- диапазоны application/schema rollback compatibility;
- config schema version;
- minimum agent version;
- supported deployment modes.

Pipeline использует Sigstore/Cosign OIDC identity и публикует verification bundle для offline customer-hosted verification. Проверка фиксирует ожидаемый issuer, repository identity и manifest digest.

### 8.3 Registry

Application images публикуются один раз в private GHCR. Runtime использует только `image@sha256`. Vendor images также закрепляются digest. Production host не выполняет `docker build`, package upgrade или разрешение mutable tag.

## 9. Control Plane data model

### 9.1 Instance metadata

`cp_instances` расширяется полями:

- deployment mode;
- jurisdiction;
- cloud provider;
- storage provider;
- edge provider;
- support tier;
- current release ID;
- current config version;
- current app/schema versions;
- last heartbeat;
- lifecycle status.

### 9.2 Desired state

`cp_instance_targets` содержит одну текущую цель на instance:

- instance ID;
- monotonically increasing generation;
- desired release ID;
- config version;
- rollout ring;
- maintenance window;
- requested by/at.

Каждое изменение desired state увеличивает generation.

### 9.3 Deployment history

`cp_deployments` содержит:

- deployment ID;
- instance ID;
- release ID;
- generation;
- previous release ID;
- runner/agent identity;
- current status;
- reason code;
- started/finished timestamps;
- bounded technical log reference.

Ограничение `unique(instance_id, generation)` обеспечивает идемпотентность.

`cp_deployment_events` является append-only журналом с уникальными `(deployment_id, sequence)` и idempotency key.

### 9.4 Credentials

`cp_instance_credentials` хранит только:

- instance ID;
- credential hash;
- created/activated/expires/revoked timestamps;
- rotation predecessor/successor references;
- last used timestamp.

Raw credential показывается только при exchange/rotation и не восстанавливается из CP.

### 9.5 Heartbeats

Raw heartbeat хранится 30 дней. Дневные агрегаты хранятся 13 месяцев. Retention job удаляет raw rows батчами и наблюдается метрикой/алертом.

## 10. Control Plane API и trust boundaries

### 10.1 Instance API

```text
POST /api/v1/instances/enroll
POST /api/v1/instances/heartbeat
POST /api/v1/instances/backup-reports
GET  /api/v1/instances/desired-state
```

Enrollment token является одноразовым и короткоживущим. Он обменивается на случайный 256-bit instance credential. Credential передаётся только по TLS, hash хранится в CP. Rotation допускает 24-часовое overlap-окно; revoke действует немедленно.

Instance identity определяется credential, а не значением из body. `clientCode` удаляется из heartbeat и backup-report DTO. Backup report всегда связывается с authenticated instance/client.

### 10.2 Runner API

```text
POST /api/v1/runner/jobs/claim
POST /api/v1/runner/jobs/{id}/events
POST /api/v1/runner/jobs/{id}/complete
```

Runner использует отдельную service identity. Разрешены только типизированные actions:

- `PROVISION_INSTANCE`;
- `APPLY_RELEASE`;
- `VERIFY_INSTANCE`;
- `ROTATE_INSTANCE_CREDENTIAL`.

Shell command, executable path и произвольный script payload не являются частью API.

### 10.3 Desired state response

Ответ содержит только:

- generation;
- release ID;
- signed manifest digest/location;
- config version;
- maintenance window;
- допустимое действие.

Managed runner и customer-hosted agent используют один semantic contract.

### 10.4 Validation и errors

- heartbeat body не более 16 KB;
- не более двух heartbeat requests в минуту на instance;
- DTO используют строгую schema validation;
- неизвестные/неограниченные telemetry fields не сохраняются;
- timestamps формируются сервером;
- ошибки соответствуют RFC 9457 и содержат стабильный `errorCode`, `traceId`, а временные ошибки — `Retry-After`;
- invalid/revoked credential получает 401;
- недоступность CP не останавливает tenant business functions.

## 11. Telemetry and privacy

Разрешённый heartbeat payload:

- app, schema, release и config versions;
- component health;
- storage used/quota;
- backup age/status;
- tunnel/agent status;
- current deployment state;
- ограниченные anonymous capacity counters.

Запрещены:

- пользователи, emails, телефоны;
- имена/содержимое файлов;
- business entity payload;
- stack traces и application logs;
- credentials/tokens;
- произвольный JSON.

Customer-hosted передаёт полные технические логи только после явного согласия. Доступ создаёт audit event и имеет срок действия.

Backup report `UPLOADED` означает только наличие artifact. `VERIFIED` присваивается после checksum verification и успешного restore drill.

## 12. Deployment state machine

Нормальный путь:

```text
REQUESTED
  → PREFLIGHT
  → BACKUP_VERIFIED
  → MIGRATING
  → DEPLOYING
  → VERIFYING
  → SUCCEEDED
```

Терминальные/аварийные состояния:

- `PREFLIGHT_FAILED`: runtime не изменён;
- `BACKUP_FAILED`: migration/deploy запрещены;
- `ROLLING_BACK`: возвращается предыдущий OCI digest;
- `ROLLED_BACK`: предыдущая версия прошла smoke;
- `RECOVERY_REQUIRED`: автоматический rollback не доказан безопасным;
- `CANCELLED`: задание отменено до mutation phase.

Для одного instance одновременно выполняется один deployment. Job использует lease; expired lease можно забрать повторно. Каждый mutation step проверяет persisted state и idempotency key до действия.

### 12.1 Preflight

Проверяется:

- manifest signature/identity/digest;
- наличие всех OCI artifacts;
- current release/schema/config;
- target compatibility;
- minimum agent version;
- свободное место и component health;
- freshness и verification status backup;
- maintenance authorization;
- отсутствие активного deployment lock.

### 12.2 Migration policy

Автоматический cloud rollout допускает только backward-compatible expand migrations. Предыдущая application version обязана поддерживать target schema. Contract/destructive phase не выполняется в том же release и начинается только после подтверждённого обновления всего флота.

### 12.3 Verification и rollback

После deploy выполняются:

- container/readiness health;
- DB/Typesense/R2 connectivity;
- authenticated login/session smoke;
- profile read;
- test entity create/read/delete;
- heartbeat desired/actual reconciliation;
- public path check через Cloudflare;
- direct-origin denial check.

Smoke failure возвращает предыдущие digests, если manifest доказывает schema compatibility. Иначе deployment переходит в `RECOVERY_REQUIRED`, создаёт P1 alert и запускает restore runbook. Runner не выполняет недоказанный destructive rollback.

## 13. Provisioning lifecycle

```text
PROJECT_PREPARED
  → REQUESTED
  → INFRA_CREATING
  → BOOTSTRAPPING
  → CONFIGURING
  → DEPLOYING
  → VERIFYING
  → ACTIVE
```

`PROJECT_PREPARED` включает созданный Hetzner Project, project API token в 1Password.eu и назначенную jurisdiction/profile policy.

Default resource profiles:

- `S`: 4 vCPU, 8 GB RAM;
- `M`: 8 vCPU, 16 GB RAM;
- `L`: выбирается по результатам benchmark.

Конкретный Hetzner server type и disk size являются versioned profile parameters. `S` считается default только после benchmark PostgreSQL + Typesense + application на 100 конкурентных сессиях. Capacity alert срабатывает до исчерпания disk/RAM.

## 14. CI, release и supply chain

### 14.1 Pull Request CI

PR pipeline выполняет:

- Maven verify, integration tests, ArchUnit;
- frontend unit/typecheck/build для обоих приложений;
- E2E critical flows;
- migration integrity и upgrade fixture;
- Compose validation;
- Terraform fmt/validate/static security scan;
- Ansible lint/syntax/idempotency checks;
- secret scan;
- dependency/container vulnerability scan.

PR pipeline не публикует production release и не имеет production credentials.

### 14.2 Release pipeline

Release создаётся только из protected `main` commit:

1. повторить required CI gates;
2. собрать application images один раз;
3. опубликовать private GHCR artifacts;
4. создать per-image SBOM;
5. выполнить vulnerability policy gate;
6. выпустить provenance;
7. сформировать и подписать release manifest;
8. проверить clean install;
9. проверить upgrade предыдущего production release;
10. проверить rollback и tampered-signature rejection;
11. зарегистрировать release в CP как `READY`.

GitHub Actions dependencies закрепляются commit SHA. Workflow permissions минимальны. Production environments требуют approval. Branch protection обязана сделать CI jobs required; её live-конфигурация проверяется как release prerequisite.

### 14.3 Build once, deploy many

Все rings и deployment modes используют одинаковые digests. Запрещены target-host builds, повторная сборка между rings и mutable tag resolution.

## 15. Rollout policy

```text
R0: staging/internal
R1: 5 pilot tenants
R2: следующие группы по 5
R3: оставшийся флот группами по 5
```

- не более пяти managed deployments одновременно;
- один deployment на tenant;
- между группами действует observation window;
- любой `BACKUP_FAILED`, `RECOVERY_REQUIRED`, signature failure или business-smoke failure ставит текущую группу и дальнейшее продвижение на pause;
- продолжение требует operator classification и audit event;
- revoked release не назначается новым tenants;
- Control Plane обновляется первым и поддерживает tenant protocol N и N-1.

Обычный managed rollout выполняется в maintenance window. Emergency security rollout вне окна требует двух approvals, причины и уведомления. Customer-hosted применяет release только после локального approval.

## 16. Secrets

Managed-cloud source of truth — 1Password.eu:

- отдельный vault на tenant;
- platform provisioning service account с write/rotate permission;
- deployment runner service account с минимальным read permission;
- human break-glass через MFA;
- audit для чтения/изменения/rotation;
- secret references вместо plaintext в inventory/templates.

Secrets не попадают в Git, CP DB, Terraform state, CI artifact, deployment events или logs. Ansible materializes необходимые runtime secrets в root-owned files `0600`. Временные файлы runner удаляются после выполнения. Rotation является типизированной операцией и завершается health verification.

Customer-hosted хранит secrets/KMS keys в своей системе; bundle содержит только schema и validation rules.

## 17. Observability

Managed tenant содержит Grafana Alloy agent, который локально собирает:

- Spring Actuator metrics;
- node/container metrics;
- structured JSON application logs;
- cloudflared health metrics.

Agent отправляет данные исходящим HTTPS в Platform Stack:

- VictoriaMetrics — metrics;
- Loki — application/agent logs;
- Grafana — dashboards;
- vmalert/Alertmanager — alerts и routing.

Для 100 tenants используется single-node metrics/logs storage с backup; observability не является tenant data-plane dependency. Переход к HA выполняется только после измеренного capacity/SLO pressure.

### 17.1 Retention

- raw metrics: 90 дней;
- SLO/capacity aggregates: 13 месяцев;
- managed application logs: 30 дней;
- deployment/security audit: configurable 1–7 лет;
- customer-hosted logs: не принимаются без consent.

Structured logs содержат `traceId`, `clientCode`, `release`, `generation`, logger, level и message. PII, tokens, presigned URLs и file names маскируются или не журналируются.

### 17.2 SLI/SLO

- managed availability: 99,5% в месяц за исключением согласованного maintenance;
- public synthetic probe: одна минута через Cloudflare path;
- API error rate и latency;
- desired/actual drift;
- tunnel availability;
- backup age/verification;
- storage/disk/memory capacity;
- deployment success/rollback rate.

### 17.3 Alerts

P1:

- tenant недоступен более пяти минут;
- подтверждённый security incident или data loss;
- backup age более 24 часов;
- `RECOVERY_REQUIRED`;
- platform-wide impact.

P2:

- повышенные 5xx/latency;
- tunnel instability;
- disk более 80%;
- длительный desired/actual drift;
- failed non-production rollout.

Telegram/email отправляются сразу. P1 без acknowledgement через 10 минут эскалируется телефоном.

## 18. Test strategy

### 18.1 Unit

- state transitions;
- manifest verification;
- compatibility decisions;
- telemetry validation;
- retention selection;
- release/ring policy.

### 18.2 Integration

- CP migrations/repositories/controllers;
- old-but-valid Flyway schema rejection;
- enrollment/rotation/revoke;
- job lease/reclaim;
- duplicate generation/idempotency;
- heartbeat retention;
- backup-report ownership binding.

### 18.3 Contract

- R2 EU;
- generic S3;
- MinIO;
- runner/CP protocol;
- customer-hosted agent/CP protocol;
- signed manifest schema.

### 18.4 E2E

- clean managed provision from prepared Project;
- clean customer-hosted install;
- N-1 to N upgrade;
- automatic app rollback;
- backup failure blocks migration;
- incompatible schema blocks deployment;
- tampered digest/signature blocks deployment;
- runner crash and lease reclaim;
- CP outage does not stop tenant;
- Cloudflare Tunnel reconnect;
- direct-origin access denied;
- test alert reaches Telegram/email/phone escalation path.

### 18.5 Live staging release gate

Release candidate проходит real Hetzner + Cloudflare Tunnel + R2 EU apply, smoke and destroy-protection test. Production destroy не тестируется; lifecycle deletion проверяется в disposable staging Project.

## 19. Delivery slices

Fleet Foundation реализуется вертикальными проверяемыми срезами:

1. **Control Plane contract/security:** IDOR fix, typed telemetry, credentials, releases, desired state, deployments.
2. **Release supply chain:** GHCR digests, SBOM, provenance, signing, manifest и release gates.
3. **Runtime separation:** отдельные platform/tenant production Compose и fail-closed deploy/rollback.
4. **Managed provisioning:** Hetzner/Cloudflare Terraform, 1Password integration, Ansible bootstrap и origin closure.
5. **Runner/customer agent:** typed jobs, leases, managed push и customer-hosted pull.
6. **Observability/validation:** agents, central stack, alerts, live staging E2E и актуальные runbooks.

Каждый срез завершается тестами и evidence. Срез не считается завершённым только по наличию файлов или успешной локальной команды.

## 20. Definition of Done

Fleet Foundation завершён, когда одновременно выполнено следующее:

- platform и tenant production Compose разделены;
- root dev Compose остаётся воспроизводимым для локальной разработки/E2E;
- production не выполняет build;
- release подписан и связан с commit, OCI digests, SBOM и provenance;
- CP хранит releases, desired/current state, deployment history и audit;
- backup-report IDOR закрыт integration test;
- arbitrary telemetry и arbitrary runner command невозможны по schema;
- один prepared Hetzner Project превращается в healthy tenant через Terraform + Ansible;
- bootstrap SSH автоматически закрывается до `ACTIVE`;
- origin недоступен напрямую;
- R2 bucket изолирован и имеет `jurisdiction=eu`;
- customer-hosted flow работает без входящего platform access;
- rollout по пять tenants останавливается на первом критическом failure;
- rollback и duplicate-generation safety доказаны E2E;
- dashboard и P1 escalation проверены тестовым incident;
- runbooks описывают A+, Cloudflare и оба deployment modes;
- действующие документы не выдают Nomad/Vault/WAL-G за production reality;
- repository documentation не содержит ссылок на отсутствующие обязательные release artifacts.

Завершение Fleet Foundation не означает GA. Общий GA остаётся заблокирован до завершения:

- Storage & Data Protection, включая per-client encryption/BYOK, malware quarantine, complete backup/restore и deletion evidence;
- Identity & Access, включая OIDC/MFA/emergency accounts и окончательную CP operator authorization;
- Reliability & Operations;
- GA Validation, включая threat model и независимый penetration test.

## 21. Явно вне scope

- новые продуктовые модули CMS;
- Kubernetes, Nomad, Consul и service mesh;
- multi-replica tenant application;
- active-active, multi-region database и automatic failover;
- autoscaling;
- SCIM;
- собственный Vault/OpenBao cluster;
- отдельный storage provider для managed cloud кроме R2;
- country-specific managed cloud residency кроме EU;
- полная реализация BYOK и malware scanning в этом проекте;
- переписывание Spring/Angular приложений.

## 22. Внешние release prerequisites

До live managed staging должны существовать:

- одобренный Hetzner limit минимум 120 Projects и достаточный server quota;
- production Cloudflare account/zone с capability set из раздела 5;
- R2 purchase и возможность создавать EU-jurisdiction buckets;
- 1Password.eu organization с service accounts;
- protected GitHub branch, required checks и production environment approvals;
- фиксированные runner egress IP;
- Telegram/email/phone notification integrations;
- назначенные владельцы security, release и incident approvals.

Отсутствие любого prerequisite блокирует соответствующий live gate; локальные mocks не заменяют production evidence.

## 23. Источники внешних ограничений

- Hetzner API tokens project-scoped: https://docs.hetzner.com/cloud/api/getting-started/using-api/
- Hetzner default Projects limit и limit requests: https://docs.hetzner.com/cloud/general/faq/
- Cloudflare Tunnel outbound-only model: https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/
- Cloudflare R2 EU jurisdiction: https://developers.cloudflare.com/r2/reference/data-location/
- Cloudflare R2 encryption: https://developers.cloudflare.com/r2/reference/data-security/
- Cloudflare R2 presigned URL security: https://developers.cloudflare.com/r2/api/s3/presigned-urls/
- 1Password service-account secret injection: https://developer.1password.com/docs/cli/secrets-scripts
- 1Password EU data region: https://support.1password.com/regions/
