# Студия: staging-приёмка RC bc559ddd

**Дата:** 2026-10-16. **RC:** `bc559ddd7f089e0505c4ed590e349df39b7c66de`.
**Статус:** план; все целевые cases ниже **NOT RUN**. Локальный CI этого SHA
закрыт по сохранённому протоколу: 108/108 checks, включая 72 live E2E.
Он не заменяет проверки целевой инфраструктуры, отдельных процессов и recovery.

Сценарии фиксируют функциональность bc559ddd. После добавления signed
prerelease pipeline (ADR-0040) целевой прогон выполняется на новом одобренном
DCO SHA с собственным полным CI; прежний SHA не подменяется новым evidence.

Этот документ детализирует [staging-план](staging-acceptance.md),
[DoR/DoD/ACC Студии](../architecture/admin-studio-plan.md),
[контракт деклараций](../architecture/admin-studio-contract.md) и ADR-0035…0039.
Это не новый runtime/дизайн: модульный монолит, EntityRuntime/QueryList,
серверная авторизация и CLI/Flyway сохраняются. Никакие команды ниже сейчас
не исполняются против staging; публикация и merge не разрешены.

## 1. DoR целевого прогона

| Вход | Критерий перед стартом |
|---|---|
| Identity | Чистый checkout RC, шесть подписанных digest и verifier PASS по staging-плану §0; конфигурация/UTC/evidence привязаны к ним |
| Модули | `studio` и `tasks` включены для сценариев; `example` временно включён только для прототипа Products. Записаны исходные и ожидаемые итоговые состояния |
| Флаги | Начать с `SMC_STUDIO_ACTIVATION_ENABLED=false`, затем согласованно включить для live cases с restart. `SMC_STUDIO_ASSISTANT_ENABLED=false`, provider `none`, endpoint/model/key пусты весь прогон |
| Роли | Отдельные admin/setup, автор (`md.settings.update` + entity.view), publisher (`studio.forms.publish`), author+publisher, читатель, читатель другой оргветви, без прав. Для assistant-off — пользователь с обоими assist+settings правами и варианты без одного из них |
| Данные | Только synthetic. Для `ms.tasks.projects`: две доступные задачи одного проекта + задача без проекта; отдельные записи другой оргветви/владельца; архивный и недоступный проект; даты около UTC midnight и границ периода |
| Аналитика | Независимые ожидаемые JSON/XLSX totals/count/NULL до запуска; числовые zero/negative, разрывы временных периодов, неполные/неподдержанные серии там, где production metadata это позволяет |
| Studio workload | **Предложение, требует согласования IN-08:** 2 live формы × 1000 записей для функционала/восстановления; 100 форм × 10 полей для каталога на disposable clone; 20 сохранённых отчётов и 5 панелей по 4 карточки. Не выдавать это за утверждённую production-ёмкость |
| Capacity | Привязать Studio к уже утверждённым 100 активным пользователям и API SLO; отдельно согласовать mix list/detail/search/report/export/save и целевые объёмы. Текущий общий k6 runner этот mix не реализует |
| Изоляция | Уникальные коды форм и запас квоты; отдельный clone/restore project для лимита 100 и двух процессов. Сброс только по согласованной recovery-процедуре; рабочие definitions не удалять SQL |
| Процессы | План временного второго server процесса на изолированном recovery-контуре; оба процесса с одним CMS/DWH и одинаковыми флагами/образами, private ports. Не помещать второй server с лимитом 10 GiB в бюджет основного app-хоста без проверки ресурсов; подготовка двух процессов — отдельная операторская работа, готового runner нет |
| Операторы | QA/Security/DBA/Ops назначены; окно mutations/restart/revoke и restore согласовано; backup/escrow и сбор метрик проверены |

Один app-хост остаётся утверждённой staging-топологией. Проверка двух процессов
в изолированном контуре нужна для ACC-17 и не доказывает HA или горизонтальный
capacity SLO. Второй процесс запускать последовательно с другими тяжёлыми drills,
в ресурсах временного restore-хоста после отдельной проверки размещения.

Границы версии, обязательные для тестировщика:

- STU-01/02 — прототип только `example.products`. Preview и версия layout
  не меняют рабочую форму. Проверка пакета зависит от исходной версии в той же
  установке; checksum не является цифровой подписью или межконтурным импортом.
- STU-04 new-form draft — локальный файл, а не серверная история layout draft.
  `cms form apply` создаёт исходники/Flyway: его выкладка требует производного
  SHA и CI. На неизменном RC проверяется prepare/download, а генерация —
  отдельно в инженерной копии по [рецепту](../cookbook/simple-form-delivery.md).
- Live формы `studio.*` неизменяемы: нет редактирования/удаления схемы,
  REF/FILE/hooks/collections/global search. До 100 форм, до 20 дополнительных
  полей, 10 типов. Записи можно создавать/изменять/удалять через общий runtime.
- Новые формы имеют scope ALL для пользователей с правом формы. Редактор
  не настраивает owner/org-unit scope или произвольный field ACL. `listed=false`
  скрывает колонку, не защищает значение. Нельзя обещать организационную
  изоляцию внутри такой формы без отдельной реализации.
- Target-field/period revoke в `ReportDatasetScopeHttpTest` и
  `ReportDatasetPeriodHttpTest`, hidden `costNote` в layout — тестовые fixtures.
  В RC нет таких grants у проекций `projectCreatedAt/projectModifiedAt`.
  На staging проверяются реальный revoke `tasks.projects.view`, штатное gated
  поле `projectName` в `ms.tasks` и отказ неизвестному полю; fixture-only
  варианты остаются локальным доказательством механизма, не target PASS.

## 2. Матрица сценариев и критериев

Все строки P0 для заявленного scope. В evidence каждой строки указывать
**expected → actual**, роль, UTC, SHA/digest/config и отдельный результат;
ни наличие теста, ни HTTP 200 без проверки тела не означают PASS.
Маршруты в таблице относительны к `/api/v1`. Эталонные коды берутся из
[OpenAPI](../api/openapi.json), серверных контрактов и cookbook; UI проверяется
в дополнение к прямым API-запросам, а не вместо них.

| ID / ACC | Действие и отрицательный сценарий | Критерий готовности / свидетельство |
|---|---|---|
| STG-STU-01 / 08–09 | `GET /form-studio/prototype`, preview Products; изменить подписи/порядок/секции. Отправить неизвестное поле, SQL/`$ref`, лишнее свойство, >32 KiB; отозвать авторство/entity.view, выключить example | Preview 200; ID/required/readonly/data и обычный form-meta прежние. Невалидный контракт 422 problem+json, размер 413, без session 401/без settings 403/недоступная entity 404. Нет активации layout |
| STG-STU-02 / 10–11 | Два редактора layout draft; create/read/PUT, пропущенный и stale If-Match; создать версию, повторить prepare, скачать/verify; изменить пакет, включая пересчитанный checksum; для dependency drift отозвать только `example.products.create`, сохранив settings/view, и проверить прежний пакет | Отсутствующий draft 200 с `draft:null`; создание 201 + ETag/Location, повтор create 409; PUT 428/409 без потери ввода. Версия 201, повтор даёт прежний пакет; verify 200, tampering 422, stale dependency 409. После revoke draft/history/package/verify закрыты, версия не изменяется |
| STG-STU-03 / 12–14 | Общий report и dashboard: владелец расшаривает роли, читатель с меньшим scope открывает/экспортирует; затем same-session revoke. Редактирование чужим пользователем, изменения/delete/sharing без или со stale If-Match | Только owner редактирует, sharing дополнительно требует settings. Недоступный report ID 404; dashboard 200 с `available:false` без прежнего имени/state. 428/409 не меняют revision/data. Синхронный aggregate XLSX 200 и JSON учитывают scope читателя; table views не становятся общими |
| STG-STU-04 / 13–14 | Панель с корректным периодом UTC, карточкой без period binding и недоступным источником; запрос результата с revision панели | Валидные карточки работают; отсутствие binding даёт 422 только своей карточке; unavailable не раскрывает metadata. `/dashboards/{id}/widgets/{viewId}/result` требует If-Match; JSON/table/chart/XLSX одного фиксированного корпуса совпадают, NULL не превращается в 0 |
| STG-STU-05 / 15–16,19 | Новый простой form draft: check-draft, prepare, download/import; незавершённый ввод, неверная декларация, oversized body, поздний ответ после правки, revoke автора | Check-draft/prepare 200, prepare no-store и стабильный checksum; 422/413/403 по контракту. Правка делает пакет неактуальным, поздний import не затирает ввод. Metadata новой формы отсутствует до отдельной activation/delivery. CLI generation не запускается в frozen checkout |
| STG-STU-06 / 11,17 | С выключенным activation проверить availability/POST; включить флаг и перезапустить все участвующие процессы; author без publish, publisher без CRUD; затем точная активация и повтор | Availability 200; off → 422, без publish → 403, module off → 404. При разрешении POST `/studio/forms` → 201 + Location/checksum/entityCode; повтор того же пакета тот же результат, без второй definition/аудита. Авторство/publish не даёт CRUD автоматически |
| STG-STU-07 / 11,17–18 | Одновременно активировать одинаковый пакет и конфликтующие пакеты одного кода; неверные типы/поля, >64 KiB, Idempotency-Key; на clone проверить границу 99→100 и 101-ю форму | Одна атомарная definition + grants + audit; точные повторы безопасны, конфликт 409; invalid/quota 422, body 413. Неподдержанный idempotency header отклоняется по контракту; лимит не обходится гонкой. Не заполнять квоту рабочего staging; нет ручного DELETE/TRUNCATE definitions |
| STG-STU-08 / 17–19 | Через UI/API создать запись, reload, edit, search/filter, history, report, export, delete; пропустить If-Match/использовать старый; подставить ID другой live формы, bulk/history/files/global search | CRUD 201/200/204, missing/stale revision 428/409 без потери данных; чужой ID 404, bulk может дать 200 с отказом элемента и без чужих данных. Files 404, global search 400. JSON поля не обещают sorting. Обязательные поля/точность проверены на всех поддержанных типах |
| STG-STU-09 / 18,23 | В открытой сессии отозвать view/edit/export и shared-role; проверить старые URL/ID, metadata/list/history/report/export/download, меню/переводы. Отключить studio; отдельно activation flag | Отозванные операции закрыты сервером; нет прежних приватных заголовков/результатов в UI. Module off закрывает существующие формы; activation off после restart запрещает только создание новых, существующий CRUD остаётся по правам. Restore исходных grants/module states фиксируется |
| STG-STU-10 / 17 | Активация процессом A, новое чтение через B с той же БД; restart обоих по одному; потеря БД и восстановление, повтор запроса | Одинаковая definition/revision/grants/menu/i18n, сохранённые записи доступны после restart; snapshot не зависит от доставки NOTIFY. Ошибка БД не даёт stale authorization fallback. Доказать разные PID/container ID; тесты нескольких pool в одном JVM не закрывают строку |
| STG-STU-11 / 23 | Dataset `ms.tasks.projects`: две задачи проекта + одна без; group/count и UTC period по `projectCreatedAt`; архивировать/ограничить проект; direct/saved/dashboard/XLSX; unknown dataset | Grain — задача: группы 2 + 1, после архивации единственного проекта NULL-группа 3, без размножения/потери задач. Missing/archived/out-of-scope target → NULL. Метаданные содержат две даты проекта; unknown dataset 404 без fallback. При фиксированных данных JSON и XLSX совпадают |
| STG-STU-12 / 18,23 | Отозвать `tasks.projects.view` в открытой сессии; metadata/run/export/saved/panel; hidden `projectName` в штатном `ms.tasks`; отключить tasks | Dataset исчезает; metadata/run/export 404, saved/panel не раскрывают источник. Реально gated поле исключается из доступных операций. Scope ALL/SUBTREE/UNITS/SELF проверяется на существующих tasks/projects, не приписывается новым live формам |
| STG-STU-13 / 01–07,14,22 | Line по UTC, NULL группа/мера, пропущенный период; horizontalBar negative/zero; таблица/JSON/XLSX; конфигурации с лишними измерениями, валютами или усечением, доступные в RC | Один temporal bucket и мера для line; NULL-группа → таблица, NULL мера/пропуск → разрыв. HorizontalBar с осью нуля; никакого сложения averages/min/max или валют. Unsupported → точная таблица всех полученных строк с причиной fallback. При усечении обязателен явный `truncated`; максимум 1000 групп, полный набор бизнес-групп не обещается и XLSX не восстанавливает отброшенные группы. Fixture-only комбинации отдельно отмечены |
| STG-STU-14 / 19 | Все Studio страницы: loading/empty/error/retry, dirty/conflict/cancel, preview/import, список/диалоги, таблица отчёта/панель; keyboard, screen reader, 360/1365/1440 px, light/dark, RU/UZ/EN | Нет недоступных основных действий, потери ввода, ложного success, overflow диалога или console/page errors; focus возвращается, labels/ошибки переведены. Axe и ручной протокол, UX-метрики по staging-плану §4 на editor/report/dashboard, ≥30 cold/warm runs на страницу |
| STG-STU-15 / 24 | Assistant off: автор с assist+settings GET availability, POST валидного draft prompt; варианты без одного права, module off. Сверить исходящий трафик и логи | GET 200 `available:false`, POST 503; 403 без одного права, module off 404. Нет внешнего LLM-запроса, сохранения draft/package/activation или содержимого prompt/response в логах. Синтетический prompt; ключ/endpoint провайдера не задавать |
| STG-STU-16 / 20 | Согласованный IN-08 Studio mix на полном task/report корпусе: list/filter/report/export/dashboard и сохранение; concurrent readers, смена прав; продолжительная нагрузка | Отдельный target-протокол/инструмент измерений с утверждённым mix, не только общий k6. API p95≤300 мс/p99≤1000 мс/errors≤0.1%, headroom/heap/pending/lag по §4; ошибки доступа и неправильные totals = 0. SQL timeout 5 с — ограничитель, не разрешённый SLO |
| STG-STU-17 / 17,20 | Два полных backup/restore; сравнить layout drafts/versions/packages, live definitions/records/grants/audit, shared reports/dashboard/period, CMS/DWH/files; повторить revoke и CRUD на восстановленном контуре | Согласованный recovery point, без missing/orphan/checksum mismatch; права не расширены, существующий пакет проверяется со своей восстановленной версией. RPO≤24 ч, полный RTO≤2 ч. После restore повторы activation не дублируют definition/audit |

Локальные источники для сверки: `FormStudioControllerTest`,
`MdFormStudioHttpIntegrationTest`, `MdFormStudioLifecycleTest`,
`MdFormStudioPackagesTest`, `MdReportDashboardHttpTest`,
`MdDashboardLifecycleTest`, `MdSimpleFormHttpTest`, `MdSimpleFormInvalidHttpTest`,
`StudioActivationHttpTest`, `StudioActivationTransactionTest`,
`StudioIsolationHttpTest`, `StudioCatalogRestartTest`,
`TaskProjectDatasetHttpTest`, `ReportDatasetScopeHttpTest`,
`ReportDatasetPeriodHttpTest`, `StudioAssistantHttpTest` и
`StudioAssistantAccessHttpTest`. Они помогают воспроизвести ожидаемый контракт,
но их fixture classes не входят в production binary.

## 3. Live browser и протокол

Готовый [live test](../../e2e/tests/browser/instance/studio-live.spec.ts)
работает с настоящим API без intercepts. После разрешения target-тестов,
из `e2e` чистого checkout и с credentials из secret manager:

```powershell
$env:INSTANCE_BASE_URL = '<approved-staging-HTTPS>'
$env:E2E_STUDIO_ACTIVATION_EXPECTED = 'true'
npm test -- --project=instance tests/browser/instance/studio-live.spec.ts
```

`ADMIN_PASSWORD`/остальные credentials загружаются приватно по E2E env-контракту,
не вписываются в командную строку/документ. Зависимости/браузер берутся из
проверенного toolchain. Перед первым opt-in прогоном выполнить тот же сценарий
с expected `false`: он проверяет prepare и недоступность activation. При
`true` тест активирует **одну новую неизменяемую форму**, выполняет
create/reload/edit с If-Match/delete записи и возвращает прежнее состояние
module flag; саму definition он не удаляет. Повторы расходуют квоту.
Не использовать `scripts/dev/test-e2e.ps1` для очистки целевого staging:
его fresh Compose lifecycle относится к локальному CI.

Этот один сценарий не закрывает всю матрицу. Остальные cases исполняются
QA/Security через реальный UI/API; если добавляется target automation,
она проходит review и фиксируется отдельным hash/version. Секреты/PII
в trace/HAR/screenshots запрещены; использовать безопасный E2E reporter,
synthetic значения и приватный каталог evidence.

До mutation каждого fixture сохранить ожидаемое состояние и ревизии.
Для сравнения JSON/XLSX остановить изменения конкретного fixture на время
парных запросов: RC не обещает snapshot между независимыми HTTP запросами.
Протокол содержит case ID, роли, expected/actual/status, полные source SHA/
image digest/config revision, UTC, artifact hashes и reviewer по §8 основного
плана. NOT RUN, BLOCKED и UNVERIFIED не заменяются PASS.

## 4. DoD и решение по границам выпуска

- STG-STU-01…17 выполнены для доступных production-контрактов; каждый отказ
  проверен на отсутствие утечки/побочного изменения, а не только по status code.
- Подтверждены ACC-17 на отдельных процессах и target restart, ACC-20 на
  согласованной нагрузке и двух restore; тогда закрывается DOD-09. Без этих
  результатов Studio остаётся локально проверенным RC.
- ACC-21 (изменение опубликованной схемы N→N+1) — **N/A для create-only RC**.
  Не имитировать поддержку UPDATE/DELETE схемы или миграции данных редактором.
- Live provider smoke — **вне scope**, а не открытый блокер выключенной
  возможности. Включение LLM требует отдельного выбора provider/model,
  правил данных/retention/egress/бюджета и разрешения; локальные HTTP fixtures
  не доказывают работу настоящего провайдера.
- Для CLI-generated новой формы отдельный derived SHA/CI и собственная
  приёмка; package preparation на bc559ddd не означает её доставку.
- Blocker/High, нарушение ACL, потеря данных/изменений, неверный отчёт,
  непройденный restore/SLO или неизвестная artifact identity → **NO-GO**.
  Только некритичные косметические P1/P2 допускают явное решение с owner/date;
  критерии безопасности/корректности и SLO исключениями не отменяются.

Rollback: сначала блокировать новые операции согласованными флагами/правами.
Отключение activation не удаляет существующие формы; отключение studio
скрывает их целиком. Downgrade бинарника с новыми definitions/dataset/chart
state допустим только после совместимого rehearsal. Иначе восстановить
согласованный CMS+DWH+objects комплект и известные digest; не менять Flyway
history, не удалять definitions и не обещать автоматическую конверсию данных.

Перед завершением сверить согласованные финальные module flags/grants,
assistant off, временные credentials и план сохранения/сброса synthetic корпуса.
Итог подписывают QA, Security, Ops/DBA и Release owner. Даже PASS этой матрицы
не разрешает публикацию/merge или production traffic без решения владельца.
