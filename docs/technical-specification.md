# Техническое задание SmartupCMS

**Версия:** 1.1

**Статус:** Базовая спецификация для подготовки релиза

**Дата:** 2026-09-03

**Горизонт запуска:** четыре месяца; точная календарная дата не утверждена

Этот документ является единым нормативным ТЗ. При расхождении документации
фактическое поведение подтверждается кодом и автоматическими контрактами, а
расхождение регистрируется как дефект документации до изменения требований.
[Контекст для AI-ассистентов](ai-context.md) является только кратким handoff и
не может добавлять или изменять требования этого ТЗ.

## 1. Назначение и границы продукта

SmartupCMS — самостоятельно размещаемая платформа контента и операционной
работы для одной организации и многих пользователей. Одна установка
обслуживает одну организацию. Изоляция организаций обеспечивается отдельными
установками, базами PostgreSQL и хранилищами объектов, а не общей
мультиорганизационной базой.

Участники системы:

- оператор установки развёртывает, настраивает, обновляет, резервирует и
  восстанавливает установку;
- администратор организации управляет пользователями, ролями, правами,
  организационной структурой и системными настройками;
- авторизованный сотрудник работает с разрешёнными ему задачами, комментариями,
  файлами, поиском, уведомлениями и объявлениями;
- интеграция или провайдер предоставляет явно настроенный оператором канал
  хранения, доставки сообщений либо webhook-получателя;
- контрибьютор или сопровождающий релиза изменяет исходный код и формирует
  проверяемые релизные артефакты.

В состав продукта входят модули идентификации и доступа, организации и
пользователей, задач, комментариев и настраиваемых полей, файлов, поиска,
уведомлений, webhook и локальных объявлений, аудита и событий безопасности, а
также системного администрирования.

Входные данные планирования управляемого контура относятся ко всему парку, а не
к каждой установке, и являются приблизительными: до 100 установок,
500 зарегистрированных пользователей суммарно, 100 одновременно активных
пользователей суммарно и 50 ГБ загружаемых файлов в месяц суммарно.
Распределение пиков по установкам и
измеренная цель задержки/SLO не подтверждены и остаются пробелами подготовки
релиза. Горизонт запуска составляет четыре месяца от утверждённой базовой точки
планирования; точная календарная дата не подтверждена.

В продукт не входят Control Plane, общая база для нескольких организаций,
обязательная телеметрия, удалённая регистрация установки, контроль лицензии,
дистрибуция через Nomad, Consul или Kubernetes, встроенная multi-host HA и
обязательный облачный провайдер для self-hosted-оператора.

В управляемой Smartup инфраструктуре внешний DNS/TLS/security edge должен
работать через Cloudflare, а объектное хранилище — через Cloudflare R2. На
инфраструктуре клиента оператор может использовать собственный edge и любой
проверенный `local_disk` или S3-совместимый provider, не изменяя продуктовую
модель.

## 2. Правила трассируемости

Идентификаторы `FR-*`, `NFR-*` и `AC-*` стабильны в пределах версии ТЗ.
Изменение смысла требования требует новой версии ТЗ или нового идентификатора.
Путь в столбце «Подтверждение» указывает на существующий код, конфигурацию,
автоматический тест или действующий эксплуатационный документ. Наличие пути не
заменяет метод приёмки: релиз принимается только по результату указанной
проверки.

## 3. Функциональные требования

### 3.1. Аутентификация и сессии

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `FR-AUTH-01` | Система должна выполнять вход по учётным данным, создавать защищённую cookie-сессию и завершать её при logout. | `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/controller/KauthAuthController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/service/KauthAuthService.java` | Интеграционный тест выполняет успешный и ошибочный вход, затем logout; после logout прежняя cookie получает `401`. |
| `FR-AUTH-02` | Система должна требовать смену bootstrap-пароля до доступа к защищённым бизнес-функциям. | `apps/server/src/main/java/com/greenwhite/dwh/instance/config/bootstrap/InstanceBootstrap.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/security/RequiresPermissionInterceptor.java`; `apps/web/src/app/features/auth/login/login.component.ts` | E2E входит bootstrap-администратором, подтверждает запрет бизнес-операции, меняет пароль и подтверждает разрешённый доступ со вторым входом. |
| `FR-AUTH-03` | Система должна хранить пароли как Argon2id-хеши, а значения сессий, OTP, reset-кодов и API-токенов — только как криптографические хеши; исходные секреты выдаются только в момент создания. | `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/service/KauthPasswordHasher.java`; `apps/server/src/main/resources/db/migration/V001__init_schema.sql`; `apps/server/src/test/java/com/greenwhite/dwh/instance/kauth/KauthPasswordHasherTest.java` | Тест хешера подтверждает Argon2id и SHA-256; запрос к тестовой БД подтверждает отсутствие исходных паролей и токенов в сохраняемых колонках. |
| `FR-AUTH-04` | Система должна отзывать отдельную или все сессии пользователя и все его API-токены при блокировке либо смене/сбросе пароля. | `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/service/KauthSessionService.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/service/KauthUserSessionInvalidator.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/md/UserBlockingInvariantTest.java` | Интеграционный тест создаёт сессию и токен, блокирует пользователя или меняет пароль и подтверждает отказ обоим прежним идентификаторам. |
| `FR-AUTH-05` | Система должна защищать изменяющие состояние cookie-запросы CSRF-токеном и не раскрывать CSRF/cookie значения в журнале. | `apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/SecurityConfig.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/SpaCsrfTokenRequestHandler.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/config/security/SecurityConfigTest.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/config/security/ProblemDetailAuthHandlersTest.java` | MVC-тест отправляет POST без/с неверным токеном и ожидает отказ, затем повторяет с корректной парой cookie/header и проверяет отсутствие секретного значения в журнале. |

### 3.2. Пользователи, организация, роли и права

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `FR-IAM-01` | Система должна позволять уполномоченному администратору создавать, просматривать, изменять, блокировать, разблокировать и удалять пользователей. | `apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdUserController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdUserService.java` | API-тест под административной ролью проходит полный жизненный цикл пользователя и проверяет аудит изменений. |
| `FR-IAM-02` | Система должна держать пользователей в области единственной компании/организации установки и поддерживать её организационные единицы и вычисляемый data scope. | `apps/server/src/main/java/com/greenwhite/dwh/instance/config/bootstrap/InstanceBootstrap.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdOrgUnitService.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdScopeService.java` | Интеграционный тест создаёт иерархию подразделений, назначает пользователя и подтверждает ожидаемый effective scope без данных другой установки. |
| `FR-IAM-03` | Система должна позволять администратору создавать, изменять и удалять роли, а также назначать их пользователям. | `apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdRoleController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/md/repository/MdRoleRepository.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/md/RbacSystemRolesIntegrationTest.java` | Интеграционный тест создаёт роль, назначает её пользователю, изменяет и удаляет с проверкой системных инвариантов. |
| `FR-IAM-04` | Система должна управлять разрешениями как парами «форма–действие», поддерживать выдачу через роли и вычислять эффективный набор прав пользователя. | `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdPermissionService.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdFormCatalogSynchronizer.java`; `apps/server/src/main/resources/db/migration/V003__rbac_role_matrix.sql` | Интеграционный RBAC-тест выдаёт и отзывает право и проверяет изменение эффективного набора и версии разрешений. |
| `FR-IAM-05` | Система должна проверять аутентификацию, право и принудительную смену пароля на сервере независимо от скрытия элементов в UI. | `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/security/KauthAuthenticationFilter.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/kauth/security/RequiresPermissionInterceptor.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/kauth/security/RequiresPermissionInterceptorTest.java` | Негативный API-тест вызывает защищённый endpoint напрямую без сессии, без права и при `force_password_change=true`; каждый запрос отклоняется ожидаемым статусом. |

### 3.3. Задачи и совместная работа

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `FR-WORK-01` | Система должна поддерживать создание, чтение, изменение и удаление задач, их статусы, типы, приоритет, сроки, проекты и подзадачи. | `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/controller/MsTaskController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsTaskService.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/ms/task/MsTaskServiceTest.java` | API/E2E-тест проходит жизненный цикл задачи и проверяет сохранённые статус, тип, срок и родительскую связь. |
| `FR-WORK-02` | Система должна назначать пользователей на задачи и проекты с проверкой разрешённой области данных. | `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdAssignmentService.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/repository/MsTaskMemberRepository.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdAssignmentServiceIntegrationTest.java` | Интеграционный тест принимает назначение внутри effective scope и отклоняет назначение пользователя вне области. |
| `FR-WORK-03` | Система должна позволять уполномоченным пользователям читать и добавлять комментарии к доступной задаче. | `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/controller/MsTaskCommentController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/service/MsTaskCommentService.java` | API-тест добавляет и читает комментарий для разрешённой задачи и выполняет отрицательный тест права/IDOR. |
| `FR-WORK-04` | Система должна поддерживать настраиваемые поля пользователей и задач и регистрировать административные и бизнес-изменения в аудите. | `apps/server/src/main/java/com/greenwhite/dwh/instance/md/service/MdCustomFieldService.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/audit/service/AuditLogService.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/audit/AuditCoverageTest.java` | Тест создаёт поле, изменяет значение сущности и подтверждает запись с actor, entity и изменёнными значениями в журнале аудита. |

### 3.4. Файлы

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `FR-FILE-01` | Система должна разрешать upload, download и delete файла только аутентифицированному пользователю с соответствующим серверным правом и доступом к связанной сущности. | `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/controller/MfFileController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/task/controller/MsTaskController.java` | Положительные и отрицательные API-тесты проверяют операции владельца/разрешённой роли и отклонение пользователя без права или доступа к сущности. |
| `FR-FILE-02` | Система должна ограничивать один загружаемый файл размером 50 MiB на уровне приложения и иметь согласованный request envelope. | `apps/server/src/main/resources/application.yml`; `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileService.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/mf/MultipartUploadConfigurationTest.java` | Тест принимает файл ровно в пределах лимита и отклоняет файл больше 50 MiB; proxy/application значения проходят контрактную проверку. |
| `FR-FILE-03` | Система должна вычислять SHA-256, проверять тип/содержимое и хранить метаданные, размер, storage bucket/key и владельца файла в PostgreSQL. | `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/FileContentInspector.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileMetadataService.java`; `apps/server/src/main/resources/db/migration/V001__init_schema.sql` | Тест загружает известные байты и сверяет SHA-256, размер, MIME, владельца и ключ с ожидаемыми значениями. |
| `FR-FILE-04` | Система должна сначала помещать непроверенные байты в непубличный quarantine-ключ и публиковать конечный объект только после положительной проверки. | `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileService.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/mf/MfFileServiceTest.java` | Тест подтверждает отсутствие конечного объекта до verdict `CLEAN`, публикацию после него и удаление quarantine при любом отказе. |
| `FR-FILE-05` | Производственная конфигурация должна запускаться только с активным malware scanner и при недоступности scanner отклонять upload с очисткой временного объекта. | `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/scan/FileScannerStartupCheck.java`; `deploy/compose/docker-compose.prod.yml`; `scripts/prod/test-deploy-fail-closed.sh` | Контракт production Compose проверяет обязательный ClamAV; EICAR отклоняется, а имитация outage подтверждает fail-closed и отсутствие quarantine/metadata residue. |
| `FR-FILE-06` | Система должна поддерживать `local_disk` и S3-совместимое объектное хранилище через единый SPI; целевым хранилищем управляемой инфраструктуры является Cloudflare R2. | `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/storage/LocalStorageProvider.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/storage/S3StorageConfiguration.java`; `README.md` | Один contract suite выполняет upload/download/delete/exists для local и S3; отдельный smoke выполняется с параметрами целевого R2. |
| `FR-FILE-07` | Система должна удалять физический объект только при удалении последней ссылки на его SHA-256, сохраняя общий объект при других владельцах/ссылках. | `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileMetadataService.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/MfFileService.java`; `apps/server/src/main/resources/db/migration/V010__file_ownership_separate_from_content.sql` | Тест создаёт две ссылки на одинаковые байты, удаляет первую и видит объект, затем удаляет последнюю и подтверждает удаление объекта. |

### 3.5. Поиск

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `FR-SEARCH-01` | Система должна использовать Typesense только как производный полнотекстовый индекс, не как источник авторитетных данных. | `apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseIndexer.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseSyncRunner.java` | Тест изменяет запись в PostgreSQL, перестраивает индекс и подтверждает совпадение поискового документа с авторитетной записью. |
| `FR-SEARCH-02` | Система должна выполнять пользовательские поисковые запросы через server API; browser не должен получать прямой доступ или ключ Typesense. | `apps/server/src/main/java/com/greenwhite/dwh/instance/search/controller/SearchController.java`; `deploy/compose/docker-compose.prod.yml`; `deploy/nginx/nginx.prod.conf` | Проверка production Compose подтверждает отсутствие опубликованного порта Typesense; E2E выполняет поиск только через `/api/v1/search`. |
| `FR-SEARCH-03` | Система должна ограничивать результаты серверной политикой авторизации и позволять полную перестройку индекса из PostgreSQL. До появления построчной фильтрации текущая политика должна допускать глобальный поиск только пользователю с `*.*`. | `apps/server/src/main/java/com/greenwhite/dwh/instance/search/service/SearchService.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/search/SearchServiceTest.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseSyncRunner.java` | Негативный тест отклоняет глобальный поиск обычной роли; административный тест удаляет индекс, запускает sync и получает только ожидаемые сущности. |

### 3.6. Уведомления, webhook и объявления

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `FR-COMM-01` | Система должна использовать по умолчанию локальные `console_mail`, `console_sms` и `console_messenger`, не выполняющие внешнюю доставку. | `apps/server/src/main/resources/application.yml`; `docker-compose.yml`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/notify/provider/ConsoleMailProvider.java` | Контракт конфигурации подтверждает все три default; наблюдение сетевого трафика выполняет `scripts/security/test-no-default-egress.ps1`. |
| `FR-COMM-02` | Система должна подключать SMTP, SMS и messenger только после явной настройки соответствующего provider SPI; встроенная доставка реализована для SMTP и Telegram, а внешний SMS-адаптер подключается через SPI. | `libs/provider-spi/src/main/java/com/greenwhite/dwh/spi/mail/MailProvider.java`; `libs/provider-spi/src/main/java/com/greenwhite/dwh/spi/sms/SmsProvider.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/notify/provider/SmtpMailProvider.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/notify/provider/TelegramBotMessengerProvider.java` | Provider-тест выбирает каждый явно настроенный адаптер; staging smoke подтверждает доставку тестового сообщения и отсутствие секрета в ответе/журнале. |
| `FR-COMM-03` | Система должна разрешать webhook только при явном включении и точном allow-list host, блокировать запрещённые/private targets по умолчанию, подписывать payload HMAC-SHA256 и выполнять ограниченные повторы. | `apps/server/src/main/java/com/greenwhite/dwh/instance/kwh/service/WebhookTargetPolicy.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/kwh/worker/KwhOutboxWorker.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/kwh/KwhOutboxWorkerSecurityTest.java` | Тесты отклоняют host вне allow-list и private/rebinding target, затем сверяют подпись разрешённой доставки и достижение `max_attempts`. |
| `FR-COMM-04` | Система должна позволять администратору создавать, публиковать, архивировать и локализовать локальные объявления, а пользователю — читать и отмечать их прочитанными. | `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/notify/controller/MsAnnouncementAdminController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/ms/notify/service/MsAnnouncementService.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/ms/notify/MsAnnouncementServiceIntegrationTest.java` | Интеграционный/E2E-тест проходит draft–publish–read–archive и проверяет язык и признак прочтения. |
| `FR-COMM-05` | Система должна быть работоспособна без обязательного исходящего соединения; любой внешний provider или webhook должен активироваться только оператором. | `docs/adr/ADR-0014-unified-open-source-runtime.md`; `docker-compose.yml`; `scripts/security/test-no-default-egress.ps1` | Чистая установка с default-конфигурацией проходит запуск и ключевые E2E, а сетевой монитор не фиксирует внешний runtime-трафик. |

### 3.7. Администрирование

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `FR-ADMIN-01` | Система должна предоставлять защищённые серверными правами функции администрирования пользователей, ролей, разрешений и системных настроек. | `apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdUserController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdRoleController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/md/controller/MdSettingController.java` | E2E администратора изменяет пользователя, роль и настройку; та же последовательность обычной ролью получает отказ. |
| `FR-ADMIN-02` | Система должна предоставлять уполномоченной роли журнал аудита, статистику и события безопасности. | `apps/server/src/main/java/com/greenwhite/dwh/instance/audit/controller/AuditLogController.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/audit/repository/AuditLogRepository.java` | API-тест с правом `audit.log:view` получает созданное событие; роль без права получает `403`. |
| `FR-ADMIN-03` | Система должна позволять оператору установки выбирать provider хранения и каналов доставки конфигурацией, а администратору — видеть безопасный системный статус активных компонентов. | `apps/server/src/main/resources/application.yml`; `apps/server/src/main/java/com/greenwhite/dwh/instance/common/provider/ProviderRegistry.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/config/system/SystemInfoController.java` | Для каждой поддерживаемой конфигурации startup-тест подтверждает выбранный provider; system-info возвращает только код и состояние без credentials. |
| `FR-ADMIN-04` | Система должна показывать санитизированный статус последнего backup без DSN, паролей и путей к архиву. | `apps/server/src/main/java/com/greenwhite/dwh/instance/config/system/BackupStatusReader.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/config/system/BackupStatusReaderTest.java`; `scripts/prod/test-backup-status.ps1` | Контракт записывает success/failure status с secret-like входом и подтверждает валидный JSON, разрешённые поля и отсутствие секрета/пути. |

### 3.8. Открытая поставка

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `FR-OSS-01` | Система должна поставляться полным исходным кодом продукта под Apache License 2.0 вместе с необходимыми notices. | `LICENSE`; `NOTICE`; `README.md`; `pom.xml`; `apps/web/package.json` | Релизная проверка подтверждает наличие лицензии/notices и возможность собрать server/web из опубликованного commit без закрытого runtime-модуля. |
| `FR-OSS-02` | Система должна использовать один и тот же продуктовый runtime для self-hosted и Smartup-managed установок; различаться могут инфраструктурная конфигурация и услуги эксплуатации. | `README.md`; `docs/adr/ADR-0014-unified-open-source-runtime.md`; `scripts/architecture/test-unified-boundaries.ps1` | Контракт границ подтверждает единственные application runtimes `apps/server` и `apps/web` и отсутствие отдельного managed/control-plane продукта. |
| `FR-OSS-03` | Система должна запускаться и выполнять функции без runtime licensing callback, remote enrollment или phone-home. | `apps/server/src/main/resources/db/migration/V019__unified_open_source_core.sql`; `docs/adr/ADR-0014-unified-open-source-runtime.md`; `scripts/security/test-no-default-egress.ps1` | Миграционный тест подтверждает удаление активного license state; чистая установка и no-egress тест проходят без внешней регистрации. |

## 4. Данные и жизненный цикл

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `NFR-DATA-01` | Система должна хранить авторитетные транзакционные данные в PostgreSQL; Typesense должен оставаться производным и полностью перестраиваемым индексом. | `apps/server/src/main/resources/db/migration/`; `apps/server/src/main/java/com/greenwhite/dwh/instance/search/typesense/TypesenseSyncRunner.java` | На disposable-установке удаляется Typesense volume, выполняется sync и результаты сверяются с PostgreSQL. |
| `NFR-DATA-02` | Система должна хранить загруженные байты в `local_disk` либо S3-совместимом хранилище; управляемая целевая конфигурация должна использовать Cloudflare R2. | `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/storage/LocalStorageProvider.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/storage/S3StorageProvider.java`; `README.md` | Один набор round-trip/delete тестов проходит для local и S3, затем для утверждённого R2 bucket/prefix. |
| `NFR-DATA-03` | Изменение схемы должно выполняться только forward-only Flyway-миграциями через отдельный одноразовый сервис `migrate`; обычный server не должен менять схему и должен fail closed при несовпадении версии. | `apps/server/src/main/resources/application.yml`; `apps/server/src/main/java/com/greenwhite/dwh/instance/config/db/MigrateModeRunner.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/config/db/SchemaVersionGate.java`; `docker-compose.yml` | Тест пустой БД выполняет `docker compose --profile tools run --rm migrate`; старт server с отстающей схемой завершается ошибкой, после migrate — readiness. |
| `NFR-DATA-04` | Перед миграцией существующей БД система поставки должна создавать зашифрованный `age` backup, формировать SHA-256, хранить age identity вне хоста, применять default retention БД 14 дней и предусматривать отдельное восстановление объектных байтов. | `scripts/prod/deploy.ps1`; `deploy/images/backup/backup-loop.sh`; `deploy/compose/docker-compose.prod.yml`; `docs/ops/maintenance-guide.md` | На disposable-данных deploy создаёт `.dump.age` и checksum; restore с отдельно предоставленной identity проходит, а DB+objects drill подтверждает согласованное восстановление и фактические RPO/RTO. |
| `NFR-DATA-05` | Установка должна классифицировать PII: профиль/идентичность, authentication/security, бизнес-контент, метаданные файлов, audit records и backup. Оператор должен до production утвердить правовое основание, точные сроки хранения, роли доступа и процесс запросов субъекта данных. | `docs/security/threat-model.md`; `docs/ops/production-launch-checklist.md` | Подписанное приложение установки содержит владельца и решение по каждому классу; тест удаления/экспорта проверяет PostgreSQL, Typesense, объекты и применимую политику backup. |

## 5. Нефункциональные требования

### 5.1. Безопасность

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `NFR-SEC-01` | Система должна применять проверяемые OWASP-контроли для аутентификации, авторизации, CSRF, rate limiting, безопасных ошибок и загрузок. | `apps/server/src/main/java/com/greenwhite/dwh/instance/config/security/`; `apps/server/src/test/java/com/greenwhite/dwh/instance/config/security/`; `docs/security/threat-model.md` | `mvn -B verify` и негативный security suite проходят; release review сопоставляет актуальные угрозы и остаточные риски. |
| `NFR-SEC-02` | Производственные контейнеры и database/backup роли должны работать с наименьшими необходимыми правами. | `deploy/compose/docker-compose.prod.yml`; `deploy/images/backup/bootstrap-role.sh`; `Dockerfile` | Контракт Compose проверяет `no-new-privileges`, dropped capabilities/read-only FS; DB-тест подтверждает запрет записи backup-роли. |
| `NFR-SEC-03` | Секреты, `.env`, dumps, customer data и расшифрованные backup не должны попадать в Git, логи или публичные diagnostic artifacts. | `.gitignore`; `SECURITY.md`; `e2e/scripts/verify-artifact-security.mjs`; `scripts/prod/test-backup-status.ps1` | Secret scan и artifact-security тест проходят на commit и релизном evidence bundle. |
| `NFR-SEC-04` | Производственная установка должна завершать TLS на внешнем edge и не публиковать PostgreSQL, Typesense или management endpoints. Smartup-managed установка должна использовать Cloudflare edge; self-hosted оператор может использовать собственный проверенный edge. | `deploy/compose/docker-compose.prod.yml`; `deploy/nginx/nginx.prod.conf`; `docs/ops/deployment-guide.md`; `docs/adr/ADR-0014-unified-open-source-runtime.md` | С внешней сети доступны только утверждённый HTTPS domain и web origin; скан портов подтверждает недоступность внутренних сервисов. Для managed-профиля дополнительно зафиксированы Cloudflare zone, WAF/rate-limit policy и блокировка прямого origin-доступа. Конкретный domain является входом установки. |
| `NFR-SEC-05` | Производственный upload должен проходить content validation и fail-closed malware scanning до публикации. | `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/service/FileContentInspector.java`; `apps/server/src/main/java/com/greenwhite/dwh/instance/mf/scan/ClamAvFileScanner.java`; `deploy/compose/docker-compose.prod.yml` | EICAR и executable-signature samples отклоняются; scanner outage не создаёт доступного объекта или метаданных. |
| `NFR-SEC-06` | Исходящие webhook должны быть защищены от SSRF посредством схемы HTTPS, точного allow-list, проверки DNS/IP и запрета private ranges по умолчанию. | `apps/server/src/main/java/com/greenwhite/dwh/instance/kwh/service/WebhookTargetPolicy.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/kwh/service/WebhookTargetPolicyTest.java` | Набор тестов покрывает loopback, link-local, private IPv4/IPv6, DNS rebinding и разрешённый публичный host. |
| `NFR-SEC-07` | Релиз должен проверяться по digest, SHA-256, SBOM, provenance и keyless Cosign signature до развёртывания. | `.github/workflows/release.yml`; `scripts/release/verify-release.ps1`; `README.md` | На release candidate успешно выполняются checksum, `cosign verify`, attestation verification и сверка SBOM для каждого image digest. |

### 5.2. Производительность и ёмкость

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `NFR-PERF-01` | До release система должна пройти воспроизводимый нагрузочный и soak-профиль на репрезентативных данных для суммарного планового контура 500 registered/100 active users и 50 ГБ upload в месяц с отдельно зафиксированным распределением по установкам. | `docs/ops/production-launch-checklist.md`; `audit/fixes/P-01-performance-baseline.md` | Release evidence фиксирует commit/image digest, host profile, dataset, сценарии, распределение нагрузки, p50/p95/p99, error rate и saturation; результат утверждает владелец SLO. |
| `NFR-PERF-02` | Числовые цели p95 API и загрузки страниц должны считаться неутверждёнными входными данными релиза, пока их не утвердят владельцы продукта и эксплуатации; текущее ТЗ не задаёт непроверенных чисел. | `docs/ops/production-launch-checklist.md`; `audit/fixes/P-01-performance-baseline.md` | В installation/release annex заполнены числовые пороги и решение владельца; без них performance gate и `AC-12` не выполнены. |

### 5.3. Надёжность

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `NFR-REL-01` | Установка должна иметь health/readiness checks для PostgreSQL, server, web, Typesense и ClamAV в production topology. | `deploy/compose/docker-compose.prod.yml`; `apps/server/src/main/resources/application.yml` | `docker compose up -d --wait` завершается успешно, а принудительный отказ каждой зависимости отражается в health/status. |
| `NFR-REL-02` | Развёртывание должно прекращаться при неуспешном backup, миграции или readiness, не продолжая rollout. | `scripts/prod/deploy.ps1`; `scripts/prod/test-deploy-fail-closed.sh` | Инъекция ошибки на каждом шаге возвращает ненулевой код и подтверждает, что новая версия server/web не объявлена готовой. |
| `NFR-REL-03` | Server и контейнеры должны поддерживать graceful shutdown в заданный Compose grace period. | `apps/server/src/main/resources/application.yml`; `deploy/compose/docker-compose.prod.yml` | Во время in-flight запроса выполняется `docker compose stop`; запрос завершается либо корректно отклоняется, процесс останавливается в установленный grace period. |
| `NFR-REL-04` | Для релиза должна существовать проверенная процедура rollback/recovery с неизменяемыми image digest и отдельным решением о восстановлении данных. | `docs/ops/rollback.md`; `scripts/prod/restore.ps1`; `scripts/prod/restore.sh` | Disposable drill выполняет application rollback и, отдельно, DB restore по документу с записью решения, checksum и результата. |
| `NFR-REL-05` | Базовая поставка не должна обещать встроенную cross-host HA; требуемая доступность и HA-профиль должны определяться оператором после утверждения SLO/RPO/RTO. | `docs/adr/ADR-0014-unified-open-source-runtime.md`; `docs/ops/architecture-overview.md` | Архитектурный review подтверждает single-host Compose boundary; договорённость установки явно принимает риск либо описывает внешнюю HA-схему. |

### 5.4. Наблюдаемость

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `NFR-OBS-01` | Система должна выдавать структурно размеченные журналы с `traceparent` и предоставлять внутренние health, info, metrics и Prometheus endpoints. | `apps/server/src/main/resources/application.yml`; `apps/server/src/main/java/com/greenwhite/dwh/instance/config/web/TraceparentFilter.java`; `deploy/compose/docker-compose.prod.yml` | Smoke создаёт синтетический запрос, находит его traceparent в container log и получает метрики/health только из внутренней сети. |
| `NFR-OBS-02` | Оператор должен определить сбор журналов и метрик, dashboards, alert transport, on-call и SLO; базовая Compose-поставка не должна заявлять их готовыми без отдельного утверждения. | `docs/ops/architecture-overview.md`; `docs/ops/production-launch-checklist.md`; `docs/adr/ADR-0014-unified-open-source-runtime.md` | `AC-12` проверяет заполненные endpoints, dashboards, маршруты тестовых alerts, named on-call и утверждённые пороги; отсутствие любого пункта блокирует production. |

### 5.5. Переносимость

| ID | Требование («Должна») | Подтверждение в репозитории | Метод приёмки |
|---|---|---|---|
| `NFR-PORT-01` | Релиз должен публиковать образы `server`, `web`, `backup`, `postgres` и `typesense` для `linux/amd64` и `linux/arm64`. | `.github/workflows/release.yml`; `scripts/release/verify-release.ps1` | Для каждого образа `docker buildx imagetools inspect` подтверждает оба platform manifest под одним immutable tag/digest. |
| `NFR-PORT-02` | Поддерживаемый запуск должен использовать Docker Engine 26+ и Docker Compose v2 без обязательной зависимости от конкретного cloud provider. | `README.md`; `deploy/compose/docker-compose.prod.yml`; `docs/adr/ADR-0014-unified-open-source-runtime.md` | Матрица smoke-тестов на amd64/arm64 с минимальной поддерживаемой Docker version проходит config, migrate и clean startup для local storage. |

## 6. Критерии приёмки релиза

| ID | Измеримый критерий | Источник и доказательство приёмки |
|---|---|---|
| `AC-01` | Команда `mvn -B verify` завершается с кодом 0 на release commit. | `pom.xml`, `.github/workflows/ci.yml`; сохраняется URL/лог CI и SHA commit. |
| `AC-02` | После `npm ci` в `apps/web` команды `npm test`, `npm run typecheck` и `npm run build` завершаются с кодом 0. | `apps/web/package.json`, `.github/workflows/ci.yml`; сохраняются лог и build artifact. |
| `AC-03` | Compose, unified-boundary, public-docs, repository-hygiene, release, release-config, backup-status и no-default-egress/security контракты завершаются с кодом 0. | `docker compose config --quiet`; `scripts/architecture/test-unified-boundaries.ps1`; `scripts/docs/test-public-docs.ps1`; `scripts/docs/test-repository-hygiene.ps1`; `scripts/release/verify-release.ps1`; `scripts/prod/test-release-config.ps1`; `scripts/prod/test-backup-status.ps1`; `scripts/security/test-no-default-egress.ps1`. |
| `AC-04` | На пустой PostgreSQL отдельный `migrate` применяет все Flyway migrations один раз, история валидна, повторный запуск не меняет схему. | `docker compose --profile tools run --rm migrate`; `apps/server/src/test/java/com/greenwhite/dwh/instance/db/FlywayMigrationValidationTest.java`. |
| `AC-05` | Чистая production Compose-установка после migrate достигает healthy/readiness для всех обязательных сервисов в пределах настроенного health timeout. | `deploy/compose/docker-compose.prod.yml`; `scripts/prod/deploy.ps1`; сохраняется `docker compose ps` и health output. |
| `AC-06` | Playwright Chromium проходит критические journeys: bootstrap/forced password change/sign-in, tasks, announcements и system status без secret-bearing artifacts. | `e2e/tests/browser/instance/auth.spec.ts`; `e2e/tests/browser/instance/tasks.spec.ts`; `e2e/tests/browser/announcements/announcements.spec.ts`; `e2e/tests/browser/system/system.spec.ts`; `e2e/scripts/verify-artifact-security.mjs`. |
| `AC-07` | Production upload отклоняет EICAR, а при outage scanner не публикует объект и очищает quarantine/metadata residue. | `apps/server/src/test/java/com/greenwhite/dwh/instance/mf/ClamAvFileScannerTest.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/mf/MfFileServiceTest.java`; `scripts/prod/test-deploy-fail-closed.sh`; сохраняется storage/DB diff. |
| `AC-08` | До миграции создаётся age-encrypted backup с валидным SHA-256; из него выполняется изолированный restore и сверка схемы/репрезентативных данных. | `deploy/images/backup/backup-loop.sh`; `scripts/prod/backup.ps1`; `scripts/prod/restore.ps1`; evidence содержит checksum, время и measured RPO/RTO. |
| `AC-09` | На целевом S3/R2 выполняются upload/download byte-for-byte/delete и проверка согласованности последней ссылки; отдельно подтверждается восстановление объектов. | `apps/server/src/test/java/com/greenwhite/dwh/instance/mf/S3StorageProviderIntegrationTest.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/mf/MfFileMetadataServiceTest.java`; evidence содержит endpoint class/region без credentials и object checksums. |
| `AC-10` | Негативные тесты ролей и IDOR отклоняют прямой доступ к чужим/недоступным task, comment и file IDs и к admin/audit endpoints. | `apps/server/src/test/java/com/greenwhite/dwh/instance/kauth/security/RequiresPermissionInterceptorTest.java`; `apps/server/src/test/java/com/greenwhite/dwh/instance/md/MdScopeServiceIntegrationTest.java`; результат release security run фиксирует проверенную матрицу. |
| `AC-11` | Для каждого release image проверены immutable digest, Cosign signature, GitHub provenance, SPDX/CycloneDX SBOM и checksums release bundle. | `.github/workflows/release.yml`; `scripts/release/verify-release.ps1`; команды проверки и их output сохраняются в release evidence. |
| `AC-12` | До production для каждой установки назначены владельцы и утверждены числовой SLO, privacy/retention, incident contacts/on-call, RPO/RTO, production domain, provider region и лицо, принимающее rollback/go-no-go решение. | `docs/ops/production-launch-checklist.md`; `docs/security/threat-model.md`; подписанное installation annex не содержит пустых значений и приложено к release review. |

## 7. Открытые решения подготовки релиза

Из репозитория невозможно подтвердить числовой SLO и целевые p95 API/page,
юридически обязательные сроки хранения, production-домены, ответственных за
инциденты и распределение пиковой нагрузки по отдельным установкам. Также
installation-specific решениями остаются RPO/RTO, provider region, хранение
age identity и ответственный за rollback/go-no-go. Эти значения нельзя выводить
из агрегатного плана управляемого парка или заменять предлагаемыми значениями из
аудита.

| Пробел | Обязательное решение до production | Закрывающий критерий |
|---|---|---|
| Нагрузка и SLO | Утвердить распределение 500 registered/100 active/50 ГБ в месяц по установкам, dataset, p95/p99/error/saturation thresholds и владельца. | `NFR-PERF-01`, `NFR-PERF-02`, `AC-12` |
| Privacy и retention | Назначить controller/processor, правовое основание, сроки по классам, DSAR/delete/hold и владельцев. | `NFR-DATA-05`, `AC-12` |
| Production topology | Зафиксировать domain, TLS edge, storage/backup provider regions и доступ к age identity. | `NFR-SEC-04`, `AC-08`, `AC-12` |
| Operations | Назначить incident contacts/on-call, alert transport, RPO/RTO и владельца rollback/go-no-go. | `NFR-OBS-02`, `NFR-REL-04`, `AC-12` |
