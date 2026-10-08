# Рецепт: хуки

**Эталон:** `ExampleRequestsHooks`
(`apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsHooks.java`);
сторонний модуль — `LibraryBookHooks` (`examples/external-module`).

## Цель

То, что объявление сказать не может: поле, которое пишет сервер в момент
события; отказ по данным, которых нет в записи; запись своих данных вместе с
сохранением; действие после коммита. Хуки — второй и последний файл сущности.
Удаление и архив записи вне черновика хук не запрещает: это говорит процесс
объявления — в конечном состоянии и в состоянии с блокировками платформа
отказывает сама (422 `entity_state_locked`, ADR-0032 §6.17).

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
}
```

| Метод | Когда | Что можно |
|---|---|---|
| `beforeSave` | после проверок полей и правил, в транзакции | менять записываемые поля (`save.values().set`; изменённое снова проходит проверки ссылок, скоупа, справочников, файлов и правила `EntityRule`), добавить проблему поля (`save.reject(...)` — один 422), отказать (`EntityRefusal`) |
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
    void aDecisionStampsItsMomentAndOtherSavesDoNot() {
        assertThat(hooks.entity()).isEqualTo(ExampleRequestsEntity.CODE);
        EntityValues approved = save(EntityOperation.ACTION, "approve");
        assertThat(approved.text("decidedAt")).isEqualTo("2026-10-03T09:15Z");
```

Через весь runtime (отметка решения, 422 `entity_state_locked` на удалении
решённой заявки) — `ExampleRequestsProcessIntegrationTest`; отказ хука с
ключом модуля (409) — `LibraryBookHooks` стороннего модуля.

## Подводные камни

- Хук на сущность — один: два бина `EntityHooks` с одним `entity()` или хук
  необъявленной сущности не дают приложению стартовать.
- SQL в хуке нет: свои данные — через репозиторий модуля, чужие — через сервис
  другого модуля.
- Ключ отказа (`error.<модуль>.<имя>`) — в каталогах ru, uz и en; сторонний
  модуль кладёт ключи в свой `messages`.
- `save.values().set` — только записываемое поле формы и значение его типа;
  иначе это дефект модуля: 500 и запись `Entity hook misuse` в журнале с
  сущностью, хуком и полем. Значение, неверное из-за данных запроса, —
  `save.reject(...)`, а не `set`.
- `EntityRefusal` — для любого модуля (сторонний не видит `ApiException`);
  встроенный может бросить и `ApiException`.
- Хук с `Clock` в конструкторе: Spring берёт конструктор с `@Autowired`,
  тест — конструктор с часами.
