# Надёжный поиск и управление индексом SmartupCMS

Дата: 2026-09-07.
Статус: направление обоих этапов одобрено пользователем; детальный дизайн ниже ожидает проверки.
Основание: запрос изучить поиск/Typesense и последующее «Давай все подрят».
Это проект реализации, не изменение канонического ТЗ до его согласования.

## 1. Результат и границы

Пользователь находит существующую запись CMS, понимает причину совпадения и открывает
именно её. Изменения PostgreSQL надёжно доходят до производного индекса. Администратор
видит состояние поиска, управляет качеством выдачи и может проверить/перестроить индекс
без предварительного удаления рабочего поколения.

Реализуются последовательно два связанных пакета:

1. Корректность, надёжная доставка индексных обновлений, API и поведение поиска.
2. Управляемые поколения индекса, фоновые операции, настройки и административный UI.

Не входят: поиск внутри файлов/OCR, LLM/RAG-ответы, расширение глобального поиска на
обычные роли, смена движка или версии Typesense, Redis/Kafka, multi-host HA,
production deployment и переиндексация пользовательского 4200 без отдельного указания.
По умолчанию «ответы» трактуются как найденные записи CMS, а не сгенерированные ответы.

### Инварианты

- PostgreSQL — источник истины; Typesense не авторизует пользователя.
- Сохраняются `platform.search.view` и текущая дополнительная unrestricted-admin проверка.
- Управление поиском также требует unrestricted admin; одной делегированной настройки
  `platform.settings.update` недостаточно для доступа к индексным данным.
- Status/preview требуют `platform.search.view`; чтение configuration дополнительно
  требует `platform.settings.view`. Save/rebuild/retry/cancel/rollback требуют
  `platform.settings.update` и той же unrestricted-admin проверки. Новые системные
  роли и делегирование индексного доступа этим пакетом не вводятся.
- В запросах, ошибках, метриках и журнале не раскрываются API keys, cookies, passwords
  или полные документы. Browser работает только через server API.
- Применённые Flyway migrations неизменяемы. Добавляется следующая свободная версия,
  ожидаемо V026; перед реализацией номер сверяется с checkout.
- Старые несвязанные изменения и локальные audit-черновики не включаются в этот пакет.

## 2. Архитектура и выбранный подход

Остаются Java 25 / Spring Boot, PostgreSQL 18, Typesense 27.1 и Angular.
Три категории поиска сохраняются: TASK, PROJECT, USER. Меняются границы доставки
индексных обновлений, не бизнес-источник данных.

Прямой best-effort `@Async` заменяется долговечной объединяемой очередью в PostgreSQL:
для каждой пары `(entity_type, entity_id)` хранится версия требуемой поисковой проекции.
Бизнес-транзакция меняет запись и увеличивает эту версию атомарно. Worker читает только
закоммиченные версии и для каждого поколения отмечает подтверждённую доставку.
Несколько изменений одной записи объединяются в необходимость доставить последнее
состояние; полная история пользовательских действий остаётся в существующем audit.

Такой outbox не хранит копии имён, email, описаний и удалённого содержимого. Повторная
обработка читает актуальные данные из PostgreSQL. Отсутствующая или исключённая политикой
запись означает идемпотентное удаление соответствующего поискового документа.

Преимущества перед только after-commit callback: очередь переживает рестарт и outage;
нет окна «commit прошёл, процесс умер до отправки». По сравнению с журналом всех событий
не нужны бесконечное хранение payload и небезопасный watermark по sequence ID:
порядок выдачи sequence не равен порядку commit.

## 3. Данные и доставка

Модуль `search` получает отдельные responsibilities:

| Компонент | Ответственность |
|---|---|
| SearchAccessPolicy | Единая текущая admin-проверка для поиска и управления |
| SearchProjectionReader | Авторитетная проекция и её версия одним согласованным read |
| SearchChangePublisher | Атомарное увеличение версии в бизнес-транзакции |
| SearchDeliveryRepository / Worker | Подтверждения по поколениям, retries, ограниченная доставка |
| TypesenseClient | Строгий HTTP-контракт, multi-search, JSONL import, чтение schema/doc metadata |
| SearchGenerationService | Жизненный цикл поколений и единый active pointer |
| SearchJobService / Worker | Проверка, rebuild, rollback, cancel, восстановление после рестарта |
| SearchSettingsService | Typed instance policy, validation, optimistic version и audit |

Названия — границы ответственности; точный список файлов закрепляется implementation plan.
Межмодульные вызовы используют публичный `SearchChangePublisher`, не Typesense internals.

### Хранимое состояние

- `search_projection_versions`: entity/type, положительная revision, changed_at.
  Строка сохраняется и для удаления, пока есть поколения, которым оно требуется.
- `search_generations`: generated ID, physical collection names, schema/profile version,
  lifecycle, created_at. Имена формирует только сервер из безопасного префикса и ID.
- `search_generation_delivery`: generation/entity, delivered revision, attempt count,
  next attempt, безопасный error code. Успех старой версии не подтверждает новую.
- `search_index_state`: один active generation pointer с optimistic version.
- `search_jobs`: action, generation, state, progress counters, actor, timestamps,
  error code и ownership/recovery marker. Не хранит document bodies.
- `search_settings`: одна typed instance configuration с version; пользовательские
  generic settings и их overrides не участвуют в её вычислении.

Изменения задач, проектов и пользователей увеличивают revision в той же транзакции.
Переименование проекта или статуса помечает затронутые task projections в той же
транзакции; worker использует единый builder для incremental и rebuild.
Задачи индексируются согласно текущему составу; пользователи и проекты — только `state=A`,
как в существующем startup/fallback. Архивный режим этим пакетом не добавляется.

Доставка выполняется выделенным ограниченным scheduler, отдельно от notification jobs
и request threads. В поддерживаемой одноэкземплярной топологии один writer управляет
поколением; пересекающиеся scheduled вызовы запрещены. Ownership и revision checks
не позволяют устаревшему callback подтвердить чужую работу. Для multi-node/зависших
внешних writes strong fencing не заявляется: это отдельная HA-задача.

Retry: экспоненциальная задержка с jitter и верхней границей, после исчерпания попыток
состояние FAILED остаётся видимым и доступным для retry, не удаляется. Новая revision
снимает ошибку старой версии и снова допускается к доставке.
Проверка тестами должна доказать отсутствие stale resurrection при update/delete/retry.

## 4. API и поведение поиска

### Запрос

Сохраняется `GET /api/v1/search?q=...&entity=...&limit=...`.
Query обрезается по краям, длина 2–200 символов; entity — ALL/TASK/PROJECT/USER.
Неизвестная entity и превышенная длина дают структурную 400.
Limit ограничивается утверждённой instance policy и остаётся глобальным для ALL.
Форма `#123` означает точный ID и может обрабатываться авторизованным SQL lookup;
одна цифра не отменяет контракт минимума двух символов.

Для обычного ALL выполняется один Typesense `/multi_search`, а не три последовательных GET.
Active generation/config snapshot читаются один раз перед сетевым вызовом.
DB transaction заканчивается до ожидания Typesense; доступ проверяется на каждом запросе.

### Выдача

- Сохраняются поля `query`, `hits`, `totalHits`; для совместимости `totalHits` остаётся
  числом возвращённых элементов. Добавляются `foundHits` (nullable), `hasMore`,
  `source` (`TYPESENSE`/`POSTGRES`) и `degraded`.
- Hits сохраняют entity/id/title/description/targetUrl. Description становится безопасным
  текстовым фрагментом совпадения, когда такой фрагмент доступен.
- Группы отображаются TASK→PROJECT→USER. Внутри группы действует relevance двигателя;
  общий бюджет распределяется по одному результату на непустую группу по кругу,
  затем результаты группируются для отображения. Не сравниваются несопоставимые
  raw scores разных collections.
- Успешный нулевой ответ остаётся нулевым, не вызывает fallback.
- Ошибка/неполный/некорректный ответ зависимости не выдается за empty success.
  При сбое любой требуемой collection выполняется единый PostgreSQL fallback для
  запрошенных категорий, без смешивания непомеченных частичных результатов.
- Fallback имеет ограниченный query budget, parameterized literal matching для `%`/`_`,
  тот же состав сущностей и глобальный limit. Если он также неуспешен — явная ошибка UI.
  GIN indexes добавляются только по сравнительному EXPLAIN/load evidence, не автоматически.

Сниппеты отображаются как text/структурированные безопасные spans, не доверенный HTML
Typesense. Сырые URL из индекса не используются как произвольный redirect.

### Навигация и rate limiting

Выбор hit открывает существующий просмотр конкретной записи по typed entity/id,
с поддержкой reload/back. Недоступная/удалённая запись даёт понятное состояние 404,
но не обход проверки доступа и не молчаливое открытие общего списка.
Новые canonical URL закрепляются в маршрутах и серверном mapper одновременно.

Search получает независимый per-user/token-owner bucket, не общий expensive bucket audit.
Предлагаемый стартовый профиль: 120 запросов/мин, burst 20. Его per-minute значение
не превышает существующую configured user/token границу для данного типа клиента;
лимиты прочих API не повышаются. Изменение настройки корректно меняет уже существующий bucket
без бесконечного бесплатного burst. UI соблюдает Retry-After и не запускает auto-retry storm.
Debounce 120 ms и отмена старых результатов сохраняются.

## 5. Поколения, rebuild и rollback

Операции длительной работы возвращают 202 + job ID. На установке одновременно допускается
одна rebuild/rollback job; повтор того же запроса идемпотентен, конфликтующий получает 409.
Проверка статуса не запускает rebuild и не изменяет индекс.

Rebuild:

1. Создать BUILDING generation с новыми physical names, не удалять active collections.
2. Пакетно обнаружить все авторитетные entity IDs и наполнить очередь необходимых проекций.
3. Доставить проекции пакетным JSONL import с ограничениями по числу и размеру документов.
   Проверять каждый import result: HTTP 200 не равен полному успеху.
4. Догнать изменения revision, произошедшие во время копирования; отсутствие очереди
   не выводить из максимального sequence ID или created_at события.
5. Проверить ID sets, schema и revision/content fingerprints. На изменяющихся данных
   сверка учитывает pending revisions и не обещает синхронный snapshot всей БД.
6. В короткой транзакции повторно проверить ownership, состояние job и пригодность
   поколения, затем атомарно заменить один active pointer.
7. Отметить старое поколение RETAINED, job SUCCEEDED. Новые изменения продолжают
   доставляться в active generation; любые оставшиеся pending revisions не теряются.

Search разрешает physical names через серверный active pointer. Это заменяет необходимость
делать три Typesense alias updates «атомарными»: общей транзакции для них нет.
Запрос, уже начавшийся на старом поколении, может завершиться на нём; старые collections
не удаляются во время переключения. Legacy physical names tasks/projects/users остаются
доступными до завершения первой контролируемой перестройки; конфликт имён alias исключён.

При первом запуске без collections создаётся первоначальная background build job;
API показывает INITIALIZING/degraded, не готовый пустой индекс. При наличии legacy
индекса он используется до подтверждённого cutover; нужда в обновлении схемы видима.
Все пропуски metadata после upgrade обнаруживаются перед объявлением index READY.

Job states: QUEUED → RUNNING → VERIFYING → ACTIVATING → SUCCEEDED;
ошибки → FAILED, разрешённая отмена до ACTIVATING → CANCELLED.
После рестарта job не теряет progress и не начинает независимую вторую операцию.
Смена настроек schema во время build не меняет её snapshot: новая конфигурация
отмечается как требующая следующего rebuild.

Rollback — не слепая смена pointer: выбранное retained поколение сначала догоняется
до текущих revisions и проверяется, затем переключается тем же протоколом.
Rollback не возвращает старые удалённые/закрытые для поиска документы.

Автоматическое удаление старых collections в первом выпуске отключено. UI показывает
сохранённые поколения и занимаемое место; перед накоплением новых копий выполняется
проверка доступного места. Очистка — отдельное подтверждённое действие оператора,
не скрытый побочный эффект кнопки rebuild. Нехватка места не затрагивает active index.
Операторский предохранитель по умолчанию допускает не более четырёх зарегистрированных
поколений одновременно, включая active/building/retained/failed; исчерпание — отказ новой
build с понятной причиной, не автоматическое удаление. Rollback использует существующее
поколение. Имена legacy collections никогда не входят в автоматическую очистку.

## 6. «Настройки → Поиск и индекс»

Отдельный компонент внутри существующих Settings, без переписывания остальных вкладок.
Доступность read/write/actions определяется серверными permissions и admin policy.

| Блок | Поля и действия |
|---|---|
| Состояние | Версия двигателя, ACTIVE generation/schema, health/ready/degraded, count по entity, pending/failed/lag, последняя успешная проверка |
| Качество | Общий limit, whitelist полей и веса, num_typos, prefix; тестовый запрос с preview |
| Языковой профиль | Mixed/default без stemming; optional RU profile. Изменение показывает «нужна переиндексация», не меняет active schema немедленно |
| Защита | Search per-minute/burst в безопасных пределах; read-only эффективные request budgets |
| Обслуживание | Проверить, Перестроить, Отменить, Повторить, Откатить; прогресс, итог и история |

Стартовые значения: global limit 10, maximum configurable limit 50; query fields
сохраняют текущую содержательную область. Weights в диапазоне 0–127, хотя бы один
ненулевой searchable field на entity. Typo tolerance 0–2; для идентификаторов/login/email/phone
по умолчанию 0, для title/name — 2. Prefix применяется только к разрешённым текстовым полям.
Предлагаемые веса: TASK title=10, description_markdown=3, status_name=2, project_name=2;
PROJECT name=10, description=3; USER name=10, login=8, email=6, phone=6.
Search rate ограничивается 30–600/min, burst 10–60 и не больше per-minute budget.
Значение global limit — целое 1–50. Все границы валидирует server, не только форма.
Mixed language profile сохраняет current tokenization до отдельного принятого выбора RU.
Для RU/узбекских апострофов/латиницы acceptance содержит эталонные запросы; фонетика
через неподтверждённый `enable_phonetic` не обещается и не используется.

Search API key и URL зависимости не показываются и не изменяются браузером.
Raw Typesense parameters, arbitrary field names, arbitrary filters и collection names
не принимаются через settings API. Сохранение имеет optimistic version: конфликт — 409,
UI сохраняет введённые значения и предлагает обновить состояние.

Save/rebuild/rollback/cancel защищены от повторной отправки. Ошибка сохраняет форму,
показывает одну локализованную причину и доступный retry. Pending actions имеют
aria-busy, keyboard focus, touch targets и блокировку конфликтующих действий.
Polling job привязан к lifecycle вкладки; уход со страницы не отменяет серверную job.
Light/dark и ширины 320/390/1366 проверяются по существующей UI foundation.

## 7. Наблюдаемость и аудит

Метрики с конечным набором labels: query duration/errors/fallback/429,
engine time, delivery pending/oldest age/retries, import success/failure,
job duration/state и generation switch. Текст запросов и user ID не становятся labels.
Статус health двигателя не выдается за доказательство актуальности данных.

Изменение settings, запуск/отмена/retry/rollback/switch пишутся в существующий audit:
actor, действие, config version/job/generation IDs и безопасные summaries.
Сырые downstream error bodies и snippets в журнал не копируются.

## 8. Проверка и порядок реализации

Для каждого поведения сначала regression RED, затем минимальная реализация и GREEN.
Проверяются реальные HTTP границы TypesenseClient, PostgreSQL transactions и worker,
а не только mock, возвращающий заранее подготовленные SearchHit.

Пакет 1:

1. Expand-only migration хранимого состояния поиска и upgrade/repeat/readiness tests.
2. Ошибки адаптера / empty success / fallback / validation / multi-search/global budget.
3. Atomic durable revisions, commit/rollback, update/delete concurrency, retry/restart.
4. Независимый search rate budget и 429/Retry-After.
5. Поиск по категориям/ID, snippets и opening record с browser regressions.

Пакет 2:

1. Typed settings consumers и server-enforced permissions поверх проверенной схемы.
2. Поколения/job lifecycle, batch import, reconciliation, cutover/rollback/cancel/recovery.
3. Settings UI, реальные значения, pending/error/version conflict и обе темы.
4. Изолированная проверка целого сценария и измерения до/после.

Обязательные acceptance cases: нет индексации незакоммиченной записи; нет потерянной
revision после рестарта; сбой одной collection не превращается в empty success;
rebuild удаляет stale hit из нового поколения; изменения во время build догоняются;
rollback не воскрешает удалённое; fallback/preview/jobs не расширяют admin-only scope;
переход приводит к выбранному ID; все сохраняемые настройки имеют проверенный consumer.

Полные Maven/Angular/typecheck/build/i18n/E2E и architecture/docs gates выполняются
после узких проверок. Graphify update — после code changes, outputs из dirty checkout
не публикуются. Внешние providers не включаются. Стенд и данные для воспроизведения
изолированы от 4200; только synthetic fixtures и отдельные volumes/images.

Absolute SLO/RPS не изобретаются. Для локального сравнения сохраняются dataset seed/counts,
query mix, warm/cold, concurrency, latencies, errors и rebuild overlap; результаты
не объявляются production capacity без согласованных нагрузки и thresholds.

## 9. Контроль перед началом кода

Пользователь проверяет этот документ, особенно admin-only границу, общий limit,
mixed/RU profile, отсутствие auto-cleanup и безопасный rollback.
После подтверждения создаются два последовательных implementation plan с точными
файлами, контрактами и командами тестов; реализация идёт в этой задаче.
Нет необходимости заново согласовывать уже описанные исправления по одному.

Источники: [каноническое ТЗ](../../technical-specification.md),
[ADR-0013](../../adr/ADR-0013-data-scope.md),
[ADR-0014](../../adr/ADR-0014-unified-open-source-runtime.md),
[правила миграций](../../guidelines/database-migrations.md).
Наблюдения аудита проверены по исходникам и read-only runtime; audit-черновики не
перенесены в документ как самостоятельный источник требований.
