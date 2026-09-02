# SmartupCMS release readiness audit — 2026-09-02

Evidence: [unified release verification](evidence/smartupcms-unified-release-2026-09-02.md),
[architecture](architecture-2026-09-02.md), [security](security-2026-09-02.md),
[testing](testing-2026-09-02.md), [DevOps](devops-2026-09-02.md),
[UI/UX](widgets-2026-09-02.md), [documentation](documentation-2026-09-02.md).

## 1. Executive summary

1. **Готовность: УСЛОВНО для repository RC; НЕТ для production launch.**
2. Unified open-source runtime реализован и pushed в `5013ebd7d59d038de7948146c7b792ae24628d45`.
3. Control Plane не входит в поддерживаемый runtime; одна установка обслуживает одну организацию.
4. Локально зелёные backend 194/194, frontend 68/68, typecheck/build и browser E2E 9/9.
5. Пустая БД V001→V019 и upgrade V018 backup→V019 прошли; `/` и `/healthz` = 200.
6. Default runtime за 65 секунд не создал внешнего трафика.
7. Gitleaks не нашёл секретов; Trivy: 0 HIGH/CRITICAL в зависимостях и 5 образах.
8. Webhooks теперь fail-closed и hardened; notification API/UI contract исправлен.
9. P0 кода, подтверждённого этим аудитом, не осталось.
10. P0 production остаются installation-specific: remote required checks/release artifacts, Hetzner/Cloudflare boundary, restore объектов, SLO/RPO/RTO/alerts, capacity/soak.
11. CI на push feature-ветки не запускается (`ci.yml:5-9`); требуется PR или утверждённый manual trigger.
12. Нельзя объявлять GO до заполнения `docs/ops/production-launch-checklist.md`.

## 2. Release blockers (P0)

| ID | Наблюдение → риск | Доказательство | Конкретное действие / критерий | Effort |
|---|---|---|---|---|
| P0-R1 | Для pushed branch нет remote required checks и immutable SemVer artifacts → локально проверенный SHA не равен проверенному release supply chain. | GitHub API: 0 branch runs; `ci.yml:5-9`; release artifacts создаются `release.yml:143-172`. | Открыть PR, получить все required jobs+DCO green; после merge создать SemVer tag и независимо проверить digest/cosign/SBOM/checksums. | S |
| P0-I1 | Реальный Hetzner/Cloudflare target не проверен → возможны origin bypass, открытые DB/management ports, TLS/WAF ошибки. | В репозитории target evidence не найдено; checklist `:21-33,95-97`. | Развернуть target, выполнить external port/TLS/header/rate/upload tests; снаружи доступен только web origin через утверждённую edge policy. | M |
| P0-I2 | Нет isolated restore target DB и uploaded objects → backup success не доказывает восстановимость. | `architecture-overview.md:57-58,98-100`; checklist `:35-47`. | Потерять disposable target, восстановить DB+local/R2 objects+age identity, сверить checksum и открыть файл; измерить RPO/RTO. | M |
| P0-I3 | Нет утверждённых SLO/SLI, alerts/on-call и target soak/load → 100 одновременных пользователей не подтверждены. | checklist `:31-33,75-86`; observability/load artifacts не найдены. | Согласовать SLO/RPO/RTO; три load scenarios + 4h soak; alerts доходят владельцу и имеют runbook. | M |
| P0-I4 | Не заполнены владельцы PII/retention/incidents и четырёхмесячный go/no-go. | threat model `:7-9`; checklist `:49-61,88-97`. | Именованные Product/Engineering/Security/Operations owners подписывают установочный evidence bundle и GO. | S |

## 3. Risk register

| Риск | Вероятность | Влияние | Mitigation |
|---|---|---|---|
| Потеря DB/объектов при отказе хоста | Medium | Critical | Off-host encrypted DB + R2/local object recovery drill; measured RPO/RTO |
| Ошибка edge/origin конфигурации | Medium | Critical | External scan, host firewall, Cloudflare origin policy, certificate-renewal test |
| Duplicate side effects при concurrency/scale-out | Medium | High | Atomic idempotency/outbox claims; enforce one server replica meanwhile |
| Capacity collapse at 100 active users | Medium | High | Representative load/soak, DB-pool and object throughput metrics |
| Malicious uploaded content | Medium | High | MIME sniff, quarantine and malware scan before download/publish |
| Enabled webhook DNS rebinding/network pivot | Low/Medium | High | Exact allow-list + current checks + host egress firewall/trusted DNS |
| Incident remains invisible | High | High | Golden signals, backup/capacity/cert/dead-letter alerts, on-call owner |
| PII retained or exposed through support/logs | Medium | High | Installation retention/legal basis, masking/access tests, incident procedure |
| Supply-chain artifact differs from reviewed SHA | Medium | High | PR checks, immutable tag/digests, SBOM/provenance, independent cosign verify |
| UI regression outside automated routes | Medium | Medium | Axe expansion, manual screen-reader pass, four targeted visual baselines |

## 4. План работ на 2–4 недели

### Неделя 1 — стабилизация и безопасность

- PR для `codex/unified-open-source`; required CI+DCO green; review webhook,
  notification contract, Compose hardening and tests.
- Реализовать atomic idempotency and outbox claim или формально закрепить
  `server replicas=1` как release invariant.
- Добавить MIME sniff/quarantine/scanner contract для файлов.
- Заполнить installation threat/PII annex: owners, legal basis, retention,
  support access, incident contacts.

### Неделя 2 — тесты, наблюдаемость и релизный контур

- Target Hetzner+Cloudflare deployment: DNS/TLS/WAF/firewall/secret permissions,
  external scan and release-by-digest.
- DB+object restore drill; измерить RPO/RTO, проверить off-host age identity.
- Ввести минимальные golden signals и alerts: health, latency/error, DB pool/disk,
  object storage, cert, backup age/failure, delivery dead letter.
- Load baseline на 100 active users и 50 GB/month growth; 4-hour soak.

### Неделя 3 — regression depth

- Cross-role/direct-API negative E2E для RBAC/IDOR/files/search.
- Axe smoke на users/roles/tasks/files + ручной NVDA/VoiceOver pass.
- Report-only coverage для backend/frontend; затем согласованные changed-code
  floors на auth/RBAC/storage/outbox.
- Contract tests/generated schema для notifications, webhooks и files.

### Неделя 4 — release rehearsal

- Stable SemVer tag, multi-arch images, checksums, SBOM/provenance/cosign verify.
- Развернуть только по immutable digests; intentional failed deploy and rollback
  rehearsal; повторный restore smoke.
- 24h target soak, triage only release blockers; финальный GO/NO-GO с owners.

## 5. Definition of Done для релиза

- [ ] Reviewed immutable SHA merged; required CI и DCO green.
- [ ] Stable SemVer artifacts/digests/checksums/SBOM/provenance/cosign verified.
- [ ] Backend, frontend, typecheck/build, config/security и 9+ critical E2E green без skip.
- [ ] Target externally exposes only HTTPS web origin; DB/search/server/management private.
- [ ] Empty migration and oldest-supported V018→V019 upgrade green.
- [ ] Encrypted DB + uploaded objects restored in isolation; measured RPO/RTO accepted.
- [ ] 100-user load and target soak meet written SLO/error/capacity thresholds.
- [ ] P0 alerts reach named on-call and link to exercised runbooks.
- [ ] Webhook egress policy/firewall and file quarantine/scanning verified.
- [ ] PII owners, legal basis, retention, masking, support access and incident actions approved.
- [ ] Product, Engineering, Security and Operations record explicit GO in the launch checklist.

## 6. Вопросы / нехватка данных

1. Кто открывает/утверждает PR и какие GitHub checks защищают `main`? Branch protection не подтверждён.
2. Каковы численные SLO: availability, p95 latency и допустимый error rate?
3. Каковы договорные RPO/RTO и срок хранения DB, objects, logs и audit events?
4. Какой точный Hetzner SKU/region/OS/disk layout и кто владеет host firewall/patching?
5. Какой Cloudflare mode используется: proxy/WAF/rate limits/origin certificate и как закрыт direct-origin access?
6. R2 — единственный managed object store? Нужны bucket retention/versioning/lifecycle и recovery owner.
7. Кто on-call и Security/PII owners; где incident/customer communication contacts?
8. Какой representative dataset разрешён для 100-user load/soak без реальных PII?
9. Нужны ли webhooks в первом релизе? Если нет, безопасный release state — disabled.
10. Какой внешний API официально поддерживается и требует versioned contract compatibility?
