# Поиск SmartupCMS / Typesense: аудит механики и производительности

> Архивный непроверенный черновик. Сохранён в Git по запросу пользователя
> 2026-09-08; публикация не подтверждает выводы, результаты проверок или
> актуальность находок. Не использовать как требования или release evidence.

Дата: 2026-09-07. Статус: локальное исследование и предложения, не утверждённые требования.
Область: глобальная command palette, `/api/v1/search`, Typesense, обновление индекса,
настройки и восстановление. Остальной CMS не переаудировался.

## 1. Executive summary

Сначала следует исправить достоверность выдачи и обновление индекса, затем оптимизировать
запросы и добавлять управление. Простая кнопка запуска существующего startup sync
не обеспечит полноценную переиндексацию. Замена Typesense или обновление его версии
не обоснованы текущими данными.

Топ-5: потеря индексных обновлений; скрытые ошибки вместо fallback; неполноценный rebuild;
лимит 10 запросов/мин для autocomplete; переход из результата в список вместо записи.

Числовая оценка готовности к target load и соответствие SLA: **не определены**.
Нет согласованного per-installation профиля, SLO, нагрузочных измерений и профилирования.
Малые локальные наборы ниже не доказывают capacity или p95/p99.

### Подтверждённые read-only проверки

Проверялся текущий исходный код, а не старые audit-черновики. Рабочий `4200` и отдельный
QA `14206` читались без изменения данных, конфигурации или индекса. Сравнивались наборы ID,
а не только количества. Значения документов, пароли и ключи в отчёт не включены.

| Установка | Сущность | PostgreSQL: ожидаемые записи | Typesense | Пропущены | Лишние |
|---|---|---:|---:|---:|---:|
| local 4200 | Задачи | 9 | 9 | 0 | 0 |
| local 4200 | Активные проекты | 10 | 10 | 0 | 0 |
| local 4200 | Активные пользователи | 1 | 1 | 0 | 0 |
| QA 14206 | Задачи | 12 | 3 | 9 | 0 |
| QA 14206 | Активные проекты | 19 | 7 | 12 | 0 |
| QA 14206 | Активные пользователи | 1 | 1 | 0 | 0 |

Это снимок на момент проверки; совпадение ID на 4200 не доказывает совпадение всех полей.
Конкретная причина каждого пропуска QA не установлена отдельным детерминированным тестом.
Обнаруженная в коде гонка до commit совместима с наблюдением, но не выдается за доказанную
причину всех 21 пропущенных записей.

- `/debug` QA подтвердил Typesense **27.1**. Обе установки возвращают схемы с пустым
  `locale`, `stem=false`; `enable_phonetic` отсутствует в эффективных полях.
- Startup log QA: `2026-09-07T03:02:42.093Z`, поток `main`, sync завершён с
  Users=1, Projects=0, Tasks=0. Это согласуется с синхронным self-invocation.
- `EXPLAIN (COSTS OFF)` текущего task fallback дал `Limit -> Seq Scan on ms_tasks`.
  На QA отсутствуют trigram indexes на `md_users`, `ms_tasks`, `ms_task_projects`.
  Seq Scan на 12 строках сам по себе не является проблемой производительности.
- `DWH_RATE_LIMIT_EXPENSIVE_PER_MINUTE` в QA = 500, на local 4200 не задан;
  дефолт исходной конфигурации = 10. Это различие test/runtime, не нагрузочный тест.
- Свежий `SearchServiceTest`: **4/4**, без failures/errors/skips. Тесты мокируют
  TypesenseClient и не проверяют реальную синхронизацию, HTTP-ошибки адаптера или rebuild.
- Текущий E2E `ui-release-regressions.spec.ts:33` ищет `admin`, проверяет HTTP success
  и завершение loading, но не наличие новой записи, полноту индекса или открытие результата.

## 2. Hot path map

| Участок | Сейчас | Latency / риск |
|---|---|---|
| Command palette | Минимум 2 символа, debounce 120 ms, отмена старого browser HTTP | 120 ms — задержка ввода, не измеренный end-to-end latency |
| GET /api/v1/search | Permission check, admin-only policy, read-only DB transaction | DB-соединение после проверки роли удерживается во время внешнего поиска |
| Typesense ALL | Три последовательных GET: tasks, projects, users | Три сетевых round trip; read timeout 3000 ms на каждый |
| Сохранение сущности | Прямой вызов `@Async` индексатора внутри бизнес-транзакции | Нет after-commit гарантии, durable retry или порядка событий |
| Startup sync | Полные таблицы в память, отдельный HTTP upsert на запись | O(N) HTTP, память O(N), работа в main |
| Выбор результата | Навигация к списку сущностей | Пользователь повторно ищет запись вручную |

## 3. Findings: десять приоритетных рисков

### P-01. Обновление индекса может опередить commit [High]

- Где: `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsProjectService.java:34`,
  `:45`, `:81`; аналогичные вызовы в `MsTaskService.java:149`, `:305` и
  `md/service/MdUserService.java:98`, `:210`;
  `search/typesense/TypesenseIndexer.java:25`.
- Механика: async-поток читает PostgreSQL отдельно от незавершённой транзакции.
  Для новой строки может получить пусто и завершиться; для update — старое состояние.
  Неуспешный upsert только логируется. Долговечной очереди именно поисковых обновлений нет.
- Impact: пропуски/устаревшая выдача независимо от скорости двигателя. Runtime mismatch
  подтверждён выше; частота именно этой гонки требует теста.
- Предложение: минимально — dispatch после commit; устойчиво — transactional search outbox,
  ограниченный worker, идемпотентные события, retry/backoff и reconciliation.
  After-commit callback один не закрывает crash между commit и отправкой.
- Effort: M / L для outbox; риск Medium. Проверка: задержанный commit, rollback,
  недоступность Typesense, рестарт worker, два update/delete в конкуренции.
  [Spring: transaction-bound events](https://docs.spring.io/spring-framework/reference/7.1/data-access/transaction/event.html).

### P-02. Ошибка Typesense маскируется под «ничего не найдено» [High]

- Где: `search/typesense/TypesenseClient.java:200`, catch в `:227`;
  `search/service/SearchService.java:48`.
- Механика: `searchCollection` глотает исключения и возвращает список; outer search
  возвращает ненулевой список. SearchService принимает даже пустой/частичный результат,
  поэтому обычные HTTP/timeout/parse ошибки не запускают предусмотренный PostgreSQL fallback.
- Impact: ложное отсутствие данных; до трёх последовательных ожиданий при сбое.
- Предложение: различать empty/success/partial/failure; передавать ошибку зависимости наверх,
  ограничивать общий deadline; явно маркировать degraded response. Пустая успешная выдача
  сама по себе не должна автоматически вызывать второй поиск в БД.
- Effort M; риск Medium. Проверка: 503, timeout, 404 collection, malformed JSON,
  отказ одной из трёх коллекций и настоящий нулевой результат.

### P-03. Startup sync — не полноценная перестройка [High]

- Где: `search/typesense/TypesenseSyncRunner.java:54`, `:60`, `:79`, `:97`;
  `TypesenseClient.java:103`, `:121`; `search/controller/SearchController.java:17`.
- Механика: upsert текущих строк не удаляет старые документы. Существующая схема только
  проверяется на наличие, не сравнивается и не мигрирует. При неготовности Typesense
  runner возвращается, хотя лог обещает отложенную синхронизацию; retry не запланирован.
  Нет admin job API, прогресса, сверки или переключения поколения индекса.
- Impact: перезапуск не гарантирует очищенный актуальный индекс, успех логируется по
  прочитанным строкам, не по подтверждённым индексным записям.
- Предложение: отдельная rebuild job, новые versioned collections, проверка каждого
  import result, сверка, обработка изменений во время копирования, переключение и rollback.
  Не очищать рабочие collections в начале операции.
- Effort L; риск Medium/High. Проверка: stale document, изменение схемы, частичный import,
  рестарт job, конкурентное редактирование, отказ переключения.
  [Collection aliases](https://typesense.org/docs/27.1/api/collection-alias.html).

### P-04. Массовая индексация тормозит запуск и не ограничена по памяти [Medium; scale risk]

- Где: `TypesenseSyncRunner.java:34`, `:43`, `:47`, три `listOfRows()` и циклы upsert.
- Механика: `run()` вызывает свой `@Async` метод напрямую; proxy не перехватывает self-call.
  Это подтверждается main-thread log. Таблицы целиком материализуются, HTTP — по одному doc.
- Impact: N строк → N upserts; приблизительная модель времени `2 s + N × средняя стоимость upsert`,
  не измеренный прогноз. Все runtime tables в этом исследовании малы.
- Предложение: настоящий background worker вне startup call, keyset batches по ID,
  bulk JSONL import с ограничением размера пакета и параллелизма. Проверять построчные ошибки:
  HTTP 200 у import не гарантирует успех всех документов. Не увеличивать server-side batch
  без измерений, поскольку это конкурирует с запросами поиска.
- Effort M; риск Medium. Проверка: memory/GC, startup readiness, p95 поиска во время rebuild.
  [Spring async proxy](https://docs.spring.io/spring-framework/reference/integration/scheduling.html),
  [Typesense import](https://typesense.org/docs/27.1/api/documents.html).

### P-05. Budget autocomplete не соответствует частоте ввода [High, configuration risk]

- Где: `apps/server/src/main/resources/application.yml:160`, `:165`;
  `config/security/RateLimitFilter.java:78`; `RateLimitService.java:61`;
  `apps/web/src/app/layout/command-palette/command-palette.component.ts:360`.
- Механика: default expensive bucket = 10/min, поиск включён в него; debounce = 120 ms.
  Серия пауз при наборе может израсходовать bucket. QA override 500 маскирует различие.
  Отмена browser HTTP не возвращает уже потраченный токен и не гарантирует отмену server work.
- Предложение: отдельные per-user search budget/burst; согласовать их с autocomplete,
  соблюдать Retry-After в UI. Не поднимать общий expensive лимит для audit ради поиска.
  При live-настройках учитывать, что текущий bucket создаётся один раз и не обновляет
  Bandwidth при изменении переданного limitPerMinute.
- Effort M; риск Medium (abuse protection). Проверка: нормальный набор, быстрые повторы,
  разные пользователи, независимость от audit, изменение лимита, 429/recovery.

### P-06. Лишние сетевые ожидания и DB connection occupancy [Medium; load verification needed]

- Где: `TypesenseClient.java:37`, `:155`, `:172`, `:183`;
  `SearchService.java:36`; `common/security/RoleMembershipAuthorizer.java:16`.
- Механика: ALL выполняет 3 GET последовательно. Проверка active admin role выполняет
  SQL внутри search transaction, которая охватывает и сетевое ожидание Typesense.
  Hikari maximumPoolSize=20; virtual threads не устраняют конечность DB pool.
- Impact: при трёх read timeouts только ожидание чтения может составлять около 9 s,
  без connect/прочих затрат. Это модель отказа, не измерение и не строгая верхняя граница.
- Предложение: `/multi_search` (3 поиска в одном HTTP), отдельные короткие DB boundaries,
  общий deadline и bulkhead; сохранить текущую проверку роли и запрет scoped пользователей.
  Multi-search не означает автоматически общий ranking или общий total limit.
- Effort M; риск Medium. Проверка: slow dependency + параллельный обычный CRUD,
  Hikari active/pending, API p95/p99, partial response semantics.
  [Typesense 27.1 multi-search](https://typesense.org/docs/27.1/api/federated-multi-search.html).

### P-07. Результат не приводит к нужной записи [Medium]

- Где: `command-palette.component.ts:434`; `apps/web/src/app/app.routes.ts:22`;
  targetUrl формируется в `TypesenseClient.java:166`, `:177`, `:188`.
- Механика: UI игнорирует id/targetUrl и открывает список. Переданные сервером detail URLs
  также не имеют соответствующих маршрутов; просто начать использовать targetUrl недостаточно.
- Предложение: согласованный typed entity/id navigation contract, открытие существующего
  просмотра записи по ID, понятный 404/нет доступа при удалении или смене прав.
  Не следовать произвольному URL из индекса и не обходить server authorization.
- Effort M; риск Medium. Проверка: каждая сущность, deep link/reload, back, stale hit, 404.

### P-08. Выдача теряет контекст, ranking и предсказуемый лимит [Medium]

- Где: `TypesenseClient.java:145`, `:200`; `SearchService.java:45`, `:158`.
- Механика: default limit=10 применяется к каждой коллекции (до 30 для ALL), результаты
  склеиваются TASK→PROJECT→USER, а fallback имеет другой порядок. `totalHits` — количество
  возвращённых элементов, не Typesense found. Match scores/highlights/search_time_ms игнорируются.
  Задача, найденная по описанию, показывает статус и приоритет, не найденный фрагмент.
  ID не включён в query_by как отдельное точное поисковое поле.
- Предложение: явно сгруппировать выдачу и ограничить общий размер; определить контракт
  found/returned/hasMore; выводить безопасные snippets и причину совпадения; добавить точный
  поиск идентификатора. Не сравнивать сырые scores разных коллекций без принятой стратегии.
- Effort M; риск Medium. Проверка: одноимённые сущности, много совпадений, exact ID,
  совпадение только в описании и безопасное отображение markup.

### P-09. Правила индексных проекций и языка не оформлены [Medium]

- Где: `TypesenseIndexer.java:29`, `:71`, `:105`; `TypesenseSyncRunner.java:64`, `:83`;
  `MsProjectService.java:81`; `TypesenseClient.java:72`, `:83`, `:91`, `:209`.
- Механика: startup выбирает только active users/projects, incremental читает независимо
  от state, search не задаёт state filter. Переименование проекта индексирует лишь проект,
  хотя task documents содержат project_name. Default schema: locale пуст, stem=false.
  `num_typos=2`, prefix=true зашиты; title/name уже имеют приоритет по порядку query_by —
  неверно утверждать, что ранжирования вообще нет.
- Фонетика/Soundex из комментариев не подтверждены: `enable_phonetic` отсутствует в
  эффективной схеме и документации Collections 27.1; полагаться на него нельзя.
- Предложение: общий projection builder, единая явно выбранная политика active/archive,
  fan-out/reconciliation зависимых документов. Отдельно проверить русский/узбекский/латиницу
  на наборе эталонных запросов; locale/stemming не включать глобально вслепую для смешанных текстов.
- Effort M; риск Medium. Проверка: rename parent/status, deactivate/reactivate, RU словоформы,
  узбекские апострофы, смешанный алфавит, коды/телефоны без нежелательных typo matches.
  [Schema / locale / stemming](https://typesense.org/docs/27.1/api/collections.html).

### P-10. Fallback требует отдельного плана масштабирования [Medium; not measured bottleneck]

- Где: `SearchService.java:78`, `:104`, `:127`;
  `apps/server/src/main/resources/db/migration/V001__init_schema.sql:11`.
- Механика: `%query% ILIKE`, несколько OR, отсутствуют trigram indexes. Наличие расширения
  pg_trgm не означает наличие нужных индексов. План и их отсутствие проверены на QA.
  `%` и `_` пользователя также сохраняют wildcard-семантику; это не SQL injection.
- Предложение: сначала согласовать fallback matching с UI и ограничить его стоимость;
  затем измерить GIN gin_trgm_ops для фактически используемых полей. Каждый индекс
  увеличивает disk/write/WAL costs. Для 2-символьных запросов универсального выигрыша нет.
- Effort M; риск Medium. Проверка: representative corpus, EXPLAIN (ANALYZE, BUFFERS)
  до/после, короткие запросы, p95 CRUD при insert/update; rollback нового индекса.
  SQL-миграция не создана и не применена без подтверждённой необходимости.
  [PostgreSQL 18 pg_trgm](https://www.postgresql.org/docs/18/pgtrgm.html).

## 4. Coverage по 11 направлениям audit-performance

| Направление | Вывод |
|---|---|
| Hot paths | Карта выше; фактический end-to-end baseline не измерен |
| Database | P-06/P-10; SQL parameterized, full startup reads; N+1 HTTP, не ORM |
| Cache | Search result cache отсутствует; добавлять его до consistency fix не рекомендуется |
| Pools | DB=20; search держит транзакцию; HTTP explicit pool/bulkhead не настроен в этом адаптере |
| Async/concurrency | P-01/P-04/P-09; отдельной долговечной search queue нет |
| Memory | Startup O(N); измерений heap/Typesense RSS под нагрузкой нет |
| Frontend | Debounce/cancellation уже есть; P-05/P-07/P-08 остаются |
| Reliability | См. scorecard ниже |
| Observability | Health есть, индексная полнота/лаг/jobs не измеряются |
| Failure modes | Silent empty, skipped startup, dropped writes, partial index |
| Capacity | 2x/5x/10x абсолютный прогноз невозможен без профиля и baseline |

## 5. Reliability scorecard

| Механизм | Покрытие |
|---|---|
| Явные HTTP timeouts | Да: connect=1500 ms, read=3000 ms; нет общего deadline |
| Fallback | Есть в service, но штатные ошибки адаптера его обходят |
| Retry / backoff / dead letter | Для search indexing нет; другие outbox не заменяют его |
| Circuit breaker / bulkhead | В search adapter отсутствуют |
| Health | Typesense health + SystemInfo; не означает готовность/актуальность collections |
| Rebuild / resume / rollback | Startup upsert only; job/поколение/переключение отсутствуют |
| Authorization | Server API + platform.search.view + unrestricted admin; сохранить fail-closed |

## 6. Предлагаемый раздел «Настройки → Поиск и индекс»

Не универсальный редактор произвольных Typesense parameters, а небольшая validated
instance-wide policy. Generic effective settings с пользовательскими overrides не должны
управлять защитными лимитами или переиндексацией. Каждый элемент должен иметь реального
server consumer, а не только сохранять строку в md_settings.

| Блок | Предложение | Требуется rebuild? |
|---|---|---|
| Состояние | Версия, health, ready/degraded, count по сущностям, последняя сверка/успех, lag/errors | Нет |
| Качество | Поля из whitelist, их веса, typo/prefix policy, общий лимит, тестовый запрос | Обычно нет |
| Язык/схема | Locale/stemming и нормализованные поля по принятой модели контента | Да, явно показывать pending schema |
| Обслуживание | Проверить индекс, полная rebuild job, прогресс, итог, история, retry/rollback | Само действие rebuild |
| Защита | Отдельный search rate/burst, request budget в безопасных пределах | Нет; отдельный admin/operator доступ |

API key, URL зависимости и низкоуровневые memory/worker параметры оставлять операторской
конфигурацией; не отдавать ключ browser. Обычный пользователь может выбирать сущность в
поиске, но не повышать бюджет и не менять authorization scope.

Рекомендуемый lifecycle rebuild: create job → build new generation из PostgreSQL пакетами →
догнать изменения после начала копирования → сверить документы/ошибки → переключить
активную generation → сохранить прежнюю для ограниченного rollback.
Три aliases не являются одной атомарной транзакцией: для all-entity switch нужен общий
generation pointer/координация. Первый переход с физических `tasks/projects/users` требует
продуманного rollout; нельзя создавать alias с уже занятой коллекцией именем и считать
миграцию решённой. Rebuild не должен автоматически удалять старые данные без retention rule.

## 7. Capacity plan и observability gaps

- Снять API latency p50/p95/p99, engine search_time_ms, error/fallback/partial/429 rates,
  Hikari active/pending, worker queue length/oldest age/retries, index missing/extra,
  import throughput/errors, heap/GC, Typesense RSS/disk, cold/warm и rebuild overlap.
- Не логировать полный запрос, emails, snippets или document body по умолчанию;
  сохранять агрегаты, correlation id и безопасные error codes. Не создавать high-cardinality
  metric labels из user ID или текста запроса.
- Для 2x/5x/10x повторить один согласованный per-installation workload. Текущая схема
  даёт линейное число startup upserts и неограниченный backlog потерь при outage;
  нельзя честно назвать предел по RPS/числу пользователей из этих локальных проверок.
- Search caching пока выключен: сначала устранить пропуски. Возможный будущий key обязан
  включать generation, settings version, normalized query, entity/limit и authorization context;
  TTL не должен скрывать revoke/delete. Отдельный Redis/шардинг сейчас не обоснован.

## 8. Порядок работ / quick wins

1. Добавить regression tests реальной индексации и адаптерных ошибок (P-01/P-02).
2. Устранить silent empty, выделить search budget и корректно обработать 429.
3. Согласовать exact entity navigation и показывать фрагмент совпадения.
4. Перейти на multi-search с явным общим лимитом и обработкой partial failures.
5. Убрать сетевые ожидания из длинной DB transaction.
6. Выделить единый projection builder и after-commit dispatch как минимальный safety fix.
7. Сделать index status/diagnostics до кнопки rebuild.
8. Реализовать durable indexing и безопасную rebuild job как отдельный архитектурный пакет.
9. Добавить limited relevance settings и эталонную выборку запросов.
10. Измерить fallback indexes/capacity; принимать tuning только по результатам.

Это порядок предложений, не утверждённый implementation plan. Пункты 6–8 взаимосвязаны:
временный after-commit fix нельзя объявлять полноценной гарантией доставки.

## 9. Варианты и границы следующего решения

1. **Рекомендуется: поэтапный repair + управляемый индекс.** Сначала correctness/query UX,
   затем durable sync/rebuild и UI управления; сохраняются три collections и admin-only policy.
2. Только «ручки» настройки и restart sync: меньше объём работ, но не устраняет пропуски,
   stale documents и скрытые ошибки; как законченное решение не рекомендуется.
3. Семантические ответы / поиск внутри документов: отдельная продуктовая задача,
   extraction/OCR/RAG, источники ответа, модель доступа и provider policy. Не считать это
   переключателем Typesense; ADR-0005 не утверждает реализацию LLM в текущем этапе.

Ожидается уточнение пользователя о смысле «ответов»: записи CMS, содержимое файлов или
ответы на вопросы. Новые settings/rebuild interfaces требуют согласования дизайна по
brainstorming. В этом исследовании не создавались production patches, SQL migrations,
новые endpoints или runtime settings; не выполнялись reindex/restart/deploy/push.
Graphify query использован для навигации; code не изменён, поэтому update не запускался.

Детальные критерии следующей проверки: [P-SEARCH-validation.md](fixes/P-SEARCH-validation.md).
