# RB-03: Бэкап провален, устарел или неизвестен

**Версия:** 1.0

**Обновлено:** 2026-10-07

**Алерты:** `SmartupcmsBackupFailed`, `SmartupcmsBackupStale` (critical);
`SmartupcmsBackupMissing` (warning) — [SLO и алерты](../ops/slo.md).

## Сигнал и цель

- `BackupFailed`: sidecar бэкапа записал в файл статуса `FAILED`.
- `BackupStale`: последний успешный бэкап старше `smc_backup_max_age_seconds`
  (`SMC_BACKUP_MAX_AGE`, без него 26 ч).
- `BackupMissing`: больше суток файла статуса нет или он нечитаем
  (`NEVER`/`UNKNOWN`).

Цель — получить новый проверенный зашифрованный бэкап, пока точка
восстановления не вышла за RPO установки.

## Немедленные действия

1. Остановите выкатку релизов и миграции: без свежего бэкапа миграция
   запрещена (fail-closed деплой).
2. Посмотрите статус на экране «Система» и журнал sidecar:

   ```bash
   docker compose -f deploy/compose/docker-compose.prod.yml \
     --env-file .env.production logs --since 26h backup
   ```

## Диагностика

1. Код отказа из статуса: `CONFIGURATION_MISSING`, `DATABASE_DUMP_FAILED`,
   `ENCRYPTION_FAILED`, `UPLOAD_FAILED`.
2. `CONFIGURATION_MISSING` — переменные бэкапа и ключ age в окружении.
3. `DATABASE_DUMP_FAILED` — доступность обеих БД (основной и pg-dwh), права
   роли бэкапа, место на диске.
4. `UPLOAD_FAILED` — доступность и учётные данные хранилища бэкапов.
5. `BackupMissing` — смонтирован ли том статуса в `server`
   (`SMC_BACKUP_STATUS_FILE`), жив ли сервис `backup`.

## Восстановление

Исправьте причину и запустите внеплановый бэкап по разделу «On-demand
encrypted backup» [руководства по обслуживанию](../ops/maintenance-guide.md);
убедитесь, что статус стал `SUCCESS`, а алерт погас. Подробности — раздел
«Backup failure or stale status» [операционного runbook](../ops/operations-runbook.md).

## Эскалация и закрытие

Бэкап не восстановлен до истечения RPO — эскалация владельцу установки (P2).
Закрытие: успешный бэкап, проверенная контрольная сумма, причина записана.
