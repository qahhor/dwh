# Настройки репозитория GitHub

**Обновлено:** 2026-10-17

**Основание:** план 10/10, фаза 1 (пункты 1.5, 1.6, 1.8, 1.9).

Часть правил CI живёт не в файлах, а в настройках репозитория: их меняет только
администратор. Этот документ перечисляет каждую такую настройку, файл, который
от неё зависит, и способ проверить, что она включена. Пока настройка не
включена, соответствующая защита в репозитории не действует, даже если файл
уже есть.

**CI только вручную (решение владельца, 2026-10-17).** Ни один workflow не
запускается автоматически: нет триггеров `push`, `pull_request`,
`pull_request_target`, `schedule`, `branch_protection_rule`. Каждая проверка
выполняется локально до merge (AGENTS.md, разделы 3 и 5); workflow остаются в
репозитории и запускаются вручную: Actions → workflow → Run workflow или
`gh workflow run <файл>`.

| Workflow | Запуск | Параметры |
|---|---|---|
| `ci.yml` | вручную; `workflow_call` из `release.yml` и `prerelease.yml` | `base_ref` (по умолчанию `main`; пусто — сравнение с `HEAD~1`, diff coverage пропускается), `allow_breaking` (аналог метки `api-breaking`) |
| `dco.yml` | вручную, на проверяемой ветке | `base_ref` (по умолчанию `main`): проверяются коммиты после merge base |
| `codeql.yml`, `scorecard.yml`, `nightly.yml` | вручную | — |
| `dependabot-auto-merge.yml` | вручную | номер PR Dependabot; сейчас ничего не включает (раздел 1) |
| `release.yml`, `prerelease.yml` | push тега `vX.Y.Z` / `vX.Y.Z-rc.N` | тег создаёт только администратор (раздел 3) |

Релизные workflow остаются на тегах: тег — осознанное действие
администратора, а не автоматический CI. Если владелец решит перевести и их
на ручной запуск, это отдельное изменение контракта
[`verify-release.ps1`](../../scripts/release/verify-release.ps1), который
сейчас требует запуск только по тегу.

[`scripts/github/test-rulesets.ps1`](../../scripts/github/test-rulesets.ps1)
падает на любом автоматическом триггере и на workflow без
`workflow_dispatch` (кроме двух релизных).

## 1. Обновление зависимостей (пункт 1.5)

Файлы: [`.github/dependabot.yml`](../../.github/dependabot.yml),
[`.github/workflows/dependabot-auto-merge.yml`](../../.github/workflows/dependabot-auto-merge.yml).

| Настройка | Где | Зачем |
|---|---|---|
| Dependabot alerts | Settings → Code security → Dependabot alerts | Предупреждения об уязвимых зависимостях. |
| Dependabot security updates | Settings → Code security → Dependabot security updates | PR с исправлением уязвимости сразу, а не в еженедельный слот. |
| Allow auto-merge | Settings → General → Pull Requests | Не используется, пока `main` не требует status checks. |

Еженедельные обновления приходят по понедельникам в 06:00 (Asia/Tashkent),
сгруппированно: Spring, плагины сборки, тестовые библиотеки, Angular, линтеры,
Playwright, базовые образы. Dependabot по-прежнему открывает PR; каждый ждёт
ревью и локальных проверок.

**Auto-merge выключен.** Каждый PR Dependabot, включая patch, вливается
вручную после локальных проверок. `dependabot-auto-merge.yml` запускается
только вручную с номером PR и включает auto-merge лишь для patch-обновления и
лишь если `main` требует status checks: без них GitHub влил бы PR сразу, без
проверок. Сейчас `main.json` их не требует (раздел 3), поэтому workflow
оставляет предупреждение и ничего не делает.

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

CodeQL читает Java, TypeScript, Python и сами workflow без сборки
(`build-mode: none`) набором запросов `security-extended`. Оба workflow
запускаются только вручную: перед релизом и после изменения зависимостей или
workflow; Scorecard — на ветке по умолчанию. Результаты — в Security → Code
scanning и на публичной странице OpenSSF Scorecard. Ruleset `main` не требует
результатов code scanning: без автоматического анализа PR такое правило
блокировало бы каждый merge.

**Триаж.** Каждое предупреждение CodeQL либо исправляется, либо закрывается в
интерфейсе Code scanning с причиной (false positive, used in tests, won't fix)
— причина видна в истории предупреждения. Цель Scorecard: не ниже 7,5 к концу
фазы 1 и не ниже 8 к концу плана.

**Проверка.** Security → Code scanning показывает инструменты CodeQL и
Scorecard с анализом последнего ручного запуска.

## 3. Защита веток и тегов, CODEOWNERS (пункт 1.9)

Файлы: [`.github/rulesets/`](../../.github/rulesets/main.json),
[`.github/CODEOWNERS`](../../.github/CODEOWNERS),
[`scripts/github/apply-rulesets.ps1`](../../scripts/github/apply-rulesets.ps1),
[`scripts/github/test-rulesets.ps1`](../../scripts/github/test-rulesets.ps1).

Правила защиты хранятся в репозитории и применяются администратором:

```powershell
gh auth login
./scripts/github/apply-rulesets.ps1          # показывает план, ничего не меняет
./scripts/github/apply-rulesets.ps1 -Apply   # создаёт или заменяет rulesets
```

| Ruleset | Что требует |
|---|---|
| `main` | Изменения только через PR: одно одобрение, ревью владельца кода (CODEOWNERS), повторное одобрение после нового push, закрытые обсуждения; только merge-коммит; удаление и force-push запрещены. Status checks и code scanning не требуются (проверки локальные). |
| `release tags: created by administrators only` | Тег `v*` создаёт только администратор. |
| `release tags: never moved or deleted` | Тег `v*` нельзя передвинуть или удалить никому, исключений нет. |

**Только merge-коммит.** Squash и rebase переписывают коммиты, а
[`.git-blame-ignore-revs`](../../.git-blame-ignore-revs) ссылается на коммиты
переформатирования по SHA.

**Обязательных проверок нет** (с 2026-10-17). Required status check
удовлетворяет только запуск workflow на коммите PR, а на PR workflow больше не
запускаются: любая обязательная проверка навсегда оставила бы PR в ожидании. Шлюз merge —
локальные проверки AGENTS.md, раздел 5; их результат автор указывает в PR.
`test-rulesets.ps1` падает, если `main.json` снова требует status checks или
code scanning, пока workflow не запускаются автоматически. **После merge этого
изменения администратор применяет rulesets заново**
(`apply-rulesets.ps1 -Apply`): до этого на GitHub действует прежний ruleset
с 15 обязательными проверками, и PR не вливается без обхода.

**Обход.** Администратор репозитория может влить PR в обход правил (режим
`pull_request`); прямой push в main после применения rulesets не проходит ни у
кого.

**CODEOWNERS.** Пока команд на GitHub нет, владелец всех путей — владелец
репозитория. Когда команды созданы, строки файла заменяются командами
соответствующих областей.

**Проверка.** Settings → Rules → Rulesets показывает три активных ruleset;
PR без одобрения не вливается; у `main` нет правил required status checks и
code scanning.

## 4. Порядок релиза (пункт 1.9)

Файл: [`.github/workflows/release.yml`](../../.github/workflows/release.yml).

1. **Gate:** тег — стабильный SemVer, коммит достижим из main.
2. **CI:** тот же `ci.yml`, что запускается вручную, вызванный через
   `workflow_call`, — набор проверок релиза равен CI, включая Gitleaks.
3. **Build:** образ собирается для `linux/amd64` и `linux/arm64` и
   публикуется по digest, **без тега**.
4. **Scan → attest → sign:** Trivy проверяет этот digest; затем provenance,
   подпись Cosign и SBOM.
5. **Tag:** только проверенный и подписанный digest получает тег версии
   (`docker buildx imagetools create --tag`). Упавший Trivy оставляет в GHCR
   лишь непомеченный digest, который нельзя получить по версии.
6. **Publish:** GitHub Release с бандлом Compose и контрольными суммами.

Порядок закреплён контрактом [`scripts/release/verify-release.ps1`](../../scripts/release/verify-release.ps1).

## 5. Ночные проверки (пункт 1.8)

Файл: [`.github/workflows/nightly.yml`](../../.github/workflows/nightly.yml).

Workflow `nightly` запускается только вручную (`workflow_dispatch`); расписания
нет с 2026-10-17. Его запускают перед релизом и после обновления зависимостей.
В нём учения готовности релиза, генератор `cms`
на Linux и Windows, учения обновления и исходящего трафика, живой API smoke,
onboarding smoke, свежие advisories (Trivy) и нагрузочные критерии
(`-Pupl-large`, `-Paudit-large`, `-Pimport-large`, `-Pwarehouse-large`).
[`scripts/docs/test-repository-hygiene.ps1`](../../scripts/docs/test-repository-hygiene.ps1)
падает, если скрипт `scripts/**/test-*` не вызывает ни один workflow.

| Настройка | Где | Зачем |
|---|---|---|
| Actions разрешены для репозитория | Settings → Actions → General | Без этого ручной запуск недоступен. |

**Проверка.** `gh workflow run nightly.yml` запускает его; Actions → nightly
показывает результат.

## 6. Подписанный staging RC (ADR-0040)

Пошаговая [инструкция настройки staging-release и доступов](staging-access-setup.md)
связывает GitHub settings с правами verifier, Hetzner SSH и Cloudflare/R2.
Она не заменяет отдельного разрешения на изменение settings или публикацию.

Файл [prerelease.yml](../../.github/workflows/prerelease.yml) публикует только
строгие теги `vX.Y.Z-rc.N`, N ≥ 1. Их покрывают существующие `v*` rulesets:
создание только release-администратором, перемещение/удаление запрещены.
Отдельного разрешения на публикацию не заменяют ни право создать тег,
ни утверждение бюджета staging.

До первого запуска администратор создаёт `staging-release` environment:
назначает independent required reviewer, включает prevent self-review,
запрещает обход protection rules и ограничивает deployment tags шаблоном
`v*.*.*-rc.*`. Семантику версии дополнительно проверяет gate. Проверить,
что тариф/тип репозитория действительно поддерживает эти защиты; отсутствие
действующего approval gate блокирует публикацию. YAML-файл не применяет
GitHub settings. Подготовить GHCR permissions и доступ к attestations;
долгоживущий signing key не требуется.

До разрешения публикации approved variables остаются отсутствующими.
После разрешения внутри environment задаются `STAGING_APPROVED_SOURCE_SHA` (одобренный
полный commit SHA) и `STAGING_APPROVED_VERSION` (точный `vX.Y.Z-rc.N`).
Запрещены одноимённые repository/organization vars: GitHub vars не позволяет
скрипту установить scope значения. Оба write jobs требуют точного совпадения;
пустое или устаревшее значение блокирует запись. Настраивать следующую пару
только после окончания предыдущего запуска; удалять approval vars после
выпуска. Это дополнительный gate, а не доказательство required reviewers.

Тег должен указывать на новый DCO SHA, включающий pipeline и прошедший полный
локальный CI. CI workflow повторяется на теге. Reviewer сверяет tag/SHA,
diff, артефакты локальных проверок и окно; `main` ancestry не требуется для RC.
Результат — prerelease, не latest, без deploy. После неудачного запуска
использовать следующий номер RC; не перемещать тег и не перезаписывать assets.
Только затем независимый verifier с `-Channel staging -ExpectedSourceSha ...`
проверяет реально опубликованные assets по
[ADR-0040](../adr/ADR-0040-signed-staging-prereleases.md).

На момент локальной реализации настройки GitHub не изменялись, workflow
не запускался и публикация не выполнялась. Первый live OIDC/GHCR/Release
прогон остаётся отдельным эксплуатационным gate.
