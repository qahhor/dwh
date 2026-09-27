# Настройки репозитория GitHub

**Обновлено:** 2026-09-28

**Основание:** план 10/10, фаза 1 (пункты 1.5, 1.6, 1.9).

Часть правил CI живёт не в файлах, а в настройках репозитория: их меняет только
администратор. Этот документ перечисляет каждую такую настройку, файл, который
от неё зависит, и способ проверить, что она включена. Пока настройка не
включена, соответствующая защита в репозитории не действует, даже если файл
уже есть.

## 1. Обновление зависимостей (пункт 1.5)

Файлы: [`.github/dependabot.yml`](../../.github/dependabot.yml),
[`.github/workflows/dependabot-auto-merge.yml`](../../.github/workflows/dependabot-auto-merge.yml).

| Настройка | Где | Зачем |
|---|---|---|
| Dependabot alerts | Settings → Code security → Dependabot alerts | Предупреждения об уязвимых зависимостях. |
| Dependabot security updates | Settings → Code security → Dependabot security updates | PR с исправлением уязвимости сразу, а не в еженедельный слот. |
| Allow auto-merge | Settings → General → Pull Requests | Patch-обновления вливаются сами после зелёных обязательных проверок. |

Еженедельные обновления приходят по понедельникам в 06:00 (Asia/Tashkent),
сгруппированно: Spring, плагины сборки, тестовые библиотеки, Angular, линтеры,
Playwright, базовые образы. Patch-обновление вливается само, minor и major
ждут ревью.

Auto-merge включается, только если main требует статус-проверки (раздел 3):
без этого GitHub влил бы PR сразу, не дожидаясь CI, поэтому workflow в этом
случае оставляет предупреждение и ничего не делает.

Не обновляются Dependabot: `actions/checkout`, `actions/setup-node`,
`actions/setup-java`, `actions/upload-artifact`, `actions/download-artifact`
и `aquasecurity/trivy-action`. Их точные коммиты утверждает контракт
[`scripts/release/verify-release.ps1`](../../scripts/release/verify-release.ps1),
и поднимаются они вручную вместе с ним.

**Срок закрытия.** Уязвимость HIGH или CRITICAL закрывается не позже 7 дней с
появления предупреждения: обновлением, заменой зависимости или записанным
исключением с причиной и сроком.

**Проверка.** Вкладка Insights → Dependency graph → Dependabot показывает
последний запуск каждого из пяти разделов конфигурации без ошибок.

## 2. Статический анализ и Scorecard (пункт 1.6)

Файлы: [`.github/workflows/codeql.yml`](../../.github/workflows/codeql.yml),
[`.github/workflows/scorecard.yml`](../../.github/workflows/scorecard.yml).

| Настройка | Где | Зачем |
|---|---|---|
| Code scanning: default setup выключен | Settings → Code security → Code scanning | Анализ задаёт `codeql.yml` (advanced setup); оба сразу GitHub не принимает. |
| Code scanning protection rule | ruleset main (раздел 3) | PR с новым предупреждением уровня high или critical не вливается. |

CodeQL читает Java, TypeScript и сами workflow без сборки (`build-mode: none`)
набором запросов `security-extended`: на каждом PR, на main и раз в неделю.
Scorecard запускается на main, раз в неделю и при изменении правил защиты
веток; результаты — в Security → Code scanning и на публичной странице
OpenSSF Scorecard.

**Триаж.** Каждое предупреждение CodeQL либо исправляется, либо закрывается в
интерфейсе Code scanning с причиной (false positive, used in tests, won't fix)
— причина видна в истории предупреждения. Цель Scorecard: не ниже 7,5 к концу
фазы 1 и не ниже 8 к концу плана.

**Проверка.** Security → Code scanning показывает инструменты CodeQL и
Scorecard с последним анализом main.
