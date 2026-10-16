# Критерии GO / NO-GO первого production выпуска

Версия: 2026-10-16. Любой незакрытый P0 означает NO-GO.
Отметки PASS заполняет ответственный по сохранённому evidence, а не по наличию
конфига/теста в Git. Текущий комплект сам по себе не разрешает публикацию.

| Приоритет | Gate | Критерий и evidence | Владелец |
|---|---|---|---|
| P0 | Кандидат | Один DCO SHA, clean tree; CI повторён на этом SHA, все jobs PASS, no-skips/coverage/API gates не ослаблены | Tech lead |
| P0 | Артефакты | Шесть immutable OCI refs, подписанный checksum manifest и bundle; exact channel/tag/source SHA/workflow, attestations и SBOM проверены по ADR-0040. Staging RC не является production GO | Release |
| P0 | Студия | [Целевая матрица RC](studio-staging-acceptance.md): live формы/отчёты/панели, revoke, разные процессы/restart, target workload и восстановление; assistant off, fixture-only и N/A явно отделены | QA + Security + Ops |
| P0 | Доступ | Отзыв session/token/role, data scope, hidden fields, SSRF/upload ограничения, login budgets PASS | Security |
| P0 | Секреты | Разные DB-role credentials, scoped R2/SMTP/registry keys, encryption keys escrow, 0600, нет secret leaks | Security + Ops |
| P0 | Edge | HTTPS, origin bypass denied, management/DB/exporters private, trust proxy и rate limits проверены | Ops |
| P0 | Install/update | Ansible rehearsal на чистом host и обновление; повтор не меняет release, missing signatures/secret/backup → fail closed | Ops |
| P0 | Миграции | CMS+DWH migration success, immutable manifest, schema-compatible rollback rehearsal либо full restore plan | DBA |
| P0 | Recovery | Два комплекта и два restore на полном корпусе; remote read-back SHA-256, Object Lock и независимые ключи | DBA + Ops |
| P0 | RPO/RTO | RPO ≤24 ч от consistent point; полный RTO ≤2 ч; capture ≤60 мин, время остановки измерено | Incident owner |
| P0 | Нагрузка | 100 активных пользователей, 4 ч soak; обычный API p95≤300 мс/p99≤1000 мс/error≤0.1% | QA + Ops |
| P0 | Upload | 20 одновременных near-limit upload, 20/20 успешных; p95≤30 с/p99≤60 с | QA |
| P0 | Ёмкость | CPU<80%, heap≤80%, Hikari pending=0, app RAM free≥4 GiB, disk free≥50 GiB и >20%, scratch peak измерен | Ops |
| P0 | Алерты | Реальная доставка ≤120 с, ACK≤10 мин; потеря app host/collector, recovery failure и ops/receiver loss обнаруживаются | On-call |
| P0 | Логи | Sanitized off-host collection/retention проверены; доступ ограничен; correlation trace_id работает | Ops + Security |
| P0 | Эксплуатация | Имена primary/secondary, incident/rollback owners; SSH/secret escrow и аварийный канал проверены | CEO + Ops |
| P0 | Решение | Release owner фиксирует GO и окно; CEO разрешает публикацию/переключение трафика отдельно | CEO + Release |
| P1 | Автоматизация ОС | Проверенный patch/reboot cadence, baseline CIS по выбранной ОС; drift detection | Ops |
| P1 | Улучшение RPO | При бизнес-потребности: WAL/PITR, versioned objects и сверка единого recovery point | DBA |
| P1 | HA | При утверждённом SLO/budget: отдельный DB failover, второй app, независимый ops и fault drills | CTO |
| P1 | Continuous delivery | Автоматический promotion одного digest через environment protection после PASS evidence | Release |

Запись решения содержит SHA, digests, env revision без секретов, ссылки на
локальные/приватные результаты, время и подписи ответственных. Недоступный
staging, неподписанные артефакты, неизвестные контакты и непройденный restore
не закрываются обещанием выполнить проверку после запуска.

## Детализация обязательных проверок

Эти пункты входят в P0-строки таблицы; переход на краткую таблицу не отменяет
ранее действовавшие проверки первого выпуска.

- [ ] SemVer tag неизменяем, соответствует проверенному DCO SHA; target verifier
  проверил checksum, подписи/provenance и оба формата SBOM всех шести образов.
- [ ] Для любой принятой Critical/High уязвимости есть owner, mitigation,
  expiry и явное решение выпуска; changelog, migration notes и ограничения готовы.
- [ ] CPU/RAM limits/reservations и bounded tmpfs заданы всем контейнерам;
  доверенные proxy сети явные, поддельный X-Forwarded-For не обходит rate limit.
- [ ] `SMC_WEB_FRAME_SOURCES` содержит только разрешённые hosts; security headers
  тестируются на публичном HTTPS адресе, Observatory A+ проверен внешним сканированием.
- [ ] Публичны только разрешённые web endpoints; secret files и Docker socket
  недоступны извне. Проверено продление TLS и план дискового роста/retention.
- [ ] Пустая CMS/DWH мигрируют; upgrade из предыдущего проверенного кандидата
  репетируется. Runtime DB user не имеет DDL/TRUNCATE/superuser; partition runway готов.
- [ ] Initial admin password изменён, bootstrap credential удалён где поддерживается;
  least-privilege admin/operator/auditor/user сценарии проверены.
- [ ] ALL/SUBTREE/UNITS/SELF: cross-branch/direct-ID deny для tasks/comments/files,
  metadata/download/delete; чужой ID не раскрывает metadata.
- [ ] Login/reset не сохраняются idempotency filter; 24-часовая retention
  и hourly cleanup подтверждены. Export streaming bounds и upload admission действуют.
- [ ] CSRF, password recovery, audit events, rate limits, session/token revoke
  и MFA проверены. SMTP/Telegram настроены; console providers не доставляют секреты.
- [ ] `SMC_PUBLIC_URL` — утверждённый HTTPS URL; reset link не использует request Host.
  `SMC_SECRETS_KEY` и age identity восстановимы независимо от app host.
- [ ] Ротация scoped delivery/storage credentials пройдена; Privacy Owner,
  Incident Response Lead, Backup Operator, Release Manager и security reporting назначены.
- [ ] E2E покрывает login, forced password change, navigation, users/roles,
  доступные прикладные модули, files, notifications, announcements, System.
- [ ] Loading/empty/success/error, keyboard/screen-reader/narrow viewport,
  near-limit и interrupted upload проверены.
- [ ] `SMC_FILE_SCANNER_REQUIRED=true`: EICAR отклонён, scanner outage
  закрывает загрузку, quarantine очищается; отключённые модули не выглядят доступными.
- [ ] Preflight/capacity/failure manifests относятся к целевому контуру;
  zero missing/orphan objects, download checksum и CMS/DWH inventories подтверждены.
- [ ] Maintenance cadence, внешний сбор логов и независимый канал эскалации
  проверены; отсутствующие target проверки отмечены UNVERIFIED и блокируют GO.
