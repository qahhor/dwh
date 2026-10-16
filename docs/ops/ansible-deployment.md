# Развёртывание проверенного release bundle через Ansible

Playbook `deploy/ansible/deploy.yml` развёртывает уже опубликованный и отдельно
одобренный релиз на подготовленном Linux Docker host. Он не создаёт инфраструктуру,
не устанавливает пакеты, не изменяет firewall и не устанавливает recovery coordinator
или его storage credentials.
Контроллер запускается из проверенного checkout репозитория. Удалённый deploy
не выполняется при локальных контрактных тестах.

Почему важно: перед запуском кода проверяются доверенный checksum manifest,
подписи и attestations образов; перед изменением существующей установки требуется
новый полный recovery set. Риски: обновление останавливает запись и имеет окно
недоступности. HA и обновление без простоя не предоставляются.

## Подготовка

1. Linux-контроллер: Python 3.12+, `ansible-core==2.21.5` из
   `deploy/ansible/requirements-controller.txt`, OpenSSH и проверенный checkout.
   Установка инструментов — отдельная согласованная операция.
2. APP: `/usr/bin/python3` версии не ниже 3.11, Bash, работающий Docker Engine,
   Docker Compose не ниже 2.24.4, PowerShell, cosign, GitHub CLI и OpenSSH client.
   Точные одобренные версии пяти CLI задаются в `smc_prerequisites`; несовпадение
   прекращает deploy. Пример фиксирует cosign 3.1.3; остальные значения требуется
   явно заполнить версиями подготовленного хоста. Пользователь Ansible имеет
   согласованные права `become: root`. Для registry/attestation verification
   заранее подготовлены root Docker/GitHub CLI credentials и сетевой доступ.
3. Скопировать `inventory.example.yml` и `vars.example.yml` в приватный каталог
   контроллера. Заполнить адрес, пользователя, проект, репозиторий, версию,
   абсолютные пути к локальным release assets и private configuration.
   Inventory не содержит паролей. Использовать один `smc_private_root` для одного
   управляемого проекта на хосте: его lock разделяется всеми контроллерами.
4. Вручную проверить fingerprints APP и OPS через доверенный канал и заранее
   подготовить `known_hosts`. `StrictHostKeyChecking=yes` обязателен; playbook не
   выполняет `ssh-keyscan` и не принимает новый ключ автоматически. Пример inventory
   указывает отдельный файл контроллера. APP → OPS использует заранее установленный
   root SSH alias `smartupcms-ops` и собственный закреплённый host key. Этот же alias
   представлен в inventory группе `smartupcms_recovery`: controller имеет SSH и
   become доступ к OPS для установки фиксированного snapshot helper.
5. Передать полный каталог assets релиза, включая `.tar.gz`, `.zip`, `SHA256SUMS`
   и metadata всех шести образов. `smc_release_checksums_sha256` — SHA-256 самого
   `SHA256SUMS`, полученный из независимо проверенного release evidence. Вычислить
   его из только что скачанного непроверенного архива недостаточно: manifest из
   того же источника сам по себе не является основанием доверия.
6. Подготовить приватный environment и отдельные файлы четырёх разных DB passwords.
   Значения inline и соответствующих файлов должны совпадать согласно production
   Compose/bootstrap contract. Перечень `smc_secret_files` содержит только имена,
   переменные `*_FILE` и пути. Для S3/observability добавить соответствующие файлы.
   Environment, credentials и rendered Compose никогда не помещаются в bundle.
   На контроллере хранить их с ограниченными правами; на APP runner пишет `0600`
   внутри root каталогов `0700`. Snapshot credential files принадлежат UID/GID
   10001 (backup/bootstrap/server), а `MONITOR_DB_PASSWORD_FILE` — UID/GID 65534
   (exporter), чтобы контейнер читал bind mount без ослабления `0600`.

`smc_expected_source_sha` обязателен: полный SHA из независимо одобренного
кандидата. `smc_release_channel` по умолчанию `stable`; `vX.Y.Z-rc.N` требует
явного `smc_release_channel: staging` и `smc_staging: true`. Playbook и runner
проверяют оба признака, передают их verifier и сохраняют в release identity.
Весь каталог assets включает подпись и provenance SHA256SUMS; неподписанный
manifest не принимается даже при правильных image signatures. Старый
`smc_release_checksums_sha256` остаётся независимым входом проверки.
Для одной базовой версии порядок `rc.2 < rc.10 < stable`; переход назад
сохраняет требования rollback approval/recovery. Новый RC не является
разрешением на автоматическое обновление production.

При `smc_staging: true` к production Compose добавляются проверенные исходники
`deploy/observability/docker-compose.observability.yml` и
`deploy/staging/docker-compose.staging.yml`. Квоты scratch, monitoring TLS/config,
monitor role, S3 bucket credentials и остальные обязательные значения готовятся
заранее по [staging acceptance](staging-acceptance.md). Playbook не создаёт эти
внешние ресурсы. Вспомогательные образы overlays также должны быть digest-pinned;
подпись release workflow проверяется для шести product images.

## Обязательное recovery перед обновлением

Полный набор снимается на OPS, где находится согласованный recovery scratch.
APP не используется для временного хранения полного object archive. До обновления
существующих контейнеров или PostgreSQL volume runner требует `smc_recovery_host`:
простой SSH alias без аргументов командной строки.

На OPS заранее установлен `deploy/recovery/smartupcms-recovery.service`, его
проверенный coordinator и `/etc/smartupcms/recovery.json`. Runtime environment
настраивает `DOCKER_HOST=ssh://smartupcms-app`; root SSH configuration и known_hosts
расположены вне home, как в `deploy/recovery/ssh_config.example`.
Recovery config должен обращаться именно к текущему APP project и его актуальным
Compose/env/secret files. Нельзя подставить новый release config до получения kit.
При первом запуске эта связь подготовлена оператором. После успешного старта APP
playbook автоматически передаёт rendered Compose snapshot на OPS через encrypted
SSH stdin. Установленный им fixed helper
`/opt/smartupcms/deployment/install_recovery_snapshot.py` принимает не более 16 MiB,
проверяет project, digest-pinned images и read-only server/backup/tools, удерживает
тот же `.recovery.lock`, что coordinator, и отказывает при активном unit.
Helper атомарно записывает `0600` snapshot и пустой env в `/etc/smartupcms`, затем
меняет `composeFiles`/`envFile` и подтверждённый `expectedSource` в recovery config. S3 settings, credentials,
лимиты и scratch path сохраняются. Таким образом следующий capture использует
актуальные APP images/config. Пути Docker secret mounts остаются путями APP:
их обработка remote Compose входит в обязательный target integration check.

До capture runner получает identity реального APP daemon через локальный Docker
socket и inspect существующих server/backup/postgres: проверяются Compose labels,
config hashes, PostgreSQL mount, DB names, object source и AGE recipient.
OPS helper получает expected identity через приватный SSH stdin и сверяет её
с тем же remote daemon и собственными Compose/storage settings. Проверяются
backup image и Docker networks, чтобы one-shot backup не мог уйти к другому
PostgreSQL. Expected source сохраняется в private deployment state и recovery
config; изменение daemon, data mount или storage source отклоняется.
Coordinator повторяет проверку под recovery lock до остановки writers, а PASS
status и complete manifest содержат `sourceProject` и `sourceIdentity`. Runner
сравнивает их с ожидаемым источником: свежий kit другой установки не подходит.
Future Compose проверяется до deploy: bucket/endpoint и AGE recipient должны
соответствовать подготовленному OPS config.
APP → OPS workflow требует S3 object source. `local_disk` на удалённом APP
отклоняется: одинаковый путь на OPS не подтверждает, что это файлы APP. Локальный
coordinator на том же хосте допускает явно проверенный bind mount.

Runner выполняет только фиксированные команды через SSH с BatchMode и строгой
проверкой ключа:

- `sudo -n systemctl show smartupcms-recovery.service` с фиксированным списком
  свойств `Result,ActiveState,ExecMainStatus,ExecMainStartTimestampMonotonic,ExecMainExitTimestampMonotonic`;
- `date +%s` для времени OPS;
- `sudo -n systemctl start smartupcms-recovery.service` и ожидание его завершения;
- `sudo -n cat /var/lib/smartupcms/recovery/status.json`;
- `sudo -n /usr/bin/python3 /opt/smartupcms/deployment/install_recovery_snapshot.py`
  с ограниченным JSON на stdin: future-config check, current-source preflight или
  postdeploy snapshot. Произвольных argv нет. Helper читает закреплённый
  `DOCKER_HOST=ssh://<APP alias>` из приватного recovery-runtime.env.

Узкие sudo rules для recovery SSH account должны разрешать ровно эти формы
`systemctl`, фиксированный `cat` и этот helper, без произвольных команд и путей. Status остаётся
`root:root 0600`. Уже работающий unit отклоняется: обновление надо повторить после
завершения capture. Начальное terminal state `failed` допускает новую попытку
после исправления причины ошибки. Проверяются новый monotonic start timestamp, успешный exit,
inactive state и schemaVersion 1 / PASS / setId / lastSuccessPoint из status.
Точка должна быть создана именно этим запуском и иметь возраст не более 24 часов;
вчерашний успешный status не заменяет новое capture. Часы сравниваются на OPS.

Coordinator проверяет backup control protocol 1 и сохраняет начальное состояние
writers. Он останавливает только работающие server/backup, снимает CMS + DWH +
objects, возвращает только ранее работающие services, загружает набор и проверяет
полное чтение с checksum. Только после его успеха runner останавливает server и
backup снова и вызывает существующий `scripts/prod/deploy.sh`.
Неподдерживаемый backup protocol или ошибка capture прекращают deploy; ручной
recovery разбор выполняется по эксплуатационному runbook.
После failed migration повторный capture поддерживает уже остановленные writers
и не включает старый server на неизвестной схеме. При неудаче собственный detached
capture container удаляется до восстановления ранее работающих services.

## Запуск и проверка

Запускать из `deploy/ansible`, чтобы применялся приложенный `ansible.cfg`:

```bash
ansible-playbook -i /secure/smartupcms/inventory.yml deploy.yml \
  -e @/secure/smartupcms/deployment.yml --syntax-check
ansible-playbook -i /secure/smartupcms/inventory.yml deploy.yml \
  -e @/secure/smartupcms/deployment.yml --check
ansible-playbook -i /secure/smartupcms/inventory.yml deploy.yml \
  -e @/secure/smartupcms/deployment.yml
```

`--check` проверяет структуру входов и локальный доверенный manifest, не пишет на
managed hosts и не запускает registry verification, recovery или Docker.
Это не проверка готовности remote target. Такой явный guard нужен, поскольку
поддержка check mode зависит от модуля ([Ansible check mode](https://docs.ansible.com/projects/ansible-core/stable-2.21/playbook_guide/playbooks_checkmode.html)).
SSH transport и список аргументов опираются на стандартные
[SSH connection](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/ssh_connection.html)
и [command argv](https://docs.ansible.com/projects/ansible/latest/collections/ansible/builtin/command_module.html).

При реальном запуске Ansible обрабатывает по одному хосту (`serial: 1`), создаёт
приватный incoming directory и удаляет только этот staging directory в `always`.
Runner удерживает nonblocking process lock на APP. Сначала сверяется независимо
закреплённый manifest, затем запускается strong verifier из доверенного checkout:
checksums всех assets, точная release workflow identity для cosign и OCI
attestation для каждого product image. Только потом распаковывается tar:
разрешены обычные файлы/каталоги внутри ожидаемого release prefix; ссылки,
устройства, абсолютные и traversal paths запрещены.

Релизы сохраняются в `releases/<version>-<manifest SHA>/bundle`. Изменить уже
сохранённое содержимое или заменить тот же version другим manifest нельзя.
Compose JSON собирается из prod и optional overlays; все образы должны быть
immutable references, build запрещён. ENV и secret snapshots находятся отдельно
в `configurations/`. Идентичность config — HMAC с приватным host key; secret hashes
и значения не выводятся. Copy/template/command tasks имеют `no_log`, diff отключён.
Не включать сторонние callback plugins или отладочный вывод private variables.

При повторе того же релиза и config с работающими healthy контейнерами нужных
images возвращается `UNCHANGED`. Registry verification всё равно повторяется.
Подготовительные временные файлы не учитываются как application change.
Изменение config требует обычного обновления с recovery. Ротация DB credentials
должна сохранять согласованность ролей, inline значений и файлов; playbook не
подменяет отдельную процедуру ротации PostgreSQL passwords.

## Ошибка, повтор и rollback plan

Приватный `state.json` содержит последнюю попытку, успешный current/previous
release и durable migration boundary. Он записывается до вызова deploy.sh.
Содержимое нельзя помещать в публичные логи: там есть пути приватных snapshots.

- До начала deploy.sh ошибка после остановки writers возвращает только исходные
  container IDs через `docker start`; пересоздания старых контейнеров нет.
- После входа в deploy.sh любая ошибка останавливает server и backup. Автоматически
  включать прежний образ на потенциально новой схеме запрещено. Повтор допускает
  тот же immutable release либо более новую версию. Снижение версии запрещено и
  после аварии процесса благодаря migration boundary.
- Если readiness failure успел пересоздать backup, runner перед повторным capture
  устанавливает на OPS сохранённый проверенный snapshot именно failed attempt.
  Он обязан совпадать с фактическим backup image и прежним data source; затем
  повторяются полные source/config checks. При forward retry ещё не запущенный
  будущий snapshot для capture не используется.
- При ошибке cleanup требуется ручная проверка APP. Успешный recovery kit не
  означает автоматически безопасный application rollback.
- Если APP уже healthy, а передача snapshot на OPS не удалась, сохраняется фаза
  `POSTDEPLOY_SYNC` и deploy не считается успешным. Новый APP продолжает работать;
  повтор с тем же release/config выполняет только sync, без capture и миграций.
  Другой release/config до завершения pending sync отклоняется. После обрыва
  процесса непосредственно в фазе `DEPLOYMENT` требуется ручная проверка состояния;
  runner не угадывает, завершились ли миграции.
- Базы автоматически не восстанавливаются. Этот playbook не выполняет rollback.

Для review плана можно выбрать `smc_action: rollback-plan` и приватный
`smc_rollback_approval_source` с JSON полями:

```json
{
  "targetVersion": "v1.2.3",
  "targetChecksumsSha256": "<manifest SHA of successful previous release>",
  "currentChecksumsSha256": "<manifest SHA at latest migration boundary>",
  "applicationRollbackTested": true,
  "evidenceReference": "<reviewed current-schema compatibility test evidence>"
}
```

Нужен реальный успешный previous release в private state и отдельно проверенная
совместимость именно с текущей схемой. Runner пишет `rollback-plan.json` с
`executeSupported: false` и argv для review. Никакая Docker команда в этом режиме
не выполняется. Перед ручным выполнением необходимы повторная проверка целевого
релиза, review совместимости и свежий recovery set. `action: rollback` отклоняется
явно; generated plan не является разрешением на restore БД.

## Локальная проверка и оставшийся target gate

```bash
python scripts/prod/test_ansible_contract.py
```

Тесты используют disposable files и fake command runner, проверяют порядок
verification → OPS recovery → quiesce → deploy, lock, traversal/link rejection,
идемпотентность, failed migration boundary и plan-only rollback. Отдельная проверка
использует установленный Docker Compose CLI только для `config`, без daemon,
pull или запуска контейнеров, и сохраняет буквальный `$` при повторном чтении.

На Windows workstation проверены Python behavior и YAML parse; полный Ansible
syntax-check и выполнение playbook требуют подготовленного Linux controller.
До заявления о готовом unattended rollout нужны target integration evidence:
реальный verified release, strict SSH и sudo на APP/OPS, registry authentication,
читаемые контейнерами secret mounts, актуальные OPS copies текущей конфигурации,
полный recovery с remote Docker, повторный `UNCHANGED`, inject-failure во время
deploy и подтверждённый ручной recovery/rollback drill. Эти проверки локальные
stubs не заменяют.
