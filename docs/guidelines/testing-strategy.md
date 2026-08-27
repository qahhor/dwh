# Стратегия тестирования и архитектурный тест-план

**Версия:** 1.0
**Дата:** 2026-08-28
**Основание:** ТЗ-01 NFR-11, ADR-0002, ADR-0006, ADR-0008, ADR-0011, CODE_STYLE.md

---

## 1. Пирамида тестирования

```
           / \
          / E2E \         Сквозные пользовательские сценарии (Playwright, F-01…F-09)
         /───────\
        / Contract \      Контрактные тесты адаптеров Provider SPI (MockServer)
       /─────────────\
      /  Integration  \   Spring Boot + Testcontainers (PostgreSQL 18, Garage S3)
     /─────────────────\
    /     ArchUnit      \ Архитектурные тесты границ модулей и инвариантов
   /─────────────────────\
  /         Unit          \ Юнит-тесты чистой доменной логики и инвариантов агрегатов
 /─────────────────────────\
```

---

## 2. Юнит-тестирование (Unit Tests)

- **Фреймворки:** JUnit 5, AssertJ, Mockito.
- **Обязательное покрытие:**
  - Доменные агрегаты и правила инвариантов: `Task` (I-T1…I-T7), `EffectivePermissions` (I-P1…I-P4), `User` (I-U1…I-U3), `NotificationOutbox` (I-N1…I-N2).
  - Никакой зависимости от Spring Context или базы данных — мгновенное выполнение (миллисекунды).
- **Пример теста агрегата Task (I-T6: закрытие с открытыми подзадачами):**
  ```java
  @Test
  void should_reject_closing_task_when_subtasks_are_open() {
      Task parentTask = TaskFixture.createInProgressTask();
      parentTask.addSubtask(TaskFixture.createOpenSubtask());

      assertThatThrownBy(() -> parentTask.changeStatus(TaskStatus.DONE, currentUser))
          .isInstanceOf(DomainRuleViolationException.class)
          .hasMessageContaining("task_closed_with_open_subtasks");
  }
  ```

---

## 3. Архитектурное тестирование (ArchUnit)

Обязательный шаг сборки CI (`mvn test`). Нарушение правил немедленно валит сборку.

### 3.1. Правило 1: Изоляция модулей и запрет циклов
```java
@ArchTest
public static final ArchRule no_cyclic_dependencies_between_modules =
    slices().matching("com.greenwhite.dwh.instance.(*)..")
            .should().beFreeOfCycles();

@ArchTest
public static final ArchRule module_internal_packages_are_isolated =
    noClasses().that().resideInAPackage("..tasks..")
               .should().dependOnClassesThat().resideInAPackage("..iam.internal..");
```

### 3.2. Правило 2: Инкапсуляция репозиториев
```java
@ArchTest
public static final ArchRule repositories_must_only_be_accessed_by_own_services =
    classes().that().haveSimpleNameEndingWith("Repository")
             .should().onlyBeAccessed().byClassesThat()
             .resideInAnyPackage("..repository..", "..service..");
```

### 3.3. Правило 3: Защита контроллеров аннотациями прав (FR-PERM-8)
```java
@ArchTest
public static final ArchRule all_rest_controllers_must_have_permission_annotation =
    methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
             .and().arePublic()
             .should().beAnnotatedWith(RequiresPermission.class)
             .orShould().beAnnotatedWith(PublicEndpoint.class);
```

### 3.4. Правило 4: Изоляция внешних вызовов (Provider SPI, ADR-0011)
```java
@ArchTest
public static final ArchRule external_providers_must_only_be_called_from_adapters =
    noClasses().that().resideOutsideOfPackages("com.greenwhite.dwh.libs.adapters..")
               .should().dependOnClassesThat().resideInAnyPackage("org.telegram..", "com.twilio..");
```

---

## 4. Интеграционное тестирование (Testcontainers)

- **Инфраструктура:** Единый переиспользуемый контейнер `PostgreSQL 18` с расширением `pgvector` и `Garage S3`.
- **Базовый тестовый класс:**
  ```java
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @Testcontainers
  public abstract class AbstractIntegrationTest {
      @Container
      static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
              .withDatabaseName("dwh_test")
              .withUsername("test")
              .withPassword("test");

      @DynamicPropertySource
      static void configureProperties(DynamicPropertyRegistry registry) {
          registry.add("spring.datasource.url", postgres::getJdbcUrl);
          registry.add("spring.datasource.username", postgres::getUsername);
          registry.add("spring.datasource.password", postgres::getPassword);
      }
  }
  ```

### Обязательные интеграционные тест-сьюты:
1. **RBAC & Эффективные права:**
   - Изменение прав роли пересчитывает `effective_permissions` в той же транзакции.
   - Инкремент `permissions_version` пользователя.
   - Отзыв прав блокирует доступ к эндпоинту за ≤ 60 с без повторного логина.
2. **Transactional Outbox:**
   - Сохранение задачи создаёт запись в `notification_outbox`.
   - Воркер успешно блокирует строки через `FOR UPDATE SKIP LOCKED` и производит отправку.
   - Сбойные отправки после 5 попыток перемещаются в статус `DEAD_LETTER`.
3. **Файловое хранилище (Garage S3):**
   - Загрузка двух одинаковых файлов создаёт одну физическую запись в Garage (SHA-256 дедупликация).
   - Загрузка файла с поддельным расширением (`.exe`, переименованный в `.png`) отклоняется валидатором magic-bytes с кодом `415 file_type_forbidden`.

---

## 5. Контрактный тест-кит Provider SPI (ADR-0011)

Каждый адаптер внешнего провайдера (SMS, Email, Telegram, LLM) обязан проходить стандартный контрактный тест-сьют перед добавлением в сборку.

### Базовый контракт для SMS-провайдеров:
```java
public abstract class AbstractSmsProviderContractTest {
    protected abstract SmsProvider createProvider();
    protected abstract void mockSuccessfulDelivery(String phone, String text);
    protected abstract void mockProviderNetworkError();
    protected abstract void mockInvalidRecipientError(String phone);

    @Test
    void should_successfully_deliver_sms_message() {
        SmsProvider provider = createProvider();
        mockSuccessfulDelivery("+998901234567", "Your OTP: 123456");

        DeliveryResult result = provider.send(new SmsMessage("+998901234567", "Your OTP: 123456"));
        assertThat(result).isInstanceOf(DeliveryResult.Success.class);
    }

    @Test
    void should_return_retryable_failure_on_network_timeout() {
        SmsProvider provider = createProvider();
        mockProviderNetworkError();

        DeliveryResult result = provider.send(new SmsMessage("+998901234567", "Test"));
        assertThat(result).isInstanceOf(DeliveryResult.Failure.class);
        assertThat(((DeliveryResult.Failure) result).retryable()).isTrue();
    }
}
```

---

## 6. Тестирование безопасности и комплаенса (Security Tests)

1. **CSRF Double-Submit:**
   - Мутирующий запрос (`POST /api/v1/tasks`) с сессионной cookie без заголовка `X-CSRF-Token` возвращает `403 Forbidden`.
   - Тот же запрос с `Authorization: Bearer <api-token>` успешно проходит без CSRF-токена.
2. **Rate Limiting (Bucket4j):**
   - 61-й неаутентифицированный запрос за 1 минуту возвращает `429 Too Many Requests` с заголовком `Retry-After`.
3. **Маскирование в логах:**
   - Тест перехватывает вывод Logback при вызове аутентификации и проверяет, что пароли, токены и телефоны заменяются на `***MASKED***`.
