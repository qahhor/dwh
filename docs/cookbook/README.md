# Cookbook: рецепты модуля на low-code платформе

**Обновлено:** 2026-10-03 · **Основание:** план 10/10, пункт 6.6;
[ADR-0032](../adr/ADR-0032-low-code-platform-v2.md),
[ADR-0033](../adr/ADR-0033-platform-api-and-module-manifest.md);
[руководство по модулям](../guidelines/module-development-guide.md).

Каждый рецепт отвечает на один вопрос «как сделать X» и опирается на
**работающий эталонный модуль**: фрагменты кода взяты из его файлов, у каждого
эталона есть тест, а контракт документации проверяет, что фрагменты не
разошлись с кодом. Руководство по модулям объясняет устройство платформы;
cookbook — короткий путь от задачи к коду.

## Эталонные модули

| Эталон | Где | Что показывает | Тесты |
|---|---|---|---|
| Справочник «товары» `example.products` | `ExampleProductsEntity` (модуль `example`) | код, неизменный после создания; выбор; деньги; архив; импорт по коду; глобальный поиск; вкладка связанных заявок | `ExampleProductsContractTest` |
| Документ со строками «заказы» `example.orders` | `ExampleOrderEntity` | коллекция строк, вычисляемые суммы, итог в валюте документа, процесс с проведением | `ExampleOrderContractTest`, `ExampleOrderDocumentIntegrationTest` |
| Документ со статусами «заявки» `example.requests` | `ExampleRequestsEntity`, `ExampleRequestsHooks` | ссылка на справочник, процесс с отзывом и решением, право на поле, хуки на переходе и удалении, скоуп по оргединице | `ExampleRequestsContractTest`, `ExampleRequestsProcessIntegrationTest`, `ExampleRequestsHooksTest` |
| Модуль вне монорепо `library.books` | `examples/external-module` | отдельный jar против `platform-api`, манифест, свои миграции и ключи | `LibraryBooksContractTest`, `LibraryModuleBoundaryTest`, `LibraryBookHooksTest` |

**Почему так (решение пункта 6.6).** Эталоны живут там, где их собирает и
проверяет CI: три встроенные сущности — в модуле `example` сервера (в поставке
выключен, ADR-0032 §19 В3), сторонний модуль — в `examples/external-module`.
Заказы уже были эталоном документа (пункт 5.7), их не дублировали. Справочник и
заявки — новые и минимальные: задачи (`ms.task_types`, `ms.tasks`) несут
продуктовую логику (порядок, системные типы, участники), которая заслоняет
приём. Новые сущности созданы командами `cms` и доведены руками; что именно
пришлось доводить — в рецептах («Подводные камни»). Включить модуль —
переключатель `example` в реестре модулей; тесты включают его сами.

## Рецепты

| Задача | Рецепт |
|---|---|
| Справочник: код, название, архив | [reference-list.md](reference-list.md) |
| Список: фильтры, сортировка, виды, поиск | [list-filters-search.md](list-filters-search.md) |
| Документ со строками | [document-lines.md](document-lines.md) |
| Документ со статусами | [document-statuses.md](document-statuses.md) |
| Переходы процесса: права, правила, вопрос, блокировки | [workflow-transitions.md](workflow-transitions.md) |
| Связи: ссылка, несколько ссылок, связанный список | [relations.md](relations.md) |
| Хуки: то, что объявление сказать не может | [hooks.md](hooks.md) |
| Права сущности и действия | [permissions.md](permissions.md) |
| Права на поле | [field-rights.md](field-rights.md) |
| Скоуп данных: чьи записи видит пользователь | [data-scope.md](data-scope.md) |
| Импорт из xlsx | [import.md](import.md) |
| Отчёты и виджеты | [list-analytics.md](list-analytics.md) |
| Оптимистическая блокировка (`If-Match`) | [if-match.md](if-match.md) |
| Описание API сущности (OpenAPI) | [openapi.md](openapi.md) |
| Точечная правка общего экрана в Angular | [screen-overrides.md](screen-overrides.md) |
| Схема и миграции: `cms migration diff` | [migration-diff.md](migration-diff.md) |
| Модуль вне монорепо | [external-module.md](external-module.md) |

## Как устроен рецепт

1. **Цель** — что получится.
2. **Команда** — что генерирует `cms` (`tools/cms-cli`).
3. **Объявление** — минимальный фрагмент эталона; блок с пометкой
   `<!-- from: путь -->` в исходнике рецепта совпадает с файлом строка в строку
   (строка `// ...` — пропуск).
4. **Тест** — что проверяет кит контракта и что — свой тест модуля.
5. **Подводные камни** — ошибки, на которые уже наступали.

## Контракт документации

`node scripts/docs/test-docs-contract.mjs` (CI, задание `cms-cli`) читает этот
каталог и руководство по модулям и падает, если код в обратных кавычках или в
блоке называет то, чего нет: класс или его член, метод построителя, файл,
команду `cms` или её флаг, селектор Angular, артефакт Maven, код сущности, — или
если блок с пометкой `from` разошёлся с файлом. Имя-иллюстрация, которого нет в
коде, объявляется в документе: `<!-- docs-contract: hypothetical имя -->`.
Собственные тесты проверки — `scripts/docs/docs-contract.test.mjs`.
