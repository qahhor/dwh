package com.smartup24.cms.instance.common.bulk;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.instance.common.error.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;

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

    private BulkRunner() {}

    /** Тело запроса: действие, записи и параметры действия. */
    public record BulkRequest(String action, List<Long> ids, JsonNode params) {}

    /**
     * Итог по одной записи; {@code code}, {@code message}, {@code messageKey} и {@code params} — только у неудачи.
     * {@code message} — текст на языке запроса (его собирает обработчик ответа по {@code messageKey}); клиент
     * может собрать его и сам, по ключу из своего каталога.
     */
    public record BulkItemResult(
            long id,
            boolean ok,
            @Nullable String code,
            @Nullable String message,
            @Nullable String messageKey,
            @Nullable Map<String, Object> params) {}

    public record BulkResult(String action, int succeeded, int failed, List<BulkItemResult> results) {

        /** The same result with the text of each failure rendered from its key; {@code render} gets key and params. */
        public BulkResult withMessages(BiFunction<String, Map<String, Object>, String> render) {
            List<BulkItemResult> rendered =
                    results.stream().map(item -> rendered(item, render)).toList();
            return new BulkResult(action, succeeded, failed, rendered);
        }

        private static BulkItemResult rendered(
                BulkItemResult item, BiFunction<String, Map<String, Object>, String> render) {
            String key = item.messageKey();
            if (key == null) {
                return item;
            }
            Map<String, Object> params = item.params();
            String message = render.apply(key, params == null ? Map.of() : params);
            return new BulkItemResult(item.id(), item.ok(), item.code(), message, key, params);
        }
    }

    /**
     * Проверенный список записей: от одной до {@link #MAX_IDS}, без пустых, повторы убраны с сохранением порядка.
     */
    public static List<Long> checkedIds(BulkRequest request) {
        List<Long> ids = request == null ? null : request.ids();
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw ApiException.validation(
                    "error.common.bulk_ids_invalid",
                    Map.of("max", MAX_IDS),
                    List.of(new FieldErrorItem("ids", BULK_INVALID, "from 1 to " + MAX_IDS + " positive ids")));
        }
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    public static ApiException unknownAction(@Nullable String action) {
        String name = action == null ? "" : action;
        return ApiException.validation(
                "error.common.bulk_action_unknown",
                Map.of("action", name),
                List.of(new FieldErrorItem("action", BULK_ACTION_UNKNOWN, "unknown action: " + name)));
    }

    public static ApiException invalidParam(String name, String message) {
        return ApiException.validation(
                "error.common.bulk_param_invalid",
                Map.of("name", name),
                List.of(new FieldErrorItem("params." + name, BULK_INVALID, message)));
    }

    /** Выполняет операцию для каждой записи и собирает итог; ожидаемые отказы — с кодом одиночной операции. */
    public static BulkResult run(String action, List<Long> ids, LongConsumer operation) {
        return run(action, ids, operation, TransactionOperations.withoutTransaction());
    }

    /**
     * As {@link #run(String, List, LongConsumer)}, each item in the scope's savepoint ({@link BulkItemScope}); without
     * a scope (slice tests) each item runs as the single operation does.
     */
    public static BulkResult run(String action, List<Long> ids, LongConsumer operation, @Nullable BulkItemScope scope) {
        return run(action, ids, operation, scope == null ? TransactionOperations.withoutTransaction() : scope.items());
    }

    private static BulkResult run(String action, List<Long> ids, LongConsumer operation, TransactionOperations items) {
        List<BulkItemResult> results = new ArrayList<>(ids.size());
        int succeeded = 0;
        for (long id : ids) {
            try {
                items.executeWithoutResult(status -> operation.accept(id));
                results.add(new BulkItemResult(id, true, null, null, null, null));
                succeeded++;
            } catch (ApiException e) {
                results.add(failure(id, e));
            } catch (RuntimeException e) {
                // Unexpected: the item is reported without internals, the log keeps the cause.
                log.warn("Bulk {} failed for id {}", action, id, e);
                results.add(new BulkItemResult(
                        id, false, BULK_ITEM_FAILED.toLowerCase(Locale.ROOT), BULK_ITEM_FAILED, null, null));
            }
        }
        return new BulkResult(action, succeeded, ids.size() - succeeded, List.copyOf(results));
    }

    /**
     * A refusal of the single operation. Its key goes out for the response handler to render; until then the message
     * is the key itself. An older caller's sentence (not a key) is the message as it is.
     */
    private static BulkItemResult failure(long id, ApiException e) {
        String code = e.getErrorCode().name().toLowerCase(Locale.ROOT);
        return e.hasMessageKey()
                ? new BulkItemResult(id, false, code, e.getMessageKey(), e.getMessageKey(), e.getParams())
                : new BulkItemResult(id, false, code, e.getMessageKey(), null, null);
    }
}
