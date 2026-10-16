# Staging: настройка GitHub и минимальных доступов

**Обновлено:** 2026-10-16. **Кандидат:**
`4a721df037e8274c3f1be56718e0319fec88c3dc`.

Инструкция для владельца и назначенного оператора перед
[staging-приёмкой](staging-acceptance.md). Она описывает будущие действия:
настройки аккаунтов, приглашения, ключи и ресурсы в рамках её подготовки
не создавались. Сейчас разрешена подготовка документов; закупки, публикация
и merge запрещены. Выдачу доступа и изменения облачных настроек выполнять
только после отдельного разрешения владельца с указанием получателя и scope.

Основание — [ADR-0040](../adr/ADR-0040-signed-staging-prereleases.md),
[workflow](../../.github/workflows/prerelease.yml),
[verifier](../../scripts/release/verify-published-release.ps1),
[preflight](../../scripts/acceptance/invoke-managed-preflight.ps1) и
[Ansible](../../deploy/ansible/deploy.yml). Инструкция изменена после RC;
её revision фиксировать отдельно, исполняемые scripts брать из чистого
checkout указанного SHA. Состав стенда, утверждённые бюджетные потолки,
RPO ≤ 24 ч, RTO ≤ 2 ч и upload p95/p99 ≤ 30/60 с остаются прежними.

## 1. Подготовить карточку доступа

Владелец заполняет в закрытом реестре одну карточку на человека или сервис:

| Поле | Что записать |
|---|---|
| Назначение | Release admin, independent reviewer, QA, deploy operator, app, backup, restore |
| Получатель | Именованная учётная запись или сервис; без общих паролей |
| Ресурсы | GitHub `owner/repo` и пакеты, Hetzner project/hosts, Cloudflare account/zone, R2 bucket |
| Права | Точный role/permission set из разделов ниже |
| Срок | Начало/конец окна UTC, срок ключа, ответственный за отзыв |
| Сеть | Согласованные исходящие IP/CIDR оператора и runner, маршрут к private endpoints |
| Секрет | Только ссылка на запись secret manager, владелец и дата ротации |
| Доказательство | Ссылка на обезличенный снимок настроек, результат проверки и отзыва |

Имена, IP и идентификаторы хранить в закрытом реестре, а не дописывать в этот
публичный шаблон. Пароли, API tokens, private SSH keys, S3 secret keys, содержимое
env, Docker/gh auth config и дампы не передавать в чат и не прикладывать к отчётам.
Существующие владельцы аккаунтов сохраняют ownership и billing. Для новых
операторов использовать персональные учётные записи с MFA.

**Минимальный набор для organization repository:** независимому reviewer нужен
GitHub Read; QA — чтение
репозитория/артефактов и нужных пакетов; cloud changes выполняет назначенный
оператор. Release workflow не развёртывает стенд и не получает Hetzner,
Cloudflare, R2 или SSH credentials. Приложение получает только свой S3 key.

## 2. GitHub: подготовить защищённый `staging-release`

### 2.1. Проверить возможность защиты

1. Зафиксировать точный `owner/repo`, его visibility, действующий тариф,
   release-администратора и независимого reviewer. Проверить, что RC SHA
   доступен в выбранном репозитории; если нет, отметить необходимость будущей
   отдельно разрешённой отправки коммита. Сейчас push не выполнять.
2. Проверить наличие environment required reviewers и artifact attestations.
   Для private/internal текущая схема требует GitHub Enterprise Cloud;
   private environments на Pro/Team сами по себе недостаточны. Для public
   проверить доступность защит на действующем плане. Если защита недоступна —
   STOP: записать ограничение для владельца; не менять visibility, не покупать
   тариф и не заменять gate ручной договорённостью.
   [Environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments),
   [attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations).
3. Настройку выполняет владелец личного репозитория либо repository Admin
   организации. В organization repository reviewer достаточно Read; он
   отличается от инициатора выпуска.
   QA не получает Admin/Write. На GitHub достаточно одного одобрения из списка
   required reviewers, поэтому список не является требованием кворума.
   [Роли](https://docs.github.com/en/organizations/managing-user-access-to-your-organizations-repositories/managing-repository-roles/repository-roles-for-an-organization),
   [reviewers](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).
4. Для personal private repository нет отдельной роли Read для collaborator:
   приглашение даёт Write. Если нужен такой доступ — STOP до решения владельца;
   не повышать права QA, не переносить репозиторий и не менять visibility
   автоматически. Public contents читаются без collaborator invitation;
   возможность назначения независимого environment reviewer проверить отдельно.
   [Personal repository permissions](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/repository-access-and-collaboration/permission-levels-for-a-personal-account-repository).

### 2.2. Настроить environment и теги после разрешения на настройки

1. Открыть **Repository → Settings → Environments → New environment**;
   имя ровно `staging-release`. Если environment уже есть — проверить его,
   не создавать второе. Сделать это до первого tag run: автоматически созданное
   workflow окружение не доказывает наличие protection rules.
2. В **Required reviewers** указать независимого reviewer/команду.
   Включить **Prevent self-review**. Выключить
   **Allow administrators to bypass configured protection rules**. Сохранить.
3. В **Deployment branches and tags** выбрать **Selected branches and tags**;
   добавить правило типа **Tag** с pattern `v*.*.*-rc.*`.
   Не добавлять разрешающие Branch rules или No restriction.
   Строгий формат `vX.Y.Z-rc.N`, N ≥ 1, проверяет сам workflow.
   [Настройка environment](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
4. Проверить **Settings → Rules → Rulesets**. Два отдельных активных ruleset
   должны совпадать с файлами ниже:

   | Ruleset | Ref | Правила и bypass |
   |---|---|---|
   | [Создание release tags](../../.github/rulesets/release-tags-create.json) | `refs/tags/v*` | Restrict creations; bypass только repository Admin |
   | [Неизменяемость release tags](../../.github/rulesets/release-tags-immutable.json) | `refs/tags/v*` | Restrict updates + deletions; bypass пустой |

   При настройке через UI использовать **New tag ruleset**, соответствующий
   pattern `v*`, перечисленные правила и **Enforcement status: Active**.
   Не объединять эти rulesets: bypass создания не должен разрешать перемещение
   или удаление тегов. Сверить сохранённый экспорт с JSON репозитория.
   [Правила тегов и bypass](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository).
5. Проверить также существующий `main` ruleset по
   [repository settings](repository-settings.md). Команда
   `scripts/github/apply-rulesets.ps1` без `-Apply` показывает план;
   `-Apply` изменяет **все три** ruleset, включая main. Это не команда узкой
   настройки staging; не запускать её без согласованного полного diff.
6. Пока публикация не разрешена, в environment **отсутствуют** обе переменные
   `STAGING_APPROVED_SOURCE_SHA` и `STAGING_APPROVED_VERSION`.
   Проверить отсутствие одноимённых variables на уровне repository и organization
   через **Settings → Secrets and variables → Actions → Variables**.
   При отсутствии прав на organization settings владелец организации подтверждает
   результат: нельзя считать невидимую область пустой.
7. Сохранить обезличенное evidence: repository/visibility/plan, environment,
   reviewer, self-review/bypass, ref type/pattern, оба ruleset и scopes variables.
   Снимки не должны содержать secrets или полный список участников организации.

### 2.3. Проверить права workflow и GHCR

**Settings → Actions → General** и organization policies должны разрешать
используемые pinned actions и следующие права из `prerelease.yml`:

| Job | `GITHUB_TOKEN` / OIDC permissions |
|---|---|
| По умолчанию | `{}` |
| `gate` | `contents: read` |
| Переиспользуемый `ci` | `actions: read`, `contents: read` |
| `build` — шесть образов | `contents: read`, `packages: write`, `id-token: write`, `attestations: write` |
| `publish` | `actions: read`, `contents: write`, `id-token: write`, `attestations: write` |

Оба записывающих job используют `staging-release`. Не добавлять постоянный
publisher PAT или signing private key: подпись выполняется через GitHub OIDC.
Не менять глобальный default на write ради этой инструкции: права заданы
в jobs и должны проходить политику организации.

Для существующих GHCR packages открыть **Package settings**: проверить связь
с выбранным source repository и его **Manage Actions access**. У workflow этого
репозитория должен быть доступ на запись; не давать доступ всем репозиториям
организации. Новые packages впервые появятся только при разрешённой публикации.
[GHCR authentication/access](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

### 2.4. Выдать потребителю доступ к артефактам

1. QA/verifier: organization repository **Read** и **Read** к шести продуктовым GHCR packages
   `server`, `web`, `backup`, `postgres`, `typesense`, `clamav` соответствующего
   namespace. Проверить фактические имена по workflow/manifest. Дополнительные
   monitoring images проверяются по конфигурации стенда отдельно.
2. Для закрытых GHCR packages: персональный **PAT classic**, только
   `read:packages`, с конечным сроком и SSO authorization, если её требует
   организация. Учётной записи нужен Read к самим пакетам. Не выдавать
   `write:packages`, `delete:packages` или `repo` только ради pull. Fine-grained
   PAT не заменяет GHCR classic token.
   [GHCR tokens](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).
3. Если требуется отдельный API token для закрытых release assets и CI evidence:
   **Settings профиля → Developer settings → Personal access tokens →
   Fine-grained tokens**, выбранный resource owner, **Only select repositories**,
   один репозиторий, срок до конца согласованного окна. Разрешения:
   **Contents: Read** для release assets, **Actions: Read** для logs/run evidence;
   **Attestations: Read** добавлять при использовании API attestations.
   Сначала проверить, что учётная запись может выбрать нужный resource owner.
   Outside collaborator закрытого organization repository не может использовать
   для него fine-grained PAT. В таком случае STOP: владелец определяет ограниченную
   integration/identity; не выдавать автоматически classic `repo` или более широкое
   membership. [Ограничения PAT](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).
   Для bundled-проверки текущего verifier это отдельное API permission само по
   себе не требуется. Если token требует одобрения организации — дождаться его.
   [Assets](https://docs.github.com/en/rest/releases/assets),
   [Actions logs](https://docs.github.com/en/rest/actions/workflow-jobs),
   [attestations API](https://docs.github.com/en/rest/repos/attestations).
4. Передать значения через secret manager. GHCR и GitHub API используют разные
   scoped credentials; не расширять один PAT до универсального.
   Авторизацию Docker/gh настроить без секретов в аргументах команд, истории
   shell и transcript; сохранить private configs под **фактической OS identity**
   deploy/restore после `become`, с правами только владельца.
5. Проверить чтение assets и pull точного digest в разрешённом окне. Полный
   [verifier](../../scripts/release/verify-published-release.ps1) запускать с
   `-Channel staging`, независимо утверждёнными `-Repository`, `-Version` и
   `-ExpectedSourceSha`; не брать доверенный SHA из скачанного `release.json`.
   Доступ к GitHub/GHCR и trust services из PRC-06 обязателен.

Текущий [deploy launcher](../../deploy/ansible/files/deploy_release.py) передаёт
subprocess `HOME`, `DOCKER_CONFIG`, `GH_CONFIG_DIR`, но не `GH_TOKEN` /
`GITHUB_TOKEN`. Авторизации только в интерактивном shell недостаточно. Проверка
подписи bundle, доступ к private registry и право читать CI evidence — разные
проверки; успешный login не заменяет ни одну из них.

### 2.5. Отдельный будущий допуск к публикации

Только после разрешения владельца на конкретные repository/tag/SHA/окно
release-admin задаёт **в environment**:

| Variable | Значение |
|---|---|
| `STAGING_APPROVED_SOURCE_SHA` | `4a721df037e8274c3f1be56718e0319fec88c3dc` |
| `STAGING_APPROVED_VERSION` | Точный свободный `vX.Y.Z-rc.N`; версия ещё не выбрана |

Это момент допуска записи в GHCR/GitHub, а не часть сегодняшней подготовки.
Пара сохраняется до завершения всего run, затем удаляется. Reviewer проверяет
разрешение, точный tag/SHA, DCO и evidence полного локального CI до одобрения
защищённых jobs. Два вида jobs не создают автоматически двух независимых ревью.
Workflow запускается tag push, без deploy; RC не обязан быть достижим из main.
После failure использовать новый разрешённый RC-номер: rerun запрещён,
перемещение тега и перезапись assets запрещены.

## 3. Hetzner: доступ к проекту и хостам

1. Зафиксировать отдельный staging project и его Owner. Создание платных
   серверов/volumes и временного restore host остаётся будущим provision-этапом
   утверждённого [плана](staging-acceptance.md). Для инструкции ресурсы не нужны.
2. Владелец и назначенные операторы включают **Hetzner Accounts → Settings →
   Two-factor authentication → Enable 2-FA**; recovery material хранится в escrow.
   [Hetzner 2FA](https://docs.hetzner.com/general/security-and-identify/two-factor-authentication/).
3. Для текущих preflight/deploy scripts **не нужен Hetzner API token**.
   SSH-оператору не требуется приглашение в Hetzner Console. Если Console
   действительно нужен для утверждённой задачи: **Project → Security → Members →
   Add Member**, именованный пользователь, минимальная подходящая роль.
   `Owner`, `Admin`, `Member`, `Restricted` не включают роль Reader;
   `Restricted` всё ещё может изменять ресурсы. Для read-only аудита её не выдавать.
   Owner/billing сохраняет владелец; Admin нужен для управления участниками/токенами,
   а Member допускает создание/удаление ресурсов.
   [Роли проекта](https://docs.hetzner.com/cloud/general/faq/).
4. Только для отдельной API-инвентаризации при необходимости:
   **Project → Security → API Tokens → Generate API token → Read**.
   Токен действует в одном project, `Read` допускает GET. Сохранить его в secret
   manager; в GitHub и приложение не передавать. Встроенные TTL/IP filtering
   и scope на отдельный сервер в исследованных документах не подтверждены:
   назначить ручной отзыв в конце окна, не обещать этих ограничений.
   [Создание токена](https://docs.hetzner.com/cloud/api/getting-started/generating-api-token/),
   [scope API](https://docs.hetzner.com/cloud/api/getting-started/using-api/).
5. Оператор передаёт только **SSH public key** и свой IP/CIDR. Существующий
   администратор добавляет ключ именованному OS-пользователю на согласованных
   app/ops/restore hosts. Ключ в Hetzner Console не устанавливается автоматически
   на уже существующий сервер.
   [Ключи существующего сервера](https://docs.hetzner.com/cloud/servers/how-to-rescue/change-ssh-key/).
6. Получить host fingerprints независимым доверенным административным каналом;
   заполнить private `known_hosts`. Сохранить `StrictHostKeyChecking=yes` из
   [Ansible inventory](../../deploy/ansible/inventory.example.yml).
   `ssh-keyscan` без независимой сверки не удостоверяет сервер. SSH allowlist
   ограничить утверждёнными IP/маршрутами; private DB/management endpoints
   не открывать для упрощения доступа.
7. Deploy/recovery требуют `become: true` и `sudo -n`: это административные
   права на выделенных staging-хостах. Read-only аудит выполнять через отдельную
   учётную запись/предоставленное evidence; не обещать ей выполнение всего playbook.
   Согласовать sudo scope по реальным командам. Группа `docker` даёт root-level
   privileges и не является безопасной заменой sudo.
   [Docker privilege model](https://docs.docker.com/engine/install/linux-postinstall/).
8. Проверить вход с разрешённого адреса, host identity и отсутствие доступа к
   посторонним хостам в согласованном scope. В конце окна удалить временные
   ключи, токены и memberships; проверить отказ отозванной учётной записи.

## 4. Cloudflare: один проверочный токен для текущего runner

### 4.1. Выдать только необходимые read permissions

1. Зафиксировать account ID, zone ID, FQDN staging и исходящий IP runner.
   Права DNS/WAF здесь ограничиваются **зоной**, не одним FQDN. Если зона общая
   с production, это явно отразить в карточке доступа.
2. Назначенный владелец создаёт ограниченный custom token через **My Profile →
   API Tokens → Create Token → Custom token**. Для постоянной service integration
   можно использовать **Manage account → Account API tokens**; создавать такой
   token может Super Administrator либо участник с API Token Provisioning,
   только в пределах собственных permissions. Получателю проверочного токена
   эти административные роли не нужны.
   [Account tokens](https://developers.cloudflare.com/fundamentals/api/get-started/account-owned-tokens/).
3. Добавить следующий набор, **Include → Specific zone** и
   **Include → Specific account**. Не выбирать All zones/All accounts.

   | Permission | Scope | Что читает `invoke-managed-preflight.ps1` |
   |---|---|---|
   | `Zone → DNS → Read` | Указанная zone | `GET /zones/{zone}/dns_records?name={fqdn}` |
   | `Zone → Zone Settings → Read` | Та же zone | `GET /zones/{zone}/settings/{always_use_https,ssl,min_tls_version}` |
   | `Zone → Zone WAF → Read` | Та же zone | `GET /zones/{zone}/rulesets/phases/{http_request_firewall_managed,http_ratelimit}/entrypoint` |
   | `Account → Workers R2 Storage → Read` | Указанный account | `GET /accounts/{account}/r2/buckets/{bucket}`; app bucket `domains/managed`, `domains/custom`, `cors`, `lifecycle`; metadata recovery bucket |

   Имена сверены с официальными
   [permissions](https://developers.cloudflare.com/fundamentals/api/reference/permissions/),
   [DNS API](https://developers.cloudflare.com/api/resources/dns/subresources/records/methods/list/),
   [Zone Settings API](https://developers.cloudflare.com/api/resources/zones/subresources/settings/methods/get/)
   и [zone rulesets](https://developers.cloudflare.com/ruleset-engine/basic-operations/view-rulesets/).
   При изменении UI проверять семантику permission, а не добавлять Edit для обхода 403.
4. Установить **Client IP Address Filtering** на реальные исходящие IP runner
   и **TTL** на согласованное окно. **Continue to summary**: сверить scope,
   permissions, срок; **Create Token**, однократно сохранить в secret manager.
   [Создание custom token](https://developers.cloudflare.com/fundamentals/api/get-started/create-token/).
5. Передать runner через process environment только `CLOUDFLARE_API_TOKEN`.
   В текущем RC это **один** token для zone и R2 REST GET: два раздельных токена
   нельзя подставить без изменения runner. Запись в `.env.acceptance` не требуется.
6. Проверить GET из таблицы; сохранить status и проверенные свойства без auth
   headers/полных ответов. 401/403 — проверить срок, IP, scope и permission;
   404 ruleset — проверить наличие требуемого правила. Недостаток прав или правила
   не является PASS и не исправляется выдачей глобального API key.

**Граница привилегий R2:** `Workers R2 Storage Read` позволяет читать не только
bucket configuration, но и objects в account. Object Read only на отдельный
bucket не заменяет REST permission. Этот account token не выдавать приложению
и не оставлять на хосте после проверки. Если account содержит другие данные,
проверку выполняет их уполномоченный оператор без передачи широкого токена QA;
если это неприемлемо — доступ остаётся блокером, а не скрытым расширением прав.
[R2 permissions](https://developers.cloudflare.com/r2/api/tokens/).

### 4.2. Доступ на изменение edge-конфигурации

По умолчанию существующий владелец сам применяет согласованный diff DNS/TLS/WAF.
Если делегируется API-изменение, выдать **отдельный** короткоживущий token ровно
на нужные операции: DNS Edit/Write, Zone Settings Edit/Write, Zone WAF Edit/Write
на одну зону; не добавлять R2/Workers/Account administration. Это доступ ко всей
выбранной зоне, поэтому для общей production-зоны требуется явное согласование
scope. После применения diff отозвать write token; проверять read token из §4.1.
Новые продукты Cloudflare и изменение тарифа этой инструкцией не разрешаются.

## 5. R2: отдельные S3 keys по назначению

1. Проверить, что R2 уже активирован и утверждённые buckets существуют.
   Если интерфейс требует подключения/покупки R2 — STOP до разрешения владельца.
   Схема сохраняет три private buckets: application, recovery, restore;
   их создание и настройки lifecycle/lock относятся к provision-этапу плана.
   [R2 prerequisites](https://developers.cloudflare.com/r2/api/tokens/).
2. **R2 object storage → Overview → Account Details → API Tokens → Manage**;
   для service keys выбрать **Create Account API token**. Для каждой строки
   таблицы создать отдельный token, выбрать Object permission и
   **Apply to specific buckets only**, ровно указанный bucket. Для разовой работы
   допустим User token с известным владельцем; не использовать его как неучтённую
   постоянную зависимость runtime.
   [S3 token setup](https://developers.cloudflare.com/r2/get-started/s3/).

   | Назначение / получатель | Bucket | Object permission | Передача |
   |---|---|---|---|
   | Приложение | application | Read & Write | `SMC_S3_ACCESS_KEY`, `SMC_S3_SECRET_KEY`, через private runtime config |
   | Копирование исходных objects | application | Read only | Только source-reader компоненту recovery |
   | Запись/проверка backup | recovery | Read & Write | Backup/recovery writer, через отдельные private secret files |
   | Чтение backup для restore | recovery | Read only | Только restore operator/process на время учения |
   | Запись восстановленных objects | restore | Read & Write | Только restore operator/process на время учения |
   | S3 roundtrip preflight | application | Read & Write | Отдельная временная пара `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` в процессе runner |

   Это проектное распределение существующих object permissions. Writer не является
   write-only. Префиксы `acceptance/`, `backups/`, `object-backups/` не сужают
   полномочия обычного bucket token; защита backup retention задаётся lock из
   [staging-плана](staging-acceptance.md). Runtime не получает Admin Read & Write.
3. Проверить выбранный bucket и срок. Сохранить **Access Key ID** и
   **Secret Access Key** в secret manager при выдаче; не вставлять bearer API token
   в поле S3 secret. Зафиксировать владельца, expiry/ротацию и отзыв. Для постоянных
   app/backup keys срок должен учитывать окно ротации; краткий срок учений не должен
   неожиданно остановить приложение или backup.
4. Сохранить утверждённые endpoint `https://<account-id>.r2.cloudflarestorage.com`,
   `region=auto`, path-style, default jurisdiction и `weur` placement hint из
   плана. Подтвердить фактическое размещение; hint не гарантирует EU residency.
   Не менять jurisdiction/endpoint при выдаче ключей.
   [Шаблон runtime](../../deploy/staging/.env.staging.example),
   [шаблон acceptance](../../deploy/staging/.env.acceptance.example).
5. Для текущего Java runtime использовать пару access/secret: его
   [S3 configuration](../../apps/server/src/main/java/com/smartup24/cms/instance/mf/storage/S3StorageConfiguration.java)
   использует `AwsBasicCredentials`. STS/session credentials требуют отдельной
   реализации и проверки; их нельзя подставлять вместо двух ключей RC.

Создание buckets и изменение lifecycle/locks требуют отдельного control-plane
оператора; соответствующие account-wide write permissions не передаются app,
backup или QA. Для текущей подготовки выдавать provisioning token не требуется.

## 6. Проверка выдачи и закрытие окна

| Проверка | Критерий готовности | Evidence без секретов |
|---|---|---|
| GitHub protections | Environment заранее создан; reviewer независим; self-review запрещён; admin bypass выключен; только RC tags; оба ruleset Active | Снимок/экспорт settings и роли проверяющего |
| Публикация ещё не разрешена | Approved vars отсутствуют во всех трёх scopes; тег не создан | Подтверждение release-admin и org admin |
| Права потребителя | Read assets/logs и нужных packages; нет publish/admin | Перечень scope и результатов чтения |
| Hetzner/SSH | Только согласованные project/hosts; fingerprint проверен; роль оператора честно отражает sudo/Docker | Идентификатор ключа, allowlist, результат входа |
| Cloudflare | GET из §4.1 успешны; запись не выдана; граница account R2 принята | Проверенные свойства/token scope/TTL, без token value |
| R2 S3 | Отдельные keys на указанные buckets; чужие buckets недоступны; read-only ключи не дают записи | Policy review и результаты разрешённых synthetic checks |
| Отзыв | Временные tokens, memberships, keys отозваны; runtime keys остаются по реестру ротации | UTC, ответственный, отказ отозванного доступа |

Проверки S3 записи/удаления выполнять только после допуска к target-приёмке,
на выделенном синтетическом объекте. Не доказывать отрицательные права попыткой
удалить реальный object/bucket или изменить DNS production. Scope за пределами
стенда сначала проверяется по policy; любые live negative probes согласуются
с владельцем этих ресурсов. Для read-only ключа отказ записи подтверждать
только на разрешённом synthetic key, который оператор сможет убрать при ошибке.

Полный `invoke-managed-preflight.ps1` **не read-only**: помимо GET он выполняет
S3 put/head/get/delete и alert drill. Сейчас его не запускать ради проверки
токенов. После допуска выполнить его и остальные PRC/STG-сценарии по
[staging-плану](staging-acceptance.md), включая
[Студию](studio-staging-acceptance.md). Подписи считаются проверенными только
по реальным assets/digests и PASS receipt schemaVersion 2, а staging — готовым
только после функциональной, нагрузочной и recovery-приёмки.

## 7. Что нужно от владельца для следующего шага

1. `owner/repo`, visibility/plan, release-admin и независимый reviewer;
   подтверждение доступности required reviewers/attestations.
2. Hetzner project, Cloudflare account/zone/FQDN, три имени bucket;
   отметка, какие ресурсы уже существуют и где потребуется будущий provision.
3. Именованный оператор, public SSH key, IP/CIDR runner, доверенный канал
   передачи host fingerprints и доступ к secret manager по ссылке.
4. Допустимость account-wide R2 read только для оператора проверки, scope/sрок
   каждого доступа и ответственный за отзыв; либо выполнение этой проверки
   самим владельцем account.
5. Разрешение на конкретные настройки/выдачу доступа. Публикация на точном
   свободном RC-tag и provision/deploy остаются отдельными последующими решениями.

Повторного утверждения архитектуры, размеров, бюджета, RPO/RTO и upload SLO
не требуется. Передача секретных значений для ответа на эти пункты не нужна.
