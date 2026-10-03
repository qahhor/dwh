# Рецепт: хуки

**Эталон:** `ExampleRequestsHooks`
(`apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsHooks.java`);
сторонний модуль — `LibraryBookHooks` (`examples/external-module`).

## Цель

То, что объявление сказать не может: поле, которое пишет сервер в момент
события; отказ по состоянию записи; запись своих данных вместе с сохранением;
действие после коммита. Хуки — второй и последний файл сущности.

## Команда

```bash
cms entity new example requests --hooks
```

`--hooks` пишет `<Модуль><Сущность>Hooks implements EntityHooks` (`@Component`)
с примером `beforeSave`.

## Объявление

<!-- from: apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsHooks.java -->
```java
@Component
public class ExampleRequestsHooks implements EntityHooks {
    // ...
    @Override
    public String entity() {
        return ExampleRequestsEntity.CODE;
    }

    /** A transition is a save with {@link EntityOperation#ACTION} and the transition's code. */
    @Override
    public void beforeSave(EntitySave save) {
        if (save.operation() == EntityOperation.ACTION && ExampleRequestsEntity.DECISIONS.contains(save.action())) {
            save.values().set("decidedAt", OffsetDateTime.now(clock).toString());
        }
    }

    @Override
    public void beforeDelete(EntityDelete delete) {
        if (!"draft".equals(delete.before().text("status"))) {
            throw EntityRefusal.conflict("error.example.request_not_draft", Map.of());
        }
    }
}
```

| Метод | Когда | Что можно |
|---|---|---|
| `beforeSave` | после проверок полей и правил, в транзакции | менять записываемые поля (`save.values().set`), добавить проблему поля (`save.reject(...)` — один 422), отказать (`EntityRefusal`) |
| `afterSave` | после записи, в той же транзакции | писать свои данные через свой репозиторий; исключение откатывает всё |
| `beforeDelete` / `afterDelete` | вокруг удаления | отказать (`EntityRefusal`), убрать свои данные |
| `beforeArchive` | перед архивом и восстановлением | отказать |
| `afterCommit` | после коммита | то, что можно потерять или повторить (уведомление); сбой пишется в журнал и не меняет ответ |

Что за сохранение — `save.operation()` (`CREATE`, `UPDATE`, `ACTION` с
`save.action()`), импорт ли это — `save.imported()`, изменилось ли поле —
`save.changed(ключ)`.

## Тест

Хук — обычный класс: тест вызывает его с типами API без платформы, часы
фиксированы:

<!-- from: apps/server/src/test/java/com/smartup24/cms/instance/example/ExampleRequestsHooksTest.java -->
```java
    @Test
    void onlyADraftIsDeleted() {
        hooks.beforeDelete(delete("draft"));
        assertThatThrownBy(() -> hooks.beforeDelete(delete("submitted")))
                .isInstanceOfSatisfying(EntityRefusal.class, refusal -> {
                    assertThat(refusal.kind()).isEqualTo(EntityRefusal.Kind.CONFLICT);
                    assertThat(refusal.messageKey()).isEqualTo("error.example.request_not_draft");
                });
    }
```

Через весь runtime (409 с текстом ключа на удалении решённой заявки) —
`ExampleRequestsProcessIntegrationTest`.

## Подводные камни

- Хук на сущность — один: два бина `EntityHooks` с одним `entity()` или хук
  необъявленной сущности не дают приложению стартовать.
- SQL в хуке нет: свои данные — через репозиторий модуля, чужие — через сервис
  другого модуля.
- Ключ отказа (`error.example.request_not_draft`) — в каталогах ru, uz и en;
  сторонний модуль кладёт ключи в свой `messages`.
- `EntityRefusal` — для любого модуля (сторонний не видит `ApiException`);
  встроенный может бросить и `ApiException`.
- Хук с `Clock` в конструкторе: Spring берёт конструктор с `@Autowired`,
  тест — конструктор с часами.
