# Разработка модулей SmartupCMS

**Версия:** 2.1

**Обновлено:** 2026-09-27

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

Cross-module интеграция выполняется через явный интерфейс или событие. Импорт
внутреннего `repository`, `service` или модели соседнего функционального модуля
запрещён. Общие value/error contracts помещаются в `libs/core-types`, общая
backend-инфраструктура — в `libs/platform-common`, а интерфейсы внешних
провайдеров — в `libs/provider-spi`. Общая библиотека не зависит от сервера.

Любой защищённый endpoint использует `@RequiresPermission`; UI-проверка лишь
улучшает UX и не заменяет серверную авторизацию. Ошибки возвращаются в общем
Problem Details формате, описанном в
[соглашениях](../architecture/biruni-smartup-conventions.md).

## Сущность как объявление: чек-лист

Запись, которую пользователь создаёт, ищет и меняет, объявляется один раз
([ADR-0019](../adr/ADR-0019-low-code-entity-model.md)). Каркас даёт
`scripts/dev/create-module.ps1 -ModuleName <код> -ModuleTitle "<Название>"`:
миграцию и пять Java-файлов. Образец в коде — заметки (`ms.note`).

1. **Миграция:** таблица со стандартными полями (`attributes jsonb`, аудит
   создания и изменения), модуль в `md_installed_modules`, выдача прав
   системным ролям.
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
5. **Контроллер** — страница списка, чтение, создание, изменение, удаление;
   на каждом методе `@RequiresPermission` с формой и действием из объявления.
6. **Переводы** в `apps/server/src/main/resources/i18n` (ru, en): `nav.<код>`,
   подписи полей и вариантов; затем `npm run i18n:sync-ru`.
7. **Экран:** маршрут в `app.routes.ts`; форма — `smt-entity-form`, просмотр —
   `smt-entity-card`, виды, экспорт и массовое удаление — `smt-entity-toolbar`;
   кнопки — по `actions` из `form-meta`, а не по своим проверкам прав. Пункт
   меню появится сам (`GET /entities/menu`).
8. **Проверки:** `EntityActionPermissionContractTest` сверяет действия
   объявления с `@RequiresPermission`, `MdFormCatalogTest` — что у каждой пары
   права есть название; объявление без того, что обещают его возможности, не
   даёт приложению стартовать.

Не нужно: записи в `MdFormCatalog`, свой источник истории, свой экспортёр,
свой endpoint массовых действий, пункт меню в `app-shell.models.ts`.

### Экран сущности: пример

Форма целиком приходит с сервера; экран только загружает `form-meta`, держит
значения и сохраняет. Полный образец — `apps/web/src/app/features/notes`.

```ts
@Component({
  selector: 'app-inventory',
  standalone: true,
  imports: [SMTEntityFormComponent, SMTEntityToolbarComponent, SMTButtonComponent, TranslatePipe],
  template: `
    @if (meta(); as form) {
      <smt-entity-toolbar [meta]="form" />
      <smt-entity-form [meta]="form" [(value)]="values" [problems]="problems()" />
      @if (canDo(form, 'create')) {
        <button smt-button smtVariant="primary" (click)="save(form)">{{ 'common.save' | t }}</button>
      }
    }
  `,
})
export class InventoryComponent {
  private readonly api = inject(ApiService);
  private readonly i18n = inject(I18nService);
  readonly meta = toSignal(inject(FormMetaService).get('ms.inventory'));
  readonly values = signal<FormValues>({});
  readonly problems = signal<FormProblems>({});
  readonly canDo = canDo;

  save(meta: FormMeta): void {
    const t = (key: string, params?: Record<string, string | number>) => this.i18n.translate(key, params);
    const problems = formProblems(meta, this.values(), t);   // те же правила, что на сервере
    this.problems.set(problems);
    if (Object.keys(problems).length) return;
    this.api.post('/inventory', recordPayload(meta, this.values()), { notifyError: false }).subscribe({
      error: problem => this.problems.set(serverProblems(meta, problem?.errors, t)),  // 422 — на поля
    });
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
npm test
npm run typecheck
npm run build
```

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
