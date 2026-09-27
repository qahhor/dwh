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
