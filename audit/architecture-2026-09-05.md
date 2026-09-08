# Architecture audit — SmartupCMS — 2026-09-05

> Архивный непроверенный черновик. Сохранён в Git по запросу пользователя
> 2026-09-08; публикация не подтверждает выводы, результаты проверок или
> актуальность находок. Не использовать как требования или release evidence.

База: `710efeb`; [контекст, executive summary и общий backlog](D:/Claude/dwh/audit/cto-audit-2026-09-05.md). Это анализ существующего продукта, а не предложение нового scope.

## Решение CTO

**Сохранить модульный монолит.** Одна организация на установку и независимые БД/storage обеспечивают coarse-grained изоляцию, а не микросервисную независимость. Spring Boot + SQL-first + transactional events + provider SPI достаточны как архитектурная основа; фактическая ёмкость требует измерений. ADR-0014 прямо исключает обязательные fleet/control plane и multi-host HA: [ADR](D:/Claude/dwh/docs/adr/ADR-0014-unified-open-source-runtime.md:21). Нельзя предлагать вернуть их под видом исправления долга.

Физические границы: browser → edge → NGINX/web → один server → PostgreSQL, Typesense, storage, ClamAV. SMTP/Telegram/webhooks — опциональные egress boundaries. Карта контейнеров приведена в [основном отчёте](D:/Claude/dwh/audit/cto-audit-2026-09-05.md).

Ключевые наблюдаемые связи модулей:

| Потребитель | Зависимость | Доказательство |
|---|---|---|
| kauth | md user/permissions; common SecurityContext | [KauthAuthenticationFilter:3](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/security/KauthAuthenticationFilter.java:3) |
| md | PasswordHasher/UserSessionInvalidator ports, audit, search | [MdUserService:20](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdUserService.java:20) |
| ms.task | md scope, file service, audit, search, task events | [MsTaskService](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsTaskService.java) |
| mf | provider-spi StorageProvider/FileScanner, md scope, metadata | [MfFileService:20](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileService.java:20) |
| report/search | Прямое чтение нескольких доменных таблиц через JDBC | [ReportService:34](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/report/service/ReportService.java:34), [TypesenseIndexer:29](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseIndexer.java:29) |
| common.security | SQL-имена task/member/comment/file tables | [ScopeFilter](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/common/security/ScopeFilter.java) |

Это scoped dependency map, не полный граф каждого класса. ArchUnit подтверждён сегодняшним прогоном: 9 tests, 0 failure/error. Проверяются package cycles, Controller→Repository, Repository→Service, md/mf→ms и аннотации. [ModularArchitectureTest:39](D:/Claude/dwh/apps/server/src/test/java/com/greenwhite/dwh/instance/architecture/ModularArchitectureTest.java:39). SQL-строки и публичные обходные overloads тестом не охватываются.

## SOLID / DDD / scorecard

Баллы 0–10 — качественная оценка инженерной готовности, **не измеренная метрика и не процент завершения**.

| Область | Балл | Основание |
|---|---:|---|
| Структура и deploy boundaries | 8 | Два application runtimes и отдельные libraries; ограниченный single-instance продукт [ADR-0014](D:/Claude/dwh/docs/adr/ADR-0014-unified-open-source-runtime.md) |
| Layering / SRP | 6 | ArchUnit защищает слои, но ReportService и Typesense читают доменные таблицы напрямую; task service 569 строк и несколько обязанностей [source](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsTaskService.java) |
| DDD и владение данными | 5 | Есть доменные task events, но shared SQL знает несколько domains; export выпал из scope [S-01](D:/Claude/dwh/audit/security-2026-09-05.md) |
| DIP / OCP / testability | 7 | Узкие provider SPI и md security ports облегчают замену адаптеров; Typesense конкретный dependency application services [MdUserService](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdUserService.java:25) |
| Concurrency / consistency | 5 | Atomic claims, file quota locks, i18n revisions; async-before-commit и task lost updates A-02/A-03 |
| Операционная эволюция / resilience | 5 | Миграции отдельно, graceful shutdown и restore tools есть; safety gaps D-01/D-02 и нет доказанного target sizing |

Не найдено оснований объявлять несоблюдение LSP только по использованию Spring/JDBC; polymorphic substitutability глубоко не измерялась. Отсутствие богатой entity-модели само по себе не дефект: transaction-script подход разумен для этой CRUD-системы. Выделять агрегаты следует вокруг подтверждённых инвариантов, не ради количества слоёв.

## A-01. Java DAG не обеспечивает владение SQL и обязательность scope

**P1/P2; M (долгосрочная часть, 3–5 дней при отдельном выполнении); риск изменения medium.**

Наблюдение: [ScopeFilter](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/common/security/ScopeFilter.java) в common содержит task/file SQL; [MsTaskRepository:52](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/repository/MsTaskRepository.java:52) сохраняет overload `findById(id)` с unrestricted scope; [MdScopeService:155](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdScopeService.java:155) возвращает unrestricted для null user. ReportService использует собственный путь чтения. Это не автоматически уязвимость каждого null call, но делает безопасный доступ необязательным для следующего разработчика.

Риск: новая read-side projection проходит ArchUnit и module permission, но не data scope — S-01 уже демонстрирует этот класс ошибки.

Минимум сейчас: исправить export, обозначить в code review/data-access contract запрещённые user-facing unrestricted calls и добавить endpoint matrix. Затем отдельный scoped task read interface для exports/search adapters, без перемещения всего репозитория. Administrative/system bypass должен быть явным типом контекста, а не null.

Миграция: новый обязательный scoped путь → перевести user-facing callers → доказать SQL equivalence/negative tests → ограничить legacy overload visibility. Не менять уже применённые Flyway миграции. Проверка: mapper/query contract и роли SELF/UNITS/SUBTREE/ALL для каждого reader.

## A-02. Поиск: race до commit, неполная recovery и блокирующий startup

**P1; M (3–5 дней), change risk medium.**

Наблюдение: [MsTaskService:147](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsTaskService.java:147) и [MdUserService](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdUserService.java) вызывают `@Async` indexer из транзакционного метода до завершения commit. [TypesenseIndexer:25](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseIndexer.java:25) заново читает PostgreSQL; при пустом результате возвращается, ошибки пишет на DEBUG. Async consumer может прочитать старую/ещё не видимую строку; durable retry не найден в этом пути.

Дополнительно [TypesenseSyncRunner:40](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseSyncRunner.java:40) напрямую вызывает собственный `@Async performInitialSync()`. При используемом proxy mode такой вызов не асинхронен: [официальная документация Spring](https://docs.spring.io/spring-framework/reference/integration/scheduling.html). Метод загружает полные наборы `listOfRows` и последовательно делает upsert; «sync отложен» при unhealthy только логируется, будущая retry-задача в этом методе не назначается. Перезапись существующей коллекции не удаляет документы отсутствующих/удалённых строк автоматически.

Минимум: событие AFTER_COMMIT устраняет чтение до commit, но **само по себе не обеспечивает crash recovery**. Для гарантии догоняния — маленькая PostgreSQL outbox/reconcile job с entity ID/version и retry; Kafka не нужна. Startup только инициирует bounded job; читать keyset batches, отправлять bulk import, публиковать новый индекс через безопасную смену collection/alias после сверки. Первым коммитом можно отделить вызов async и AFTER_COMMIT; последующим — durable delivery и rebuild.

Проверка: задержанный commit/rollback, crash после commit, Typesense outage и recovery; lag/failed count доступны оператору; rebuild не возвращает удалённые записи, память bounded размером batch. Не открывать scoped global search без серверных predicates: текущий admin-only restriction принят [ТЗ FR-SEARCH-03](D:/Claude/dwh/docs/technical-specification.md:121).

## A-03. Тихие потери параллельных изменений задачи

**P1; M (2–4 дня, входит I-08), change risk medium.**

[MsTaskRepository.update:126](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/repository/MsTaskRepository.java:126) обновляет поля по `where id = :id` без expected version; [MsTaskService:274](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsTaskService.java:274) сначала читает existing, затем пишет. Два редактора одного поля дают last-writer-wins без предупреждения. Фактический пользовательский инцидент не наблюдался; поведение следует из SQL.

Минимум: monotonic revision колонка отдельной forward migration, expectedRevision в mutation DTO, compare-and-set UPDATE; 0 affected → 409. UI сохраняет несохранённый текст и предлагает reload/merge, не перезаписывает автоматически. Уже существующий образец: [MdI18nRepository:114](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/md/repository/MdI18nRepository.java:114).

Проверка: два concurrent updates одной revision — один success, один conflict; scope и audit сохраняются. Поэтапно: схема → сервер/контракт → UI → обязательность revision. Для каждого release этапа зафиксировать совместимость предыдущего клиента/server.

## A-04. Idempotency reservation не атомарна с бизнес-операцией

**P1; M (часть I-04 3–6 дней), change risk high.**

[IdempotencyService.claim/complete](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/config/idempotency/IdempotencyService.java:35) — отдельные транзакции вокруг servlet chain; бизнес-коммит живёт внутри service. [Filter:100](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/config/idempotency/IdempotencyFilter.java:100) сохраняет ответ после chain. Crash после business commit до complete оставляет PENDING; после исключения release reservation может разрешить повтор уже совершённой операции. `cleanupOldKeys` объявлен, но вызов scheduled cleanup в main source не найден поиском; автоматической TTL/reconciliation политики для PENDING в claim нет.

Риск: бесконечный 409 на том же ключе либо duplicate business effect после ручного/таймерного снятия reservation. Поэтому простой «просрочить PENDING через 5 минут» небезопасен.

Минимум: сначала узкий allow-list идемпотентных business endpoints (S-04); операция и durable outcome/identity в одной DB-транзакции; уникальный business operation ID. Для crash reconciliation отличать not-committed и committed outcome. Отдельный retention job только для safe completed states; запросы повторно проверяют актуальные полномочия.

Проверка: kill/error в точках before mutation, after commit, before response; повтор не создаёт второй эффект и не зависает навсегда. Контракт replay status/body/headers явно ограничить JSON business API. Не распространять generic cache на auth/файлы/стриминг.

## A-05. Граница горизонтального масштабирования

**Не P0 для принятой single-server модели; P0 перед добавлением второго server; L для полноценного multi-node проекта.**

DB sessions и permissions доступны всем потенциальным replicas. Outbox claims уже используют `FOR UPDATE ... SKIP LOCKED` и claim token: [KwhOutboxRepository](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/kwh/repository/KwhOutboxRepository.java), [V021](D:/Claude/dwh/apps/server/src/main/resources/db/migration/V021__atomic_outbox_claims.sql). Это полезная основа, но не полная multi-node гарантия.

Остаются process-local [MfFileObjectLock:15](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileObjectLock.java:15), [MsSseRegistry](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/ms/notify/sse/MsSseRegistry.java), [RateLimitService](D:/Claude/dwh/apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/RateLimitService.java), local_disk и per-process search sync. Второй server означает независимые locks/buckets и SSE fanout; sticky sessions не синхронизируют события от другого writer. Удаление последнего файла может конкурировать с публикацией той же physical hash на другом процессе.

Рекомендация: сейчас scale-up и распределение независимых установок по hosts; не `compose --scale server=2`. При доказанном потолке одной установки — отдельный RFC с DB/object coordination, shared notification fanout, cluster limiter, scheduler ownership и failure tests. Сначала горизонтально масштабировать количество независимых установок, что не требует микросервисов.

## Горизонт 24 месяцев и эволюция

На имеющихся входных данных нельзя подтвердить ёмкость на два года. Архитектурное соответствие ожидается при сохранении одной организации на установку, но 12–24 месяца накопленных задач/audit/files должны быть представлены в benchmark. Долю тяжёлой аналитики и enterprise integrations никто не подтвердил.

Триггер реорганизации — измеренное насыщение после оптимизации, нужный независимый жизненный цикл/изоляция либо утверждённый HA контракт; не количество пользователей само по себе. Сперва стабилизировать transaction/access boundaries, затем профилировать, только затем оценивать extraction конкретного worker. Сроки, владельцы и бюджет — [дорожная карта](D:/Claude/dwh/audit/cto-audit-2026-09-05.md).
