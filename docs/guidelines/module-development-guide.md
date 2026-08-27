# Руководство разработчика по созданию нового модуля (Module Developer Guide)

**Версия:** 1.0  
**Дата:** 2026-08-28  
**Основание:** ADR-0006 (Модульный монолит), ADR-0003 (RBAC), `biruni-smartup-conventions.md`, `CODE_STYLE.md`

---

## 1. Концепция модульности

Платформа DWH построена по принципу **модульного монолита** с жесткой изоляцией доменных границ:
- Каждый модуль имеет уникальный 2–5 буквенный префикс (например, `trade`, `wms`, `fin`, `crm`).
- Модули общаются между собой **только** через публичные интерфейсы фасадов (`*Service` / `*Facade`) или через **Spring Application Events** (доменные события).
- Прямые SQL-запросы к таблицам чужого модуля и инъекция чужих репозиториев **строго запрещены** и блокируются тестами **ArchUnit**.

---

## 2. Пошаговый алгоритм создания нового модуля (на примере модуля `trade`)

### Шаг 1. Регистрация префикса и пакета
1. Выберите уникальный префикс (например, `trade`).
2. Создайте пакет в `apps/instance/src/main/java/com/greenwhite/dwh/instance/trade/`.
3. Внутри пакета сформируйте стандартную слоистую структуру:
   ```
   trade/
   ├── config/               # Spring Beans конфигурация модуля
   ├── controller/           # REST-контроллеры (TradeOrderController)
   ├── dto/                  # Запросы и ответы (TradeOrderCreateRequest, TradeOrderResponse)
   ├── event/                # Доменные события (TradeOrderCreatedEvent)
   ├── model/                # Неизменяемые Records / Доменные агрегаты
   ├── pref/                 # TradePref.java (константы, pcodes, настройки)
   ├── repository/           # Доступ к данным через JdbcClient / jOOQ
   └── service/              # Бизнес-логика и публичный фасад TradeOrderService
   ```

---

### Шаг 2. Создание DDL миграции (Flyway)
1. Создайте файл миграции `apps/instance/src/main/resources/db/migration/V***__trade_init.sql`.
2. Все таблицы, индексы, ограничения и внешние ключи обязаны начинаться с префикса `trade_`:
   ```sql
   -- Таблица заказов
   create table trade_orders (
       id bigint generated always as identity primary key,
       order_number text not null unique,
       customer_id bigint not null references md_users(id),
       status text not null default 'NEW' check (status in ('NEW', 'PROCESSING', 'COMPLETED', 'CANCELLED')),
       total_amount numeric(18, 2) not null default 0.00,
       attributes jsonb not null default '{}'::jsonb, -- Поддержка динамических полей (FR-ATTR-3)
       created_at timestamptz not null default now(),
       modified_at timestamptz not null default now(),
       created_by bigint references md_users(id),
       modified_by bigint references md_users(id)
   );

   -- GIN-индекс для быстрого поиска по динамическим атрибутам
   create index trade_orders_attributes_gin_idx on trade_orders using gin (attributes jsonb_path_ops);
   ```

---

### Шаг 3. Создание класса преференсов и констант (`TradePref.java`)
В соответствии с традицией **Biruni / Smartup**, все константы модуля выносятся в единый класс `*Pref`:

```java
package com.greenwhite.dwh.instance.trade.pref;

public final class TradePref {
    private TradePref() {}

    // Префикс модуля
    public static final String MODULE_CODE = "trade";

    // Коды экранных форм RBAC
    public static final String FORM_ORDERS = "trade.orders";
    public static final String FORM_CUSTOMERS = "trade.customers";

    // Допустимые статусы заказов
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    // Настройки по умолчанию
    public static final int DEFAULT_ORDER_LIMIT = 50;
}
```

---

### Шаг 4. Реализация бизнес-сервиса и репозитория

```java
package com.greenwhite.dwh.instance.trade.service;

import com.greenwhite.dwh.instance.trade.dto.*;
import com.greenwhite.dwh.instance.trade.event.TradeOrderCreatedEvent;
import com.greenwhite.dwh.instance.trade.repository.TradeOrderRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradeOrderService {

    private final TradeOrderRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public TradeOrderService(TradeOrderRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public TradeOrderResponse createOrder(TradeOrderCreateRequest request, Long currentUserId) {
        // 1. Валидация и сохранение заказа
        TradeOrder order = repository.create(request, currentUserId);

        // 2. Публикация доменного события для оповещений и аудита
        eventPublisher.publishEvent(new TradeOrderCreatedEvent(order.id(), order.orderNumber(), currentUserId));

        return TradeOrderResponse.from(order);
    }
}
```

---

### Шаг 5. Реализация REST-контроллера с контролем доступа RBAC

Каждый метод контроллера обязан содержать аннотацию `@RequiresPermission`:

```java
package com.greenwhite.dwh.instance.trade.controller;

import com.greenwhite.dwh.instance.md.annotation.RequiresPermission;
import com.greenwhite.dwh.instance.trade.dto.*;
import com.greenwhite.dwh.instance.trade.pref.TradePref;
import com.greenwhite.dwh.instance.trade.service.TradeOrderService;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trade/orders")
@Validated
public class TradeOrderController {

    private final TradeOrderService orderService;

    public TradeOrderController(TradeOrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(form = TradePref.FORM_ORDERS, action = "create")
    public TradeOrderResponse createOrder(@RequestBody @Validated TradeOrderCreateRequest request) {
        Long currentUserId = SecurityContextHelper.getCurrentUserId();
        return orderService.createOrder(request, currentUserId);
    }

    @GetMapping
    @RequiresPermission(form = TradePref.FORM_ORDERS, action = "view")
    public KeysetPage<TradeOrderResponse> listOrders(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String after) {
        return orderService.listOrders(limit, after);
    }
}
```

---

### Шаг 6. Интеграция с UI (Angular 22 Lazy Module)

1. Создайте lazy-loaded feature модуль в `apps/web/projects/instance-ui/src/app/modules/trade/`.
2. Зарегистрируйте маршруты:
   ```typescript
   export const TRADE_ROUTES: Routes = [
     {
       path: 'orders',
       loadComponent: () => import('./pages/order-list/order-list.component').then(m => m.OrderListComponent),
       canActivate: [permissionGuard('trade.orders', 'view')]
     }
   ];
   ```
3. Используйте корпоративные обёртки UI-кита (`<ui-table>`, `<ui-card>`, `<ui-button>`, `<ui-dynamic-attributes>`).

---

## 3. Чек-лист проверки модуля (ArchUnit & Quality Gate)

Перед отправкой Pull Request убедитесь, что:
- [ ] Все классы именуются с префиксом модуля (`TradeOrderController`, `TradePref`).
- [ ] Все таблицы и индексы в миграции начинаются с префикса `trade_`.
- [ ] В контроллерах нет методов без аннотации `@RequiresPermission`.
- [ ] Нет прямых обращений к репозиториям других модулей (проверено тестами `ModularArchitectureTest`).
- [ ] Внешние сетевые вызовы не выполняются внутри `@Transactional`.
- [ ] Написаны интеграционные тесты с **Testcontainers** (PostgreSQL 18).
