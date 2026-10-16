# ADR-0040: подписанный prerelease для staging и точная identity выпуска

**Дата:** 2026-10-16. **Статус:** принято для реализации по поручению владельца;
публикация, merge и изменение GitHub settings требуют отдельного разрешения.
Основание: NFR-SEC-07, AC-11; [staging-план](../ops/staging-acceptance.md).

## Контекст

Студия проверена локально на RC `bc559ddd`, но штатный release принимал только
`vX.Y.Z` с commit, достижимым из default branch. Это не позволяло получить
подписанные staging-артефакты до merge. Проверка image signatures сама по себе
не аутентифицировала Compose/scripts bundle: SHA256SUMS можно было заменить
вместе с архивом. `gh attestation verify --repo` не ограничивал ожидаемый SHA.

## Решение

Два отдельных workflow используют одну политику сборки и один bundle builder:

| Канал | Тег | Workflow / ancestry | Назначение |
|---|---|---|---|
| stable (default) | `vX.Y.Z` | `release.yml`, commit достижим из default branch | Стабильный выпуск |
| staging (явно) | `vX.Y.Z-rc.N`, N ≥ 1 без ведущих нулей | `prerelease.yml`, без требования merge в default branch | Только staging; GitHub prerelease, `--latest=false` |

Суффиксы beta/alpha, build metadata, неполные версии и RC с номером 0
отклоняются. Stable workflow не запускается на RC. PR/manual dispatch не
публикуют артефакты. Checkout/tag/event SHA должны совпадать; перед публикацией
требуются DCO и общий `ci.yml` на том же source. Внешние actions и базовые
образы остаются закреплёнными; шесть образов проходят build → Trivy →
GitHub attestation → Cosign → version tag. Ни `latest`, ни production deploy
не создаются prerelease workflow.

RC signing/publish jobs используют `staging-release`. Администратор заранее
создаёт защищённое environment с независимым reviewer, запрещает self-review
и ограничивает допустимые tags. Сам YAML не доказывает настройки GitHub.
В environment задаются `STAGING_APPROVED_SOURCE_SHA` (полный SHA) и
`STAGING_APPROVED_VERSION` (точный RC-tag). Оба write jobs проверяют их
равенство текущему event перед записью; отсутствие или отличие блокирует
выпуск. Одноимённые repository/organization vars запрещены политикой
настройки: выражение GitHub vars не доказывает источник значения. Проверка
самих reviewer/tag protections остаётся обязательным административным gate.

Повтор номера RC не является retry доставки: после неудачного запуска нужен
следующий номер и новое одобрение. Нельзя передвигать Git tags или перезаписывать
опубликованные assets; существующие `v*` tag rulesets распространяются и на RC.

`build_release_bundle.py` на Python standard library собирает те же Compose,
scripts, документацию и шесть metadata-наборов для обоих каналов. Дополнительно
создаёт `release.json` с schemaVersion 1, channel/version/sourceSha и шестью
ordered image refs. Он входит в SHA256SUMS вместе с `.tar.gz`, `.zip` и metadata.
После этого publish job создаёт отдельные `SHA256SUMS.sigstore.json` и
`SHA256SUMS.provenance.json`. Они не включаются в подписываемый manifest,
чтобы не создавать циклическую зависимость hashes.

Verifier из доверенного checkout сначала проверяет Cosign blob signature и
GitHub provenance SHA256SUMS, затем hashes и все шесть image signatures,
локальные image provenance bundles, SPDX/CycloneDX и `release.json`.
Внешние доверенные входы: repository, version, **ExpectedSourceSha**, channel
(default stable). Expected SHA нельзя брать только из скачанного `release.json`.

Cosign использует точный certificate identity workflow + tag и GitHub issuer.
`gh attestation verify` дополнительно ограничивает source ref, source digest,
signer digest и запрещает self-hosted runners. Это certificate constraints,
а не доверие к произвольным полям SLSA predicate. Для staging невозможно
неявно принять stable signer или наоборот. Используются именно поставленные
provenance bundles; их наличие без проверки не считается доказательством.

PASS schemaVersion 2 содержит channel/sourceSha/sourceRef/workflow/version,
SHA256SUMS hash и шесть digest refs. Неуспешный повтор удаляет прежний PASS.
Любой неизвестный/неполный вход, invalid signature/provenance, отсутствующий
asset или несовпадение identity завершает проверку ошибкой до запуска кода.
Старые unsigned bundles новым verifier не принимаются; до первого релиза
совместимость с ними не поддерживается.

Ansible передаёт `smc_release_channel` (stable default) и обязательный
`smc_expected_source_sha`. Staging RC допускается только с `smc_staging: true`
и проверенными observability/staging overlays. Порядок версий числовой:
`rc.2 < rc.10 < stable` одной базовой версии. Повтор и rollback сохраняют
source/channel identity и существующий fail-closed recovery contract.

## Проверка и границы

Локально проверяются реальные gate/bundle/consumer scripts, отказные пути
verifier, YAML/workflow policy, Ansible и репозиторий. External Cosign/GitHub
boundaries в unit tests заменены контролируемыми fixtures: это не свидетельство
реального OIDC issuance, GHCR upload или GitHub Release.

Перед первым внешним запуском: новый DCO SHA с этим workflow, полный CI на нём,
review tag/environment protections, явное разрешение владельца на push tag/
публикацию, затем verifier на настоящих assets. `bc559ddd` этого pipeline не
содержит; его прежний CI нельзя приписать новому SHA. Подписанный staging RC
не даёт production GO и не отменяет target acceptance/recovery/permissions.
Архитектура приложения, БД, API и runtime не меняются.

## Источники контракта инструментов

- [GitHub CLI attestation verify](https://cli.github.com/manual/gh_attestation_verify)
  — exact identity, source/signer digest, source ref, bundle и hosted runners.
- [GitHub CLI release create](https://cli.github.com/manual/gh_release_create)
  — prerelease, verify-tag и latest flag.
- [Sigstore signing blobs](https://docs.sigstore.dev/cosign/signing/signing_with_blobs/)
  — keyless signature и отдельный bundle для файла.
- [GitHub environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)
  — protection rules на стороне репозитория.
