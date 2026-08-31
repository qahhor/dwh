# Verification evidence — updated 2026-09-01

Контекст: проверки выполнены по текущему workspace `D:\Claude\dwh`, ветка `main`. До реализации дерево уже содержало более 160 изменённых/удалённых/untracked путей, поэтому локальный результат не объявляется immutable release candidate до commit/push и remote CI.

## Выполненные проверки

| Проверка | Результат | Артефакт / команда |
|---|---|---|
| Карта зависимостей | 3 938 узлов; import cycles не обнаружены | `graphify query ...`; `graphify-out/GRAPH_REPORT.md` |
| Backend architecture | **PASS**, 9/9 | Maven Testcontainers run, `ModularArchitectureTest`; controller→repository violation устранено через service boundary |
| Полный backend suite | **PASS**, instance 159 + control-plane 4 = **163/163** | `mvn -B verify` в Maven-контейнере с Docker/Testcontainers |
| web-instance | **PASS**, 23 spec-файла, **55/55**; typecheck и production build | Node 24.15.0 container: `npm ci`, `npm test`, `npm run typecheck`, `npm run build` |
| web-cp | **PASS**, 3 spec-файла, **5/5**; typecheck и production build | Node 24.15.0 container: те же gates |
| npm audit | **PASS**, 0 vulnerabilities в web-instance, web-cp и E2E | `npm audit` после exact `npm ci` |
| Production config | **PASS** | `scripts/prod/test-release-config.ps1`: Compose config, hardened non-root proxy build, `nginx -t`, Bash/PowerShell syntax |
| Deploy failure injection | **PASS** | `scripts/prod/test-deploy-fail-closed.sh`: failed DB backup блокирует migrations и возвращает nonzero |
| Clean-slate deployment | **PASS** | Удалены только `smartupcms_*` volumes; пустые DB мигрированы instance V001–V018 и CP V001–V005; 7/7 services healthy |
| Production proxy smoke | **PASS** | `nginx:nginx`, container port 8080, `/healthz` = 200; host publish только loopback в fleet Compose |
| E2E/config/artifact security | **PASS** | config 3/3, TypeScript, failure-artifact secret scan, Playwright Chromium **8/8** |
| Backup | **PASS** для обеих DB | `scripts/prod/backup.ps1`: PostgreSQL custom-format dump + SHA-256 для instance и control-plane |
| Restore drill | **PASS** для обеих DB | `scripts/prod/restore.ps1`: checksum, preserved `_pre_restore_*` DB, instance V017 dump forward-migrated to V018; после drill выполнен новый clean-slate deploy |
| Secret history | **PASS**, 139 commits, 0 leaks | Gitleaks 8.28.0 с тремя документированными exact-fingerprint false-positive fixtures в `.gitleaksignore` |
| Dependency manifests | **PASS**, 0 HIGH/CRITICAL | Trivy 0.74.0: root/instance/CP POM и оба production npm lockfile; E2E дополнительно покрыт полным `npm audit` |
| Runtime images | **PASS**, 0 HIGH/CRITICAL во всех 7 release images | `scripts/security/scan-runtime-images.ps1`: instance, CP, web, web-cp, hardened PostgreSQL, Typesense и proxy |
| Git whitespace | **PASS** | `git diff --check` |

## Подтверждённые изменения release contour

- SSO и публичный module callback недоступны; unsafe Markdown trust bypass удалён; CSP не допускает inline script.
- Search для пользователей без wildcard admin permission закрыт fail-closed до полноценного entity/data-scope filter.
- Production images используют lowercase OCI names; PostgreSQL/Typesense/Nginx runtime layers patched и работают non-root там, где это технически применимо.
- CI запускает frontend unit/typecheck/build, clean E2E, SBOM, Gitleaks, filesystem scan и фактический runtime-image scan.

## Остаточные ограничения — release decision

- **GA: нет. Ограниченный pilot: условно**, после pushed immutable SHA и зелёного remote CI.
- Не подтверждены registry digests/promotion/provenance, branch protection и environment approvals.
- Внешний TLS/DNS/certificate rotation не разворачивались: проверен только loopback proxy contract.
- Автоматический rollback на previous digest отсутствует; deploy fail-closed, backup и manual restore проверены.
- Backup не покрывает `app-files`/будущий object storage, offsite encryption и WAL/PITR; RPO/RTO письменно не согласованы.
- Нет representative load test, production telemetry, SLO/SLI, alert ownership и DR rehearsal на отдельном host.
- Webhook SSRF/redirect/egress hardening и axe accessibility gate остаются P1.
