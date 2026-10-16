# Модель угроз SmartupCMS и реестр персональных данных

**Версия:** 2.0

**Обновлено:** 2026-10-16

**Область:** одна установка SmartupCMS, одна организация, много пользователей

Это базовая модель репозитория. До production оператор установки дополняет её
реальными доменами, схемой сети, владельцем данных, правовым основанием,
решениями по срокам хранения, провайдерами, контактами на случай инцидента и
принятыми остаточными рисками.

Пути ниже даны от корня репозитория; `S/` — это
`apps/server/src/main/java/com/smartup24/cms/instance/`, `T/` —
`apps/server/src/test/java/com/smartup24/cms/instance/`.

## Как модель поддерживается

- Дата в строке «Обновлено» — дата последнего пересмотра. Релиз не выходит,
  если модель пересмотрена больше чем за 30 дней до релизного коммита:
  `scripts/security/test-threat-model-freshness.ps1` в job «Release tag gate»
  (`.github/workflows/release.yml`). В pull request та же проверка только
  предупреждает (job `release-config` в `ci.yml`), чтобы старая дата не
  блокировала несвязанные изменения (план 10/10, п. 7.6).
- Шаблон pull request (`.github/pull_request_template.md`) требует отметить,
  что модель пересмотрена, когда изменение добавляет или меняет endpoint,
  право, тип загружаемого файла, внешний провайдер, токен или секрет, модуль
  или границу развёртывания.
- DAST: ZAP baseline (`scripts/security/run-zap-baseline.ps1`, правила
  `scripts/security/zap-baseline.conf`) идёт в shard 2 job `e2e` и падает на
  любой находке High (п. 7.7). Итог последнего прогона — в разделе
  «Результаты DAST».

## Активы и границы доверия

Защищаемые активы: токены сессий и API, хеши паролей, учётные данные
провайдеров и webhook, ключ шифрования хранимых секретов (`SMC_SECRETS_KEY`),
данные пользователей, бизнес-записи сущностей, загруженные объекты, журналы
аудита и безопасности, архив аудита, резервные копии, age identity и
подписанные релизные артефакты. Студия добавляет декларации, черновики,
неизменяемые версии, роли общих отчётов/панелей и записи live-форм;
при явном включении помощника — пользовательское задание и ключ LLM-шлюза.

Границы доверия:

1. Браузер ↔ единый HTTPS-origin `web`.
2. Обратный прокси `web` ↔ `server` в закрытой сети backend.
3. `server` ↔ PostgreSQL (основная база и pg-dwh), Typesense, ClamAV,
   локальное или S3-совместимое хранилище объектов.
4. Необязательный исходящий трафик `server` к явно настроенным провайдерам
   уведомлений, адресатам webhook и хранилищу архива аудита.
5. `backup` ↔ PostgreSQL, локальное хранилище копий, необязательный R2/S3.
6. Доступ оператора к хосту, Docker daemon, файлам секретов, каталогу
   `/app/modules`, релизным ключам и материалам восстановления.
7. Автор Студии ↔ серверный валидатор/хранилище деклараций; отдельно
   публикатор live-форм ↔ фиксированная схема `studio_records` и каталог прав.
   Получатель отчёта/панели выполняет запрос с собственными текущими правами.
8. Только при opt-in: сервер ↔ настроенный оператором HTTPS LLM-шлюз;
   предложение модели возвращается в редактор, не в исполнитель кода/DDL.
9. Разработчик CLI и GitHub release workflow ↔ исходники, GHCR и assets;
   оператор verifier ↔ независимо утверждённые repository/channel/tag/SHA.
   Доступ к приложению не предоставляет полномочий этой цепочки поставки.

PostgreSQL, Typesense, порт сервера и порт management в production Compose не
опубликованы. Edge-прокси, ОС хоста, Docker daemon, DNS и внешние провайдеры —
вне границы доверия приложения.

## Базовые угрозы

| Угроза | Мера в репозитории | Требование к установке / остаточный риск |
|---|---|---|
| Кража учётных данных, захват аккаунта | Argon2id; хешированные токены сессий и API; отзыв сессий; лимиты частоты; принудительная смена bootstrap-пароля; cookie сессии HttpOnly и `SameSite=Lax` (`S/kauth/security/KauthSessionCookies.java`, `T/kauth/security/KauthSessionCookiesTest`) | Защитить TLS, файлы секретов, почту и OTP-провайдеров, устройства администраторов; флаг `Secure` cookie зависит от корректного списка trusted proxy |
| IDOR и повышение привилегий | `@RequiresPermission`, SQL-предикаты области данных, 404 для недоступных id (`MdScopeServiceIntegrationTest`, `TaskFileDataScopeControllerTest`) | Повторять отрицательные тесты по ролям для каждой новой сущности и endpoint |
| CSRF / XSS / clickjacking | CSRF-токен для запросов с cookie (`S/config/security/SecurityConfig.java`, `T/config/security/SecurityConfigTest`); строгая обработка URL в markdown; CSP, запрет фреймов, заголовки referrer и permissions | Проверять заголовки на внешнем origin; CSP веба разрешает inline-стили (см. «Результаты DAST») |
| SQL- и командные инъекции | Параметризованный JDBC; идентификаторы сущностей проверяются регулярками при объявлении; пользовательский ввод не попадает в shell-команды релиза | Статический анализ и состязательные API-тесты на каждый релиз |
| SSRF и неконтролируемый исходящий трафик | Webhook выключены по умолчанию, точный allow-list, запрет private-адресов, повторная проверка URL перед отправкой, без редиректов, таймауты; телеметрии по умолчанию нет | Держать opt-in private-адресов выключенным на internet-установках; egress-контроль на хосте |
| Вредоносная загрузка | Лимит 50 MiB в приложении, 51 MiB на прокси; отказ по сигнатуре исполняемых файлов; сверка MIME с содержимым; непубличные ключи карантина; fail-closed ClamAV в production Compose из собственного усиленного образа релиза (`deploy/images/clamav`: официальный `clamav/clamav-debian` по digest плюс обновления безопасности Debian, Trivy HIGH/CRITICAL = 0 в CI и релизе, подпись и SBOM); скачивание с проверкой прав и `Content-Disposition: attachment`; квота объектов; лимиты xlsx (раздел «Загрузки xlsx и антивирус») | Обновлять образ и сигнатуры сканера; доказать отказ на EICAR и очистку при недоступном сканере; такой же лимит на внешнем edge |
| Раскрытие секретов и ПДн через API или логи | Секрет подписи webhook показывается один раз; запросы и учётные данные webhook скрыты; структурный аудит; сканирование секретов | Проверять тексты ошибок провайдеров и support bundle; не прикладывать дампы, байты объектов, `.env`, расшифрованные копии к публичным issue |
| Компрометация цепочки поставки | Зафиксированные зависимости, actions и базовые образы по digest; SBOM, provenance, keyless Cosign | Защитить права репозитория, правила веток и тегов, OIDC; проверять digest, подпись и provenance каждого образа |
| Потеря или порча данных | Отдельная миграция Flyway, обязательная зашифрованная копия до миграции, контрольные суммы, скрипты восстановления и отката | Байтам объектов нужен свой источник восстановления; учения по восстановлению; age identity вне хоста |
| Доступность, исчерпание ресурсов | Раздельные бакеты лимитов (анонимный/логин, публичный i18n, пользователь, API-токен, дорогие пути); квоты; ограниченные ретраи webhook; health endpoints; graceful shutdown | Бакеты в памяти одного процесса; HA и доставки алертов нет в комплекте |

## STRIDE по областям, добавленным после 2026-09-04

Дополнение pre-production: login budget связан с account ID и нормализованным
IP, сериализуется PostgreSQL advisory locks (IP → account) в ограниченной
транзакции; aliases не создают новый бюджет. SSE перед данными/heartbeat
перепроверяет исходный credential и текущее право inbox, закрывается при
ошибке проверки. Регрессии: `T/kauth/KauthLoginBudgetIntegrationTest`,
`T/kauth/MsSseRevocationIntegrationTest`. Проверка на каждом SSE send
увеличивает нагрузку БД и входит в target capacity gate.

Production DB passwords разделены между postgres/migrator/runtime/backup,
источники env/file должны совпадать. Это не заменяет защищённые host files,
secret manager и проверку ротации. Consistent recovery coordinator и
deployment runner работают с привилегированным Docker/SSH доступом;
configuration и helper scripts принадлежат root. Контроллер/ops являются
частью доверенной computing base; их компрометация требует отзыва доступов.

Буквы: S — подмена, T — изменение, R — отказ от действий, I — раскрытие,
D — отказ в обслуживании, E — повышение привилегий.

### Low-code runtime `/api/v1/entities`

Скачивание FILE/IMAGE и повторное использование вложения проверяют текущие
права поля через `EntityFieldRights.visibleFiles` и ограничение `field_key` в
`MfRecordFileRepository`. Скрытый, удалённый или не-файловый ключ не даёт
доступа. `EntityFileAccessIntegrationTest` проверяет отзыв, перенос скрытого
чужого UUID в открытое поле, несколько связей и собственную загрузку.
Проверка выполняется до выдачи потока; уже полученные байты отозвать нельзя.

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| E | Доступ к сущности без права | Аннотация контроллера пускает вошедшего; права конкретной сущности проверяет `S/common/entity/runtime/EntityGate.java` (нет права на просмотр — 404, на действие — 403); сущность без области прав модуля не даёт серверу стартовать → `T/common/entity/runtime/EntityRuntimeIntegrationTest`, `T/support/entity/KitAccessChecks`, покрытие всех сущностей — `T/support/entity/EntityContractCoverageTest` | Защита держится на вызове `EntityGate` в каждой точке входа; новая точка входа без него откроет данные |
| I | Чтение записей вне области данных | `EntityReads`, `S/common/entity/EntityScopes.java` (предикат в том же SQL, 404 вне области); ссылки проверяются в области целевой сущности (`EntitySaveChecks`) → `EntityRuntimeIntegrationTest#filtersDoNotBypassTheScope`, `#referencesAreCheckedInTheTargetsScope` | — |
| I, T | Чтение или запись закрытого поля | `S/common/entity/EntityFieldRights.java` (скрытие при чтении, отказ записи скрытого и read-only поля) → `T/common/entity/EntityFieldRightsIntegrationTest`, `T/support/entity/KitFieldRightChecks` | Дополнительные поля и собственные свойства записи прав на поля не имеют |
| I | Повтор сохранённого ответа после отзыва доступа | `S/config/idempotency/IdempotencyService.java` связывает запрос с контекстом авторизации; `S/common/entity/RetainedRecordAccess.java` повторно проверяет текущий скоуп записей ответа и подписей ссылок → `T/common/entity/FieldAccessRevocationIntegrationTest`, `T/common/security/RetainedTaskAccessIntegrationTest` | Уже полученные клиентом данные отозвать невозможно |
| T | Потерянное обновление | `If-Match` для PATCH, архивации и действий (`S/common/web/Revisions.java`: 428/409) → `KitCrudChecks#staleUpdate`, `#staleArchive` | `DELETE` принимает запрос без ревизии |
| T | SQL-инъекция через фильтр | Поля фильтра только из реестра, лимиты условий и длины (`S/common/query/QueryCompiler.java`), идентификаторы проверены регулярками (`libs/platform-api/.../FieldSource.java`, `EntityScope.java`), значения — параметры → `T/common/entity/EntityScopeTest#scopeColumnsAreIdentifiers` | — |
| D | Большое тело или тяжёлый список | Тело до 512 KiB (`S/config/web/EntityBodyLimitFilter.java` → `EntityBodyLimitFilterTest`); страница до 200 записей | — |

### Массовые операции (`EntityBulkController`)

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| E | Массовое действие без права | Право берётся из объявленного действия, скрытая сущность — 404 (`S/common/entity/EntityBulkController.java`) → `T/common/entity/EntityFeaturesTest`, `KitAccessChecks#outsideBulk` | — |
| R, T | Обход области, хуков и аудита | Каждая запись идёт отдельной одиночной операцией в savepoint (`S/common/bulk/BulkRunner.java`, `BulkItemScope.java`) → `T/ms/task/MsTaskEntityIntegrationTest#bulkRunsRecordByRecord`, `T/config/idempotency/IdempotentBulkIntegrationTest` | Массовое удаление и архивация идут без ревизии |
| D | Тяжёлый запрос | До 100 id за запрос (`BulkRunner.MAX_IDS`) | `/bulk` не входит в дорогие пути лимита частоты: до 100 операций на запрос в пользовательском бакете |

### Задачи импорта и экспорта

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| E | Импорт или экспорт без права | Импорт: видимость, capability `IMPORT`, право `form.import` (`S/common/entity/runtime/EntityImports.java`); экспорт: право списка при запросе и при выполнении, задача берёт текущие права владельца (`S/report/export/ExportPrincipals.java`) → `T/report/ReportExportControllerTest#jobRunsWithTheOwnersCurrentRights`, `T/report/ReportImportIntegrationTest#blockedOwnerFailsTheImport` | — |
| I | Чужой файл или отчёт | Импорт только из своего xlsx; статус, отчёт и выгрузка — только владельцу и только до истечения срока → `ReportExportControllerTest#requestIsCheckedAtOnceAndExportsArePrivate`, `ReportImportIntegrationTest#requestAndJournalChecks` | — |
| I | Скачивание готовой выгрузки после отзыва доступа | `S/report/export/ExportAccess.java`: контекст авторизации, видимые колонки и текущий скоуп сохранённых ID; для собственного списка — `QueryListExporter.visible(ids)` → `T/common/entity/FieldAccessRevocationIntegrationTest`, `T/common/security/RetainedTaskAccessIntegrationTest` | Уже скачанные копии остаются у получателя; проверка права выполняется перед выдачей потока |
| T | Formula injection в выгрузке | CSV: экранирование префиксов `= + - @` и управляющих символов (`S/report/service/ReportService.java`) → `T/report/ReportExportIntegrationTest#csvNeutralizesUnsafePrefixes…`; xlsx пишет значения строковыми ячейками без формул | В общем xlsx-экспорте (`ExportWorkbookWriter`) нет явного экранирования `=` и отдельного теста на отсутствие формул |
| D | Zip-бомба, поток shared strings, ячейка за последним столбцом | Лимиты xlsx до чтения (`S/common/xlsx/XlsxGuard.java`) → `T/report/imports/ImportSheetsTest#zipBombIsRefused`; не больше 3 активных импортов и экспортов; до 100 000 строк импорта, до `smc.reports.export.max-rows` строк экспорта | См. «Загрузки xlsx и антивирус» |

### Отчёты, виджеты и встраиваемые отчёты

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| I | Агрегаты по чужим записям или закрытым полям | `S/common/entity/report/EntityReports.java`: предикат области в том же SQL, поле без права — 422 (`QueryAggregates`) → `T/common/entity/report/EntityReportIntegrationTest#totalsFollowTheScope`, `#fieldRightsHoldInReports` | — |
| I | Чужой виджет или сохранённый отчёт | Личные виджеты выбираются по владельцу, общие отчёты — по текущим ролям и полям читателя (`S/md/service/MdReportWidgetService.java`, `S/md/service/MdReportCatalogService.java`) → `EntityReportIntegrationTest#reportViewsAreChecked`, `#widgetsAreLimited`, `T/md/MdReportDashboardHttpTest` | Доступ к общему определению не выдаёт права на данные; подробнее — раздел общих отчётов ниже |
| S, I | Встраиваемый отчёт (`embed/:code`) показывает вредоносную страницу | Маршрут под `authGuard`; URL пункта меню проверяется на сервере (`S/md/service/NavigationItemService.java#validateUrl`), пункты меняет только право `md.navigation.manage` | iframe получает `allow-scripts` и `allow-same-origin`; `frame-src` с 2026-10-08 — `'self'` и источники из `SMC_WEB_FRAME_SOURCES` (`apps/web/nginx/spa-csp.conf`, ADR-0034), поэтому внешний отчёт показывается только с хоста, который назвал оператор; шаблон URL пропускает `//host`; серверного теста валидации URL нет |

### Студия: декларации, пакеты, CLI и live-формы

Пересмотр 2026-10-16 касается реализованных границ STU-01…06 и подписанной
поставки. Это дополнение к существующей модели, не свидетельство нового полного
security scan или приёмки целевого контура. Решения:
[ADR-0035](../adr/ADR-0035-declaration-lifecycle.md),
[ADR-0037](../adr/ADR-0037-simple-form-cli-delivery.md),
[ADR-0038](../adr/ADR-0038-live-simple-forms.md).

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| E, T | Автор декларации внедряет исполняемую конфигурацию, скрытое поле или подменяет пакет с пересчитанным checksum | Закрытые форматы, строгий UTF-8/JSON, лимиты размера/глубины и проверка текущего metadata; `md.settings.update` не заменяет доступ к исходной форме. `S/md/service/MdFormStudioService.java:136` сравнивает пакет с доверенной сохранённой версией и dependencies, не только с checksum → `T/md/MdFormStudioPackagesTest`, `T/md/MdFormStudioLifecycleTest#aRecomputedAttackerChecksumDoesNotReplaceTheTrustedPackage` | Layout-прототип ограничен `example.products`; подготовка пакета не активирует layout обычных форм. Checksum новых simple-form packages подтверждает целостность, не доверие к автору |
| T, R | Конкурентное сохранение теряет правки или оставляет частично подготовленную версию | `If-Match`, CAS черновика, блокировка при подготовке, неизменяемые версии и транзакционный аудит (`S/md/service/MdFormStudioService.java:75`) → `T/md/MdFormStudioLifecycleTest` | Версии не имеют автоматического retention; изменение исходного metadata может сделать старую декларацию несовместимой. Восстановление и совместимость старого бинарника проверяются отдельно |
| E, T | Входной пакет CLI пишет за пределами checkout или меняет существующую схему | `tools/cms-cli/lib/form-delivery.mjs:75` проверяет create-only/receipt и использует существующие шаблоны; `tools/cms-cli/lib/safe-path.mjs:6` проверяет путь и realpath существующего предка. Первые grants — административным ролям → `tools/cms-cli/test/form-delivery.test.mjs` | CLI выполняется с правами локального разработчика, checkout/receipt должны быть доверенными. Повтор сохраняет ручные изменения, а не доказывает их безопасность; обязательны review, CI и Flyway. Прерванная генерация требует сверки исходников |
| E, T, R | Неправомочная активация, конфликт кода или частичная регистрация прав | `studio.forms.publish`, активный модуль и выключенный по умолчанию `SMC_STUDIO_ACTIVATION_ENABLED`; `StudioPackages` повторно валидирует пакет и namespace `studio.*`. `S/studio/service/StudioActivationService.java:70` сериализует каталог, ограничивает его 100 формами и атомарно пишет определение/права/аудит → `T/studio/StudioActivationHttpTest`, `T/studio/StudioActivationTransactionTest` | Активация только create-only; изменённый пакет с прежним кодом даёт конфликт. Выключение активации не удаляет существующие формы/данные; эволюция схемы и деактивация не реализованы |
| I, T | Подстановка ID читает/меняет запись другой live-формы | `S/studio/service/StudioEntityCompiler.java:24` фиксирует таблицу и серверный `form_id`; `S/common/entity/store/EntityPartitions.java:14` связывает partition со storage, runtime сохраняет обычные права. `StudioDefinitions` читает immutable snapshot из БД для запроса → `T/studio/StudioIsolationHttpTest`, `T/studio/StudioCatalogRestartTest` | Это изоляция форм в одной организации, не tenant isolation. Live-форма использует `EntityScope.all()`: право просмотра открывает все её строки; SELF/SUBTREE для неё не заявлены. Независимый restart/restore drill и ёмкость JSONB на staging остаются открытыми |

### Общие отчёты, datasets и именованные панели

[ADR-0036](../adr/ADR-0036-shared-reports-and-dashboards.md) и
[ADR-0039](../adr/ADR-0039-report-datasets-and-assistant.md) различают доступ
к определению отчёта и доступ к его данным. Получатель не действует от имени автора.

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| I, E, T | Общая панель раскрывает скрытый источник или сохранённое состояние после отзыва роли | `S/md/service/MdPreferenceSharing.java:65` допускает sharing только владельцем с `md.settings.update`; текущие роли/поля проверяются при чтении/запуске/XLSX. `S/md/service/MdDashboardService.java:147` заменяет недоступный источник карточкой без старого определения. Изменения требуют `If-Match`, мутации не принимают idempotency key → `T/md/MdReportDashboardHttpTest`, `T/md/MdDashboardLifecycleTest` | Уже скачанный XLSX/полученные данные отозвать нельзя; расчёты разных HTTP-запросов не образуют единый снимок данных |
| I, E | Проекция связанной сущности обходит права поля, scope или module flag | `S/common/entity/report/EntityReportDatasets.java:75` разрешает только объявленный доверенным кодом dataset, проверяет root REF, target view/поля; LEFT JOIN включает scope и исключает архивные target. Пользовательский SQL/joins отсутствуют → `T/report/ReportDatasetScopeHttpTest`, `T/common/entity/report/EntityReportDatasetSqlTest` | Расширение datasets — доверенное изменение кода с отдельными тестами. Ограниченный EXPLAIN на фикстуре не доказывает target capacity |
| D | Панель или агрегированный экспорт истощают ресурсы | Лимиты: 20 панелей владельца, 12 карточек, 20 ролей; агрегатор — 2 группировки, 4 меры, 1000 групп, SQL timeout 5 с (`S/md/service/MdReportPreferencesInput.java`, `S/common/query/QueryAggregates.java:39`, `S/common/query/QueryAggregate.java:26`, `S/common/query/QueryAggregateRepository.java:33`) → `T/md/MdDashboardLifecycleTest#concurrentCreationCannotExceedTheOwnerLimit`, `T/report/ReportDatasetHttpTest` | Лимит одного запроса не заменяет нагрузочную приёмку одновременно открытых панелей и R2/backup операций |

### Подключаемый помощник черновиков

`apps/server/src/main/resources/application.yml:161` и production Compose оставляют activation/assistant
выключенными, provider — `none`. Внешний вызов возможен только после явной
настройки; штатный endpoint задаёт оператор, пользователь API его не выбирает.

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| I, E, T | Prompt/ответ модели раскрывает данные или становится исполняемой формой без подтверждения | `S/studio/service/StudioAssistantAccess.java:27` требует `studio.forms.assist` и `md.settings.update`, перепроверяет credential/эффективные права после ответа. `StudioAssistantService` передаёт только prompt и фиксированный формат, валидирует simple-form и возвращает предложение без save/package/activate; `StudioAssistantController` запрещает replay → `T/studio/StudioAssistantAccessHttpTest` | Пользователь может вручную вставить ПДн/секрет в prompt: DLP нет. Валидная декларация может быть семантически неверной; редактор должен проверить её до отдельного сохранения/активации |
| I, D | Шлюз сохраняет задание, перенаправляет запрос или удерживает ресурсы | `S/studio/llm/OpenAiCompatibleLlmProvider.java:35` требует HTTPS без credentials/query/fragment, запрещает redirects и автоматические retries; connect 5 с/request 30 с, ограниченный ответ (`LlmResponseBody`: 64 KiB envelope; declaration: 32 KiB). Prompt ≤ 2000 code points, 2 конкурентных вызова на процесс; ошибки не журналируют prompt/response → `T/studio/llm/OpenAiCompatibleLlmProviderTest`, `T/studio/service/StudioAssistantServiceTest` | `store:false` не доказывает retention поставщика. Оператор отвечает за доверенный endpoint, egress, ключ, бюджет и политику данных; лимиты не глобальны для нескольких процессов. Реальный provider smoke и внешняя отправка не подтверждены локальной HTTP fixture |

### Подписанный staging prerelease и verifier

[ADR-0040](../adr/ADR-0040-signed-staging-prereleases.md) отделяет разрешение
публикации от подготовки RC. Администратор GitHub и оператор verifier — отдельные
доверенные участники; подпись не подтверждает безопасность самого приложения.

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| E, T | Публикация другого SHA/канала, повтор номера или обход review | `.github/workflows/prerelease.yml:54` и `:224` используют `staging-release`; оба write jobs сравнивают точные approved SHA/version. `scripts/release/release_policy.py:29` требует tag push, первый attempt, совпадение event/HEAD/tag и DCO; общий CI предшествует сборке → `scripts/release/test_release_pipeline.py`, `scripts/release/test_workflow_contract.py` | Независимый reviewer, запрет self-review/admin bypass, tag rulesets и отсутствие одноимённых repo/org vars проверяются в GitHub: YAML их не доказывает. Реальный OIDC/GHCR/publish ещё не проверен |
| S, T | Подмена bundle/metadata или подпись от другого workflow/source | `scripts/release/verify-published-release.ps1:42` сначала проверяет Cosign и GitHub provenance SHA256SUMS, затем hashes и шесть image digests/signatures/bundles; exact repository/channel/tag/source/signer SHA, hosted runners. Старый PASS удаляется до повторной проверки → `scripts/release/test-published-release.ps1`; Ansible сохраняет эту identity через `deploy/ansible/files/deploy_release.py:103` | Verifier и ожидаемый SHA берутся из независимого доверенного источника, не из скачанного bundle. Компрометация разрешённого workflow/оператора остаётся внутри доверенной границы; нужны фактические настройки и проверка настоящих assets по [staging-плану](../ops/staging-acceptance.md). Подписанный RC не является production GO |

### Поисковая проекция (Typesense)

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| I | Поиск раскрывает записи вне области | Фильтр индекса по ключам области; каждое попадание перепроверяется в БД (`S/search/service/SearchScopes.java`); при сбое Typesense — PostgreSQL с той же областью → `T/search/SearchManagementAuthorizationTest#aStaleIndexHitThatTheDatabaseNoLongerFindsIsNeverAnswered`, `T/search/SearchFallbackIntegrationTest` | — |
| I | В индекс попадают закрытые поля | Индексируются только текстовые поля списка без ограничения доступа (`libs/platform-api/.../EntitySearchSpec.java`) → `SearchRevisionIntegrationTest` | — |
| E | Управление индексом без роли | `S/search/service/SearchAccessPolicy.java` → `SearchManagementAuthorizationTest#permissionsNeverDelegateUnrestrictedIndexAccessToNonAdmins` | — |
| S | Dev-ключ Typesense в production | `S/config/env/DefaultSecretsGuard.java` → `T/config/env/DefaultSecretsGuardTest#defaultTypesenseKeyStopsTheStart` | Канал к Typesense без TLS (закрытая сеть backend) |

### Webhooks: подпись и каталог событий

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| S, T | Подделка или изменение доставки | HMAC-SHA256 над `timestamp.body`, заголовки `X-Signature-SHA256`, `X-Signature-Timestamp`, `X-Delivery-Id` (`S/webhook/worker/WebhookOutboxWorker.java`) → `T/webhook/WebhookOutboxWorkerSecurityTest#dispatchesSignedDelivery…` | Ротации секрета нет; защита от повтора — на стороне получателя по времени и id доставки |
| I | Утечка секрета подписи | 32 случайных байта, показываются только при создании, хранятся в AES-256-GCM (`S/common/security/StoredSecrets.java`, ADR-0029) → `T/webhook/WebhookServiceTest#shouldReturnSigningSecretOnlyAtCreation`, `T/common/security/StoredSecretsTest` | — |
| I | Доставка закрытых полей | Неизвестные события отклоняются; данные проходят `EntityFieldRights.forEveryone` (`S/webhook/service/EntityWebhookListener.java`) → `T/webhook/WebhookSubscriptionControllerTest` | — |
| E (SSRF) | Запрос во внутреннюю сеть | `S/webhook/service/WebhookTargetPolicy.java` (только HTTPS, allow-list, запрет private/loopback/link-local/CGNAT/ULA, повтор перед отправкой, без редиректов) → `T/webhook/service/WebhookTargetPolicyTest`, `WebhookOutboxWorkerSecurityTest#revalidatesTheStoredTarget…` | Между проверкой и подключением DNS разрешается заново (окно rebinding, сужено allow-list); NAT64 `64:ff9b::/96` не блокируется |
| D | Медленный адресат | Таймауты 3 с / 10 с, 5 попыток с backoff, затем dead letter → `WebhookOutboxWorkerSecurityTest#boundsDeliveryTime…` | — |

### Внешние модули (`/app/modules`, манифесты)

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| T | Некорректный или конфликтующий манифест | Строгая проверка манифеста до создания бинов: поля, `minPlatform`, зависимости, циклы, дубли (`S/common/module/ModuleManifests.java`, `S/config/module/ModuleManifestSelector.java`, ADR-0033) → `T/common/module/ModuleManifestsTest`, `T/config/module/ModuleManifestStartupTest` | — |
| E | Выключение системного модуля, смена модулей без права | `S/md/controller/ModuleRegistryController.java` (`md.modules.manage`, `If-Match`) → `T/md/ModuleRegistryIntegrationTest#systemModulesCannotBeDisabled`, `KitAccessChecks#moduleOffClosesEveryPath` | — |
| T, E | Подменённый jar модуля исполняет код | Установка только оператором (файл в каталоге или томе) | Подписи и хеша jar модуля нет, модуль работает с полными правами JVM на общем classpath; Cosign покрывает образ, но не jar модулей. Требование к установке: том `/app/modules` только для чтения, jar из доверенного источника с проверкой контрольной суммы |

### Код, сгенерированный CLI

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| T | Генератор выпускает небезопасный код | Имена проверяются (`tools/cms-cli/lib/names.mjs`), текст в SQL экранируется (`templates.mjs`); сгенерированный модуль собирается со Spotless, Error Prone, Checkstyle, ArchUnit и entity contract kit (`scripts/dev/test-cms-cli.ps1`, `tools/cms-cli/scripts/smoke.mjs`) → `tools/cms-cli/test/cli.test.mjs`, `EntityContractCoverageTest` | Полная сборка сгенерированного модуля — только в `nightly.yml`; в PR — без сборки |
| E | Слишком широкие права по умолчанию | Права `view/create/update/delete` объявляются в шаблоне | Роль `manager` получает `delete`, `user` — `view`; автор модуля обязан сузить права |

### Приглашения

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| S | Угадывание или повтор ссылки | 256 бит `SecureRandom`, в БД SHA-256, срок 72 ч, атомарное одноразовое погашение, привязка к версии аутентификации, новое приглашение отменяет старое (`S/kauth/service/KauthInvitationService.java`, `S/kauth/repository/KauthPasswordResetRepository.java`) → `T/kauth/KauthPasswordResetIntegrationTest` (общий механизм) | — |
| I | Ссылка уходит в stub-канал | При stub-почте и `smc.delivery.enforce` — 409 → `T/kauth/KauthInvitationDeliveryTest#stubMailRefusesTheInvitation` | Отдельного лимита частоты приглашений нет (только право администратора) |

### Ссылки сброса пароля

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| S | Перебор, повтор, перехват ссылки | 256 бит, SHA-256, срок 15 мин, одноразовость, новая ссылка отменяет старую, после сброса закрываются сессии и API-токены (`S/kauth/service/KauthPasswordResetService.java`) → `T/kauth/KauthPasswordResetIntegrationTest#usedAndExpiredLinksAreRejected`, `#newLinkVoidsThePreviousOne`, `#emailLinkSetsPasswordAndClosesSessions` | — |
| I | Перечисление адресов | Одинаковый ответ для известного и неизвестного адреса, доставка после commit вне потока запроса → `#knownAndUnknownEmailsLookTheSame` | — |
| D | Поток запросов | 3 ссылки в час на пользователя; 5 неудачных подтверждений за 15 мин с одного IP — 423; общий IP-лимит → `#fiveRejectedLinksLockTheAddress` | Лимиты в памяти одного узла и по IP |

### Stub-каналы и production guard

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| I, S | Production тихо «отправляет» коды и ссылки в консоль | `S/config/env/ProductionStartGuard.java` не даёт стартовать с `console_*` почтой при `smc.delivery.enforce`, без `SMC_PUBLIC_URL`, с профилем `demo`; `S/kauth/service/KauthDeliveryGuard.java` — stub-канал у пользователей 2FA; stub-провайдеры маскируют адреса и не пишут тело → `T/config/env/ProductionStartGuardTest#stubMailNeedsDeliveryOff`, `T/kauth/KauthDeliveryGuardTest`, `T/ms/notify/StubProvidersLogTest` | `SMC_DELIVERY_ENFORCE=false` снимает защиту; stub SMS и мессенджер без пользователей 2FA дают только предупреждение |

### Архив аудита в S3

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| T, R | Порча или подмена архива | gzip JSON-lines, SHA-256 и число строк, перечитывание и сверка до признания архива проверенным; партиция удаляется только после архива (`S/audit/archive/AuditArchiveService.java`) → `T/audit/AuditArchiveIntegrationTest#archiveIsVerifiedAndRestorable`, `#deletionTakesOnlyArchivedPartitions` | SHA-256 хранится в той же БД; нет Object Lock / WORM |
| I | Утечка ключей или содержимого | Ключи скрыты в `toString` (`S/audit/archive/S3AuditArchiveStore.java`), проверка контрольных сумм SDK → `T/audit/AuditArchiveStoresTest` | Нет SSE и клиентского шифрования; доступ к архиву — только правами на bucket |

### Описание API (OpenAPI)

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| I | Анонимное чтение карты API | `/api/v1/openapi.json` требует входа, Swagger UI выключен (`S/config/security/SecurityConfig.java`, `application.yml`) → `T/config/security/SecurityConfigTest#apiDescriptionNeedsASignIn` | Описание читает любой вошедший пользователь, оно перечисляет все сущности и поля |

### Загрузки xlsx и антивирус (п. 7.6)

| STRIDE | Угроза | Меры (код → тест) | Остаточный риск |
|---|---|---|---|
| D | Zip-бомба: маленький файл распаковывается в гигабайты | Каждая запись распаковывается один раз через считающий поток, заявленным размерам zip не верим: распакованный объём (по умолчанию 1 GB), число записей (1 000), степень сжатия записи больше 1 MiB (200×) (`S/common/xlsx/XlsxGuard.java`, `XlsxLimits.java`, `smc.uploads.xlsx.*`); вызывается до fastexcel в импорте (`S/report/imports/ImportFile.java`) и в пакетах загрузок (`S/upl/parse/UplXlsxParser.java`) → `T/common/xlsx/XlsxGuardTest#aZipBombIsRefusedByItsRatioBeforeItIsInflatedWhole`, `T/upl/UplXlsxParserTest#zipBombIsRejectedUnread`, `T/report/imports/ImportSheetsTest#zipBombIsRefused` | Отказ показывается пользователю как «файл не читается» (`IMPORT_UNREADABLE`, `UPL_PKG_UNREADABLE`); какой предел нарушен — только в логе сервера |
| D | Поток shared strings, объявленный `uniqueCount` | Счётчик `<si>` и объявленные `count`/`uniqueCount` до 5 000 000, размер части до 256 MB → `XlsxGuardTest#sharedStringsAreBoundedByTheirDeclaredAndRealCount`, `#theSharedStringsPartIsBoundedBySize` | — |
| D | Ячейка за последним столбцом раздувает строку читателя | Строки до 1 048 576, столбцы до 16 384 (XFD) по атрибутам `r` и по счёту → `XlsxGuardTest#rowsAndColumnsStayWithinTheSheet` | — |
| D, I | XML-сущности (billion laughs, XXE) | StAX без DTD и внешних сущностей, часть с DTD отклоняется → `XlsxGuardTest#anEntityDeclarationIsNeverExpanded` | — |
| T | Вредоносное содержимое | ClamAV в production: `StreamMaxLength 64M`, `MaxFileSize 100M`, `MaxScanSize 400M` через `CLAMD_CONF_*` образа (`deploy/compose/docker-compose.prod.yml`); сервер сам обрывает поток после 60 MiB (`smc.files.scanner.clamav.max-stream-size`, `S/mf/scan/ClamAvFileScanner.java`), порядок значений проверяет `scripts/prod/test-release-config.ps1` → `T/mf/ClamAvDaemonLimitsIntegrationTest` (настоящий clamd из усиленного образа релиза, собранного из `deploy/images/clamav/Dockerfile`, с опциями Compose: файл 50 MiB просканирован до последнего байта, поток сверх лимита — fail-closed), `T/mf/ClamAvFileScannerTest` | Распакованные части архива больше `MaxFileSize` и данные сверх `MaxScanSize` clamd пропускает без проверки (без `AlertExceedsMax`: иначе большие легитимные xlsx отклонялись бы); теста с настоящей строкой EICAR в CI нет — она остаётся ручным пунктом [чек-листа запуска](../ops/production-launch-checklist.md) |

## Результаты DAST

Прогон 2026-10-08: ZAP baseline (`zaproxy/zap-stable@sha256:781a2bdaea47…`) по
локальному стенду Compose интеграционной ветки фазы 7 (с заголовками nginx
п. 7.4), веб-origin со spider и все операции `docs/api/openapi.json` без
аутентификации. Первый прогон (2026-10-07, до п. 7.4): High 0, Medium 2,
Low 10; исправлены `frame-src` с целыми схемами, заголовки на статике и
`SameSite` у `XSRF-TOKEN`.

| Уровень | Число типов | Находки | Решение |
|---|---|---|---|
| High | 0 | — | — |
| Medium | 1 | CSP: `style-src 'unsafe-inline'` | Принято: стили компонентов Angular вставляются элементами `<style>`; обоснование и альтернатива (nonce) — ADR-0034, п. 2.1; правило 10055 не игнорируется |
| Low | 7 | `XSRF-TOKEN` без HttpOnly (так задумано: веб читает токен для double submit; `SameSite=Lax`); нет COEP, COOP, CORP (веб) и CORP (API); «debug error message» — ложное срабатывание на текст перевода в `/api/v1/i18n/en`; `bypassSecurityTrustHtml` в бандле | COEP/COOP/CORP не заданы: COEP `require-corp` ломает встраиваемые отчёты; остаточный риск принят до решения по изоляции origin |
| Informational | 6 | Кеширование, идентификация сессии, запрос входа, `userId` в query аудита, современное веб-приложение | Не требует действий |

`zap-baseline.conf` игнорирует только правило 100000 (коды 4xx, 229
вхождений): скан без аутентификации, и каждая защищённая операция по
построению отвечает 400/401/403.

## Реестр персональных данных

| Класс данных | Примеры и хранение | Жизненный цикл по умолчанию | Решение до production |
|---|---|---|---|
| Идентичность и профиль | имя, логин, email, телефон, язык, часовой пояс, аватар и оргединица в PostgreSQL; производные документы поиска в Typesense | Хранятся до авторизованной анонимизации или удаления; поиск — перестраиваемые производные данные | Контролёр/обработчик, цель и правовое основание, порядок запросов субъекта, точный срок, проверка удаления из поиска |
| Аутентификация и безопасность | хеш пароля, хеши сессий и токенов, IP, user agent, попытки входа, события безопасности в PostgreSQL | Истечение и отзыв управляет приложение; сессия перестаёт действовать после 12 ч простоя или 7 дней в запросе активной сессии ([ADR-0034](../adr/ADR-0034-edge-headers-and-session-lifetime.md)). Ночная задача ([ADR-0025](../adr/ADR-0025-retention-and-cluster-cache.md)) удаляет события безопасности через 365 дней, попытки входа через 30, одноразовые коды и коды сброса через 7 дней после истечения, закрытые сессии через 90 дней (`SMC_RETENTION_<NAME>_DAYS`, [приложение](../ops/privacy-and-retention-annex.md#42b-journal-tables)) | Сроки, роли доступа, удержание при инциденте, правила экспорта и удаления |
| Бизнес-содержимое | задачи, комментарии, объявления, записи сущностей, доп. поля и метаданные в PostgreSQL | До авторизованного удаления или политики установки | Классификация, сроки клиента, правила удаления и удержания |
| Задание помощнику и предложение модели (только opt-in) | Введённый пользователем prompt и фиксированное описание формата передаются настроенному LLM-шлюзу; приложение не добавляет записи/файлы автоматически (`S/studio/service/StudioAssistantService.java`) | Сервис не сохраняет prompt/сырой ответ; принятую декларацию пользователь сохраняет отдельным действием. Retention шлюза не определяется приложением | До включения: допустимое содержимое, провайдер, место обработки и его retention; не вводить секреты/ПДн без утверждённого основания |
| Загруженные файлы | исходное имя, MIME, хеш, владелец в PostgreSQL; байты на диске или в S3-совместимом хранилище; файлы импорта и выгрузки до истечения их срока | До авторизованного удаления; дедуплицированные байты удаляются после последней записи владельца | Допустимое содержимое, политика вредоносного ПО, версии и жизненный цикл объектов, стирание и восстановление |
| Записи аудита | изменения, идентификаторы актора и сессии, IP, user agent, детали событий безопасности в PostgreSQL; архив партиций (локально или S3) | Активные партиции по умолчанию 12 месяцев, затем отсоединяются и архивируются; архив хранится 90 дней | Владелец архива, окончательное распоряжение, правовой срок аудита, обзор доступа |
| Резервные копии | зашифрованная копия базы локально и необязательно вне хоста; байтам объектов нужна своя защита | По умолчанию Compose — 14 дней | Утверждённые RPO/RTO, срок, место вне хоста, хранители age-ключа, проверенное восстановление объектов |

У SmartupCMS нет телеметрии и phone-home по умолчанию. Установка под
управлением Smartup может использовать Cloudflare для защиты edge и R2 для
объектов; установка клиента может настроить другого совместимого провайдера.
Провайдеры и места хранения данных записываются в карту данных установки.

## Когда пересматривать модель

Пересматривайте модель при изменении метода аутентификации, публичного
endpoint, права, внешнего провайдера, типа файла, хранилища, поля данных,
правила хранения, топологии развёртывания, механизма модулей или
привилегированного действия оператора. Релиз — NO-GO, пока пробелы установки
выше не получили владельцев и доказательства в
[чек-листе запуска в production](../ops/production-launch-checklist.md).
