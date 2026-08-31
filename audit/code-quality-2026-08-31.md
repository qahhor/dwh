# Code quality and technical debt audit — 2026-08-31

## Сводка

Текущий код использует typed errors, RFC 9457, parameterized SQL, records и централизованные permission annotations. Главный риск качества — не отсутствие паттернов, а расхождение между ними и новыми изменениями: красный ArchUnit, frontend tests, дублирование UI и документация, описывающая уже удалённые/несуществующие артефакты.

| Наблюдение | Риск / что сломается | Доказательство | Минимальная рекомендация / эффект | Усилие | Приоритет | Класс долга |
|---|---|---|---|---|---|---|
| Обязательные проверки текущего workspace красные | Нельзя получить воспроизводимый RC; регрессии уже есть | `audit/evidence/verification-2026-08-31.md` | Зафиксировать commit RC и вернуть ArchUnit + 53 frontend tests в green | M | P0 | код / процесс |
| Огромные standalone components | Изменение одного flow затрагивает сотни шаблонных строк и ухудшает тестируемость | `tasks.component.ts` ~3 136 строк; users ~1 579; roles ~1 354; settings ~1 179 | Дробить только затрагиваемые экраны на container + focused presentational components; не делать big-bang rewrite | L | P1 | код-smell |
| Две копии одинаковых UI button/badge, в CP две pagination реализации | Drift поведения/a11y между приложениями | `apps/web-instance/src/app/shared/ui/*`; `apps/web-cp/src/app/shared/*` | В рамках исправляемых P0/P1 выбрать один canonical API; удалить только реально дублируемую CP pagination | M | P2 | архитектурный |
| Spring Boot Maven plugin без явной версии в app POM | Build может подтянуть несовместимый plugin; локально Maven показал model warning | `apps/instance/pom.xml:137`; `apps/control-plane/pom.xml:85`; root `pom.xml:104-130` не pin-ит plugin | Pin `${spring-boot.version}` в root pluginManagement; проверить effective-pom | S | P1 | зависимость |
| Error handling централизован, generic 500 не отдаёт детали | Хорошая базовая защита и единый контракт | `GlobalExceptionHandler.java:34-164` | Сохранить; добавить correlation id в problem/log contract и masking test | S | P2 | — |
| README и архитектурный doc противоречат дереву | Новый разработчик запускает/ищет несуществующие модули, release checklist ненадёжен | `README.md:21` говорит Angular 20; `package.json` — 22.1.4; `docs/architecture/monorepo-structure.md:17-84` описывает отсутствующие root package/workspaces/deploy dirs | Сгенерировать актуальный inventory из repo и удалить/пометить superseded ссылки; один source of truth | M | P1 | документация |
| OpenAPI вручную покрывает малую часть REST | Интеграции не имеют полного машинного контракта; drift код/док | `OpenApiController.java:57-118` содержит 6 paths при 29 REST controllers | Генерировать OpenAPI из controller annotations либо временно маркировать документ partial и покрыть только release-critical endpoints contract tests | M | P1 | документация/API |
| Lockfiles есть, production npm audit чист | Снижает supply-chain drift runtime dependencies | `apps/web-instance/package-lock.json`, `apps/web-cp/package-lock.json`, `e2e/package-lock.json`; evidence | Оставить `npm ci` и добавить audit policy для prod/dev отдельно | S | P2 | зависимость |

## Технический долг: приоритетный реестр

1. **Безопасность:** SSO trust bypass, unsigned moderation callback, markdown XSS, search authorization, webhook SSRF/secret exposure. Исправление закрывает прямые attack paths.
2. **Инфраструктура:** production nginx ports/TLS и deploy false-success. Исправление делает release реально запускаемым и обратимым.
3. **Процесс:** нет чистого RC и frontend unit gate в CI. Исправление превращает «локально работает» в проверяемый artifact.
4. **Архитектура:** concurrency semantics для idempotency/outbox и one-replica assumptions. Исправление предотвращает двойные side effects.
5. **Документация:** broken links/stale claims и неполный OpenAPI. Исправление делает эксплуатацию и onboarding воспроизводимыми.
