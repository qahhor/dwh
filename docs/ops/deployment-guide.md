# Руководство по развёртыванию платформы Smartup DWH / CMS

**Версия:** 2.0 · **Дата:** 2026-08-29  
**Область:** пилотное развёртывание экземпляра и Control Plane через Docker Compose Fleet + внутренний NGINX.
**Проверено:** `docker compose config`, `nginx -t`, fail-closed backup/deploy regression и синтаксис Bash/PowerShell-скриптов.

> Решение GO принимается только по [Production Launch Checklist](production-launch-checklist.md). Наличие compose-файла само по себе не подтверждает production readiness.

---

## 1. Prerequisites Checklist

Отметьте всё до начала. Пропуск пункта = отказ развёртывания.

### Аппаратные требования (по профилю ресурсов, ТЗ-01 разд. 5.1)

| Профиль | Пользователей | RAM | vCPU | Диск | Кому |
|---|---|---|---|---|---|
| **S** | до 50 | 10 ГБ | 4 | 200 ГБ SSD | малый клиент |
| **M** | до 500 | 24 ГБ | 8 | 1 ТБ SSD | средний |
| **L** | до 10 000 | 64 ГБ | 16 | 4 ТБ SSD | крупный (DWH на отдельном узле) |

Указанные значения — на весь стек экземпляра (приложение + PostgreSQL + хранилище файлов).

### Программные требования

- [ ] Linux x86-64 с systemd (проверено: Ubuntu 24.04 LTS, Debian 12)
- [ ] Docker Engine ≥ 26 и Docker Compose plugin v2 (`docker compose version`)
- [ ] Файловая система с поддержкой `--data-checksums` для тома PostgreSQL
- [ ] Учтено: в PostgreSQL 18 том монтируется на `/var/lib/postgresql` целиком
      (не на `/var/lib/postgresql/data`) — образ 18+ отказывается стартовать
      при старой раскладке
- [ ] Синхронизированное время (chrony/systemd-timesyncd) — критично для сессий, OTP и аудита
- [ ] Reverse proxy с TLS перед приложением (nginx/Caddy/Traefik) — приложение
      публикуется **только на loopback**

### Сетевые требования

| Направление | Порт | Назначение |
|---|---|---|
| Входящий (публично) | 443 | HTTPS через reverse proxy |
| Входящий (loopback) | 8088 | fleet proxy без TLS — только для host reverse proxy |
| Входящий (loopback) | 8080 | одиночный instance — только для host reverse proxy |
| Внутренний (сеть мониторинга) | 9090 | `/actuator/*` — **наружу не публиковать** |
| Внутренний (сеть compose) | 5432 | PostgreSQL — наружу не публикуется |
| Исходящий | 443 | Telegram Bot API, SMS-шлюз, control plane |
| Исходящий | 587/465 | SMTP |

### Подготовка

- [ ] Создан несистемный пользователь для деплоя, добавлен в группу `docker`
- [ ] Каталог развёртывания: `/opt/dwh/<client-code>/`
- [ ] Каталог бэкапов на **отдельном томе/диске** (не на системном)
- [ ] Сгенерированы пароли: `openssl rand -base64 24` — БД и первый администратор
- [ ] Известен тег образа (конкретная версия, **никогда `latest`**)

---

## 2. Развёртывание

### Вариант A. Fleet (instance + Control Plane + оба UI)

Fleet compose **не завершает TLS** и не публикует фиктивный HTTPS-порт. Он
слушает `127.0.0.1:8088`; host nginx/Caddy/Traefik с валидным сертификатом
должен пересылать HTTPS-трафик на этот адрес. Публикация `PROXY_BIND=0.0.0.0`
без отдельного защищённого сетевого периметра запрещена.

```bash
cp deploy/compose/.env.example .env.production
chmod 600 .env.production
```

Заполните все обязательные секреты, затем выполните единый release gate:

```bash
COMPOSE_FILE=deploy/compose/docker-compose.fleet.prod.yml \
ENV_FILE=.env.production \
bash scripts/prod/deploy.sh
```

PowerShell-вариант:

```powershell
./scripts/prod/deploy.ps1 -ComposeFile deploy/compose/docker-compose.fleet.prod.yml -EnvFile .env.production
```

Скрипт валидирует Compose, снимает обязательные бэкапы существующих БД,
последовательно применяет обе миграции и завершится успешно только после
`docker compose up --wait`. Для первого запуска бэкап пропускается лишь когда
контейнеров обеих БД ещё нет.

Проверка внутреннего proxy до подключения TLS:

```bash
curl -fsS http://127.0.0.1:8088/healthz
```

Ожидаемый ответ: `ok`. Затем обязательно проверьте внешний HTTPS и отсутствие
публичного доступа к `8088`, `9090`, `9091`, PostgreSQL и Typesense.

### Вариант B. Один экземпляр без Control Plane UI

### Шаг 0. Имя проекта (группа в Docker)

Все контейнеры экземпляра объединяются в одну группу Docker. Имя берётся из
`PROJECT_NAME` в `.env`, по умолчанию **SmartupCMS**.

```bash
grep PROJECT_NAME .env
```

Docker приводит имя группы к нижнему регистру — в Docker Desktop она видна как
`smartupcms-<код-клиента>`. Имена контейнеров регистр сохраняют:
`SmartupCMS-app`, `SmartupCMS-db`. Это не ошибка, а нормализация Docker.

### Шаг 1. Сборка образа

Выполняется на CI или машине сборки, не на прод-узле:

```bash
docker build --build-arg APP=instance -t smartupcms/instance:1.0.0 .
```

Проверка образа перед выкладкой:

```bash
docker run --rm --entrypoint sh dwh/instance:1.0.0 -c "id"
```

Ожидаемо: `uid=10001(dwh)` — приложение не работает от root.

### Шаг 2. Конфигурация

```bash
mkdir -p /opt/dwh/client-042 && cd /opt/dwh/client-042
cp <repo>/deploy/compose/docker-compose.prod.yml .
cp <repo>/deploy/compose/.env.example .env
chmod 600 .env
```

Заполните `.env`. Обязательные без умолчаний: `CLIENT_CODE`, `APP_VERSION`,
`DB_PASSWORD`, `DWH_INSTANCE_ADMIN_*`. Compose откажется стартовать без них —
это защита от развёртывания с пустыми паролями.

### Шаг 3. Миграции (отдельным шагом — обязательно)

```bash
docker compose -f docker-compose.prod.yml --env-file .env run --rm migrate
```

Ожидаемо в конце: `Миграции применены. Текущая версия схемы: 003`.
Приложение мигрировать схему **не может** — при несовпадении версии оно
откажется стартовать (schema-gate, FR-INST-2). Это защита от отката без плана.

### Шаг 4. Запуск

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d
```

### Шаг 5. Проверка развёртывания

```bash
docker compose -f docker-compose.prod.yml ps
```

Все сервисы — `healthy`. Далее:

```bash
curl -fsS http://127.0.0.1:8080/api/v1/auth/login -X POST -H 'Content-Type: application/json' -d '{"login":"admin","password":"<пароль из .env>"}' -i | head -3
```

Ожидаемо: `HTTP/1.1 200`, заголовки `Set-Cookie: DWH_SESSION=…; Secure; HttpOnly`
и `XSRF-TOKEN`. В логах при первом старте:
`Экземпляр инициализирован: client_code=…` и `Первый администратор создан`.

### Шаг 6. Регистрация экземпляра в control plane

Пока экземпляр не зарегистрирован, он не виден в реестре флота: панель не
покажет ни его версию, ни доступность, ни отчёты о бэкапах. На работу самого
экземпляра это не влияет — связь односторонняя и необязательная (ADR-0004).

1. Откройте панель управления, вкладка **Клиенты**.
2. Если клиента ещё нет — **Новый клиент**: код (совпадает с `CLIENT_CODE`),
   название, профиль ресурсов.
3. **Регистрация экземпляра**: клиент, контур (`production` / `staging`),
   внешний адрес экземпляра.
4. Панель покажет **одноразовый enrollment token** и срок действия. Это ещё не
   runtime credential: token действует 15 минут и уничтожается первым успешным
   обменом через `POST /api/v1/instances/enroll`.
5. Передайте enrollment token защищённому bootstrap-процессу в памяти. Не
   записывайте его в `.env`, shell history, URL или логи. Bootstrap-процесс должен
   сохранить только поле `credential` из ответа enrollment в secret-хранилище.
   Полный request/response-контракт приведён в
   [Control Plane Instance API v1](../api/control-plane-instance-v1.md).
6. В текущем Compose имя переменной сохранено для обратной совместимости:
   `DWH_CP_HEARTBEAT_TOKEN` содержит именно **runtime credential**, а не enrollment
   token. Заполните конфигурацию экземпляра и перезапустите приложение:

```bash
DWH_CP_URL=https://cp.smartup.uz
DWH_CP_HEARTBEAT_TOKEN=<runtime credential из enrollment response>
DWH_CP_HEARTBEAT_INTERVAL=5m
```

В логах после старта: `Heartbeat в control plane включён: … каждые 5 мин`.
Через минуту экземпляр появляется на вкладке **Флот** в состоянии
«работает» с фактической версией приложения и схемы.

Runtime credential ротируется через `POST /api/v1/instances/credentials/rotate`:
новое значение сохраняется до перезапуска, затем старое отзывается оператором.
Overlap старого и нового credential — 24 часа. Публичного endpoint повторной выдачи
enrollment для существующего instance в текущем срезе нет: потеря обоих runtime
credentials требует операторского recovery и не должна маскироваться повторным
использованием старого enrollment token.

### Шаг 7. Обязательные действия после первого запуска

- [ ] Войти администратором и **сменить пароль** (система потребует сама)
- [ ] Включить 2FA администратору
- [ ] Удалить пароль администратора из `.env` (он больше не нужен — учётка создана)
- [ ] Проверить восстановление из бэкапа на тестовом контуре (см.
      [Maintenance Guide](maintenance-guide.md), разд. «Проверка бэкапов»)
- [ ] Убедиться, что экземпляр виден в панели в состоянии «работает» (шаг 6)

---

## 3. Типичные ошибки развёртывания

| Симптом | Причина | Решение |
|---|---|---|
| `Схема БД не соответствует приложению` при старте | не выполнен шаг 3 | запустить `run --rm migrate` |
| `Экземпляр не инициализирован: задайте dwh.instance.client-code` | не заполнен `.env` | заполнить обязательные переменные |
| `Could not initialize local storage path` | том `appdata` не смонтирован | проверить секцию `volumes` сервиса `app` |
| Контейнер `unhealthy`, в логах пусто | приложение не успело подняться | увеличить `start_period` (медленный диск) |
| 401 на `/actuator/prometheus` | обращение на публичный порт | actuator живёт на 9090, не на 8080 |

---

## 4. Что это развёртывание НЕ обеспечивает

Осознанные ограничения текущего контура (закрываются фазой P):

- **Файлы клиента лежат на диске узла** — при потере узла теряются (блокер C-3).
- **Секреты в `.env`-файле**, не в Vault — нет ротации и аудита доступа (C-7).
- **Логи только локальные** — централизованного поиска нет (C-11).
- **Бэкапы без WAL-архива, шифрования и автопроверки** — RPO равен суткам,
  а не 15 минутам по NFR-7.
- **Один узел** — отказ узла = простой до ручного восстановления.
- **Email/SMS — заглушки в лог**, реальные каналы появятся в фазе F.
