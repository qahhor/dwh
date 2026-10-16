# Staging: утверждённая конфигурация и приёмка

**Обновлено:** 2026-10-16. Кандидат: **`4a721df037e8274c3f1be56718e0319fec88c3dc`**.
Конфигурация, бюджет, RPO/RTO и upload SLO 30/60 с утверждены владельцем
2026-10-12; ниже они сохранены. RC `4a721df0` заменяет `bc559ddd` как
основание следующей приёмки; старые результаты ему не приписываются.
Это план будущей приёмки, а не протокол выполненных staging-проверок.
Создание ресурсов, закупки, публикация, merge и выкладка этим документом
не разрешены. Дата начала испытаний зависит от входов §6.

## 0. Основа приёмки и готовность кандидата

По сохранённому протоколу от 2026-10-16 на указанном DCO SHA завершены
**116/116 обязательных локальных проверок**: backend 3306 тестов, web 2579,
accessibility 143, live E2E 72 (37 + 35), без E2E retries и quarantine.
Указатель на проверенные результаты — игнорируемый
`.local/rc-prerelease-final-20261016/EVIDENCE.md`; первичные `final-proof.json`,
`ci-report.md`, security review и журналы остаются приватными.
Это результаты локального CI с описанными в отчёте адаптациями Windows/Linux,
не подтверждение целевой инфраструктуры или идентичности GitHub-hosted runner.
Настоящий план обновлён после RC и не входит в его проверенный commit tree.
Source SHA выше остаётся неизменным; редакцию runbook фиксировать отдельно.
При включении этих документов в новый commit/bundle потребуется новый кандидат
и полный CI его SHA. Для испытаний использовать отдельный чистый checkout
`4a721df0`, а не рабочее дерево с настоящими незакоммиченными изменениями.

В RC входят prerelease pipeline, verifier и исправление LF/CRLF-контракта.
Первый кандидат `9fceb040` отклонён из-за CRLF; все 116 checks повторены на
`4a721df0`. Разобраны 44 сигнала CodeQL; подтверждённых блокеров не найдено.
Actions проверен официальным suite через свежую JS DB с Workflow AST 8/8:
native extractor был заблокирован Windows Execution Policy. GitHub OIDC,
публикация GHCR/Release и release ARM64 этим локальным CI не подтверждены.
Пять реальных остаточных рисков ZAP (inline CSS, COEP/COOP/CORP web и CORP API)
сохраняются по ADR-0034 / threat-model; это не false positive и не новое
разрешение production. Их target-проверка входит в S2/S6.

| Gate | Состояние на 2026-10-16 | Что закрывает |
|---|---|---|
| Исходники / DCO / локальный CI | PASS по сохранённому протоколу RC | Повторять полный CI при изменении исходного SHA |
| Подписанные артефакты | BLOCKED: registry digest/provenance для staging ещё не предоставлены | Шесть образов, SBOM и bundle; проверка provenance и точного source SHA |
| Целевой контур и доступы | NOT READY: целевой HTTPS и acceptance-config не предоставлены | Заполненные входы §6 и host/external preflight |
| Студия на staging | NOT RUN | [Матрица Студии](studio-staging-acceptance.md), реальные роли/данные/процессы |
| SLO, alerts, recovery | NOT RUN | Полный корпус, реальные receipts, два restore и полный RTO |
| Production GO | NO-GO | Все P0, владельцы и отдельное решение выпуска |

По поручению владельца подготовлен отдельный путь staging prerelease:
[prerelease.yml](../../.github/workflows/prerelease.yml), тег `vX.Y.Z-rc.N`,
явный `-Channel staging` и обязательный `-ExpectedSourceSha` у verifier.
Решение и trust policy — [ADR-0040](../adr/ADR-0040-signed-staging-prereleases.md).
Merge в default branch для RC не требуется; стабильный release сохраняет
своё ancestry-ограничение. Checksums manifest подписывается и attested,
channel/tag/source SHA/workflow проверяются до запуска содержимого bundle.

**Кандидат для будущего тега уже определён: `4a721df0`.** Новый SHA не нужен,
пока не меняется состав поставки. Точное имя свободного `vX.Y.Z-rc.N` выбирает
Release owner; номер здесь не придумывается. DCO sign-off коммита не является
Cosign-подписью артефакта. Код workflow не означает наличие подписанных assets:
нужны настройки `staging-release`, независимый approval и отдельное разрешение
на push tag/публикацию. GitHub settings, refs, GHCR и Releases не изменялись.

Локальный Docker image ID не является registry digest. Даже сборка того же
source SHA может получить новые базовые пакеты: будущие образы сканируются,
проверяются и проходят runtime/E2E на своих digest. Неподписанный rehearsal
не закрывает gate артефактов. Зафиксировать связь source SHA → шесть digest →
конфигурация → evidence; не подменять её названием тега.

### 0.1. P0: проверка подписанного prerelease

Все строки ниже пока **NOT RUN**. Ответственные — Release + Security;
допуск к deploy возможен только после PRC-01…06. PRC-07 проверяет уже
развёрнутые digest до нагрузки. Наличие локальных fixture-тестов verifier
не закрывает эти целевые проверки.

| ID | Действие | Критерий готовности / evidence |
|---|---|---|
| PRC-01 | Администратор проверяет фактические GitHub protections до разрешённого push | `v*` tag ruleset запрещает update/delete, создание ограничено Release; `staging-release` имеет независимого required reviewer, prevent self-review, запрет bypass и tag policy `v*.*.*-rc.*`. Тариф/тип repo поддерживает эти защиты. В environment только утверждённые `STAGING_APPROVED_SOURCE_SHA=4a721df037e8274c3f1be56718e0319fec88c3dc` и точный `STAGING_APPROVED_VERSION`; одноимённых repo/org vars нет. Сохранены redacted settings и reviewer/UTC |
| PRC-02 | После отдельного разрешения проверить tag-triggered run | Tag, event и checkout равны полному SHA; DCO и вызванный `ci.yml` PASS на нём. Run URL/ID/attempt, approvals и workflow identity сохранены; первая попытка, не rerun. После любого failed run — новый номер RC и новое одобрение, без передвижения tags/перезаписи assets |
| PRC-03 | Проверить результаты build/sign/publish всех шести компонентов | `server/web/backup/postgres/typesense/clamav`: build и Trivy PASS на публикуемых digest, attestation и Cosign успешно выпущены; проверены заявленные платформы amd64/arm64. Release имеет `prerelease=true`, `draft=false`, не latest. Сохранены tar/zip, `release.json`, 24 image/SBOM/provenance файла, `SHA256SUMS` и его sigstore/provenance bundles. Никакого deploy/production promotion |
| PRC-04 | Независимый verifier из доверенного checkout на настоящих assets | Явный `Channel=staging`, утверждённые repository/version и полный ExpectedSourceSha. Сначала Cosign + provenance manifest, затем hashes и все image signatures/provenance/SPDX/CycloneDX. Точный signer `prerelease.yml@refs/tags/<version>`, issuer GitHub Actions, source/signer digest совпадают с RC, self-hosted signer отклонён. Exit 0 и новый receipt schemaVersion 2, status PASS, channel/version/sourceSha/sourceRef/workflow, hash SHA256SUMS и шесть refs совпадают с утверждённой поставкой |
| PRC-05 | Отрицательные проверки на отдельных локальных копиях assets | Неверные SHA/repository/tag/channel, отсутствие/подмена manifest signature или provenance, изменённый archive с пересчитанным неподписанным manifest, missing/mixed image refs/SBOM/provenance должны дать ненулевой exit до запуска bundle/Compose. Для корректного синтаксиса Version проверить удаление прежнего PASS в том же отдельном evidence-каталоге после неуспешного повтора. Исходные assets не менять; сохранить expected/actual и затем повторить positive PRC-04 |
| PRC-06 | Повторить verifier под фактической deploy/restore OS identity | Есть read-only доступ к GitHub assets/attestations, GHCR и trust endpoints. Проверить аутентификацию с `become`, доступ к приватным `HOME`/`DOCKER_CONFIG`/`GH_CONFIG_DIR`; одного `GH_TOKEN` в интерактивном shell недостаточно — wrapper его не передаёт. Ansible: `smc_release_channel: staging`, `smc_staging: true`, expected SHA точно RC, checksum pin из независимого PRC-04 receipt; overlays проверены. `--check` Ansible и `deploy.ps1 -VerifyRelease` не заменяют этот gate |
| PRC-07 | Связать проверенную поставку с target runtime | Шесть продуктовых refs совпадают с verifier; migrate/backup-bootstrap используют соответствующие server/backup digest. Остальные observability images отдельно закреплены по digest и входят в config evidence; фактические platform digest/revision labels соответствуют подписанному manifest/index. Сохранены цепочка index → platform digest, sanitized config revision и UTC. Runtime smoke, security headers и live E2E повторены на этих образах; локальные Docker IDs/результаты сборки не заменяют этот прогон |

После окончания разрешённого выпуска удалить approval vars из environment;
проверку этого cleanup сохранить вместе с approvals. Build amd64/arm64 и
проверка multiarch metadata не являются runtime-тестом ARM64: утверждённый
target x86_64 проверяет свою платформу, другая требует отдельного стенда.
Проверка форматов SBOM не доказывает их полноту; scan receipts PRC-03 обязательны.
Негативы выполняются без выпуска новых подписей для испорченных fixtures.
Случаи, которым нужен специально подписанный неверный source/signer или набор,
фиксируются как LOCAL из regression suite, а не как LIVE PASS; фактическое
покрытие каждого сценария и причина ограничения записываются отдельно.

Verifier требует сети к GitHub/GHCR и используемым Sigstore trust endpoints;
полностью offline/air-gapped режим не заявлен. Версии `pwsh`, `gh`, `cosign`
фиксируются в evidence по контракту поставки; скачивание assets/инструментов
выполняется оператором только в разрешённом следующем этапе. Нужны scoped
read credentials под реальной OS identity, не private signing key: подпись
pipeline keyless. Секреты не вводить в команды и не прикладывать к отчёту.

Команда ниже — шаблон PRC-04 после появления одобренных assets; сейчас она
не выполняется. Repository/version/каталоги задаёт оператор из IN-03/09.
`ExpectedSourceSha` нельзя получать из скачанного `release.json`.

```powershell
$trustedCheckout = '<absolute-clean-checkout-of-approved-RC>'
$assetsDirectory = '<absolute-private-downloaded-assets-directory>'
$evidenceDirectory = '<absolute-private-positive-evidence-directory>'
$repository = '<approved-owner/repository>'
$version = '<approved-unused-vX.Y.Z-rc.N>'
$expectedSourceSha = '4a721df037e8274c3f1be56718e0319fec88c3dc'
$actualSha = (& git -C $trustedCheckout rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualSha -cne $expectedSourceSha) { throw 'Wrong checkout SHA' }
$dirty = @(& git -C $trustedCheckout status --porcelain=v1)
if ($LASTEXITCODE -ne 0 -or $dirty.Count) { throw 'Checkout is not clean' }
pwsh -NoProfile -File (Join-Path $trustedCheckout 'scripts/release/verify-published-release.ps1') `
  -ReleaseDirectory $assetsDirectory -Repository $repository -Version $version `
  -Channel staging -ExpectedSourceSha $expectedSourceSha -EvidenceDirectory $evidenceDirectory
if ($LASTEXITCODE -ne 0) { throw 'Prerelease verification failed' }
```

Receipt: `<evidenceDirectory>/release-integrity-<version>.json`. Security
сверяет все поля PRC-04, hashes первичных файлов и утверждённую identity;
одного наличия файла PASS недостаточно. Не запускать verifier или другие
скрипты из скачанного bundle до аутентификации его manifest. Отрицательные
проверки должны сохранять логи, но не credentials; shared positive evidence
не использовать для destructive/tampering fixtures.

## 1. Топология и бюджет

| Назначение | Утверждённый размер |
|---|---|
| App, Hetzner NBG1 | CCX33: 8 dedicated vCPU, 32 GB RAM, 240 GB NVMe; Ubuntu 24.04 x86_64 |
| Ops/load | CCX23: 4 dedicated vCPU, 16 GB RAM, 160 GB NVMe + отдельный scratch volume 250 GB |
| Изолированный restore | Временный CCX33, до 48 ч; отдельные project, БД, bucket/prefix |
| Первый месяц | Не более **€260 + $100 до налогов**; R2 ≤ $50, Cloudflare Pro $25, остаток $25 для доставки/резерва |

В euro ceiling входят два IPv4, резерв ≤ €20 для scratch volume и временный
restore. Это утверждённые потолки расходов, не гарантированные provider hard
caps и не quote. Перед отдельным разрешением на покупку проверить цену,
налоги, квоты и доступность SKU. HEL1 — только после отдельного выбора региона.
Единственный app-хост не обеспечивает HA; RTO 2 ч не доказывает SLO 99.9 %.

| Постоянный сервис app-хоста | CPU limit | RAM limit / reservation |
|---|---:|---:|
| server | 3 | 10 / 4 GiB |
| PostgreSQL | 1.5 | 6 / 3 GiB |
| ClamAV | 1.5 | 4 / 2 GiB |
| Typesense | 0.5 | 2 / 1 GiB |
| web | 0.25 | 2 / 0.25 GiB |
| backup | 0.25 | 1 / 0.25 GiB |
| Prometheus collector | 0.25 | 1 / 0.5 GiB |
| node/postgres exporter, каждый | 0.1 | 64 / 16 MiB |
| **Итого** | **7.45** | **26.125 / 11.03125 GiB** |

Фактический `MemTotal` должен быть ≥ 30.125 GiB: не менее 4 GiB остаётся ОС.
Размер тарифа в GB не заменяет это измерение. Server heap 1–4 GiB, `/tmp`
3 GiB, JNA 16 MiB; web `/tmp` 64 MiB, nginx tmp 1536 MiB, run 16 MiB.
Upload-buffer выделен фактическому пути `/var/lib/nginx/tmp/client_body`
release web image: отключение buffering на host edge не отключает внутренний nginx.
ClamAV `/tmp` — **дисковая** файловая система с квотой 2 GiB, bind сам по себе
квоту не задаёт. PostgreSQL: shared_buffers 1536 MB, work_mem 8 MB,
maintenance 256 MB, autovacuum 128 MB, 80 connections, shm 512 MiB;
fsync/full_page_writes/synchronous_commit/checksums включены. Пулы CMS/DWH
сохраняют 20/4; DWH=4 задан исходным кодом, не выдуманной env-переменной.
Миграции: 2 CPU / 4 GiB, heap 2 GiB, только при остановленном server;
bootstrap: 0.25 CPU / 256 MiB. Они не входят в постоянную сумму.

На ops k6 получает ≤ 3 CPU / 8 GiB, мониторинг ≤ 0.5 CPU / 2 GiB.
Object backup ≤ 3 CPU / 4 GiB выполняется вместо k6. Scratch 250 GB рассчитан
на исходные 50 GiB объектов, tar и зашифрованный архив, плюс ≥ 20 % свободно.
До нагрузки проверить дисковые квоты, места и фактический RSS, не только Compose.

## 2. Подготовленные файлы и их применение

- [Compose overlay](../../deploy/staging/docker-compose.staging.yml) применяется
  третьим после production и observability (Compose ≥ 2.24.4).
- [Параметры установки](../../deploy/staging/.env.staging.example) объединяются
  с `deploy/compose/.env.example` в приватном файле. Все шесть image refs —
  реальные digest из проверенных release metadata; пустые обязательные значения
  прерывают Compose render. Придуманные digest запрещены.
- [Приёмочный env](../../deploy/staging/.env.acceptance.example) содержит числовые
  пороги, но намеренно не содержит target identity и доступов.
- [Host TLS edge](../../deploy/staging/origin.nginx.conf.example) требует cert,
  zone-specific AOP CA и актуальный список доверенных Cloudflare CIDR.
- [Collector](../../deploy/staging/prometheus-collector.yml.example) отправляет
  метрики на отдельный ops endpoint через mTLS; порты management не публикуются.
- [Ops Compose](../../deploy/staging/docker-compose.ops.yml),
  [receiver/rules](../../deploy/staging/prometheus-ops.yml.example) и
  [приватный TLS ingress](../../deploy/staging/ops.nginx.conf.example) принимают
  remote write и вычисляют правила на другом хосте. Prometheus UI доступен
  оператору через SSH tunnel к loopback 19090. Alertmanager/получатель —
  отдельно управляемый endpoint, его подключение остаётся target gate.

Путь каждого bind/secret абсолютный. Каталоги секретов 0700, env/keys 0600;
права чтения TLS collector выдаются его UID 65534, без доступа остальных.
Команда ниже только проверяет конфигурацию; вывод с секретами не сохранять
в evidence и не печатать. Рабочий каталог — корень подготовленной поставки.

```powershell
docker compose --env-file /secure/staging.env `
  -f deploy/compose/docker-compose.prod.yml `
  -f deploy/observability/docker-compose.observability.yml `
  -f deploy/staging/docker-compose.staging.yml config --quiet
```

Host-check/deploy/restore принимают один `-ComposeFile`. Перед их запуском
оператор создаёт объединённый Compose через `config --no-interpolate --no-path-resolution --no-normalize` с теми
же тремя `-f`, **в защищённом каталоге**, затем проверяет render с приватным env.
Не запускать их с одним базовым Compose: staging-лимиты тогда потеряются.
В объединённом файле остаются template-переменные; хранить его как sensitive,
проверить абсолютные пути bind/secrets и использовать то же имя проекта.
`--no-normalize` обязателен: без него export фиксирует имена томов/сетей,
и последующий `-p` не изолирует restore. Regression проверяет эквивалентность
services/name/volumes/networks/secrets и новые имена ресурсов с restore project.
Значения опций описаны в [Docker Compose config](https://docs.docker.com/reference/cli/docker/compose/config/).
Фактические команды выкладки и подключения выполняются после отдельного
разрешения, подписи образов и заполнения target identity.

## 3. Edge и R2

Cloudflare Pro: proxied DNS, Full (strict), TLS ≥ 1.2, Always Use HTTPS,
Managed WAF и allowlist IP операторов/runner. Rate limits: `/api/v1/auth/login`
20/60 с/IP, block 60 с; `/api/*` 6000/10 с/IP, block 10 с. Выражения
используют HOST/PATH/IP; не требуют Pro-недоступных полей. Cache bypass для
API, HTML/auth и SSE; cache только для versioned static assets. На API не
применять интерактивные challenge и не ослаблять WAF ради upload-теста.

Origin: 443 только с Cloudflare IPv4/IPv6, SSH только с operator IP;
80, 5432, 8080, 8108, 9090, 3310, 9100, 9187 извне закрыты. Список CIDR
проверить перед настройкой. TLS использует отдельный сертификат зоны для
authenticated origin pulls; общий Cloudflare client CA не ограничивает зону.
Это различие описано в [Cloudflare AOP](https://developers.cloudflare.com/ssl/origin-configuration/authenticated-origin-pull/).
Host nginx очищает forwarded headers, допускает body 51 MiB и одновременно
20 upload; сверх лимита 429. SSE без buffering. Прямой origin должен
отказывать также при верном Host/SNI. Это проверяется с внешнего runner.

R2: три приватных bucket — application, recovery и restore. Default
jurisdiction + location hint `weur`, Standard, region `auto`, path-style.
Hint не гарантирует EU residency. EU-jurisdiction потребует отдельного
изменения management preflight (`cf-r2-jurisdiction`) и нового решения.
`r2.dev` выключен, custom domains отсутствуют, CORS пустой. Application:
abort incomplete multipart через сутки, rule id `abort-incomplete-multipart`,
без удаления живых объектов по возрасту. Recovery: lock 14 дней на prefixes
`backups/` и `object-backups/`, lifecycle expiry 15 дней; probes не запирать.
Restore использует отдельный bucket и `restore-drill/<project>/`.

Ключи разделены: приложение не имеет доступа к recovery; источник object
backup read-only, recovery writer отдельный, restore временный. Prefix не
выдаётся за IAM-границу. `age` identity и `SMC_SECRETS_KEY` восстанавливаются
вне app-хоста отдельными полномочиями. Проверить R2 policy/byte-for-byte
upload/download/delete/lifecycle и отрицательные права на целевом аккаунте.

## 4. Измеримая приёмка

Одна staging-установка: 500 зарегистрированных / 100 активных пользователей,
20 оргединиц глубиной 4, 100 тыс. задач, 1 млн audit, 1 млн DWH rows,
5000 файлов; физические CMS+DWH ≤ 30 GiB, объекты ≤ 50 GiB. Плановый трафик
50 GB upload/месяц — отдельная величина. Только синтетические данные.

| Проверка | Порог |
|---|---|
| API latency | p95 ≤ 300 мс, p99 ≤ 1000 мс |
| 20 near-limit uploads | p95 ≤ 30000 мс, p99 ≤ 60000 мс; 20/20 HTTP 201, 0 ошибок |
| Остальной API | errors ≤ 0.001, корректные read/write checks |
| Насыщение | Hikari pending = 0; heap ≤ 80 %; CPU < 80 % на каждом окне 5 мин; MemAvailable ≥ 4 GiB; нет устойчивого swap |
| Диск / temp | свободно ≥ 50 GiB и > 20 %; общий temp peak ≤ 4096 MiB; рост после drain ≤ 128 MiB за ≤ 5 мин |
| Зависимости | scanner p95 ≤ 5000 мс; R2 upload p95 ≤ 10000 мс |
| Целостность | 0 orphan, 0 missing, 0 checksum mismatch; 0 OOM и незапланированных restart |
| Фоновые задачи | head lag ≤ 5 мин; outbox lag ≤ 15 мин; dead letters = 0 |
| Инциденты | fault injection → доставка ≤ 120 с; ack ≤ 10 мин |
| UX страниц | p75 LCP ≤ 2.5 с, INP ≤ 200 мс, CLS ≤ 0.1; Chrome 1365×768, 10/2 Mbps, RTT 100 мс, CPU ×4; ≥ 30 cold/warm runs на страницу |

Порядок: verifier → external/host preflight → access negatives и revoke/hidden
fields → 15 мин interactive → 20 uploads → 4 ч soak → failure drills → два
полных isolated restore. Порог runner контролирует только поля runtime-metrics
контракта; CPU, temp peak, checksum inventory, UX, OOM/restart и доставка
алертов требуют отдельного evidence за то же окно, а не ручного PASS.
Runner сохраняет оба latency-бюджета; вручную увеличенный предел отклоняется.
Шаблон app collector собирает метрики каждые 5 секунд. Для проверки кратких
пиков pending/heap/temp требуется дополнительная выборка gauges раз в секунду
за то же UTC-окно; подключение и выгрузка runtime-metrics проверяются до нагрузки.
`automatedStatus=PASS` у preflight/failure runner означает успех автоматических
проверок. Общий `status` остаётся `UNVERIFIED`, пока нет независимого receipt
доставки: HTTP 2xx тестового endpoint не закрывает контроль ≤120 секунд.

Негативные сценарии на реальных контрактах RC повторяются на target;
fixture-only случаи и недоступные возможности явно отделены в матрице Студии.
Alertmanager/получатель, защищённые monitoring health/drill endpoints,
exporter runtime-metrics и probes R2/ClamAV/целого backup-комплекта ещё должны
быть подключены оператором. Обычные API/upload latency alerts имеют `for: 15m`
и не могут доказать доставку за 120 с. Для acceptance fault drills нужен
отдельный короткий probe route; измерять от инъекции, включая задержку scrape,
remote write, evaluation, `for`, routing и доставку. Остановка app-хоста также
должна обнаруживаться с ops-хоста по свежести последнего sample; сохранённый
`up=1` после обрыва remote write не доказывает доступность.

Вызовы monitoring probes в текущих preflight/failure scripts не принимают
Bearer token или клиентский TLS-сертификат. До запуска согласовать доступ
runner по закрытой сети/IP allowlist и проверить запрет с постороннего адреса.
Если инфраструктура требует Bearer/mTLS именно для этих HTTP-вызовов, нужна
отдельная доработка runner и новый проверенный SHA; такой поддержки пока нет.
Это ограничение не относится к уже настроенному mTLS remote write collector.

## 5. RPO / RTO и блокирующие условия recovery

**RPO ≤ 24 ч (86400 с), RTO ≤ 2 ч (7200 с).** Полный согласованный комплект
CMS + DWH + object bytes дважды в сутки, 00:00/12:00 UTC, длительность ≤ 60 мин.
Recovery point — начало согласованного снимка после остановки всех writers,
включая jobs; время завершения upload manifest не улучшает RPO.
Полный комплект: warning после 14 ч, critical после 20 ч, нарушение после 24 ч.
`SMC_BACKUP_MAX_AGE=20h` измеряет только backup status БД, не эти три компонента.

В staging `BACKUP_COORDINATED_ONLY=true`: sidecar ожидает согласованный запуск
и не создаёт самостоятельный dump при restart. Coordinator на ops управляет
app через проверенный SSH Docker endpoint; UTC timer — 00:00/12:00.
Он проверяет idle, останавливает writers, снимает CMS/DWH/object bytes,
возвращает сервисы в работу и выполняет remote read-back SHA-256.
`complete.json` публикуется последним, `consistentPoint` соответствует
остановленным writers. Обычные ошибки/таймауты выполняют cleanup;
SIGKILL/power loss требуют операторской проверки.

Полный local cache на ops ограничен **одним** проверенным комплектом вместо
отдельного DB cache: одновременное хранение старых полных копий и plaintext/tar
не укладывается в scratch 250 GB. Перед capture удаляется только проверенная
локальная копия, remote retention 14 дней сохраняется. На app временный DB
комплект удаляется после копирования. Guard требует ≥200 GiB и ≥20% свободного
места; фактический peak проверяется на полном корпусе. До такого rehearsal
timer/soak не включать. Ops нужны Python ≥3.11, `pwsh`, `aws`, `age`, `tar`.
Реализация и аварийные процедуры: [production handbook](production-handbook-ru.md).

RTO включает обнаружение 5 мин + решение 10 + provision 25 + restore 55 +
smoke 25 = 120 мин. Два target-drill с полным корпусом должны подтвердить
таймер от отказа до рабочей установки, CMS/DWH row inventory, все object
checksums и выборочные download. RTO скрипта restore покрывает только часть
этого времени. Cold SKU availability неизвестна; запасной хост не оплачен.
PITR/HA не заявлены. Потеря всего R2 account не покрывается копией в том же
аккаунте и требует отдельного recovery-дизайна.

## 6. Входы и решения, необходимые от владельца

Порядок настройки GitHub и выдачи прав — в
[инструкции staging-доступов](staging-access-setup.md): роли, UI-шаги,
точные permissions, проверка и отзыв. Текущий runner не требует Hetzner API
token; его Cloudflare R2 REST read шире доступа только к metadata одного bucket.

Повторное утверждение размеров, бюджетных потолков, NBG1, RPO/RTO и upload SLO
не требуется. Нужны значения, ответственные и ссылки на доступы ниже.
В чат передаются только несекретные идентификаторы и пути/ссылки на secret manager;
пароли, токены, ключи, dump и env с секретами не передавать и не коммитить.
Если доступ выдаётся оператору, достаточно подтвердить его имя и готовность.

| ID | Что предоставить / решить | Минимальный доступ и результат | Когда нужно |
|---|---|---|---|
| IN-01 | FQDN staging, Cloudflare account/zone ID, Hetzner project ID; app/ops/runner IP и private route | Назначенный оператор в отдельном project; SSH public key и проверенный host fingerprint, operator IP allowlist. Root/account-owner token не нужен для аудита | До provision/preflight |
| IN-02 | Три имени R2 bucket: application, recovery, restore; endpoint и подтверждённое размещение | Утверждён default jurisdiction + `weur`; EU residency, если нужна, — отдельное решение. Lifecycle/lock/CORS как §3 | До storage preflight |
| IN-03 | Ссылки на scoped Cloudflare/R2/registry credentials | CF read для проверки настроек зоны/account и отдельный write только оператору изменений; application key, source read-only, recovery writer, временный restore key отдельно, с expiry/revoke. Registry pull и доступ verifier к assets/attestations; GitHub repo/actions/attestations read и GHCR read:packages для закрытых пакетов, без publish/admin у тестировщика. Приватные gh/docker config под фактической deploy/restore OS identity и сетевой доступ PRC-06 | До artifacts/preflight |
| IN-04 | Secret manager и ответственный за escrow | Разные DB users, `SMC_SECRETS_KEY`, age recipient; age identity хранится отдельно и доступна двум назначенным recovery-операторам. Приватный env/config путь, права 0600/0700, известный SSH host | До deploy и backup |
| IN-05 | SMTP/контролируемые тестовые адреса, alert recipient и резервный канал | Scoped SMTP credential; проверка login/reset/MFA и доставки только на согласованные адреса; доступ к monitoring, logs и реальным receipts/ACK | До functional/fault checks |
| IN-06 | Имена Release / Ops / QA / Security / DBA / Incident / Rollback owners, primary и secondary | Ответственные принимают evidence; окно нагрузки, остановки сервисов, тестовых записей и isolated restore согласовано | До target mutations |
| IN-07 | Учётные записи отрицательной матрицы и нагрузочные пользователи | Отдельный временный Studio admin; автор, publisher, reader, пользователь другой ветви и пользователь без прав. 100 уникальных least-privilege API tokens для утверждённой нагрузки через secret manager | До доступа и нагрузки |
| IN-08 | Корпус и режим Студии из [матрицы](studio-staging-acceptance.md#1-dor-целевого-прогона) | Согласовать предложенный объём Studio-записей/отчётов; выделить каталог кодов и изолированный clone для квоты/двух процессов. Включение activation только на время согласованной проверки; assistant остаётся off | До Studio target tests |
| IN-09 | Repository `owner/name`, свободный точный RC-tag для `4a721df0`, Release administrator и независимый reviewer | SHA/DCO/локальный CI уже готовы. Нужны реальные protections и пара environment vars из PRC-01, запрет repo/org shadow vars, GHCR write/OIDC/attestation permissions только workflow jobs. Отдельное разрешение на settings и push tag/публикацию; пока они не выполнены | До PRC-01…03 |
| IN-10 | Окно и полномочия следующего этапа | Отдельное разрешение на закупки/provision/deploy и load/fault/restore, когда готовы конфиги и checklist; merge/publication не подразумеваются утверждением бюджета | До выполнения этих действий |

Удобный минимальный ответ владельца: repository и желаемый RC-tag; FQDN и
project/zone/bucket IDs; имена Release/Ops и независимого reviewer; ссылки
на secret manager/приватные config paths; тестовые получатели и окно;
решение по предложенному Studio workload IN-08. Если стенд ещё не создан,
достаточно статуса каждого входа и ответственного. Разрешение на публикацию
или изменения инфраструктуры не запрашивается этим обновлением плана.

## 7. Последовательность приёмки

**Предположение для планирования:** 6–8 рабочих дней QA/Ops после готовности
доступов, подписанных артефактов и тестового корпуса; без времени закупок,
подготовки GitHub environment/подписанных assets или исправления найденных дефектов. Операторы
работают в согласованное окно. Нагрузку, fault injection и backup не запускать
одновременно; полные capture на ops выполняются вместо k6.

| Этап / приоритет | Работа и evidence | Зависимость | Роль / оценка |
|---|---|---|---|
| S0 / P0 | Закрыть IN-01…10; сохранить конфигурацию без секретов, конкретный workload и расписание. Проверить release path и готовность probes/exporter | Входы владельца | Release + Ops + QA, 0.5–1 день |
| S1a / P0 | PRC-01…06: protected settings, tag/CI/signing receipts, positive/negative verifier и проверка deploy identity | S0 и отдельные разрешения; публикация пока запрещена | Release + Security, 0.5–1 день вне оценки target trials |
| S1b / P0 | Ansible clean install/update rehearsal, CMS/DWH migrations, host/HTTPS/R2/edge preflight; PRC-07 runtime/E2E на подписанных digest | S1a, target preflight и чистый checkout RC | Ops + Security, 1 день |
| S2 / P0 | Login/reset/MFA, scopes/revoke/hidden fields; матрица Студии, live browser, restart/два процесса; CSP/origin risks, embed compatibility и HTTPS cookie/header checks | S1b, роли и synthetic fixture | QA + Security + Backend, 1–2 дня |
| S3 / P0 | Полный backup rehearsal и cache guard; затем 15 мин interactive, 20 uploads, 4 ч soak и отдельный Studio workload; метрики/UX за то же окно | S2, полный корпус, работающие exporter/probes | QA + Ops, 1 день |
| S4 / P0 | Scanner/DB/backup/R2 и потеря app/collector/ops: отказ, восстановление, настоящая доставка ≤120 с и ACK≤10 мин. До R2 drill согласовать точный endpoint/egress rule | S3, окно отказов | Ops + On-call, 0.5–1 день |
| S5 / P0 | Два согласованных полных комплекта и два isolated restore; отрицательные restore; Studio definitions/records/grants/reports; RPO/RTO и rollback decision | S3–S4, доступ escrow | DBA + Ops + QA, 1 день; temporary host ≤48 ч |
| S6 / P0 | Свести результаты, дефекты и ограничения; проверить итоговую конфигурацию и digest; подписать GO/NO-GO | Все предыдущие | Release + Security + владелец, 0.5 дня |

Этапы со сбоями повторяются после исправления с сохранением первого результата.
Изменение продукта/runner требует нового RC SHA и соответствующих проверок;
смена конфигурации/digest требует повторить затронутые target gates. Нельзя
приписать новое evidence прежнему SHA или считать пропущенный сценарий PASS.

### Команды и границы автоматизации

Полные параметры — в [managed acceptance runbook](managed-infrastructure-acceptance.md)
и [production handbook](production-handbook-ru.md). Для этого staging все
host/deploy/fault/restore команды получают объединённый Compose, а не только
`docker-compose.prod.yml` (см. §2). До запуска проверить `git rev-parse HEAD`
и clean tree: host/preflight/capacity берут SHA из checkout; bundle без `.git`
не заменяет его. Evidence и приватные конфиги хранятся вне tracked tree.

| Шаг | Имеющийся инструмент | Дополнительное evidence |
|---|---|---|
| Артефакты | `scripts/release/verify-published-release.ps1` | Явные Channel/Version/ExpectedSourceSha, подписанный manifest, все digest/подписи/attestations/SBOM; `deploy.ps1 -VerifyRelease` не заменяет этот verifier |
| Host / edge | `invoke-managed-host-check.ps1`, `invoke-managed-preflight.ps1` в `scripts/acceptance/` | Изолированный origin/management, R2 permissions, настоящие alert receipts |
| Нагрузка | `scripts/acceptance/run-capacity.ps1 -Profile all` | Согласованные 100 tokens, runtime-metrics JSON на всё окно, CPU/disk/temp/UX/restart и Studio workload отдельно |
| Отказы | `scripts/acceptance/run-failure-scenario.ps1` по одному: scanner, database, backup | R2/host/collector/ops-loss выполняет оператор; получатель подтверждает доставку и ACK |
| Recovery | `scripts/prod/recovery_coordinator.py`, `verify_recovery_set.py`, `restore-combined.ps1` | Один согласованный комплект CMS+DWH+objects; отдельный project/bucket, `-MaxRpoSeconds 86400 -MaxRtoSeconds 7200`; полный внешний таймер |
| Студия | [Матрица и live E2E команда](studio-staging-acceptance.md#3-live-browser-и-протокол) | Остальные cases — проверяемый UI/API протокол; универсального target-runner всех ACC нет |

Аргументы `-AcknowledgeTargetLoad`/`-AcknowledgeServiceDisruption` у runners
используются только в согласованное окно. Для restore обязательны
`-IsolatedProjectName smartupcms-restore-...`, отдельный bucket/prefix и
объединённый Compose без зафиксированных имён volumes/networks. Неверный age
key, отсутствующий object archive и усечённый архив должны дать отказ без
изменений рабочего project. Таймер RTO скрипта не заменяет полный таймер §5.

## 8. DoD, GO / NO-GO и протокол

DoR target-прогона: входы §6 закрыты, реальные SHA/digest известны, конфиги
проверены, corpus/roles/окно/восстановление согласованы, probes/exporter готовы.
До этого target acceptance остаётся BLOCKED/NOT RUN; локальные PASS сохраняются.
Локальные контракты не закрывают целевые `AC-09`, `AC-11`, `AC-12` или production GO.

- PRC-01…07 выполнены; подписанный prerelease допущен только к staging.
  Неизвестные source/signer/digest, unsigned manifest, недействующие protections
  или непроверенный runtime означают NO-GO независимо от зелёного локального CI.
- Все P0 из [production launch checklist](production-launch-checklist.md) и
  применимые Studio cases выполнены на утверждённом target; нет открытых
  Blocker/High, нарушений доступа, порчи данных или потери изменений.
- Пороги §4–5 выдержаны без ослабления; два полных restore доказаны. Статусы
  `automatedStatus=PASS` с `status=UNVERIFIED` не проходят приёмку до receipt.
- DOD-09 / ACC-20 Студии закрываются только target evidence. ACC-21 относится
  к будущей эволюции опубликованных схем и для create-only RC имеет N/A;
  live LLM остаётся вне разрешённого scope, проверяется выключенный режим.
- Допустимы только некритичные P1/P2 отклонения с владельцем, сроком и явным
  решением Release/Security/владельца; исключения не отменяют P0 или SLO.
- Release owner сверяет итоговые флаги/доступы и срок временных credentials,
  rollback owner подтверждает процедуру; CEO отдельно разрешает запуск.

Приватный acceptance index содержит case/gate ID, полный source SHA, шесть
registry digest (и platform digests), release tag/channel/workflow, run ID/URL,
approvals, hash подписанного SHA256SUMS и receipt verifier, отдельную редакцию
runbook, revision и hash конфигурации без секретов, hashed target ID,
UTC start/end, роль, expected/actual, статус, hash/путь артефакта, reviewer и
решение по отклонениям. Capacity manifest связывается hash с host/preflight
manifest и той же конфигурацией: сам он не доказывает всю identity стенда.
Секретные env, cookies, HAR с токенами, данные пользователей и webhook URLs
в evidence не допускаются. Скриншоты используют только synthetic данные.

Архитектура приложения, две PostgreSQL databases, серверная авторизация и
Compose runtime сохранены. Обновление плана не выполняет target-команды,
не публикует локальные отчёты и не означает production GO.
