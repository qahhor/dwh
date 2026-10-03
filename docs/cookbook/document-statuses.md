# Рецепт: документ со статусами

**Эталон:** `example.requests` — `ExampleRequestsEntity` и `ExampleRequestsHooks`
(`apps/server/src/main/java/com/smartup24/cms/instance/example/service/`).

## Цель

Документ проходит состояния «черновик → на рассмотрении → утверждена или
отклонена» (с отзывом обратно в черновик). Каждый переход — действие записи
со своим правом и кнопкой на общем экране; состояние блокирует поля;
решённый документ только читается.

## Команда

```bash
cms entity new example requests --title "Заявки" --title-en Requests --title-uz Arizalar --icon approval --hooks
cms entity add-field example.requests status --type select --options draft,submitted,approved,rejected
```

Дальше руками: поле статуса — `readonly()` с умолчанием `draft`, процесс
`.workflow(...)`, действия переходов в правах и в миграции прав.

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsEntity.java -->
```java
            .field(select("status", "example.requests.col.status", STATUSES, "example.requests.status.")
                    .column("status")
                    .readonly()
                    .defaultValue(FieldDefault.fixed("draft"))
                    .list(sortable()))
            // ...
            .workflow(EntityWorkflow.on("status")
                    .state("draft", "example.requests.status.draft")
                    .initial()
                    .state("submitted", "example.requests.status.submitted")
                    .locks("subject", "productId", "qty", "orgUnitId")
                    .state("approved", "example.requests.status.approved")
                    .terminal()
                    .state("rejected", "example.requests.status.rejected")
                    .terminal()
                    .transition("submit", "draft", "submitted")
                    .transition("recall", "submitted", "draft")
                    .transition("approve", "submitted", "approved")
                    .transition("reject", "submitted", "rejected")
                    .confirm("example.requests.reject_confirm")
                    .build())
```

- Статус меняет только переход: поле `readonly()`, `PATCH` со статусом — 422
  `readonly`.
- `.locks(...)` — поля, которые состояние запрещает менять; `terminal()` —
  запрещает всё.
- Переход: `POST /api/v1/entities/example.requests/{id}/actions/approve` с
  `If-Match`. В записи и в `form-meta` поле `actions` уже отфильтровано по
  состоянию и правам зрителя — экран рисует кнопки по нему.
- Миграция статуса: `status text not null default 'draft'` с `check` по
  списку состояний (`V197__example_products_requests.sql`).

## Тест

<!-- from: apps/server/src/test/java/com/smartup24/cms/instance/example/ExampleRequestsContractTest.java -->
```java
class ExampleRequestsContractTest extends EntityContractTestKit {
    // ...
    @Override
    protected EntityFixture fixture(FixtureContext context) {
        return EntityFixture.valid(Map.of("productId", ExampleProducts.insert(context.jdbc(), context.anyUserId())));
    }
}
```

Кит сам проходит каждый переход: из состояния, которое он не покидает, —
422 `entity_transition_not_allowed`; без права — 403; из допустимого —
новое состояние, ревизия + 1, переход в истории; в каждом блокирующем
состоянии — 422 `readonly` на заблокированных полях. Решение, штамп времени и
отказ в удалении проверяет `ExampleRequestsProcessIntegrationTest`.

## Подводные камни

- Состояние без пути из начального кит не проверит — и пользователь до него
  не дойдёт; держите граф связным.
- Без вкладок карточка — «Поля» и «История»; вкладки объявляют целиком
  (`EntityTab.sections`, `EntityTab.history`), см. справочник.
- Подтверждение перехода — ключ `confirm`; текст с `{name}` записи.
- Не путайте процесс с `ENUM` и действием `set_status` (задачи, `MsTaskEntity`):
  там статус — справочник, который настраивает администратор, а не граф
  переходов с правами.
