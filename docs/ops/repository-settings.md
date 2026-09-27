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
| `main` | Изменения только через PR: одно одобрение, ревью владельца кода (CODEOWNERS), повторное одобрение после нового push, закрытые обсуждения; только merge-коммит; зелёные обязательные проверки; CodeQL без предупреждений уровня high и выше; удаление и force-push запрещены. |
| `release tags: created by administrators only` | Тег `v*` создаёт только администратор. |
| `release tags: never moved or deleted` | Тег `v*` нельзя передвинуть или удалить никому, исключений нет. |

**Только merge-коммит.** Squash и rebase переписывают коммиты, а
[`.git-blame-ignore-revs`](../../.git-blame-ignore-revs) ссылается на коммиты
переформатирования по SHA.

**Обязательные проверки** — имена джобов: `backend (mvn verify + ArchUnit + SBOM)`,
`frontend (unit + typecheck + build)`,
`release config (Compose + NGINX + fail-closed deploy)`,
`e2e (clean deploy + Playwright Chromium, shard 1/2)` и `… shard 2/2`,
`security (gitleaks + trivy)`, `Verify commit sign-offs`,
`codeql (java-kotlin)`, `codeql (javascript-typescript)`, `codeql (actions)`.
`test-rulesets.ps1` в CI падает, если обязательной проверке не соответствует ни
один джоб: переименование джоба без правки `main.json` не пройдёт. Прежняя
проверка `e2e (clean deploy + Playwright Chromium)` после разбиения на шарды
больше не существует: если она указана в старой защите ветки, её нужно
заменить применением rulesets.

**Обход.** Администратор репозитория может влить PR в обход правил (режим
`pull_request`); прямой push в main после применения rulesets не проходит ни у
кого.

**CODEOWNERS.** Пока команд на GitHub нет, владелец всех путей — владелец
репозитория. Когда команды созданы, строки файла заменяются командами
соответствующих областей.

**Проверка.** Settings → Rules → Rulesets показывает три активных ruleset;
PR без одобрения или с красной проверкой не вливается.

## 4. Порядок релиза (пункт 1.9)

Файл: [`.github/workflows/release.yml`](../../.github/workflows/release.yml).

1. **Gate:** тег — стабильный SemVer, коммит достижим из main.
2. **CI:** тот же `ci.yml`, что и на каждом PR, вызванный через
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
