# Структура монорепозитория и спецификация модулей

**Версия:** 1.0
**Дата:** 2026-08-28
**Основание:** ADR-0002, ADR-0006 (модульный монолит), ADR-0011 (Provider SPI), ADR-0012 (UI)

---

## 1. Концепция монорепозитория

Проект DWH Platform организован в виде единого монорепозитория, объединяющего Backend (Maven multi-module), Frontend (Angular workspace), Инфраструктурные шаблоны (Nomad/Consul/Vault) и документацию.

---

## 2. Дерево структуры каталогов

```
dwh/
├── pom.xml                                 # Корневой Maven POM (управление версиями, Java 25)
├── package.json                            # Корневой frontend-манифест (Angular 22, pnpm)
├── CODE_STYLE.md                           # Единый стандарт стиля кодовой базы
├── CONTRIBUTING.md                         # Процессы разработки, ветвления и ревью
├── README.md                               # Входная точка документации
│
├── apps/                                   # Исполняемые приложения
│   ├── instance/                           # Бэкенд экземпляра клиента (Spring Boot 4.1)
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/com/greenwhite/dwh/instance/
│   │       │   │   ├── md/                 # Master Data: пользователи, роли, формы, права, настройки (Md*)
│   │       │   │   ├── kauth/              # Kernel Auth: сессии, токены, OTP/2FA, сброс паролей (Kauth*)
│   │       │   │   ├── ms/                 # Messaging & Services: задачи, комментарии, оповещения, outbox (Ms*)
│   │       │   │   │   ├── task/           # Задачник и проекты (MsTask*)
│   │       │   │   │   └── notify/         # Оповещения, Transactional Outbox, объявления (MsNotify*)
│   │       │   │   ├── mf/                 # Media & Files: метаданные файлов, S3 клиент (Mf*)
│   │       │   │   └── audit/              # Логирование изменений (JSONB) и security-события (Audit*)
│   │       │   └── resources/
│   │       │       ├── db/migration/       # Flyway-миграции ядра (V001__init.sql...)
│   │       │       └── application.yml
│   │       └── test/                       # ArchUnit, юнит и интеграционные тесты (Testcontainers)
│   │
│   ├── control-plane/                      # Бэкенд центрального Control Plane (Spring Boot 4.1)
│   │   ├── pom.xml
│   │   └── src/
│   │       ├── main/java/com/greenwhite/dwh/cp/
│   │       └── main/resources/db/migration/ # Flyway-миграции Control Plane
│   │
│   └── web/                                # Angular 22 SPA Workspace
│       ├── angular.json
│       ├── projects/
│       │   ├── instance-ui/                # Клиентский веб-интерфейс
│       │   └── control-plane-ui/           # Административный интерфейс флота
│       └── tsconfig.base.json
│
├── libs/                                   # Переиспользуемые библиотеки и адаптеры
│   ├── core-types/                         # Общие базовые типы, RFC 9457 ProblemDetail DTO
│   ├── crypto-vault/                       # Клиент HashiCorp Vault (Transit, подпись/проверка)
│   ├── provider-spi/                       # Интерфейсы SPI (SmsProvider, MailProvider, etc.)
│   ├── adapters/                           # Реализации провайдеров (ADR-0011)
│   │   ├── mail-smtp/                      # Реализация отправки почты через SMTP
│   │   ├── messenger-telegram/             # Telegram Bot API адаптер
│   │   ├── sms-eskiz/                      # SMS-шлюз Eskiz
│   │   └── mock-providers/                 # Mock-адаптеры для локальной разработки и тестов
│   ├── ui-tokens/                          # Дизайн-токены (SCSS-переменные цветов, отступов)
│   └── ui-kit/                             # Обёртки Angular Material + CDK (ui-grid, ui-button)
│
├── deploy/                                 # Инфраструктурный код и оркестрация
│   ├── nomad/                              # Nomad job specifications (*.nomad.hcl)
│   │   ├── client-instance.nomad.hcl       # Шаблон развёртывания клиентского экземпляра
│   │   ├── control-plane.nomad.hcl         # Развёртывание Control Plane
│   │   └── flyway-batch.nomad.hcl          # Batch-job наката миграций БД
│   ├── consul/                             # Шаблоны регистрации сервисов
│   ├── vault/                              # Политики доступа Vault (HCL)
│   └── spike/                              # Скрипты стенда Spike Week S-0
│
└── docs/                                   # Вся документация платформы
    ├── adr/                                # Архитектурные решения (ADR-0001…0012)
    ├── trd/                                # Технические задания (TRD-01…04)
    ├── guidelines/                         # Инженерные стандарты (миграции, тестирование)
    ├── runbooks/                           # Регламенты эксплуатации (RB-01…04)
    ├── architecture/                       # Архитектурные спецификации
    ├── plan/                               # Планы вех (M0, M1...)
    └── audit/                              # Отчёты аудита
```

---

## 3. Правила межмодульных зависимостей (Maven)

1. `apps/instance` зависит от:
   - `libs/core-types`
   - `libs/crypto-vault`
   - `libs/provider-spi`
   - Выбранных адаптеров `libs/adapters/*` (включаемых через профили сборки или runtime).
2. Запрещено:
   - `libs/core-types` не может зависеть ни от одного другого модуля.
   - `libs/provider-spi` не зависит от реализаций адаптеров.
   - `apps/instance` и `apps/control-plane` **не зависят друг от друга** (взаимодействие только по REST-протоколу, TRD-04 разд. 6).
