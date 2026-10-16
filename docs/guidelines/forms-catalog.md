# Реестр экранов и форм SmartupCMS

Источник реестра — [маршруты приложения](../../apps/web/src/app/app.routes.ts),
объявления сущностей и компоненты, ссылки на которые приведены ниже.
Это карта интерфейса для разработки и приёмки, а не протокол успешного
тестирования всех комбинаций прав, данных и размеров экрана.

Общие правила — [стандарт на одной странице](forms-one-page.md) и
[подробный стандарт](forms-ux-standard.md). Одинаковые поля, ошибки,
сохранение, подтверждение выхода и доступность реализуются общими компонентами.
Дерево, матрица, доска и мастер сохраняют свой способ работы.

**Как считать:** строка — семейство пользовательских сценариев. Создание,
изменение и просмотр перечислены вместе; родитель и вложенный компонент
не считаются разными формами. Специальное представление сущности отмечено
отдельно, но не является второй сущностью. Служебные страницы без ввода
тоже перечислены: требование CRUD к ним неприменимо.

## 1. Девять объявленных сущностей

Для каждого кода общий runtime предоставляет маршруты списка `/e/<code>`,
создания `/e/<code>/new`, просмотра `/e/<code>/<id>` и изменения
`/e/<code>/<id>/edit`. Наличие маршрута не даёт право выполнить действие:
его разрешают серверные метаданные, права и состояние записи.

Список, поиск, фильтры, поля и карточка используют
[общие экраны](../../apps/web/src/app/shared/entity/page/entity.routes.ts).
История, архив, экспорт, массовые операции, импорт, процесс и отчёты доступны
только там, где объявлены соответствующие возможности.

| Код / форма | Операции и особенности | Источник |
|---|---|---|
| `md.users` — Пользователи | Создание, изменение, просмотр; блокировка, 2FA, принудительная смена пароля; обезличивание вместо обычного удаления. Вкладки прав, подразделений и сессий. | [MdUserEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/md/service/MdUserEntity.java) |
| `ms.tasks` — Задачи | Создание, изменение, просмотр, смена статуса; специальная доска и карточка с обсуждением. | [MsTaskEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/ms/task/service/MsTaskEntity.java) |
| `ms.projects` — Проекты | Создание, изменение, просмотр, состав участников; специальный список и карточка. | [MsProjectEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/ms/task/service/MsProjectEntity.java) |
| `ms.task_types` — Типы задач | Создание, изменение, порядок, архив и удаление по ограничениям справочника; импорт по коду. | [MsTaskTypeEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/ms/task/service/MsTaskTypeEntity.java) |
| `ms.task_statuses` — Статусы задач | Создание, изменение, порядок, архив и удаление по ограничениям справочника; терминальные и системные состояния. | [MsTaskStatusEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/ms/task/service/MsTaskStatusEntity.java) |
| `ms.notes` — Заметки | Создание, изменение, просмотр, закрепление, архив, удаление; доска открывает ту же форму из метаданных. | [MsNoteEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/ms/note/service/MsNoteEntity.java) |
| `example.products` — Товары | Эталон справочника: CRUD, архив, импорт по коду, ограничения связанных записей. | [ExampleProductsEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleProductsEntity.java) |
| `example.orders` — Заказы | Эталон документа: создание, изменение, просмотр, строки, деньги, проведение и отмена; импорт по номеру. | [ExampleOrderEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleOrderEntity.java) |
| `example.requests` — Заявки | Эталон процесса: создание, изменение, просмотр, согласование; удаление ограничено черновиком. | [ExampleRequestsEntity](../../apps/server/src/main/java/com/smartup24/cms/instance/example/service/ExampleRequestsEntity.java) |

Три встроенных эталона находятся в отдельном разделе меню «Примеры».
Раздел учитывает права просмотра и исчезает при отключении модуля `example`.

Эталоны нужны разработчикам framework и рецептам расширения, поэтому наличие
этих форм само по себе не является причиной удаления. Задачи, проекты,
заметки и UPL входят в отключаемые прикладные модули; ядро администрирования
остаётся доступным без них. Состав серверных модулей —
[карта модулей](../architecture/module-map.md).

## 2. Вход и личный кабинет

| Форма | Где открыть | Применимые сценарии | Реализация |
|---|---|---|---|
| Вход | `/login` | Логин, пароль, OTP и обязательная смена пароля по ответу сервера; CRUD неприменим. | [login](../../apps/web/src/app/features/auth/login/login.component.ts) |
| Запрос восстановления | Вход → восстановление пароля | Email, отправка запроса, обратная связь, отмена. | [reset dialog](../../apps/web/src/app/features/auth/login/components/login-reset-modal.component.ts) |
| Новый пароль | `/reset-password` | Новый пароль и подтверждение по ссылке восстановления. | [reset-password](../../apps/web/src/app/features/auth/reset-password/reset-password.component.ts) |
| Разблокировка сессии | Диалог после простоя | Подтверждение личности или выход; не редактор записи. | [idle lock](../../apps/web/src/app/layout/app-shell/components/idle-lock-dialog.component.ts) |
| Профиль и безопасность | `/iam/profile` | Просмотр учётной записи и состояния безопасности. | [profile](../../apps/web/src/app/features/iam/profile/profile.component.ts) |
| Смена пароля | Профиль → смена пароля | Текущий пароль, новый пароль, подтверждение, правила и ошибки. | [password card](../../apps/web/src/app/features/iam/profile/components/profile-password-card.component.ts) |
| Каналы связи | Профиль → каналы | Привязка адреса, подтверждение кодом, отвязка; два последовательных диалога. | [channels card](../../apps/web/src/app/features/iam/profile/components/profile-channels-card.component.ts) |
| Сессии | Профиль → активные сессии | Просмотр, обновление, завершение одной или других сессий с подтверждением. | [sessions card](../../apps/web/src/app/features/iam/profile/components/profile-sessions-card.component.ts) |
| API-токены | Профиль → API-токены | Список, выпуск с именем и сроком, однократный показ секрета, отзыв. Редактирования секрета нет. | [tokens card](../../apps/web/src/app/features/iam/profile/components/profile-tokens-card.component.ts) |

## 3. Команда и доступ

| Форма | Где открыть | Применимые сценарии | Реализация |
|---|---|---|---|
| Пользователи | `/e/md.users` | Общий CRUD из раздела 1 и специальные вкладки карточки. | [user overrides](../../apps/web/src/app/features/iam/users/users.overrides.ts) |
| Роли и матрица прав | `/iam/roles` | Список/поиск, создание, изменение, просмотр прав, удаление по ограничениям роли. | [roles](../../apps/web/src/app/features/iam/roles/roles.component.ts) |
| Подразделения | `/iam/org-units` | Дерево/поиск, создание, изменение, родитель, порядок, состояние, анализ влияния изменения структуры. | [org units](../../apps/web/src/app/features/iam/org-units/org-units.component.ts) |
| Подразделения пользователя | Карточка пользователя → роли и права | Выбор подразделений и просмотр эффективной области данных. | [user units](../../apps/web/src/app/features/iam/org-units/user-org-units-panel.component.ts) |
| Область данных роли | Редактор роли | Матрица правил области данных по ресурсам. | [role scope](../../apps/web/src/app/features/iam/org-units/role-scope-panel.component.ts) |
| Дополнительные поля | `/iam/custom-fields` | Выбор формы, список, создание, изменение и удаление метаданных поля; тип, ограничения и порядок. | [custom fields](../../apps/web/src/app/features/iam/custom-fields/custom-fields.component.ts) |

## 4. Настройки framework

| Форма | Где открыть | Применимые сценарии | Реализация |
|---|---|---|---|
| Общие настройки | `/settings?tab=general` | Компания, язык, часовой пояс, формат даты; изменение единого набора. | [general panel](../../apps/web/src/app/features/settings/components/settings-general-panel.component.ts) |
| Безопасность | `/settings?tab=security` | Политики сессии, простоя и 2FA; числовые ограничения и ошибки под полем. | [security panel](../../apps/web/src/app/features/settings/components/settings-security-panel.component.ts) |
| Хранилище | `/settings?tab=storage` | Квота пользователя и ограничения значения. | [storage panel](../../apps/web/src/app/features/settings/components/settings-storage-panel.component.ts) |
| Личные предпочтения | `/settings?tab=preferences` | Язык, тема и пользовательские настройки; CRUD неприменим. | [preferences panel](../../apps/web/src/app/features/settings/components/settings-preferences-panel.component.ts) |
| Языки | `/settings?tab=languages` | Список, добавление языка из кода/названия/JSON, экспорт, переключение. | [languages panel](../../apps/web/src/app/features/settings/components/settings-languages-panel.component.ts) |
| Переводы | Языки → изменить | Поиск по ключам/тексту, непереведённые строки, значения, сброс отдельного значения, импорт/экспорт JSON, сохранение пакета. | [language editor](../../apps/web/src/app/features/settings/language-editor.component.ts) |
| Модули | `/settings/modules` | Просмотр, включение/отключение допустимых модулей; системные модули защищены. Создание и удаление через UI неприменимы. | [modules](../../apps/web/src/app/features/settings/modules/modules.component.ts) |
| Меню | `/settings/navigation`, также вкладка `navigation` | Дерево, создание/изменение/удаление пользовательских пунктов, порядок и доступность. | [navigation](../../apps/web/src/app/features/settings/navigation/navigation-settings.component.ts) |
| Поиск | `/settings?tab=search` | Политика поиска, предпросмотр запроса, состояние индекса и задания переиндексации. | [search settings](../../apps/web/src/app/features/settings/search/search-settings.component.ts) |
| Webhooks | `/settings?tab=webhooks` | Список подписок, создание с адресом и событиями, включение/отключение, удаление; секрет показывается однократно. Изменение адреса/событий и журнал доставок через этот UI не реализованы. | [webhooks](../../apps/web/src/app/features/settings/webhooks/webhooks-settings.component.ts) |

Эти формы редактируют настройки или метаданные, а не вторую копию обычной
бизнес-сущности. Их специальные экраны остаются; контролы, кнопки и поведение
ошибок подчиняются общему стандарту.

## 5. Отключаемые прикладные модули

| Форма | Где открыть | Применимые сценарии | Реализация |
|---|---|---|---|
| Задачи | `/tasks`, `/tasks/items/<id>` | Список/доска, поиск/фильтры, создание, изменение, карточка, статус, комментарии, участники и файлы. | [tasks](../../apps/web/src/app/features/tasks/tasks.component.ts) |
| Типы и статусы задач | Задачи → справочники | Два редактора справочников из раздела 1; порядок и ограничения системных значений. | [dictionaries](../../apps/web/src/app/features/tasks/components/task-dictionaries-modal.component.ts) |
| Проекты | `/tasks/projects`, `/tasks/projects/<id>` | Список/поиск, создание, изменение и просмотр. | [projects](../../apps/web/src/app/features/tasks/projects/projects.component.ts) |
| Участники проекта | Карточка проекта → участники | Поиск пользователей, добавление и удаление участников. | [members](../../apps/web/src/app/features/tasks/projects/components/project-members-modal.component.ts) |
| Заметки | `/notes` | Поиск, доска, создание/изменение общей metadata-формой, история, закрепление, архив и удаление. | [notes](../../apps/web/src/app/features/notes/notes.component.ts) |
| Источники загрузки | `/upl/sources` | Список/поиск, создание источника, переход к карточке. | [sources](../../apps/web/src/app/features/upl/sources/sources-list.component.ts) |
| Карточка источника | `/upl/sources/<id>` | Просмотр/изменение реквизитов, версии формата, создание черновика формата. | [source card](../../apps/web/src/app/features/upl/sources/source-card.component.ts) |
| Формат загрузки | `/upl/sources/<id>/formats/<v>` | Мастер: файл, листы/колонки/правила, проверка, сохранение и публикация версии с датой действия. | [format editor](../../apps/web/src/app/features/upl/formats/format-editor.component.ts) |
| Пакеты загрузки | `/upl/packages` | Список/фильтры, выбор источника/периода/файла, запуск, карточка состояния и ошибок; обработанный пакет не редактируется как обычная запись. | [packages](../../apps/web/src/app/features/upl/packages/packages.component.ts) |
| Обзор загрузок | `/upl/overview` | Показатели, фильтры и переходы; CRUD неприменим. | [overview](../../apps/web/src/app/features/upl/overview/upl-overview.component.ts) |

## 6. Сервисные экраны

| Форма | Где открыть | Применимые сценарии | Реализация |
|---|---|---|---|
| Файлы | `/files` | Реестр/поиск, загрузка, просмотр сведений, скачивание и удаление по доступу. Загрузка — действие с прогрессом, не редактирование черновика. | [files](../../apps/web/src/app/features/files/files.component.ts) |
| Аудит | `/audit` | Два журнала: изменения данных и события безопасности; поиск/фильтры/страницы и подробности. Создание, изменение, удаление записей через UI неприменимы. | [audit](../../apps/web/src/app/features/audit/audit.component.ts) |
| Уведомления | `/notifications` | Входящие, непрочитанные, обновление, прочтение и переход к объекту; CRUD неприменим. | [notifications](../../apps/web/src/app/features/notifications/notifications.component.ts) |
| Настройки уведомлений | Уведомления → настройки | Матрица типов событий и каналов, сохранение, отмена; на узком экране название события расположено над тремя каналами. | [preferences matrix](../../apps/web/src/app/features/notifications/components/notification-preferences-modal.component.ts) |
| Объявления | `/announcements` | Список/фильтры, многоязычный редактор, тип/срок действия, изменение и предусмотренные действия публикации. | [announcements](../../apps/web/src/app/features/announcements/announcements.component.ts) |
| Экспорты | `/exports` | Список заданий, состояние, скачивание и отмена по состоянию; новый экспорт запускается из исходного списка. | [exports](../../apps/web/src/app/features/exports/exports.component.ts) |
| Аналитика | `/analytics` | Период, показатели, графики и сохранённые виджеты; создание/изменение записей неприменимо. | [analytics](../../apps/web/src/app/features/analytics/analytics.component.ts) |
| Система | `/system` | Состояние и диагностические сведения; CRUD неприменим. | [system](../../apps/web/src/app/features/system/system.component.ts) |
| Встроенный отчёт | `/embed/<code>` | Просмотр настроенного отчёта; отдельный CRUD неприменим. | [embedded report](../../apps/web/src/app/features/reports/embedded-report.component.ts) |

## 7. Общие вложенные формы

| Форма / компонент | Где применяется | Единый контракт |
|---|---|---|
| [Metadata-форма](../../apps/web/src/app/shared/entity/smt-entity-form.component.ts) и [строки документа](../../apps/web/src/app/shared/entity/smt-entity-lines.component.ts) | Создание/изменение объявленных сущностей, редактор заметки | Порядок полей из метаданных, скрытые/readonly поля, blur/submit, ошибки по адресам, фокус, добавление/перемещение/удаление строк. |
| [Карточка](../../apps/web/src/app/shared/entity/page/smt-entity-record-page.component.ts) | Просмотр сущности | Значения, вкладки, история, связи, строки и разрешённые действия. |
| [Фильтры](../../apps/web/src/app/shared/ui/ui-filter-panel.component.ts) | Реестры | Поле/оператор/значение, добавление/удаление условия, применить/сбросить. |
| [Сохранённые виды](../../apps/web/src/app/shared/ui/ui-list-views.component.ts) | Реестры | Выбор, сохранение названия и конфигурации, удаление вида по доступу. |
| [Импорт](../../apps/web/src/app/shared/entity/smt-entity-import.component.ts) | Сущности с импортом | Файл, проверка, результаты по строкам, подтверждение записи; отсутствие файла — состояние без действия. |
| [Отчёты](../../apps/web/src/app/shared/entity/report/smt-entity-report-saved.component.ts) | Реестры с отчётами | Измерения/меры, выполнение, сохранение определения и параметры виджета. |
| [Результаты массового действия](../../apps/web/src/app/shared/ui/ui-bulk-result.component.ts) | Реестры | Успехи и ошибки отдельных записей; не повторять успешную часть вслепую. |
| [Подтверждение отмены](../../apps/web/src/app/shared/ui/discard-changes.ts), [диалог](../../apps/web/src/app/shared/ui-kit/components/modal/dialog.component.ts), [действия формы](../../apps/web/src/app/shared/ui/ui-form-actions.component.ts) | Все применимые редакторы | Одни названия и порядок действий, защита черновика, pending, фокус и клавиатура. |
| [Глобальный поиск](../../apps/web/src/app/layout/command-palette/command-palette.component.ts) | Верхняя панель / Ctrl+K | Поиск и переход к доступному объекту; не форма CRUD. |

## 8. Проверка и сопровождение

Для каждой строки при изменении проверять применимые операции: список и
поиск; просмотр; создание/изменение; удаление/архив; пустые данные и отказ
загрузки; 422/403/409; повторная отправка; отмена и выход; скрытые поля и
отзыв доступа. Неприменимые операции не добавлять ради одинаковых кнопок.

- Статический gate: `npm run forms:audit` — ноль исключений; также typecheck,
  lint, i18n/aria/contrast/signals/api/comments audits и build.
- Поведение: соседние `*.spec.ts`, полный `npm run test:coverage` и пороги.
- Отрисовка и клавиатура: [a11y-сценарии](../../e2e/tests/a11y/screens.spec.ts) на production
  build в обеих темах; матрица уведомлений, metadata-форма, поиск меню и меню действий задач дополнительно на 360 px.
- Реальные API и права: [browser E2E](../../e2e/playwright.config.ts),
  оба шарда на отдельном свежем Compose-стенде.

При добавлении маршрута, сущности или вложенного редактора обновлять этот
реестр. Результаты конкретного запуска, SHA и ограничения среды записывать
в handoff/протокол проверки; наличие строки в реестре не означает приёмку staging.

Дополнительные регрессии прохода 2026-10-14: `responsive-controls.spec.ts`
(мобильные действия и доступная диаграмма), `navigation-table-controls.spec.ts`
(ширина колонки действий, компактное меню, переключатель, вложенный заголовок и drawer фильтра),
`roles-layout.spec.ts` (длинные названия матрицы, checkbox и безопасный фокус).
Это проверки изолированного API; ручной обход наполненного демо не изменяет
записи, права, настройки интеграций и опубликованные форматы.
