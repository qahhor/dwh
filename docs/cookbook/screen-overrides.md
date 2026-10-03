# Рецепт: точечная правка общего экрана в Angular

**Эталон:** пользователи — `apps/web/src/app/features/iam/users/users.overrides.ts`
(контролы языка и часового пояса, две вкладки карточки), подключены в
`apps/web/src/app/features/entity-screens.routes.ts`; тест —
`apps/web/src/app/features/iam/users/users.screen.spec.ts`. Механизм —
`provideEntityOverrides` (`apps/web/src/app/shared/entity/page/entity-overrides.ts`, ADR-0032 §7.2).

## Цель

Сущность без кода веба получает общий экран `/e/<код>`: список
(`smt-entity-list-page`), карточку (`smt-entity-record-page`), форму
(`smt-entity-form`). Если нужна мелочь — своя ячейка списка, свой контрол поля,
своя секция или вкладка карточки, — она задаётся по ключу, а не своим экраном.

## Команда

Не нужна: эталонные модули `example` своего кода веба не имеют вовсе.

## Объявление

<!-- from: apps/web/src/app/features/iam/users/users.overrides.ts -->
```ts
export function provideUserScreen(): EnvironmentProviders {
  return provideEntityOverrides(USERS_ENTITY, {
    fields: { language: UserSettingFieldComponent, timezone: UserSettingFieldComponent },
    tabs: [
      {
        key: 'security',
        labelKey: 'iam.users.tab.security',
        component: UserSecurityTabComponent,
        requires: [{ form: USERS_ENTITY, action: 'view' }],
      },
```

Провайдер подключают к маршруту общего экрана — он грузится вместе с
экраном и ничего не добавляет к старту приложения:

<!-- from: apps/web/src/app/features/entity-screens.routes.ts -->
```ts
export const ENTITY_SCREEN_ROUTES: Routes = [
  {
    path: '',
    providers: [provideUserScreen()],
    children: ENTITY_ROUTES,
  },
];
```

| Ключ | Компонент получает (`input()`) |
|---|---|
| `cells: { поле: Компонент }` | `row`, `field` |
| `fields: { поле: Компонент }` | `field`, `value`, `problem`, `disabled`, `set` — значение меняют вызовом `set(значение)` |
| `sections: { секция: Компонент }` | `meta`, `record`, `values` |
| `tabs: [{ key, labelKey, component, requires }]` | `meta`, `record`; `requires` — вкладку видит держатель одного из прав |

Поле можно заменить и в своём экране, не переписывая форму: шаблон
`ng-template smtEntityField="поле"` внутри `smt-entity-form`.

## Тест

`users.screen.spec.ts` рисует общий экран настоящим роутером поверх API из
фикстур (`renderEntityScreen` из `apps/web/src/testing/entity-page.ts`) и
проверяет, что контрол и вкладки на месте и вкладка без права не
показывается. Тот же приём для своей сущности — спека рядом с
`*.overrides.ts`.

## Подводные камни

- Кнопки действий рисуются по `actions` записи и подписываются ключом
  `entity.action.<код>`; вопрос перед действием — ключ
  `entity.action_confirm.<код>` с `{name}`.
- Несколько вызовов `provideEntityOverrides` для одной сущности складываются.
- Свой экран целиком — только для другого способа работы (доска, календарь,
  мастер): см. раздел «Свой экран сущности» руководства по модулям.
- Права решает сервер: `requires` вкладки — удобство, данные вкладки всё равно
  читаются с правами пользователя.
