# Руководство по обслуживанию

**Версия:** 1.0 · **Дата:** 2026-08-28
**Область:** плановые работы на экземпляре — обновления, патчи, бэкапы,
ротация секретов, обслуживание БД.

---

## 1. Обновление версии приложения

Окно обслуживания согласуется с клиентом (NFR-6: работы вне его рабочего времени).

### Порядок (нарушать нельзя)

1. **Прочитать release notes** — есть ли деструктивные миграции или ручные шаги.
2. **Свежий бэкап перед обновлением** (не полагаться на ночной):
   ```bash
   cd /opt/dwh/<client-code> && docker compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc -f "/backups/pre-upgrade-$(date -u +%Y%m%dT%H%M%SZ).dump"
   ```
3. **Забрать образ заранее** — чтобы простой не включал время скачивания:
   ```bash
   docker pull ${IMAGE_REGISTRY}/instance:<новая-версия>
   ```
4. **Миграции отдельным шагом**:
   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env run --rm migrate
   ```
   Провал — не продолжать, идти в [RB-04](../runbooks/RB-04-migration-failure-triage.md).
5. **Обновить версию и перезапустить приложение**:
   ```bash
   sed -i 's/^APP_VERSION=.*/APP_VERSION=<новая-версия>/' .env && docker compose -f docker-compose.prod.yml --env-file .env up -d app
   ```
6. **Проверить**: health, вход, одна бизнес-операция, очередь доставки.
7. При проблемах — [откат](rollback.md), не «чиним на живом».

**Правило пилота:** новую версию сначала на внутренний стенд, затем на 1–2
согласованных клиентов, и только потом на остальных. Автоматические кольца
развёртывания появятся в фазе P; пока порядок соблюдается вручную.

## 2. Патчинг зависимостей и базовых образов

| Что | Периодичность | Как |
|---|---|---|
| Уязвимости Critical/High в зависимостях | немедленно при обнаружении | CI (Trivy) блокирует сборку; обновить, выпустить патч-релиз |
| Базовый образ (`eclipse-temurin`, `postgres`) | ежемесячно | пересобрать образ, прогнать CI, выкатить по разд. 1 |
| Minor-версии Spring Boot | в течение квартала после выхода | ADR-0002, политика обновлений |
| Java | только LTS | переход на следующий LTS в течение 6–12 мес |

Проверить, что стоит сейчас:

```bash
docker compose exec -T app sh -c 'java -version' 2>&1 | head -1
```

## 3. Бэкапы и проверка восстановления

### Что настроено

Ежесуточный `pg_dump` в `./backups`, хранение `BACKUP_RETENTION_DAYS` (умолчание 14).

### Ограничения текущего контура — знать наизусть

- **Нет WAL-архива**: точка восстановления — момент последнего дампа
  (до суток потерь), а не 15 минут по NFR-7.
- **Нет шифрования**: каталог бэкапов содержит данные клиента в открытом виде —
  доступ к нему ограничить правами ФС; на внешние носители не копировать
  без шифрования.
- **Нет автопроверки**: восстановление проверяется вручную.

### Ежемесячная проверка восстановления (обязательна)

Непроверенный бэкап следует считать отсутствующим.

```bash
docker run -d --name pg-restore-test -e POSTGRES_PASSWORD=t -e POSTGRES_DB=restore_test smartupcms/postgres:18-alpine-hardened
```

```bash
docker cp ./backups/<последний>.dump pg-restore-test:/tmp/b.dump
```

```bash
docker exec pg-restore-test pg_restore -U postgres -d restore_test --no-owner /tmp/b.dump
```

Smoke-проверки после восстановления:

```bash
docker exec pg-restore-test psql -U postgres -d restore_test -c "select (select count(*) from md_users) users, (select count(*) from ms_tasks) tasks, (select max(version) from flyway_schema_history) schema_version"
```

Счётчики правдоподобны и версия схемы совпадает с боевой — проверка пройдена.
Записать результат в отчёт эксплуатации. Убрать за собой:

```bash
docker rm -f pg-restore-test
```

## 4. Ротация секретов

| Секрет | Периодичность | Процедура |
|---|---|---|
| Пароль БД | ежегодно и при увольнении имевшего доступ | сменить в PostgreSQL, обновить `.env`, перезапустить стек в окне |
| Пароль администратора | по политике клиента | самим администратором через профиль |
| Токен Telegram-бота | при подозрении на утечку | перевыпустить у BotFather, обновить `.env`, перезапустить |
| API-токены клиента | по запросу или при увольнении | отозвать в UI (немедленное действие, перезапуск не нужен) |
| Ключ подписи лицензий | при компрометации | [RB-03](../runbooks/RB-03-license-key-emergency-rotation.md) |

Смена пароля БД:

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d postgres -c "alter user \"$DB_USER\" with password '<новый>'"
```

Затем обновить `.env` (права 600) и `docker compose up -d`. Порядок именно такой:
сначала БД, потом конфиг — иначе приложение не подключится.

## 5. Обслуживание базы данных

### Рост таблицы аудита

`audit_log` партиционирована по месяцам, retention 12 месяцев (FR-AUD-2).
Проверить размер:

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "select relname, pg_size_pretty(pg_total_relation_size(relid)) from pg_catalog.pg_statio_user_tables order by pg_total_relation_size(relid) desc limit 10"
```

Отцепление старой партиции — только после выгрузки в архив:

```bash
docker compose exec -T postgres pg_dump -U "$DB_USER" -d "$DB_NAME" -t audit_log_YYYY_MM -Fc -f /backups/audit_YYYY_MM.dump
```

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "alter table audit_log detach partition audit_log_YYYY_MM; drop table audit_log_YYYY_MM"
```

**Создание партиций на будущее** — обязательный плановый пункт: партиции заданы
миграцией на конкретные месяцы, при их исчерпании записи уходят в
`audit_log_default`. Проверять наличие партиций на 2 месяца вперёд.

### Обслуживание статистики

Автовакуум PostgreSQL включён по умолчанию и вмешательства обычно не требует.
После массовых удалений (например, отцепления партиций) полезно:

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "analyze"
```

## 6. Пересмотр доступа (ежеквартально)

Организационная процедура, которую спросит любой enterprise-клиент:

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "select u.login, u.state, string_agg(r.name, ', ') roles from md_users u left join md_user_roles ur on ur.user_id = u.id left join md_roles r on r.id = ur.role_id group by u.login, u.state order by u.login"
```

Проверить: нет активных учёток уволенных; нет лишних носителей роли `admin`;
API-токены с давним `last_used_at` отозваны. Результат — в журнал пересмотра.

## 7. Календарь обслуживания

| Когда | Что |
|---|---|
| Ежедневно | health, очередь доставки, свежесть бэкапа |
| Еженедельно | место на диске, разбор dead-letter, рост аудита |
| Ежемесячно | проверка восстановления, обновление базовых образов, партиции на 2 мес вперёд |
| Ежеквартально | пересмотр доступа, учебный откат и восстановление, ротация по календарю |
| Ежегодно | ротация пароля БД, пересмотр политик хранения |
