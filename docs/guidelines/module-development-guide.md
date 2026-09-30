# Разработка модулей SmartupCMS

**Версия:** 2.2

**Обновлено:** 2026-10-01

**Основание:** [каноническое ТЗ](../technical-specification.md),
[ADR-0014](../adr/ADR-0014-unified-open-source-runtime.md) и
[структура монорепозитория](../architecture/monorepo-structure.md).

У приложения два исходных корня:

- `apps/server/src/main/java` — Spring Boot modular monolith;
- `apps/web/src/app` — Angular SPA.

Новый runtime или параллельный application root не добавляется без отдельного
принятого ADR. Flyway SQL и runtime-конфигурация остаются ресурсами сервера, а
не третьим приложением.

## Серверный модуль

Размещайте новую функцию в существующей предметной области либо создавайте
отдельный предметный пакет с явной границей. Внутри модуля роли разделяются так:

1. **Controller** разбирает HTTP-контракт, валидирует вход, требует серверную
   авторизацию и делегирует use case. Он не содержит транзакционной бизнес-логики
   и прямого SQL.
2. **Application service** владеет use case, бизнес-инвариантами и границей
   транзакции. Он координирует репозитории, provider interfaces и события.
3. **Repository или adapter** владеет I/O: SQL, внешним HTTP, объектным
   хранилищем, mail, SMS или messenger. Он не принимает решение о праве
   пользователя на бизнес-операцию.
4. **DTO и domain records** не раскрывают секреты и не протаскивают
   provider-specific типы через публичную границу модуля.

Cross-module интеграция выполняется через явный интерфейс или событие. Соседний
модуль виден только через его пакеты `service` и `api`; импорт его
`repository`, `controller` и любых других пакетов запрещён
(`ModuleBoundariesTest`: старые нарушения заморожены и только убывают).
Контроллер не видит пакет `repository` даже своего модуля. Общие value/error contracts помещаются в `libs/core-types`, общая
backend-инфраструктура — в `libs/platform-common`, а интерфейсы внешних
провайдеров — в `libs/provider-spi`. Общая библиотека не зависит от сервера.

Любой защищённый endpoint использует `@RequiresPermission`; UI-проверка лишь
улучшает UX и не заменяет серверную авторизацию. Ошибки возвращаются в общем
Problem Details формате ([ADR-0021](../adr/ADR-0021-error-model.md),
[как ведёт себя API](../api/README.md)).

## Серверные правила

Правила фазы 3 плана 10/10 проверяются сборкой; генератор модуля им следует.

| Правило | Как | Основание и проверка |
|---|---|---|
| Ошибка | `ApiException` с `ErrorCode`, ключом `error.<модуль>.<имя>` и параметрами: `ApiException.notFound(ErrorCode.NOT_FOUND, "error.note.not_found", Map.of("id", id))`; ключ — в каталогах ru, uz и en (`apps/server/src/main/resources/i18n`); предложений в коде нет | [ADR-0021](../adr/ADR-0021-error-model.md); `ErrorTextsTest`, `ErrorModelTest` |
| DTO | запросы и ответы — records в пакете `api` модуля; контроллер не видит `repository` | [ADR-0022](../adr/ADR-0022-openapi-from-code.md); `ModuleBoundariesTest` |
| Описание API | springdoc строит его по контроллерам; после изменения контроллера или DTO — `mvn -B test -pl apps/server -Dtest=OpenApiContractTest -Dopenapi.update=true`, затем в `apps/web` — `npm run api:types` | [ADR-0022](../adr/ADR-0022-openapi-from-code.md); `OpenApiContractTest`, `scripts/api/test-api-contract.ps1` |
| Статусы | создание — `201` и `Location` (`Created.at(...)`) с `@ResponseStatus(HttpStatus.CREATED)`; удаление — `204` с `@ResponseStatus(HttpStatus.NO_CONTENT)`; переключатель принимает состояние (`PUT …/pin {pinned}`) | [ADR-0023](../adr/ADR-0023-uniform-rest.md); `ResponseStatusDeclaredTest`, Spectral |
| Блокировка | таблица с `revision`; ответ — record, реализующий `Revisioned` (заголовок `ETag`); `PUT`/`PATCH` принимает `@RequestHeader(Revisions.IF_MATCH)` и передаёт `Revisions.required(ifMatch)`; репозиторий пишет `where id = :id and revision = :expected` и на пустой результат бросает `Revisions.conflict()`: без ревизии — 428, устаревшая — 409 | [ADR-0024](../adr/ADR-0024-optimistic-locking.md); `ChangesNameTheirRevisionTest` |
| Страницы | растущая коллекция — `KeysetPage`: список реестра полей (`QueryList`, ADR-0016) или `TimePage` вне реестра; `limit` выше максимума — 422; для таблицы без предела — `QueryList.withEstimatedTotal()` (`totalExact: false`) | план 10/10, пункт 3.5; `CollectionsArePagedTest` |
| JSON-колонки | `JsonColumns` (`common.json`) с общим `ObjectMapper`; своих `toJson`/`parseJson` нет | план 10/10, пункт 3.11; Checkstyle |
| Журналы | таблица, которая растёт с каждым событием, объявляет бин `RetentionPolicy` (имя, таблица, условие с `:cutoff`, срок по умолчанию); срок — `smc.retention.days.<имя>` | [ADR-0025](../adr/ADR-0025-retention-and-cluster-cache.md); `RetentionJobIntegrationTest` |
| Кэш | имя кэша регистрируется в `CacheConfig`; очистка доходит до всех узлов после коммита | [ADR-0025](../adr/ADR-0025-retention-and-cluster-cache.md) |
| Размер | класс до 400 строк, метод до 60 | план 10/10, пункт 3.10; Checkstyle |
| Комментарии | по-английски; ссылки — на `ADR-NNNN`, `FR-…`/`NFR-…`, «plan 10/10, item N» | [CODE_STYLE](../../CODE_STYLE.md), §2.1.2; `CommentLanguageTest` |
| Миграции | правила именования и типов с V128; DDL и данные в разных файлах; новый файл — в манифест: `mvn -B test -pl apps/server -Dtest=MigrationManifestTest -Dmigrations.manifest.append=true` | [ADR-0020](../adr/ADR-0020-database-naming.md), [руководство](database-migrations.md); `MigrationLintTest`, `MigrationFileRulesTest`, `MigrationManifestTest` |

## Сущность как объявление: чек-лист

Запись, которую пользователь создаёт, ищет и меняет, объявляется один раз
([ADR-0019](../adr/ADR-0019-low-code-entity-model.md)). Каркас даёт
`scripts/dev/create-module.ps1 -ModuleName <код> -ModuleTitle "<Название>" [-TitleEn … -TitleUz …]`:
две миграции (таблица и данные), пакет `api` (ответ и запросы), репозиторий,
список, объявление, сервис и контроллер по серверным правилам выше, ключи
ошибки, меню и подписей в каталогах ru/uz/en. В конце он печатает, что
осталось сделать руками. `scripts/dev/test-create-module.ps1` проверяет, что
результат генератора собирается, проходит архитектурные тесты и стартует.
Образец в коде — заметки (`ms.note`).

1. **Миграция:** таблица со стандартными полями (`attributes jsonb`, аудит
   создания и изменения, `revision`) по [ADR-0020](../adr/ADR-0020-database-naming.md);
   отдельным файлом — права в каталоге, выдача системным ролям, модуль в
   `md_installed_modules`; оба файла — в манифест миграций.
2. **Список реестра** (`<Prefix><Name>Query`): `QueryList` с полями, их SQL,
   фильтрами, сортировкой и поиском (ADR-0016).
3. **Объявление** (`<Prefix><Name>Entity`): `EntityDefinition` — поля формы и
   их правила, секции, действия с правом каждое, названия права для матрицы
   (`EntityRights`), пункт меню (`EntityMenu`), возможности
   (`HISTORY` c `auditTable`, `EXPORT`, `SAVED_VIEWS`, `BULK` с действием
   `delete`, `CUSTOM_FIELDS` с типом сущности) и бин `EntityRecords`: видимость
   записи в скоупе зрителя, страница списка, удаление одной записи.
4. **Сервис** проверяет каждое сохранение `EntityValidator.check` по
   объявлению (при изменении — частично) и пишет аудит в `auditTable`.
5. **Контроллер** — страница списка, чтение, создание (201 + `Location`),
   изменение (`If-Match`), удаление (204); на каждом методе
   `@RequiresPermission` с формой и действием из объявления. Запросы и ответ —
   из пакета `api`.
6. **Переводы** в `apps/server/src/main/resources/i18n` (ru, uz, en):
   `nav.<код>`, подписи полей и вариантов, ключи ошибок; затем
   `npm run i18n:sync-ru`.
7. **Экран:** маршрут в `app.routes.ts`; форма — `smt-entity-form`, просмотр —
   `smt-entity-card`, виды, экспорт и массовое удаление — `smt-entity-toolbar`;
   кнопки — по `actions` из `form-meta`, а не по своим проверкам прав. Пункт
   меню появится сам (`GET /entities/menu`).
8. **Проверки:** `EntityActionPermissionContractTest` сверяет действия
   объявления с `@RequiresPermission`, `MdFormCatalogTest` — что у каждой пары
   права есть название; объявление без того, что обещают его возможности, не
   даёт приложению стартовать. Тесты модуля — как у заметок
   (`MsNoteControllerTest`, `MsNoteIntegrationTest`). Новый модуль получает
   строку порога покрытия в `apps/server/coverage-floors.csv` (без неё
   `scripts/quality/test-coverage-floors.ps1` падает) и своё имя в
   `ModuleBoundariesTest.MODULES` с префиксом таблиц в `ownerOf`, чтобы
   границы проверялись и для него.

Не нужно: записи в `MdFormCatalog`, свой источник истории, свой экспортёр,
свой endpoint массовых действий, пункт меню в `app-shell.models.ts`.

### Экран сущности: пример

Форма целиком приходит с сервера; экран только загружает `form-meta`, держит
значения и сохраняет. Эталонный экран — `apps/web/src/app/features/notes`:
новый экран копирует его устройство, а не старые фичи.

| Файл | Что в нём |
|---|---|
| `notes.api.ts` | типизированный сервис данных: модель записи и все запросы фичи; компонент не зовёт `ApiService` сам |
| `notes.component.ts` + `.html` | экран: `rxResource` для формы, метаданных списка и первой страницы; `linkedSignal` для догружаемых страниц; `OnPush`; `@if`/`@for` |
| `note-card.component.ts` | одна запись: `input()`/`output()`, действия по `actions` из `form-meta` |
| `note-form-dialog.component.ts` | создание и правка через `smt-entity-form`; экран создаёт диалог на одно открытие |
| `*.spec.ts` | по спеке на компонент: загрузка, пусто, ошибка, права, сохранение, отказ сервера |

Удаление подтверждается общим `SMTModalService.confirm()` с `action`, а не
своим диалогом. Сокращённо:

```ts
@Injectable({ providedIn: 'root' })
export class InventoryApi {
  private readonly api = inject(ApiService);
  // revision — ревизия загруженной записи: без If-Match сервер ответит 428, устаревшая — 409
  save(id: number | null, payload: Record<string, unknown>, revision?: number): Observable<Item> {
    return id === null
      ? this.api.post<Item>('/inventory', payload, { notifyError: false })
      : this.api.put<Item>(`/inventory/${id}`, payload, { notifyError: false, ifMatch: revision });
  }
}

@Component({
  selector: 'app-inventory',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [SMTEntityFormComponent, SMTButtonComponent, TranslatePipe],
  templateUrl: './inventory.component.html',
})
export class InventoryComponent {
  private readonly items = inject(InventoryApi);
  private readonly i18n = inject(I18nService);
  private readonly formMeta = inject(FormMetaService);
  private readonly form = rxResource({ stream: () => this.formMeta.get('ms.inventory') });
  readonly meta = computed(() => this.form.value() ?? null);
  readonly canCreate = computed(() => canDo(this.meta(), 'create'));
  readonly values = signal<FormValues>({});
  readonly problems = signal<FormProblems>({});

  save(meta: FormMeta): void {
    const t = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    this.problems.set(formProblems(meta, this.values(), t)); // те же правила, что на сервере
    if (Object.keys(this.problems()).length) return;
    this.items.save(null, recordPayload(meta, this.values())).subscribe({
      error: (problem: ProblemDetail) => this.problems.set(serverProblems(meta, problem?.errors, t)), // 422 — на поля
    });
  }
}
```

```html
@if (meta(); as form) {
  <smt-entity-form [meta]="form" [(value)]="values" [problems]="problems()" />
  @if (canCreate()) {
    <button smt-button smtVariant="primary" (click)="save(form)">{{ 'common.save' | t }}</button>
  }
}
```

Кнопки показываются по `actions` из `form-meta`, а не по своим проверкам прав:
сервер уже отфильтровал действия по правам зрителя. Отдельное поле можно
заменить своим шаблоном, не переписывая форму:

```html
<smt-entity-form [meta]="form" [(value)]="values">
  <ng-template smtEntityField="color" let-field let-set="set">
    <my-color-picker (picked)="set($event)" />
  </ng-template>
</smt-entity-form>
```

Полный список точек расширения — в
[extension-points.md](../architecture/extension-points.md).

## Angular feature

Новая пользовательская функция создаётся под `apps/web/src/app/features`.
Маршрут объявляется в `app.routes.ts`, API-вызов идёт через общий HTTP-слой к
серверу, модели ответа типизируются, а состояния loading/empty/error/forbidden
проверяются тестом компонента. Переиспользуемое поведение размещается в `core`,
визуальные примитивы — в `shared`.
Готовые компоненты и правило префиксов (`smt-`, `ui-`, `app-`) перечислены в
[shared/README.md](../../apps/web/src/app/shared/README.md): сначала ищите там.

Браузер не обращается напрямую к PostgreSQL или Typesense и не принимает
окончательное решение об авторизации. Поисковый результат всегда получает
разрешённый пользователю набор через API сервера.

## Изменение данных и внешних интеграций

- Изменение схемы выпускается новой неизменяемой Flyway-миграцией по
  [руководству миграций](database-migrations.md).
- Для storage/mail/SMS/messenger сначала расширяется интерфейс
  `libs/provider-spi`, затем runtime adapter; feature зависит от интерфейса.
- Новый исходящий вызов получает timeout, безопасную конфигурацию, обработку
  ошибки и тест. Секреты не входят в исходный код, логи или тестовые artifacts.
- Изменение, влияющее на требования или release acceptance, связывается с
  соответствующим `FR-*`, `NFR-*` или `AC-*` из канонического ТЗ.

## Проверка перед review

Используйте точные команды из корневого README.

Backend:

```bash
mvn -B verify
```

Web:

```bash
cd apps/web
npm ci
npm run lint
npm run typecheck
npm run api:audit
npm test
npm run build
```

После изменения генератора модуля — `scripts/dev/test-create-module.ps1`.

End-to-end после запуска Compose из quick start:

```bash
cd e2e
npm ci
npx playwright install chromium
npm test
```

Кроме тестов изменённого модуля выполните относящиеся к изменению архитектурные,
документационные, configuration, release и security gates из
[стратегии тестирования](testing-strategy.md). В pull request перечислите
затронутые требования ТЗ, миграции, проверенные негативные сценарии и команды с
фактическим результатом.
