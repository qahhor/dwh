# Operations Runbook — эксплуатация экземпляра

**Версия:** 1.0 · **Дата:** 2026-08-28
**Аудитория:** дежурный инженер, администратор экземпляра.
**Связанные процедуры:** [RB-01 отказ узла](../runbooks/RB-01-node-failure-recovery.md) ·
[RB-02 Vault и кворум](../runbooks/RB-02-vault-unseal-and-raft-quorum.md) ·
[RB-03 ротация ключа лицензий](../runbooks/RB-03-license-key-emergency-rotation.md) ·
[RB-04 провал миграции](../runbooks/RB-04-migration-failure-triage.md) ·
[Откат релиза](rollback.md)

---

## 1. Ежедневный контроль (5 минут)

```bash
cd /opt/dwh/<client-code> && docker compose ps
```

Все сервисы `Up (healthy)`. Далее:

```bash
curl -fsS http://localhost:9090/actuator/health | head -c 200
```

Проверить глубину очереди доставки (растёт — значит канал не работает):

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "select status, count(*) from ms_notification_outbox group by status"
```

Проверить свежесть бэкапа: последний файл в `./backups` не старше суток.

## 2. Матрица эскалации

| Уровень | Что случилось | Реакция | Кто |
|---|---|---|---|
| **P1 инцидент** | Экземпляр недоступен; потеря или порча данных; подозрение на компрометацию | немедленно, круглосуточно | дежурный → техлид → CEO |
| **P2 высокий** | Не работает вход у части пользователей; провал миграции; провал проверки бэкапа; dead-letter растёт | в течение рабочего дня | дежурный → техлид |
| **P3 средний** | Деградация скорости; отказ одного канала доставки; отставание версии > 2 minor | в течение недели | дежурный |
| **P4 низкий** | Косметика, единичные ошибки без влияния на пользователей | в плановом порядке | бэклог |

Правило: если сомневаетесь между P1 и P2 — это P1. Ложная тревога дешевле
пропущенного инцидента.

## 3. Сценарии диагностики

### 3.1. Приложение не стартует

```bash
docker compose logs app --tail 50
```

| В логах | Причина | Действие |
|---|---|---|
| `Схема БД не соответствует приложению` | не применены миграции или откат версии без плана | [RB-04](../runbooks/RB-04-migration-failure-triage.md) |
| `Экземпляр не инициализирован: задайте …` | пустые переменные в `.env` | заполнить, перезапустить |
| `Could not initialize local storage path` | не смонтирован том `appdata` | проверить `volumes`, перезапустить |
| `Connection refused` к postgres | БД не поднялась | `docker compose logs postgres`, проверить место на диске |
| `Port 8080 already in use` | остался прежний контейнер/процесс | `docker compose down`, затем `up -d` |

### 3.2. Пользователи не могут войти

1. Массово или один? Один — проверить состояние учётки:
   ```bash
   docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "select login, state, force_password_change from md_users where login = '<login>'"
   ```
   `state = 'P'` — учётка заблокирована администратором (это не сбой).
2. Массово — проверить журнал попыток:
   ```bash
   docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "select failure_reason, count(*) from kauth_login_attempts where attempt_at > now() - interval '15 min' group by 1 order by 2 desc"
   ```
3. Много `429` в ответах — сработали лимиты частоты. Смотреть события:
   ```bash
   docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "select details, count(*) from security_events where event_type = 'rate_limit_exceeded' and created_at > now() - interval '1 hour' group by 1 order by 2 desc limit 10"
   ```
   Чаще всего это некорректная интеграция клиента, а не атака: найти токен,
   связаться с клиентом, при необходимости отозвать токен.
4. Не приходит OTP — см. 3.3.

### 3.3. Не доставляются уведомления или OTP

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "select channel, status, count(*), max(last_error) from ms_notification_outbox where created_at > now() - interval '1 hour' group by 1,2"
```

- `PENDING` растёт, `SENT` нет → воркер стоит или провайдер недоступен.
- `DEAD_LETTER` → исчерпаны попытки; причина в `last_error`.
- В текущем контуре email/SMS — **заглушки, пишущие в лог** (фаза F).
  Реально работает только Telegram, если задан токен бота.

Временное решение при недоступности Telegram и включённой 2FA: администратор
отключает 2FA пользователю до восстановления канала (действие пишется в аудит).

### 3.4. Медленная работа

```bash
docker stats --no-stream
```

```bash
docker compose exec -T postgres psql -U "$DB_USER" -d "$DB_NAME" -c "select pid, state, wait_event_type, left(query,80) from pg_stat_activity where state <> 'idle' order by query_start limit 10"
```

Проверить исчерпание пула соединений (метрика `hikaricp_connections_pending`
на `:9090/actuator/prometheus`). Проверить место на диске: `df -h`.

### 3.5. Кончается место на диске

Порядок безопасного освобождения:
1. Старые бэкапы сверх политики хранения.
2. Логи Docker: `docker system prune -f` (не трогает тома).
3. Старые партиции аудита — только по регламенту, см.
   [Maintenance Guide](maintenance-guide.md).

**Никогда** не удаляйте файлы из тома `appdata` вручную: там пользовательские
файлы, на которые ссылаются записи в БД.

## 4. Регулярные проверки

| Периодичность | Что | Где описано |
|---|---|---|
| Ежедневно | health, очередь доставки, свежесть бэкапа | разд. 1 |
| Еженедельно | рост `audit_log`, свободное место, `dead_letter` разобран | Maintenance |
| Ежемесячно | **тестовое восстановление из бэкапа** | Maintenance |
| Ежемесячно | обновление образов (патчи безопасности) | Maintenance |
| Ежеквартально | пересмотр прав пользователей, ротация секретов | Maintenance |

## 5. Что ещё не автоматизировано (делать руками)

Честный список ограничений текущего контура:

- Мониторинг и алерты — нет централизованного стека, проверки ручные (фаза P).
- Проверка восстановления бэкапа — вручную, ежемесячно (в фазе P станет автоматической).
- Обновление — вручную по [rollback.md](rollback.md) и Deployment Guide;
  колец развёртывания и canary пока нет.
- Ротация секретов — вручную; Vault появится в фазе P.
