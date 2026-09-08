# Search verification proposal — 2026-09-07

> Архивный непроверенный черновик. Сохранён в Git по запросу пользователя
> 2026-09-08; публикация не подтверждает выводы, результаты проверок или
> актуальность находок. Не использовать как требования или release evidence.

Статус: сценарии для согласования, не выполненный нагрузочный тест и не release evidence.
Связанный аудит: [performance-2026-09-07.md](../performance-2026-09-07.md).

## Correctness / integration

1. Реальные PostgreSQL и Typesense 27.1 на disposable data. Создать сущность, удержать
   транзакцию до разрешения async чтения, подтвердить поиск после commit; rollback ничего
   не публикует. Повторить update/deactivate/delete и concurrent updates.
2. Отключить только тестовую зависимость: writes CMS завершаются согласно контракту,
   outbox сохраняется; после восстановления все события доставлены без дублей/stale resurrection.
3. Вставить stale индексный документ только в disposable collection; rebuild устраняет его,
   а реальное изменение во время копирования не теряется. Проверить рестарт job и частичный import.
4. 503/timeout/404/malformed JSON/одна сломанная collection дают явный degraded/error,
   а не ложное «ничего не найдено». Empty success не запускает fallback автоматически.
5. Unrestricted admin разрешён; неадминистратор даже с platform.search.view отклоняется.
   Settings/rebuild запрещены без отдельного полномочия. Scope-policy не расширять этим пакетом.
6. Совпадение найденного ID, opening record, reload/back, удалённый/недоступный hit.

## Relevance set

Использовать искусственные данные без PII. Минимально: точное имя/ID/login, префикс,
одна/две опечатки, совпадение лишь в длинном описании, одинаковые названия трёх сущностей,
русские словоформы, узбекские апострофы, латиница/кириллица, телефон, спецсимволы,
много результатов, отсутствие совпадений. Для каждого запроса задать ожидаемый top-K
и запрещённые результаты до настройки весов/locale. Измерять recall@K/MRR отдельно от latency.

## Proposed load profiles

Только на изолированном стенде, после утверждения dataset size, RPS/concurrency, длительности,
SLO и stop thresholds. Не использовать данные всех установок как нагрузку одной установки.

- Healthy search: запросы с паузами печати 150–300 ms, оба алфавита, запросы длиной 2–40
  символов; отдельные профили cold/warm. Числа — входы сценария, не обещание допустимой нагрузки.
- Coexistence: поиск + типичный CRUD; отдельно Typesense delay/timeout и fallback.
- Rebuild overlap: поиск/CRUD во время batch import; сравнить с baseline, проверить delta replay.
- Rate limiting: normal typing, короткий burst, sustained abuse, 429/Retry-After/recovery,
  независимость search от audit, live-change bandwidth существующего bucket.
- Capacity: 1x, 2x, 5x, 10x согласованного baseline. Stop на превышении утверждённых
  p99/error/pool/memory/disk thresholds; никакого бесконтрольного теста на 4200.

## SQL candidate evaluation, не миграция

Для фактического task fallback изучить отдельные GIN gin_trgm_ops индексы title и
description_markdown; для active users/projects — соответствующие partial indexes только
на действительно запрашиваемых полях. Не создавать все индексы «на всякий случай».
До/после: EXPLAIN (ANALYZE, BUFFERS), insert/update latency, WAL, размер индексов,
длинные тексты и 2-символьные запросы. Planner может обоснованно оставить Seq Scan на
малой таблице. Rollback — удаление только новых именованных индексов; способ online DDL
и Flyway transaction policy должны быть согласованы до написания миграции.

## Acceptance / artifact safety

Сохранять test command, SHA/dirty diff, image digest, dataset seed/counts, timestamps,
metrics summary и failures. Не сохранять keys/cookies/passwords, реальные запросы,
личные данные или полный export документов. Не выдавать зелёные mocked tests за
подтверждение актуальности live индекса. Старый индекс удалять только после верифицированного
cutover, утверждённого retention и возможности rollback.
