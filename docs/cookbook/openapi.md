# Рецепт: описание API сущности (OpenAPI)

**Эталон:** пути `/api/v1/entities/example.products…`,
`/api/v1/entities/example.requests…` в `docs/api/openapi.json`; строит их
`EntityOpenApiCustomizer` ([ADR-0022](../adr/ADR-0022-openapi-from-code.md)).

## Цель

У каждой сущности — свои типизированные пути и схемы в описании API: клиент
(веб, интеграция) видит поля записи, тела создания и изменения, страницу
списка и действия, хотя контроллера у сущности нет.

## Команда

После нового поля или новой сущности:

```bash
mvnw -B -pl apps/server test -Dtest=OpenApiContractTest -Dopenapi.update=true
```

затем в `apps/web` — `npm run api:types` (типы `apps/web/src/app/core/api/openapi.d.ts`).
`cms entity new` печатает обе команды в списке «что осталось».

## Что строится

| Схема | Что в ней |
|---|---|
| `<Код>Record` | запись: поля объявления, `id`, `revision`, `actions`, строки коллекций |
| `<Код>Create`, `<Код>Patch` | поля формы и `attributes`; системных (`id`, `revision`, авторы, даты) нет; значение поля `readonly()` сервер отвергнет 422 `readonly` |
| `<Код>Page` | страница списка (`KeysetPage`) |

Пути сущности: список и создание, запись (чтение, изменение, удаление), архив
(`…/{id}/archived`, если объявлен), каждое действие и переход
(`…/{id}/actions/{код}`). Общие пути (массовые действия, выгрузка, отчёт,
импорт) описаны один раз с `{code}`. Имя схемы — из кода сущности:
`example.requests` → `ExampleRequestsRecord`.

## Тест

`OpenApiContractTest` сравнивает описание, которое строит приложение, с
`docs/api/openapi.json`: забытая перегенерация — красная сборка. В CI
`scripts/api/test-api-contract.ps1` ищет ломающие изменения относительно
`main` (Spectral, openapi-diff) и сверяет типы веба.

## Подводные камни

- Удалённое или переименованное поле — ломающее изменение API: метка
  `api-breaking` или трейлер `Api-Breaking:` и запись в `CHANGELOG.md`.
- Описание строится и для выключенного модуля: путь есть, а запрос к
  выключенной сущности — 404.
- Типы веба генерируются только из `docs/api/openapi.json`; править
  `openapi.d.ts` руками бессмысленно.
