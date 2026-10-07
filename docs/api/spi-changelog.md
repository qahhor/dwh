# Журнал публичного API платформы (SPI)

Изменения артефактов `com.smartup24.cms:platform-api` и
`com.smartup24.cms:provider-spi` — того, против чего собирается модуль вне
монорепо ([ADR-0033](../adr/ADR-0033-platform-api-and-module-manifest.md)).
Версия API — SemVer, отдельная от версии приложения (`platform-api.version` в
корневом `pom.xml`); REST API описан отдельно ([README](README.md),
[openapi.json](openapi.json)).

Правила записи:

- каждое изменение API — строка в разделе своей версии в той же ветке, что и код;
- несовместимое изменение — только в новой MAJOR-версии, и для `STABLE` —
  только того, что было `@Deprecated(since = "X.Y")` хотя бы в одной выпущенной
  MINOR-версии (ADR-0033, §5); совместимое добавление — новая MINOR-версия;
- после слияния выпуска базовую линию japicmp обновляет
  `scripts/api/update-platform-api-baseline.ps1`.

## Не выпущено

### Поведение без изменения сигнатур (japicmp совместим)

- `EntityDefinition.fieldsByKey()` строится один раз с объявлением и
  возвращает карту только для чтения (раньше — новую изменяемую карту на
  каждый вызов).
- Runtime: удаление и архив записи в конечном состоянии процесса или в
  состоянии с блокировками — 422 `entity_state_locked`; значения, которые
  поставил хук или обработчик действия, проверяются по данным (ссылки,
  скоуп, справочники, файлы) и правилами `EntityRule`; неверный вызов
  `EntityValues.set` — 500 с записью `Entity hook misuse` в журнале
  (ADR-0032 §6.17).

### Тест-кит (EXPERIMENTAL, точки входа без изменений)

- Группа «коллекции и процесс»: в конечном состоянии и в состоянии с
  блокировками запись не удаляется и не архивируется (422
  `entity_state_locked`), её `actions` их не предлагают.
- Группа «SQL boundaries»: SQL объявления (таблицы, выражения, вычисляемые
  поля, ответ своего скоупа) читает только таблицы с префиксом таблицы
  сущности и опубликованные `<владелец>_pub_*` (свой скоуп — ещё `md_*`).

- Пользователи кита получают `view` на каждую сущность, которую называет
  ссылка объявления (обязательная ссылка в фикстуре больше не даёт 422
  `not_found`).
- «Пользователь без прав полей» держит права формы без прав её полей: право на
  поле, совпадающее с действием своей формы, теперь проверяется (модуль, где
  такое поле было записываемым для всех, кит не пройдёт).

## 1.0.0 — 2026-10-03

Первая версия публичного API (план 10/10, пункты 6.3 и 6.4). Базовая линия
japicmp — jar этой версии в `libs/platform-api/baseline` и
`libs/provider-spi/baseline`.

### Добавлено

- `com.smartup24.cms.platform.api`: `PlatformApi`, `Stability`,
  `PlatformVersion`.
- `com.smartup24.cms.platform.api.actor.AuditActor` (был
  `com.smartup24.cms.instance.common.actor.AuditActor`).
- `com.smartup24.cms.platform.api.entity` и подпакеты `field`, `hook`,
  `workflow`, `collection`, `event`, `importing`, `search`: объявление сущности,
  перенесённое из `com.smartup24.cms.instance.common.entity` (ADR-0032) без
  смены имён типов; `QueryRef` перенесён из `common.query` в
  `platform.api.entity.field`.
- `EntityRefusal` — отказ хука или обработчика действия (403, 409, 422 с ключом
  текста) для модуля, которому не виден `ApiException`.
- `EntityScope.Condition` — правило строк `EntityScope.ScopeProvider`
  (EXPERIMENTAL).
- `EntityValues.ValueCheck` — проверка значения, которую runtime передаёт в
  `EntityValues.writable`.
- `RuleErrors.Problem` — проблема правила или хука.
- `FieldType.sortable()`, `EntityField.listKeys()`,
  `EntityDefinition.CUSTOM_SECTION`.
- `provider-spi`: все типы помечены `@PlatformApi`; версия артефакта — версия
  API.

### Изменено относительно кода до 1.0 (внутри монорепо)

- `EntityField.queryField(s)`, `FieldType.listType()`,
  `EntityModel.listFields()` убраны из объявления: список строит платформа
  (`common.entity.EntityListFields`).
- `EntityScope.ScopeProvider.filter` возвращает `EntityScope.Condition` вместо
  внутреннего `ScopeFilter`.
- `EntityValues.writable(entity, values)` →
  `EntityValues.writable(entity, values, check)`.
- `RuleErrors.items()` возвращает `RuleErrors.Problem` вместо `FieldErrorItem`.

### Стабильность

`EXPERIMENTAL`: `EntityScope.ScopeProvider`, `EntityScope.Condition`,
`AttributeCasts`, `EntityImportSpec`, `EntitySearchSpec`; точки входа тест-кита
(`EntityContractTestKit`, `EntityFixture`, `FixtureContext`,
`EntityTransport`). Остальное — `STABLE`.
