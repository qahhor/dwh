# RB-06: Доставка вебхуков и уведомлений отстаёт

**Версия:** 1.0

**Обновлено:** 2026-10-07

**Алерты:** `SmartupcmsOutboxStalled` (critical); `SmartupcmsOutboxLagHigh`,
`SmartupcmsOutboxDeadLetters` (warning) — [SLO и алерты](../ops/slo.md).

## Сигнал и цель

Метка `outbox` называет очередь: `webhook` (`kwh_outbox`) или `notification`
(`ms_notification_outbox`: e-mail, SMS, Telegram).

- `OutboxLagHigh` / `OutboxStalled`: самая старая доставка ждёт сверх срока
  дольше 15 мин / 1 ч.
- `OutboxDeadLetters`: доставки исчерпали попытки и стали `DEAD_LETTER`.

Цель — восстановить доставку и решить судьбу dead letters.

## Немедленные действия

1. Дашборд «jobs and outbox»: «Deliveries by outcome» — все ли попытки
   `retry`/`dead_letter` или воркер не работает совсем.
2. Журнал: `webhook_delivery_failed`, `webhook_target_rejected`,
   `Failed to deliver notification outbox`, `*_backlog_sample_failed`.

## Диагностика

1. Вебхуки: включены ли (`SMC_WEBHOOKS_*`), доступен ли адрес подписчика,
   не отклоняет ли его политика целей (частные адреса, список разрешённых).
2. Уведомления: активный провайдер канала (SMTP, Telegram), его учётные данные
   и лимиты; заглушки каналов вне dev (`smc.delivery.enforce`).
3. Dead letters и их последняя ошибка:

   ```sql
   select id, event_type, attempts, last_error, last_http_status, processed_at
     from kwh_outbox where status = 'DEAD_LETTER' order by processed_at desc limit 50;
   select id, channel, attempts, last_error, processed_at
     from ms_notification_outbox where status = 'DEAD_LETTER' order by processed_at desc limit 50;
   ```

## Восстановление

- Исправьте провайдера или адрес подписчика; накопленные `PENDING` уйдут сами.
- Dead letters повторяются только осознанно, после исправления причины, по
  решению владельца установки: событие может быть уже неактуальным.
  Подробности — раздел «Notification or webhook failure»
  [операционного runbook](../ops/operations-runbook.md).

## Эскалация и закрытие

Доставка стоит дольше часа или уведомления о безопасности (сброс пароля,
OTP) не уходят — эскалация (P2). Закрытие: отставание в норме, dead letters
разобраны, причина записана.
