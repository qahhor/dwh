# Staging: утверждённая конфигурация и приёмка

**Решение владельца:** 2026-10-12, конфигурация, бюджет, RPO/RTO и upload SLO
30/60 с утверждены. Основа — RC `aa098672ea2f826e3c24b36212891ed502caf861`.
Изменения спецификации, runner и гистограммы требуют нового SHA и новых образов;
старый RC не содержит их. Этот annex фиксирует требования установки, не факт
приёмки. Закупки, создание ресурсов, публикация и merge не разрешены.

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

Все негативные проверки, прежде закрытые локально, повторяются на target.
Alertmanager/получатель, authenticated monitoring health/drill endpoints,
exporter runtime-metrics и probes R2/ClamAV/целого backup-комплекта ещё должны
быть подключены оператором. Обычные API/upload latency alerts имеют `for: 15m`
и не могут доказать доставку за 120 с. Для acceptance fault drills нужен
отдельный короткий probe route; измерять от инъекции, включая задержку scrape,
remote write, evaluation, `for`, routing и доставку. Остановка app-хоста также
должна обнаруживаться с ops-хоста по свежести последнего sample; сохранённый
`up=1` после обрыва remote write не доказывает доступность.

## 5. RPO / RTO и блокирующие условия recovery

**RPO ≤ 24 ч (86400 с), RTO ≤ 2 ч (7200 с).** Полный согласованный комплект
CMS + DWH + object bytes дважды в сутки, 00:00/12:00 UTC, длительность ≤ 60 мин.
Recovery point — начало согласованного снимка после остановки всех writers,
включая jobs; время завершения upload manifest не улучшает RPO.
Полный комплект: warning после 14 ч, critical после 20 ч, нарушение после 24 ч.
`SMC_BACKUP_MAX_AGE=20h` измеряет только backup status БД, не эти три компонента.

Существующий sidecar работает постоянно и создаёт DB backups; его интервал
43200 с отсчитывается после завершения, поэтому не является UTC-расписанием.
Перед GO нужен проверенный операторский coordinator: дождаться завершения
sidecar, приостановить его, drain/stop server, снять CMS/DWH/object inventory и
зашифрованные bytes, проверить remote checksums, записать consistent-point,
в `finally` восстановить server и sidecar. Повторный старт sidecar создаёт
ещё один DB backup — учесть в ёмкости. Запрещено объявлять RPO выполненным
только по наличию трёх несогласованных архивов или уменьшать retention удалением
последнего проверенного комплекта.

Локальный DB cache: последние **два успешных комплекта**, ≤ 60 GiB; полный
remote retention 14 дней. Count-based pruning и free-space guard — ещё
обязательная часть coordinator: базовый `BACKUP_RETENTION_DAYS=14` не делает
этот cache ограниченным. До их реализации и проверки на полном корпусе
scheduled staging backup/soak не запускать. Ops нужны `pwsh`, `aws`, `age`,
`tar`, `sha256sum`; локальный тестовый age fallback не является production образом.

RTO включает обнаружение 5 мин + решение 10 + provision 25 + restore 55 +
smoke 25 = 120 мин. Два target-drill с полным корпусом должны подтвердить
таймер от отказа до рабочей установки, CMS/DWH row inventory, все object
checksums и выборочные download. RTO скрипта restore покрывает только часть
этого времени. Cold SKU availability неизвестна; запасной хост не оплачен.
PITR/HA не заявлены. Потеря всего R2 account не покрывается копией в том же
аккаунте и требует отдельного recovery-дизайна.

## 6. Что ещё требуется перед выкладкой

| Вход / решение | Критерий завершения |
|---|---|
| Domain, zone/account, provider region, source IP | Конкретные значения в приватном env; private ops route и TLS подтверждены |
| Доступы | Hetzner project/SSH; ограниченные Cloudflare/R2 keys; registry pull; SMTP; monitoring/on-call, через secret manager |
| Выпуск | Новый SHA, DCO и локальные проверки; отдельное разрешение на публикацию шести подписанных образов; verifier на target |
| Эксплуатация | Названы release, operations, security и rollback owners; privacy/retention/on-call подтверждены |
| Recovery и alert integration | Coordinator/cache guard/complete-kit probe и ≤ 120 с fault route проверены, два restore drill PASS |

Локальные render, promtool и k6 regression проверки не закрывают эти строки,
`AC-09`, `AC-11`, `AC-12` или production GO. Архитектура приложения, два
PostgreSQL databases, серверная авторизация и Compose runtime сохранены.
