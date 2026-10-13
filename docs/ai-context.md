# Контекст SmartupCMS для AI-ассистентов

**Актуализировано:** 2026-10-13

**Назначение:** краткий воспроизводимый handoff для следующей AI-сессии

**Статус:** справочный контекст, не нормативный источник требований

Этот файл помогает быстро восстановить контекст проекта. Он не заменяет
[каноническое ТЗ](technical-specification.md), действующие
[ADR](README.md#authority-tier-2--current-decisions), код, конфигурацию или
результаты проверок. При конфликте зафиксируйте расхождение и проверьте
первичный артефакт, а не дополняйте пробел догадкой.

## 1. Продукт

SmartupCMS — self-hosted **low-code CMS для разработчиков**. Сущность
объявляется один раз на сервере (`EntityDefinition`), а список, форма,
карточка, история, экспорт, массовые действия, пункт меню и названия прав
строятся платформой ([ADR-0019](adr/ADR-0019-low-code-entity-model.md)).
Встроенные модули — пользователи и роли, задачи и проекты, заметки, файлы,
поиск, уведомления, аудит, загрузки данных (`upl` поверх `jobs`, `warehouse`, `units`) — построены на той же
платформе. Одна установка обслуживает одну организацию. Языки интерфейса:
русский (канонический), узбекский и английский; другие администратор
добавляет в редакторе языков.

## 2. Нормативные источники

1. Прямое текущее указание пользователя.
2. [Техническое задание](technical-specification.md) (`FR-*`, `NFR-*`, `AC-*`).
3. Действующие ADR по [индексу документации](README.md).
4. Код, миграции, Compose и автоматические проверки.
5. Активные engineering/operations/security документы.

Датированные аудиты и планы агентов удалены из дерева; история — в git.

## 3. Карта системы

| Область | Реализация |
|---|---|
| Backend | Java 25, Spring Boot 4.1, модульный монолит `apps/server`, пакет `com.smartup24.cms` |
| Web | Angular 22 SPA `apps/web`, UI kit `shared/ui-kit`, компоненты сущности `shared/entity` |
| Общие библиотеки | `libs/core-types`, `libs/platform-common`; публичный API — `libs/platform-api` и `libs/provider-spi` (ADR-0033), тест-кит — `libs/platform-testkit` |
| Данные | PostgreSQL 18, неизменяемые Flyway-миграции (`V###`, манифест контрольных сумм), вторая база для загруженных данных |
| Поиск | Typesense 27.1 — производный индекс, не источник авторизации |
| Файлы | `local_disk` или S3-совместимое хранилище через SPI |
| API | REST `/api/v1`, описание генерируется из кода (`docs/api/openapi.json`), поведение — [docs/api/README.md](api/README.md) |
| Поставка | Docker Compose, отдельный шаг `migrate`, зашифрованный backup, ClamAV в production |

Модули сервера (`com.smartup24.cms.instance.*`) — назначение, таблицы,
области прав и точки входа каждого — в
[карте модулей](architecture/module-map.md); список бизнес-модулей задаёт
`ModuleBoundariesTest.MODULES`, соответствие карты и `package-info.java`
проверяет `ModuleMapTest`. Точки
расширения — в [extension-points.md](architecture/extension-points.md),
порядок работы над модулем — в
[руководстве](guidelines/module-development-guide.md).

## 4. Платформа low-code

- **Реестр полей** (ADR-0016): `QueryList` → `GET /api/v1/query-meta/{code}`;
  фильтр — JSON DSL с группами `{"any": [...]}` и полями-ссылками; keyset-курсор.
- **Сущность** (ADR-0019): `EntityDefinition` → `GET /api/v1/form-meta/{code}`;
  `EntityValidator` проверяет сохранение (422 на поле); `EntityRecords` даёт
  историю, экспорт и `POST /api/v1/entities/{code}/bulk`; `EntityRights` и
  `EntityMenu` — названия прав и `GET /api/v1/entities/menu`.
- **Web:** `smt-entity-form`, `smt-entity-card`, `smt-entity-toolbar`,
  `ui-server-table` + `registryTableConfig`.
- **CLI `cms`** (`tools/cms-cli`, Node.js без пакетов, план 10/10, пункт 6.1):
  `cms module new`, `cms entity new` (объявление, хуки, тест кита, миграции
  таблицы и прав со следующими свободными номерами в манифесте, ключи ru/uz/en,
  порог покрытия, строка карты модулей), `cms entity add-field`,
  `cms migration diff` (через `EntitySchemaDiffTest`; сравнение — то же
  `EntitySchemaCheck`, что и при старте), `cms doctor`; манифест модуля —
  `META-INF/smartupcms/modules/<область>.json` по ADR-0033; повторный
  запуск ничего не меняет, правки рукой не перезаписываются. Результат проверяет
  `scripts/dev/test-cms-cli.ps1` / `tools/cms-cli/scripts/smoke.mjs` (nightly на
  ubuntu и windows), тесты CLI — `npm test` в `tools/cms-cli` (ci).
- **Эталоны и cookbook** (план 10/10, пункт 6.6): [docs/cookbook](cookbook/README.md)
  — рецепт на задачу; эталоны модуля `example` (выключен в поставке):
  справочник `example.products`, документ со строками `example.orders`,
  документ со статусами `example.requests` (хуки, право на поле); модуль вне
  монорепо — `examples/external-module`; свой экран — заметки (`features/notes`).
  Контракт документации — `node scripts/docs/test-docs-contract.mjs` (ci).

## 5. Инварианты

- Авторизация — только на сервере (`@RequiresPermission`, скоуп данных); UI не
  является границей безопасности.
- **Ошибки** — `ApiException` с `ErrorCode`, ключом `error.<модуль>.<имя>` и
  параметрами; ключ есть в ru, uz и en; ответ — `application/problem+json`
  (ADR-0021).
- **API** — DTO в пакете `api` модуля; после изменения контроллера или DTO
  перегенерировать `docs/api/openapi.json` (`-Dopenapi.update=true`) и типы веба
  (`npm run api:types`); 201 + `Location`, 204 объявлены `@ResponseStatus`;
  ломающее изменение — метка `api-breaking` или трейлер `Api-Breaking:` и
  запись в `CHANGELOG.md` (ADR-0022, ADR-0023).
- **Блокировка** — изменение записи называет ревизию (`If-Match`,
  `Revisions.required`); без неё 428, устаревшая — 409 (ADR-0024).
- **Страницы** — растущая коллекция отдаётся `KeysetPage` (реестр полей или
  `TimePage`), `limit` выше максимума — 422.
- **Журналы и кэш** — журнальная таблица объявляет `RetentionPolicy`; имя кэша
  регистрируется в `CacheConfig` (ADR-0025).
- **Код** — комментарии по-английски со ссылками только на ADR, FR/NFR и пункты
  плана 10/10; класс до 400 строк, метод до 60; JSON-колонки через
  `JsonColumns`; `catch` не глотает ошибку ([CODE_STYLE](../CODE_STYLE.md)).
- **Миграции** — после публикации не меняются (`migration-manifest.sha256`,
  новый файл добавляется `-Dmigrations.manifest.append=true`); с V128 —
  правила ADR-0020; DDL и данные в разных файлах; изменение данных помечается
  заголовком `destructive: approved` с причиной и утвердившим.
- **Конфигурация** — продукт `smc.*` / `SMC_*`, хранилище `warehouse.*` /
  `WAREHOUSE_*`; старые имена `dwh` не читаются (установок нет, переходных
  периодов и синонимов не бывает — `AGENTS.md`, раздел 3); класс
  `@ConfigurationProperties` помечен `@Validated`; после изменения настроек —
  `-Dconfig.reference.update=true` для
  [справочника](ops/configuration-reference.md) (ADR-0027).
- **SQL** — только в репозиториях: класс `*Service` запросов не пишет
  (`ServicesRunNoSqlTest`); очередь `jobs` не зависит от `warehouse` (ADR-0030).
- Не коммитить `.env`, секреты, дампы, данные клиентов, `graphify-out/`.
- Русский каталог `apps/server/src/main/resources/i18n/ru.json` — источник
  ключей; `uz` и `en` полные; после изменений — `npm run i18n:sync-ru`.

## 6. Проверки перед пушем

- Сервер: `mvn -B clean spotless:check verify` (Spotless, Checkstyle, Error
  Prone и NullAway, тесты с архитектурными правилами, JaCoCo), затем
  `scripts/quality/test-no-skipped-tests.ps1` (пропущенные тесты Maven,
  `skip`/`only`/`todo` в тестах веба и e2e) и
  `scripts/quality/test-coverage-floors.ps1`; покрытие изменённых строк
  ветки (diff-cover 10.6.0, как в `ci.yml`, порог 80 %):
  `python -m diff_cover.diff_cover_tool apps/server/target/site/jacoco/jacoco.xml --compare-branch=main --src-roots apps/server/src/main/java --fail-under=80`.
- Совместимость публичного API (`libs/platform-api`, `libs/provider-spi`):
  `scripts/api/test-platform-api-compat.ps1`.
- Web (`apps/web`): `npm run lint`, `npm run typecheck`, `npm run i18n:audit`,
  `npm run aria:audit`, `npm run contrast:audit`, `npm run api:audit`,
  `npm run signals:audit`, `npm run comments:audit`, `npm run test:coverage`
  и порог покрытия `node scripts/coverage-summary.mjs`, `npm run build`; после
  изменения API — `npm run api:types`.
- API: `scripts/api/test-api-contract.ps1` (Spectral, свежесть типов веба,
  openapi-diff против базовой ветки).
- E2E (`e2e`): `npm run typecheck`; доступность — `npm run test:a11y`; полный
  прогон на стенде Compose с почтовой заглушкой
  (`docker compose -f docker-compose.yml -f scripts/dev/e2e-mail.compose.yml up -d --wait`)
  — `scripts/dev/test-e2e.ps1` (в CI два шарда, `-Shard N/2`; `-CheckReadiness`
  в конце останавливает postgres и проверяет readiness,
  `scripts/dev/test-readiness-dependency.ps1`).
- Документация и репозиторий: `scripts/docs/test-public-docs.ps1` (каждый ADR
  в индексе, ссылки), `scripts/docs/test-repository-hygiene.ps1`,
  `scripts/architecture/test-unified-boundaries.ps1`, обязательные проверки
  ruleset — `scripts/github/test-rulesets.ps1`.
- CLI `cms`: `npm test` в `tools/cms-cli` (как в CI: `node --test
  'tools/cms-cli/test/**/*.test.mjs'` и `node tools/cms-cli/scripts/smoke.mjs
  --skip-build`); сгенерированный модуль целиком —
  `scripts/dev/test-cms-cli.ps1` (`-SkipBuild` — без сборки Maven).
- Контракт документации (cookbook и руководство по модулям): `node --test
  scripts/docs/docs-contract.test.mjs` и `node scripts/docs/test-docs-contract.mjs`.
- Локальный запуск из исходников (план 10/10, пункт 6.5):
  `scripts/dev/run-local.sh` / `scripts/dev/run-local.ps1` (`up`, `migrate`,
  `down`, `status`; `--demo`, `--detach`, `--search`; порты из
  окружения `DB_PORT`, `SERVER_PORT`, `MANAGEMENT_PORT`, `WEB_PORT`,
  `MAILPIT_*_PORT`, проект Compose `SMC_LOCAL_PROJECT`). Пароль первого
  администратора — в игнорируемом `.local/admin-password`, логи там же; `make`
  вызывает те же скрипты. Проверка «от `git clone` до UI ≤ 10 минут» —
  `scripts/dev/test-onboarding-smoke.ps1` (ночной job `onboarding`), локально
  на своих портах со сносом стенда.
- Коммиты подписываются `git commit -s`; CI (`ci.yml`, `dco.yml`) запускается
  на push в main и на pull request, `nightly.yml` — по расписанию.
- Одна рабочая станция — не больше 2–3 параллельных AI-агентов (владелец,
  2026-10-08); тяжёлые проверки (полный verify реактора, стенды e2e, ZAP,
  Testcontainers, профили `-P*-large`) идут по одной; агента останавливают, как
  только он отчитался. JVM самого Maven ограничена `.mvn/jvm.config` (`-Xmx2g`).

## 7. Точка продолжения — 2026-10-13

**Последовательное закрытие аудитов:** в том же кандидате добавлены проверка
текущих прав FILE/IMAGE при скачивании и повторном прикреплении, обязательный
HTTPS webhook, recovery-алерты 14/20 часов и явный same-host bind профиль
`docker-compose.local-storage.yml`. Receipt алерта остаётся `UNVERIFIED`,
даже когда endpoint принял событие; preflight/failure отчёты отделяют
`automatedStatus` от итоговой приёмки. Восстановление managed установки
требует архивов CMS, DWH и объектов одного комплекта. Исправлены описания
API-токенов (права владельца) и OAuth metadata (SSO-вход не реализован).
Браузерные проверки усилены сверкой скачанных байтов и русского fallback.
Новые локальные проверки ещё не заменяют перечисленные ниже target gates.

**Pre-production:** активный кандидат готовится к DCO-коммиту в ветке
`codex/preproduction-readiness` от main `d406d696`. В нём собраны изменения
RC `aa098672`, staging `26fbf590` и форм без изменения исходных checkout.
Исправлены независимые DB credentials, account/IP login budget, отзыв
доступа существующих SSE-соединений; новые миграции V206–V208. Миграция
отключаемого tasks-модуля — V205, RC V204 не переписана.
Подготовлены Ansible deployment, OPS recovery coordinator с ограничением
scratch и read-back, Alertmanager/blackbox/textfile collector, русский
[handbook](ops/production-handbook-ru.md), [Ansible guide](ops/ansible-deployment.md)
и [launch checklist](ops/production-launch-checklist.md). Результаты текущей
проверки и gap-анализ — локальные audit artifacts, не требования продукта.
Точный SHA и результаты повторного CI фиксируются в отдельном локальном
release report. До подтверждения release gates и target drills статус **NO-GO**.
Реальный staging, SSH/secret provisioning, доставка alert/dead-man,
полный R2 recovery/restore и capacity ещё не подтверждены. Публикация,
merge и внешнее развёртывание не выполнялись и не разрешены.

**Staging после RC `aa098672`:** владелец утвердил конфигурацию, бюджет,
RPO 24 ч / RTO 2 ч и отдельный upload SLO 30/60 с. Ветка
`codex/staging-acceptance` от RC обновляет `NFR-PERF-02`, capacity runner,
HTTP histogram и Prometheus; обычный API сохраняет 300/1000 мс, upload errors
остаются в доступности. Утверждённые значения, шаблоны и незакрытые target
gates — [staging annex](ops/staging-acceptance.md). Требуются новый SHA/образы
и свежие проверки; старый полный CI RC не является проверкой этих изменений.
Staging identity/secrets и реальные drills ещё не готовы; coordinator/cache
guard и короткий alert route подготовлены в текущем кандидате. Закупки, публикация
и merge по-прежнему не разрешены. Исторические результаты ниже сохранены
для трассируемости; текущие результаты писать в отдельный release report.

**История подготовки финального release candidate:** владелец разрешил DCO-коммит
накопленных исправлений и повтор полного локального CI на его SHA; публикация
и merge не разрешены. Результаты ниже относятся к предыдущему незакоммиченному
кандидату. Итоговый отчёт нового прохода должен содержать точный commit SHA,
проверку чистоты checkout, DCO trailers, команды и результаты, включая
ограничения локального воспроизведения. Отчёт хранится отдельно от исходников,
чтобы его запись после проверок не меняла проверенный commit. Staging и
решение о production выпуске по-прежнему не подтверждены.

**P0-06, локальная проверка завершена:** без staging, публикации и merge.
Исправлены checksum/path/full-bundle проверки release verifier, точность
Cosign identity и удаление старого PASS при отказе; 12 регрессий прошли.
Release drill корректно получает ошибку PowerShell и проверяет код вместе
с причиной отказа; в CI добавлены оба набора и полная Git-история этого job.
Свежий полный reactor: 2781 тест/375 suites/8 модулей, 0 failures/errors/skips;
floors, SPI/API и diff coverage 95% успешны. Web после отдельного `npm ci`:
2177 тестов/313 файлов, все audits/build и floors успешны; E2E 69 и a11y 46
прошли без retries. Trivy dependency/runtime gates, Gitleaks всей истории,
ZAP (0 High, прежние предупреждения), миграции/headers/readiness успешны.
CodeQL: прежние 28 Java/13 JS сигналов с теми же fingerprint, Actions 0.
Actions suite использует свежую совместимую JS-базу со всеми 7 workflows:
нативный extractor ограничен локальной Windows policy, её не меняли.
Полный readiness drill, CLI-сборка внутреннего и внешнего модулей, combined
restore, no-default-egress, production upgrade и live API smoke прошли.
Исправлен только диагностический вывод числа OpenAPI-маршрутов; отдельная
проверка строки даёт 171. Onboarding из чистого клона: 175 с из 600,
UI/API/readiness/admin login/16 demo-записей; кеши workstation прогреты.
Runtime fixture совпадает с кандидатом, его commit не является release SHA.
Независимое ревью: 0 Critical/Important, 1 Minor исправлен (fetch-depth);
поздняя правка вывода и документация проверены исполнителем. Evidence содержит
матрицу AC-01…AC-13, UTC/команды/логи и хеш diff; кандидат не закоммичен.
**Production NO-GO:** итоговый SHA/DCO/hosted CI, опубликованные подписи,
provenance/платформы, целевой контур и решения владельца остаются открытыми.
Основной локальный проект на `localhost:4200` продолжает работать.
Подробности и границы — в [плане P0/P1](ops/release-readiness-plan.md).

**P0-05, локальная часть:** staging ещё не готов. В capacity runner исправлены
UTC-окно и ложный PASS на NaN/пустых/недопустимых отрицательных метриках;
пороги k6 включают границу, в том числе нулевой бюджет ошибок. Prometheus
показывает нулевую долю ошибок при API-трафике без 5xx; отсутствующие API-ряды
не заменяются нулём. 22 регрессии runner с настоящим evaluator k6 без сети
и правила 33 алертов прошли; новая регрессия включена в CI. Полный локальный
capacity-guards, 26 тестов метрик/трассировки и 13 интеграционных тестов
scrape/S3/ClamAV прошли. Также прошли все 7 тестов четырёх больших профилей:
UPL по 1 млн строк в heap 512 МиБ (пик 152/335 МиБ), импорт 10 000 строк
за 22,9 с, очистка 1 млн raw-строк за 24 мс, аудит 50 млн строк с p95
8/8/<1 мс для списка/истории/итогов. Всего 143 серверных теста без ошибок
и пропусков. Большие профили используют тестовый PostgreSQL 14.22 без
durability; их замеры не являются ёмкостью production PostgreSQL 18.
SLO-документ приведён к NFR-PERF-02: только SSE исключён из задержки API,
рекомендация исключать медленные загрузки/выгрузки удалена.
Архитектура приложения и её API не менялись.
Нагрузка целевой установки, 4h soak, согласованные пороги насыщения и
доставка алерта оператору по-прежнему требуют staging и не приняты.

**Продолжение P0-03/P0-04:** владелец подтвердил, что целевой staging ещё не
готов. На отдельном локальном контуре текущий JAR прошёл реальный ClamAV
1.5.4: чистый upload/download и восстановление после отказа успешны; EICAR
и ZIP отклонены с 422, остановленный scanner — 503, метаданных/байтов и
остатков карантина после отказов нет. Локальные security headers прошли;
опубликован только web на loopback, это не доказательство target HTTPS.
Далее P0-04 выявил и исправил потерю UTC/формата даты при чтении manifest и
отсутствие восстановления ролей до проверки CMS/Flyway. Используется
существующий `backup-bootstrap`; модель ролей сохранена. Расширенный
`test-recovery.ps1` прошёл: обе БД, объекты, app/migrator permissions,
checksum/missing-key/future-date отрицательные случаи. Полная схема
кандидата также восстановлена с совпадением контрольных строк и DWH-данных,
двух объектов и их checksum, без missing/orphan; локальный RTO 18 с на
маленьком наборе не является production SLO. Финальные 42 теста повторного
исполнения прошли без failures/errors/skips: сбой после COPY без дубликатов,
lease takeover/fencing, стабильные идентификаторы повторной доставки у
HTTP-получателя и почтового провайдера. Production configuration и managed
acceptance contract прошли. Recovery-тест сам собирает образы репозитория.
Полный reactor/web/E2E/CodeQL из предыдущего прохода не запускались повторно:
изменены восстановительные скрипты и тесты. Target S3/R2, TLS/edge,
alert delivery, утверждённые RPO/RTO и нагрузка остаются открытыми.

**P0-03, локальные исправления:** на той же ветке исправлены выдача готового экспорта и
идемпотентного ответа после отзыва доступа. Сохранённые копии проверяют
текущий контекст прав, доступ к строкам и состояние модулей; экспорт —
также видимые колонки. V204 хранит зависимости записей ответа. Пустая
необязательная REF очищается без HTTP 500. Свежий review выявил лишнее
требование `view` при replay назначений IAM и комментариев; два теста
воспроизвели 409 и после исправления прошли. Целевой набор — 75/75,
включая миграционные правила. Первый полный прогон нашёл отсутствие
таймаутов новой V204; заголовок исправлен, контрольная сумма заново
добавлена штатным `MigrationManifestTest` (старые миграции не изменены).
Финальный `clean spotless:check verify` успешен: 2780/2780, 375 suites,
все 8 модулей, без ошибок и пропусков; добавлено 12 тестов. Diff coverage
текущей ветки — 189/198 строк (95%), пороги не ослаблены. API-контракт,
CLI (30 тестов и smoke), docs/hygiene/rulesets прошли. Trivy 0.70.0 по
новому SBOM (237 компонентов) не нашёл исправляемых HIGH/CRITICAL.
CodeQL Java security-extended: 124 запроса, прежние 28 сырых сигналов,
новых нет; один log sink сместился по строке с тем же fingerprint.
Новые исходники включены в базу; ограничение build-mode none/inferred
classpath сохраняется. Web/E2E/ZAP и прочие незатронутые проверки P0-02
в этом шаге заново не запускались. ClamAV на целевой установке, её
HTTPS/headers/внутренние порты и CI финального SHA не подтверждены.

**Рабочий пакет подготовки релиза:** ветка `codex/release-readiness` от
`d406d696`, пока не в `main`. По поручению владельца создан
[план P0/P1](ops/release-readiness-plan.md), привязанный к AC-01…AC-13.
Первый пакет исправляет инструкции outbox/секретов и локализацию статического
каталога прав: существующие `formNameKey`/`actionNameKey`, 76 ключей ru/uz/en,
сохранение приоритета названий сущности. Экран ролей и панель персональных
прав обновляют подписи при смене словаря, не сбрасывая несохранённые права.
Миграций, новых API-полей и изменений авторизации нет.

**Продолжение P0-02:** исправлены подтверждённые ошибки backup regression
(чтение приватных файлов тем же UID), двух web i18n-контрактов, E2E-проверки
ротированного секрета (20 символов вместо 28) и a11y fixture server
(нормализация Windows-пути `WEB_DIST`). Архитектура и пороги сохранены.
Полный `clean spotless:check verify` в отдельном worktree: 2768 тестов без
пропусков, coverage floors и SPI mutation gate успешны. После разрешения
владельца установлены необходимые зависимости CI. На Node 24.21.0: web
2177/2177, floors, build и audits успешны; после исправления логирования
diff-cover 10.6.0 — 100% (61/61), включая новые Java-файлы.
Штатные E2E-шарды на свежих БД и образах из исходников — 36+33=69,
a11y — 46/46, Playwright Chromium 153.0.8010.12, без retries и карантина.
Release config, backup regression, readiness, headers и observability успешны.
API contract и CLI 30/30 + smoke без сборки повторены; CLI также проверен
в Linux (Alpine). Штатный SBOM CycloneDX 2.9.1: 237 компонентов, версии
совпадают с набором полного backend-прогона.
Подробная матрица и ограничения — в [плане P0/P1](ops/release-readiness-plan.md).
Trivy 0.70.0 для SBOM/npm и 0.74.0 для 6 runtime-образов на свежих базах:
0 HIGH/CRITICAL с исправлением; dependency scans включают dev dependencies
и стандартный поиск секретов. ZAP web/API: 0 High и FAIL, 1 Medium,
7 Low, 6 Informational строк без новых подавлений; Medium по CSP описан в ADR-0034.
25 CodeQL-сигналов логирования разобраны по источникам и общей границе вывода
(24 URI, один `Accept`). Подтверждён CRLF из декодированного числового параметра
через MVC и `GlobalExceptionHandler`; точный URI/header-триггер каждого сигнала
не доказан. Dev-консоль теперь использует штатный `SafeTextLogFormatter`:
маскирование компонентов, затем экранирование всего события, включая стек.
ECS дополнительно экранирует U+2028/U+2029. API и архитектура сохранены.
14 новых случаев и соседние тесты — 64/64; исходный вывод давал 4 падения
из 8 проверок. Для всех 25 сигналов сохранены отдельные отчёты Codex Security.
Подавлений CodeQL нет; повторный чистый backend прошёл 2768/2768, 372 suites,
все 8 модулей, no-skips и coverage floors успешны. Свежая Java-база CodeQL
включает новые файлы; все 124 запроса успешны, те же 28 сигналов, новых нет.
Локальный triage 25 log-injection сигналов закрыт проверенной границей вывода
на исправленном кандидате; raw-предупреждения сохранены. Это не доказательство
25 отдельных атак или 25 ложных срабатываний исходного кода. После нехватки
Java heap при 2 ГБ повтор анализа той же базы с 1 потоком и 6 ГБ успешен.
`build-mode: none` сохраняет предупреждение об inferred classpath.
Первый полный прогон дал один `BindException` PostgreSQL-соединения в
`AuthenticationGenerationHttpTest`; отдельное сравнение — 35/35 на чистом
`main` и 35/35 на кандидате, без retries; повторный полный прогон также зелёный.
Причина коллизии сокета не установлена; первое падение сохранено в результатах.

**Открыто в P0-02:** исходный лог падения оргструктуры пока не получен.
Keyboard/discard-сценарий прошёл 24 раза в диагностическом Chrome 154
(8 с CPU ×6) и ещё 9 раз в штатном Playwright Chromium, без retries.
Причина прежнего сбоя не установлена и исправление не объявлено.
Rulesets GitHub по read-only API пусты; classic protection без авторизации
неизвестна. Последний проверенный CI `main` (run `38107212537`) красный
на backup/restore, E2E пропущены; это не прогон текущего diff. Нужны итоговый
SHA и CI на нём. P0-02 и разрешение production остаются открытыми.
Изменения не закоммичены; QA Compose project остановлен, пользовательский
локальный сервер не останавливался.
Обновление локального графа не выполнено: Python-модуль `graphify` отсутствует.
**Текущая рабочая ветка `codex/framework-forms` (не влита):** по решению
владельца поставка — ядро + отключаемые tasks/projects, notes и upl.
Одностраничный [стандарт форм](guidelines/forms-one-page.md) и подробный
[forms-ux-standard](guidelines/forms-ux-standard.md) описывают одинаковое
поведение общего runtime и специальных редакторов. Исправлены адресные
ошибки полей, сохранение ввода после отказа, блокировка повторного сохранения
и закрытия, даты таблиц и перенос подписей. Удалены неиспользуемый
`@angular/material` и дублирующий API-клиент настроек модулей. Данные и
прикладные возможности сохранены. Отключение модуля закрывает custom API,
entity runtime и idempotency replay; принятые UPL jobs завершаются.
Области `mf`, `notify`, `webhook` явно относятся к системным манифестам.
Три специальных вопроса об отмене изменений IAM заменены общим confirm:
безопасный начальный фокус, сохранение черновика при возврате и отмена
устаревшего вопроса при отзыве прав/уничтожении экрана. Семь дублирующих
ключей переводов удалены из ru/uz/en.
Повторный проход: общий диалог начинает клавиатурный обход с содержимого,
сохраняя крестик в заголовке; Markdown-поле имеет одно доступное имя.
Заметки и настройки поиска используют числовые даты и 24-часовое время.
Проход меню: закрытые разделы исключены из клавиатурного обхода и имеют
различимые имена; компактное меню отмечает текущий маршрут, обновляет
открытый раздел из действующего каталога, поддерживает стрелки и возврат
фокуса по Escape. Карточка задачи сохраняет активный пункт. Мобильное меню
удерживает фокус и принимает клики поверх фона; переход фокусирует
содержимое. Граница 767/768 px согласована, раскрытая панель на планшете
занимает свою ширину. Ссылка настроек без tab открывает панель по умолчанию.

Дополнительный проход форм: [реестр](guidelines/forms-catalog.md) связывает
девять сущностей, все маршруты и вложенные редакторы с исходниками и
применимыми операциями; компоненты и пользовательские формы не смешиваются
в одном счётчике. Исправлены отказ загрузки настроек уведомлений (форма
больше не подменяет сохранённые настройки значениями по умолчанию), pending,
возврат фокуса и мобильная матрица трёх каналов. Редактор переводов использует
общие кнопки, native form submit и подтверждение выхода; во время сохранения
ввод/импорт/закрытие блокируются. Переход между вкладками и со страницы
защищает языковой черновик, выбранная вкладка соответствует содержимому.
Удалены собственные стили кнопок и три неиспользуемых ключа переводов;
baseline ключей уменьшен на `common.saving`.

На этом дополнительном проходе: 2 245 web-тестов в 315 файлах, все web audits,
пороги покрытия и build; 74 browser/a11y-сценария в двух темах, включая
14 сценариев уведомлений и переводов; 3 серверных теста `MdI18nCatalogTest`.
Ручная проверка — production build с mock API на 360×800 и 1280×800:
черновик сохраняется при отказе от выхода, каналы не обрезаются, фокус
возвращается, ошибок/предупреждений в консоли нет. Это не ручная приёмка
всех комбинаций прав и данных. Graphify по-прежнему недоступен (`No module
named graphify`); новый инструмент не устанавливался.

При повторе real E2E получено точное падение `org-structure.spec.ts:529`:
после POST 201 повторный GET дерева оставался в состоянии загрузки.
Убрана гонка порядка действий теста: сетевой перехват снимается после
появления сохранённой строки, а не во время запуска GET. Проверка строки,
таймаут и запрет retries сохранены; продуктовое дерево не менялось.
После изменения 10 последовательных повторов всей группы — 70/70.
Внутренняя причина зависания браузерного запроса не доказана: упрощённый
HTTP/Playwright reproducer прошёл 100 повторов без отказа.
Финальные оба E2E-шарда после этого изменения прошли: 37 + 34 сценария,
каждый на новой отдельной Compose-базе, без retries и quarantine.
Временные стенды остановлены; исходный пользовательский стенд не менялся.
Повторены типизация E2E, docs contract, public docs, hygiene и проверка
полного рабочего diff на секреты. Это проверки рабочей ветки, не нового RC.

Эта ветка начинается от main `d406d696`, а RC `aa098672` и staging-работа
`26fbf590` сохранены отдельно. Не считать её новым RC. При последующем
объединении разрешить конфликт номера новой невыпущенной миграции
`V204__tasks_optional_module.sql` с RC `V204__idempotency_record_access.sql`,
не изменяя выпущенные миграции; затем проверить единый SHA. Публикация и
merge владельцем не разрешены. Локальная инвентаризация и результаты
проверок находятся в игнорируемой `.local/forms-review/`; это рабочие
доказательства, а не публичная спецификация.

Проверки до дополнительного прохода реестра 2026-10-12: полный Maven reactor — 2 837 тестов,
web — 2 239 тестов, a11y — 60, оба E2E-шарда на отдельных чистых базах —
37 + 34, без retries и quarantine. Пороги покрытия, web audits, build,
OpenAPI/types, docs/hygiene/rulesets и CLI unit/smoke прошли; CLI smoke
выполнен с `--skip-build`. Diff coverage серверных изменений — 100%.
Это не повтор release CI на объединённом SHA и не staging-приёмка.
После унификации IAM confirm, фокуса диалогов, дат и Markdown-подписей
повторены web/a11y и оба E2E-шарда на новых чистых базах; после очистки
каталогов — 15 серверных тестов переводов. Все прошли.
Для меню добавлены 14 браузерных сценариев в двух темах. После последнего
уточнения мобильной вёрстки повторены 87 unit-тестов оболочки, 14 сценариев
меню, типизация, lint и сборка; второй E2E-шард уже на этой сборке. Первый
шард выполнен до последнего уточнения класса компактной панели. Backend
в проходе меню не менялся; Maven относится к предыдущей проверке форм.

Тег `gate-2026-10-11` отмечает состояние main после работ 2026-10-10…11:
стандарт форм ([forms-ux-standard](guidelines/forms-ux-standard.md),
утверждён владельцем 2026-10-10; базовый список `forms-audit` пуст — новое
нарушение роняет `npm run lint`); правки по визуальной проверке (заголовок
сущности из `form-meta.titleKey`, названия областей прав
`iam.roles.area.<область>`, запрет внутренних номеров работ в текстах
каталога, шапки списков по `ui-page-header`); формы множественного числа
(`<ключ>.one|few|many|other` + `count`); названия модулей из каталогов
(`<код>.module.name`, поле `name` манифеста удалено); один активный пункт
меню (самый длинный маршрут), «Announcements» / «System status», иконки
чекбоксов кита; опрос заданий поиска уважает `Retry-After`, чтения
управления поиском — в бюджете пользователя (ADR-0008 §2.2); все 8
обновлений Dependabot (Maven 3.10.0, Angular 22.2.2, jsdom 30 и др.);
hardened-образ ClamAV (`deploy/images/clamav`, шесть образов релиза).

**Открыто в main:** локализация форм/действий `MdFormCatalog` закрывается
рабочим пакетом выше; заголовок «Новая запись» вместо «Новая задача» требует названия
сущности в единственном числе в `platform-api` (решение владельца);
прежний сигнал e2e `org-structure.spec.ts:529` относится к старому прогону;
в текущем кандидате устранён тестовый перехват после ожидания строки, оба
шарда прошли без retries (исходная ошибка Chromium не установлена);
администратор GitHub применяет `.github/rulesets/main.json` (нужен
`gh auth login`; после этого прямой push в main закрыт); Observatory A+ —
шаг релиза; гейт 4 — три недели зелёного CI. Параллельно не больше 2–3
агентов, тяжёлые проверки по одной (`AGENTS.md` §6).

### Предыдущая точка — 2026-10-10

Решения владельца продукта 2026-10-10 выполнены веткой `claude/leftovers`
поверх main `2f0233da` (`CHANGELOG.md` — «Owner decisions of 2026-10-10»):
пороги задержки API p95 ≤ 300 мс и p99 ≤ 1 с утверждены (`NFR-PERF-02`,
[SLO](ops/slo.md)); экспортёры хоста и PostgreSQL — необязательный overlay
`deploy/observability/docker-compose.observability.yml`, правила
`rules/infrastructure.yml`, runbook RB-10 и RB-11, роль мониторинга
создаёт оператор (SQL в SLO, раздел 7); запрос комментария задачи принимает
только `textMarkdown` (`commentMarkdown` удалён, `Api-Breaking`).

Пункты глубокого изучения после фазы 7 закрыты веткой интеграции
`claude/open-int` поверх main `2e9c8619` (2026-10-09; раздел «Фаза 7 —
статус» плана, `CHANGELOG.md` — «Open items after phase 7»): уведомления и
выгрузка задач на языке получателя, кириллица в литералах запрещена и в
`libs/*`; цикл `ms.task` ↔ `ms.notify` разорван событием
`TaskDeadlineApproaching`; напоминание одно на кластер (advisory-блокировка);
SSE на всех узлах через `NOTIFY` на `smc_cache`; секреты без `v1:`
останавливают старт (`StoredSecretsCheck`, V203 очищает заглушки SSO; старый
локальный стенд — `run-local.ps1 down -Volumes`); правила модулей проверяют
импорты (`ModuleSourceBoundariesTest`), `config` и инфраструктурные модули,
чужой SQL — в любом классе с SQL и в `config` (`MdInstanceService`); `MdPref`
и `KauthPref` — в `api`; пороги покрытия webhook, mf, ms.notify, ms.task
подняты.

Фазы 0–7 [плана 10/10](plan-10-10.md) выполнены. Фаза 7 и исправления
платформы влиты веткой интеграции `claude/p7-int` поверх main `56609c90`
(2026-10-08): JSON-логи ECS с маскированием и строгим `traceparent`
(`X-Client-Code` убран), трейсинг OpenTelemetry выключен по умолчанию (span
JDBC, комментарий в SQL, трасса вебхука), гистограммы и метрики,
[SLO](ops/slo.md), правила и дашборды `deploy/observability`, runbook
RB-01…RB-09, заголовки nginx и сроки сессий (ADR-0034, V202), модель угроз
2.0 и лимиты xlsx/ClamAV, ZAP baseline в e2e, секции `raw.rows` по загрузке
(миграция pg-dwh V004); платформа — 422 `entity_state_locked`, проверка значений хуков,
`EntitySqlBoundariesTest`, автономный `--external`.

**Следующее** (раздел «Фаза 7 — статус» плана): онлайн-проверка Mozilla
Observatory A+ — шаг релиза; строки журнала при старте без `trace_id`
(записано); HIGH в образах экспортёров ждут пересобранных upstream-релизов
(SLO, раздел 7); открытые критерии ревью фаз 0–6. Гейт 4: все строки таблицы
«Что значит 10/10» зелёные в CI три недели подряд. Администратор GitHub
применяет `.github/rulesets/main.json` заново (обязательная проверка
`observability`).
Правила для всех AI-ассистентов — в [`AGENTS.md`](../AGENTS.md), карта модулей —
в [module-map.md](architecture/module-map.md).

- **Ревью фаз 0–6** (2026-10-03, исправления влиты веткой `claude/rf-int`
  2026-10-07 поверх main `ca7efde7`): см. раздел «Ревью фаз 0–6» плана и
  `CHANGELOG.md`. Вебхук: каталог `GET /api/v1/webhooks/events` (`*`, события
  сущностей, действия и переходы), 422 `error.webhook.event_unknown`, подпись
  `X-Signature-SHA256` от `<timestamp>.<тело>`; `/api/v1/openapi.json` — только
  вошедшим; `ProductionStartGuard` (нужен `SMC_PUBLIC_URL` вне dev/test); один
  `EntityGate` на все пути сущности (выключенный модуль — 404); ключи
  `error.fnd.*` стали ключами модулей; права аудит-партиций — V200. Профиль
  `devtools` одобрен владельцем продукта (2026-10-07). Администратор GitHub
  должен заново применить `.github/rulesets/main.json` (обязательные проверки
  CLI `cms`).

- **Фаза 5** (2026-10-03, ADR-0032): общий runtime сущностей
  `/api/v1/entities/<код>` — объявление `EntityDefinition` и хуки вместо
  контроллера, сервиса и репозитория; типы полей, документы со строками и
  статусами, импорт, отчёты, поиск и вебхуки по возможностям; 9 сущностей на
  модели; тест-кит `EntityContractTestKit` (пункт 6.2) у каждой сущности.
- **Фаза 6, 6.3 и 6.4** (ADR-0033): публичный API — `libs/platform-api`
  (`com.smartup24.cms.platform.api..`, `@PlatformApi`, japicmp против 1.0.0,
  `scripts/api/test-platform-api-compat.ps1`); манифест модуля
  `META-INF/smartupcms/modules/<код>.json` (поля `code`, `version`,
  `minPlatform`, `dependencies`, `areas`, `configuration`, `migrations`,
  `messages`; неизвестное поле останавливает старт; название модуля — ключи
  `<код>.module.name` (обязателен в ru/uz/en) и `<код>.module.description`
  в каталогах, 2026-10-11); версия модуля — из манифеста, колонки
  `md_installed_modules.version` нет (V196); сравнение объявлений со схемой —
  `common.entity.EntitySchemaCheck` (`EntitySchemaGate` при старте,
  `EntitySchemaContractTest` в сборке); кит — артефакт `platform-testkit`;
  пример модуля вне монорепо — `examples/external-module`.
- **Фаза 6, 6.1:** CLI `cms` (`tools/cms-cli`) заменил `create-module.ps1`;
  генерирует под контракт 6.3/6.4. Проверки — `node --test` в `tools/cms-cli`,
  `scripts/dev/test-cms-cli.ps1`.
- **Фаза 6, 6.6:** [cookbook](cookbook/README.md) — 17 рецептов на эталонах
  `example.products`, `example.orders`, `example.requests` (V197, V198) и
  `examples/external-module`; фрагменты кода сверяет с файлами
  `scripts/docs/test-docs-contract.mjs`. Кит выдаёт `view` на цели ссылок и
  снимает с «пользователя без прав полей» права полей, даже если это действия
  формы. Тесты CLI считают номер миграции от манифеста.
- **Фаза 6, 6.5:** `make dev`, `scripts/dev/run-local.{sh,ps1}`, профиль
  `demo`, `.devcontainer`, `.editorconfig`, ночной job `onboarding`. Очередь
  заданий тикает только после `ApplicationReadyEvent` (гонка с созданием
  первого администратора). Профиль `devtools` проверен: опциональная зависимость
  `spring-boot-devtools` работает при старте через `make dev` / `run-local.ps1 -DevTools`.

- **Фаза 3** прошла ревью качества; её долги закрыты: `NOT_YET_LOCKED` и
  `NOT_YET_PAGED` удалены, хранилище замороженных нарушений ArchUnit пусто,
  «чужой SQL» запрещён (опубликованные представления, ADR-0026), комментарии на
  английском (выпущенные миграции заморожены контрольной суммой).
- **Фаза 4:** настройки `smc.*` / `warehouse.*` (ADR-0027); коды прав по
  модулю (ADR-0028); схема БД и шифрование секретов
  `SMC_SECRETS_KEY` (ADR-0029); `fnd` разделён на `jobs`, `warehouse`, `units`
  (ADR-0030); модуль вебхуков — `webhook`; ключи переводов
  `<module>.<screen>.<element>` (ADR-0031); старое имя продукта осталось только у хранилища
  (cookie `SMC_SESSION`, токены `smc_`, `type` ошибки — URN, метрики `smc`).
- **Переходные периоды отменены (2026-10-01):** установок у клиентов нет,
  поэтому старые имена конфигурации, псевдоним `fnd.migration.MigrateMain`,
  cookie и префикс токенов со старым именем, старые ключи браузера и старые
  коды прав больше не принимаются (ADR-0027 §4, ADR-0028 §5, ADR-0030 §5).

Известные пробелы платформы перечислены в
[extension-points.md](architecture/extension-points.md#7-известные-пробелы):
сверяйте их с фазой 5: часть пробелов закрыта общим runtime (ADR-0032).
