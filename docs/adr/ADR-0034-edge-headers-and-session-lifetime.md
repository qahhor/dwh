# ADR-0034: Заголовки безопасности web-origin, доверие к прокси и срок жизни сессии

**Статус:** Принято (2026-10-07)
**Дата:** 2026-10-07
**Зависит от:** ADR-0008 (базовая безопасность), ADR-0014 (единый runtime и
edge), ADR-0027 (имена конфигурации); план 10/10, пункты 7.4 и 7.5

---

## 1. Контекст

**Заголовки.** Единственный опубликованный origin — контейнер `web`
(`apps/web/nginx.conf`): он раздаёт SPA и проксирует `/api/` на сервер. CSP,
`X-Content-Type-Options` и `Referrer-Policy` стояли на уровне `server`, но
nginx отбрасывает внешние `add_header`, как только в `location` есть свой
`add_header`: `/healthz` и ассеты (`expires` + `Cache-Control`) уходили без
CSP и `nosniff`. HSTS не было вовсе, версия nginx не скрывалась. `location
/api/` без `^~` проигрывал регулярному выражению ассетов, и `/api/…/x.js`
уходил в статику. CSP разрешал `fonts.googleapis.com` / `fonts.gstatic.com`,
хотя шрифты лежат в образе (`src/fonts.css`), а `frame-src 'self' http: https:
data: blob:` разрешал встраивать что угодно — ради встроенных отчётов
(`embed/:code`, пункт навигации типа iframe с адресом, который задаёт
администратор). Отдельный `deploy/nginx/nginx.prod.conf` и образ
`deploy/images/nginx-proxy` нигде не подключались.

**Доверие к прокси.** Сервер верил `X-Forwarded-For` от всех частных сетей
(`10/8`, `172.16/12`, `192.168/16`, `fc00::/7`, `fe80::/10`), nginx дописывал
пришедший от клиента заголовок. Любой узел частной сети мог назвать себя
другим клиентом и обойти лимиты по IP.

**Сессии.** Условие активной сессии (`KauthSessionRepository`) проверяло
только `closed_at`, состояние пользователя и версию аутентификации. Неактивную
сессию закрывал `KauthSessionCleanupWorker` раз в час по жёстким 12 часам;
абсолютного срока не было, cookie жила 7 дней. Остановленный воркер —
бессрочные сессии. `last_seen_at` писался запросом `update` на каждый запрос
(само обновление строки — не чаще раза в минуту).

## 2. Решение

### 2.1. Заголовки — в файлах, которые подключает каждый location

- `apps/web/nginx/security-headers.conf` — общие заголовки всех ответов:
  `Strict-Transport-Security: max-age=31536000; includeSubDomains`,
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: same-origin`, `Permissions-Policy` (камера, микрофон,
  геолокация, датчики, платежи, USB запрещены).
- `apps/web/nginx/spa-csp.conf` — CSP приложения без сторонних хостов:
  `default-src 'self'`, `script-src 'self'`, `style-src 'self'
  'unsafe-inline'` (стили компонентов Angular), `font-src 'self'`,
  `object-src 'none'`, `frame-ancestors 'none'`, `base-uri`, `form-action`.
- `apps/web/nginx/api-csp.conf` — CSP ответов API (та же политика, что пишет
  сервер). В `location ^~ /api/` nginx скрывает заголовки безопасности сервера
  (`proxy_hide_header`) и ставит свои: в ответе ровно одна копия каждого.
- На уровне `server` `add_header` нет; каждый `location` (включая `/healthz`,
  ассеты и `@payload_too_large`) подключает общий файл и один CSP. Это
  проверяет `scripts/prod/test-release-config.ps1`.
- `server_tokens off` — явно, не полагаясь на конфигурацию базового образа.
- `location ^~ /api/` — путь под `/api/` никогда не попадает в статику.

**HSTS preload не включаем.** Список preload привязывает весь
регистрируемый домен установки и все его поддомены и снимается месяцами; это
решение владельца домена, а не продукта. Оператор может добавить `preload` на
своём внешнем edge.

**Spring и HSTS.** Сервер пишет HSTS только для защищённого запроса
(`request.isSecure()`); `server.forward-headers-strategy` не включён, за nginx
запрос приходит по HTTP, поэтому сервер HSTS не ставит. Остальные заголовки
сервера nginx скрывает — дублей нет. Для прямых вызовов сервера (локальный
запуск без nginx) его собственные заголовки остаются.

### 2.2. Узкий `frame-src`, список задаёт оператор

`frame-src 'self'` плюс источники из `SMC_WEB_FRAME_SOURCES` (через пробел или
запятую, `https://host[:port][/path]`, допускается `*.` в начале имени).
Шаг запуска `40-smc-edge-config.sh` в `/docker-entrypoint.d/` проверяет каждое
значение и пишет `/run/nginx/smc-edge.conf`; целые схемы (`https:`, `data:`,
`blob:`, `*`) и значения с кавычками отвергаются — контейнер не стартует.
По умолчанию список пуст: встроенный отчёт с внешнего хоста не откроется,
пока оператор не назовёт хост. Это осознанное изменение: раньше встраивалось
всё.

### 2.3. Доверие к прокси — только из явного списка

- **nginx:** `set_real_ip_from` только из `SMC_WEB_TRUSTED_PROXIES`
  (`real_ip_header X-Forwarded-For`, `real_ip_recursive on`). На сервер уходит
  `X-Forwarded-For: $remote_addr` — один адрес клиента, а не цепочка от
  клиента. `0.0.0.0/0` и `::/0` отвергаются.
- **Сервер:** `smc.security.trusted-proxies` (`SMC_SECURITY_TRUSTED_PROXIES`)
  по умолчанию — только loopback (`127.0.0.1/32`, `::1/128`).
- **Production Compose:** сеть `frontend` закреплена
  (`SMC_FRONTEND_SUBNET`, по умолчанию `172.30.80.0/24`); оба списка по
  умолчанию равны ей: сервер верит только контейнеру `web`, nginx — шлюзу
  сети, через который приходит edge на хосте. Edge на другом адресе
  (`HTTP_BIND=0.0.0.0`) указывается в `SMC_WEB_TRUSTED_PROXIES` явно.
- **Compose разработки** не закрепляет сеть (параллельные стенды
  конфликтовали бы) и явно называет пулы Docker `172.16.0.0/12,192.168.0.0/16`.

### 2.4. Срок жизни сессии — в условии активной сессии

| Параметр | Переменная | По умолчанию |
|---|---|---|
| Абсолютный срок (и `Max-Age` cookie) | `SMC_SESSION_ABSOLUTE_TTL` | `7d` |
| Таймаут бездействия | `SMC_SESSION_IDLE_TIMEOUT` | `12h` |
| Запись последней активности не чаще | `SMC_SESSION_TOUCH_INTERVAL` | `1m` |
| Период уборки | `SMC_SESSION_CLEANUP_INTERVAL` | `1h` |

- Условие активной сессии: `created_at > now − absolute_ttl` и
  `last_seen_at > now − idle_timeout`; `now` — часы приложения (`Clock`),
  ими же пишутся `created_at` и `last_seen_at`. Просроченная сессия получает
  401 сразу, без воркера.
- Значения по умолчанию сохраняют прежнее поведение: 12 часов бездействия
  (как у воркера) и 7 дней (как у cookie), теперь как жёсткий предел.
- `last_seen_at` пишется не чаще раза в `touch-interval`: сессия, увиденная
  внутри интервала, не порождает ни одного запроса записи.
- `KauthSessionCleanupWorker` только помечает закрытыми сессии, которые
  условие уже отвергает; удаление закрытых — retention (ADR-0025, 90 дней).
- Индекс `kauth_sessions_open_last_seen_idx` (V202) обслуживает уборку.
- Настройки проверяются при старте: idle не больше абсолютного срока,
  интервал записи меньше idle.
- Web: 401 при вошедшем пользователе — истёкший сеанс
  (`session-expired.interceptor.ts`): вкладка забывает сеанс, показывает
  сообщение и открывает вход с возвратом на ту же страницу.

## 3. Проверка

- `scripts/security/test-security-headers.ps1` и `.sh` — на работающем
  контейнере: `/`, настоящий `main-*.js`, `/healthz`, `/api/v1/i18n/languages`,
  401 сервера и `/api/v1/…/x.js` (должен ответить сервер). Правила повторяют
  офлайн-проверки Mozilla HTTP Observatory: CSP без `unsafe-inline`/`eval` в
  `script-src` и без целых схем, `frame-ancestors`, HSTS ≥ 1 года с
  поддоменами, `nosniff`, запрет фреймов, `Referrer-Policy`, отсутствие
  `Access-Control-Allow-Origin: *` и версии в `Server`, ровно одна копия
  заголовка. Шаг job `e2e` в `ci.yml` после подъёма стенда.
- Онлайн-оценка Observatory (A+) требует публичного адреса — шаг выпуска в
  `docs/ops/production-launch-checklist.md`.
- `scripts/prod/test-release-config.ps1` — `nginx -t` с шагом запуска,
  отказ шага на неверных значениях, include в каждом location, закреплённая
  сеть и списки доверия production Compose.
- `KauthSessionExpiryHttpTest` — через настоящую цепочку фильтров:
  сессия старше абсолютного срока и неактивная дольше idle получают 401 без
  воркера (часы двигает тест), запись активности не чаще интервала, воркер
  закрывает только просроченные. E2E `session-expiry.spec.ts` — 401 в
  браузере ведёт на вход с сообщением об истёкшем сеансе.

## 4. Последствия

- Встроенные отчёты с внешних хостов требуют `SMC_WEB_FRAME_SOURCES`.
- Установка за прокси вне сети `frontend` обязана назвать его в
  `SMC_WEB_TRUSTED_PROXIES`, иначе все клиенты видны с адреса прокси (лимиты
  по IP общие) — это безопасный отказ, а не обход лимитов.
- Активный пользователь входит заново раз в 7 дней.
- `deploy/nginx/nginx.prod.conf` и `deploy/images/nginx-proxy` удалены:
  внешний edge — Cloudflare или прокси оператора (ADR-0014).
