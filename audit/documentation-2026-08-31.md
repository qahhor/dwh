# Documentation audit — 2026-08-31

## Оценка по аудиториям

| Аудитория | Состояние | Почему |
|---|---|---|
| Новый разработчик | Условно | README/onboarding/guidelines есть, но версии и дерево репо расходятся с кодом, часть ключевых ссылок удалена |
| Release engineer | Не готово | Runbooks есть, но deploy/backup contract не соответствует скриптам; нет immutable artifact/promotion procedure |
| Оператор/on-call | Не готово | Есть operations/maintenance/rollback тексты, но нет фактических alerts/dashboard, owner matrix и drill evidence |
| API consumer | Не готово | Manual OpenAPI покрывает только малую часть controllers и содержит contract drift |

| Наблюдение | Риск | Доказательство | Минимальная рекомендация / критерий | Усилие | Приоритет |
|---|---|---|---|---|---|
| README ведёт на удалённые TRD и plan документы | Onboarding теряет продуктовые требования и remediation context | `README.md:109-112,123,137`; файлы `docs/trd/TRD-01..04`, `docs/plan/remediation-plan.md`, `docs/plan/M0-plan.md` отсутствуют в текущем workspace | Восстановить намеренно удалённые документы либо удалить ссылки с явным replacement; link checker = 0 broken | S | P1 |
| Версии и структура устарели | Команды/предположения разработчика неверны | `README.md:21` — Angular 20, manifest — 22.1.4; `monorepo-structure.md:17-84` описывает отсутствующие root package, `apps/web`, `crypto-vault`, adapters, ui-kit, Nomad/Vault production dirs | Сгенерировать фактический inventory и исправить только entry docs; CI сверяет versions/paths | M | P1 |
| Readiness claims противоречат source/runbooks | Руководитель принимает ложный GO | `AUDIT-05-production-readiness-final.md:14-17` говорит ready; `deployment-guide.md:186-195` признаёт local files, local secrets/logs, no WAL/encryption/autocheck; `ADR-0010-resilience-tiers.md:90` action unchecked | Пометить старый audit superseded; один signed launch checklist с measured evidence | S | P0 process |
| Manual OpenAPI описывает лишь 6 paths при 29 REST controllers, bearer format назван JWT/Token | Клиент генерирует неполный/неверный SDK; API drift | `apps/instance/src/main/java/com/greenwhite/dwh/instance/config/openapi/OpenApiController.java:30-31,57-118`; REST controller inventory | Немедленно пометить `partial`; затем генерировать из annotations или держать release-critical contract tests | M | P1 |
| Upload contract в OpenAPI расходится с implementation error/status semantics | Интеграция неверно обрабатывает success/quota | OpenAPI `OpenApiController.java:94-100`; implementation `MfFileController.java:31-44`, typed exceptions в `MfFileService.java:36-63` | Contract test на actual status/problem codes; обновить spec из теста | S | P1 |
| Runbooks охватывают deployment/operations/rollback/maintenance, migration guide содержит expand/contract | Хорошая документальная база | `docs/ops/*.md`; `docs/guidelines/database-migrations.md`; `docs/runbooks/` | Сохранить, но каждый destructive/recovery command должен проходить rehearsal | S | P2 |
| Known limitations описаны, но не связаны с коммерческим tier | SLO/RPO обещания расходятся | `deployment-guide.md:186-195`; `production-launch-checklist.md:61-93`; `ADR-0010-resilience-tiers.md:50-52` | Один tier matrix: implemented mechanism → measured SLI/RPO/RTO → promise; owner approval | S | P1 |

## Что нужно добавить

- Release manifest: source SHA, image digests, migration versions, config schema version, SBOM/provenance, previous release.
- Проверенный runbook для clean deploy, smoke, rollback и full restore; приложить machine-readable results.
- API ownership/versioning/deprecation policy и generated/contract-tested spec.
- Data catalog: PII fields, purpose, owner, retention, masking, erase/export and backup handling.
- Threat model и security exception register с owner/expiry.
- SLO/SLI и alert catalog, только после согласования product tier.
