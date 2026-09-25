package com.greenwhite.dwh.instance.common.bulk;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;

/**
 * Массовое действие над выбранными записями ({@code POST …/bulk}): одна и та же операция для каждой записи,
 * каждая — отдельным вызовом сервиса, то есть в своей транзакции. Ошибка одной записи не откатывает
 * остальные: ответ говорит по каждой, прошла ли она и почему нет. Права, скоуп данных и аудит остаются
 * на одиночной операции сервиса — массовое действие не обходит ни одну из её проверок.
 */
public final class BulkRunner {

    public static final int MAX_IDS = 100;
    public static final String BULK_INVALID = "BULK_INVALID";
    public static final String BULK_ACTION_UNKNOWN = "BULK_ACTION_UNKNOWN";
    public static final String BULK_ITEM_FAILED = "BULK_ITEM_FAILED";

    private static final Logger log = LoggerFactory.getLogger(BulkRunner.class);

    private BulkRunner() {
    }

    /** Тело запроса: действие, записи и параметры действия. */
    public record BulkRequest(String action, List<Long> ids, JsonNode params) {
    }

    /** Итог по одной записи; {@code code} и {@code message} — только у неудачи. */
    public record BulkItemResult(long id, boolean ok, String code, String message) {
    }

    public record BulkResult(String action, int succeeded, int failed, List<BulkItemResult> results) {
    }

    /**
     * Проверенный список записей: от одной до {@link #MAX_IDS}, без пустых, повторы убраны с сохранением порядка.
     */
    public static List<Long> checkedIds(BulkRequest request) {
        List<Long> ids = request == null ? null : request.ids();
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw ApiException.validation(BULK_INVALID, List.of(new FieldErrorItem("ids", BULK_INVALID,
                    "from 1 to " + MAX_IDS + " positive ids")));
        }
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    public static ApiException unknownAction(String action) {
        return ApiException.validation(BULK_ACTION_UNKNOWN, List.of(new FieldErrorItem("action", BULK_ACTION_UNKNOWN,
                "unknown action: " + action)));
    }

    public static ApiException invalidParam(String name, String message) {
        return ApiException.validation(BULK_INVALID, List.of(new FieldErrorItem("params." + name, BULK_INVALID, message)));
    }

    /** Выполняет операцию для каждой записи и собирает итог; ожидаемые отказы — с кодом одиночной операции. */
    public static BulkResult run(String action, List<Long> ids, LongConsumer operation) {
        List<BulkItemResult> results = new ArrayList<>(ids.size());
        int succeeded = 0;
        for (long id : ids) {
            try {
                operation.accept(id);
                results.add(new BulkItemResult(id, true, null, null));
                succeeded++;
            } catch (ApiException e) {
                results.add(new BulkItemResult(id, false, e.getErrorCode().name().toLowerCase(Locale.ROOT), e.getMessage()));
            } catch (RuntimeException e) {
                // Unexpected: the item is reported without internals, the log keeps the cause.
                log.warn("Bulk {} failed for id {}", action, id, e);
                results.add(new BulkItemResult(id, false, BULK_ITEM_FAILED.toLowerCase(Locale.ROOT), BULK_ITEM_FAILED));
            }
        }
        return new BulkResult(action, succeeded, ids.size() - succeeded, List.copyOf(results));
    }
}
