# RB-12 — внешний контроль, recovery и доставка alert

Область: отдельный ops host; alerts `SmartupcmsExternalUnavailable`,
`SmartupcmsCollectorStale`, `SmartupcmsRecoveryUnverified`,
`SmartupcmsRecoveryAging`, `SmartupcmsRecoveryUrgent`,
`SmartupcmsRecoveryFailed`, `SmartupcmsAlertDeliveryFailed`.

## Первые действия

1. Дежурный подтверждает получение за 10 минут, фиксирует UTC-время,
   installation и последний успешный release. При отсутствии ACK вызывается
   secondary; при влиянии на пользователей — incident owner.
2. Проверить внешний HTTPS API, отдельно readiness app и доступность ops.
   Не выполнять restart, rollback или restore до определения причины.
3. Сверить актуальность данных. Последнее `up=1` при прекращении remote_write
   не доказывает доступность app. Проверить UTC, scrape/remote_write ошибки,
   mTLS certificate, private route и свободный диск обоих hosts.

## Действие по сигналу

| Alert | Проверка и действие |
|---|---|
| ExternalUnavailable | Сравнить DNS/TLS/Cloudflare/origin и app readiness; проверить запрет кеширования probe endpoint. Edge outage не лечится восстановлением БД. |
| CollectorStale | Проверить app collector, очереди remote_write и ops receiver. Восстановить сбор и убедиться, что timestamp новой выборки меняется. |
| RecoveryAging | Полный проверенный комплект старше 14 часов: проверить пропущенный запуск, ошибки capture/read-back и место. Подготовить повтор до критического порога 20 часов; DB-only backup возраст полного комплекта не обновляет. |
| RecoveryUrgent | Полный комплект старше 20 часов: вызвать primary/secondary и incident owner, восстановить capture до RPO 24 часа. Не увеличивать порог и не заменять timestamp вручную. |
| RecoveryUnverified | Найти последний PASS `status.json`, `consistentPoint` и remote `complete.json`; проверить часы и таймер. Не заменять timestamp вручную. Если RPO превышен, incident owner принимает риск и ограничивает изменения. |
| RecoveryFailed | Проверить systemd exit, место scratch, SSH/Docker, R2 read/write и age. Убедиться в состоянии server/backup и pause marker. Следовать процедуре восстановления writers из handbook. Повторить capture только после исправления причины и с разрешённым maintenance window. |
| AlertDeliveryFailed | Проверить receiver, secret file permissions, TLS, DNS, failed notifications. Сообщить по независимому каналу; сломанный receiver не использовать для подтверждения доставки. |

Журналы изучать в приватной сессии; env, Compose snapshot, credentials и
payload пользовательских запросов не копировать в incident ticket.
После power loss/SIGKILL/OOM сначала убедиться, что прежний coordinator
не работает. Только после этого проверять stale lock и восстанавливать writers.

## Завершение и доказательства

Зафиксировать причину, recovery point/возраст, продолжительность простоя,
доставку ≤120 секунд и ACK. Повторить независимый probe, проверить свежий
collector timestamp и новый полный PASS recovery при затронутом backup.
Закрыть инцидент после подтверждения business smoke и восстановления alert route.
Отказ самого ops выявляется внешним dead-man monitor, который настраивается
отдельно; локальные promtool tests его работу не подтверждают.

Команды и порядок работы с writers: [русский handbook](../ops/production-handbook-ru.md).
Решение о запуске: [GO/NO-GO checklist](../ops/production-launch-checklist.md).
