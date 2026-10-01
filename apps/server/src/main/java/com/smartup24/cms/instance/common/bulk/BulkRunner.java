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
 * A bulk action on selected records ({@code POST …/bulk}): the same operation for each record, each one a
 * separate service call and therefore its own transaction. A failure on one record does not roll back the
 * others: the response says for each record whether it succeeded and why not. Permissions, data scope and audit
 * stay with the single-record service operation, so the bulk action bypasses none of its checks.
 */
public final class BulkRunner {

    public static final int MAX_IDS = 100;
    public static final String BULK_INVALID = "BULK_INVALID";
    public static final String BULK_ACTION_UNKNOWN = "BULK_ACTION_UNKNOWN";
    public static final String BULK_ITEM_FAILED = "BULK_ITEM_FAILED";

    private static final Logger log = LoggerFactory.getLogger(BulkRunner.class);

    private BulkRunner() {}

    /** The request body: the action, the records and the action's parameters. */
    public record BulkRequest(String action, List<Long> ids, JsonNode params) {}

    /**
     * The outcome for one record; {@code code}, {@code message}, {@code messageKey} and {@code params} are set only
     * on failure. {@code message} is the text in the request language (the response handler builds it from
     * {@code messageKey}); the client may also build it itself from the key in its own catalog.
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
     * The validated record list: one to {@link #MAX_IDS} ids, no blanks, duplicates removed with order preserved.
     */
    public static List<Long> checkedIds(BulkRequest request) {
        List<Long> ids = request == null ? null : request.ids();
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(id -> id == null || id < 1)) {
            throw ApiException.validation(
                    "error.common.bulk_ids_invalid",
                    Map.of("max", MAX_IDS),
                    List.of(FieldErrorItem.keyed(
                            "ids", BULK_INVALID, "error.common.bulk_ids_invalid", Map.of("max", MAX_IDS))));
        }
        return List.copyOf(new LinkedHashSet<>(ids));
    }

    public static ApiException unknownAction(@Nullable String action) {
        String name = action == null ? "" : action;
        return ApiException.validation(
                "error.common.bulk_action_unknown",
                Map.of("action", name),
                List.of(FieldErrorItem.keyed(
                        "action", BULK_ACTION_UNKNOWN, "error.common.bulk_action_unknown", Map.of("action", name))));
    }

    /** A parameter of the action is wrong; {@code messageKey} and {@code params} say why, for the field error. */
    public static ApiException invalidParam(String name, String messageKey, Map<String, ?> params) {
        return ApiException.validation(
                "error.common.bulk_param_invalid",
                Map.of("name", name),
                List.of(FieldErrorItem.keyed("params." + name, BULK_INVALID, messageKey, params)));
    }

    /** Runs the operation for each record and collects the outcome; expected refusals carry the single-op code. */
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
     * is the key itself. A sentence instead of a key (a defect, ADR-0021) is logged and replaced by the code's text.
     */
    private static BulkItemResult failure(long id, ApiException e) {
        String code = e.getErrorCode().name().toLowerCase(Locale.ROOT);
        if (!e.hasMessageKey()) {
            log.error("ApiException without a catalog key in bulk item {}: {}", id, e.getMessageKey());
            String key = ApiException.defaultKey(e.getErrorCode());
            return new BulkItemResult(id, false, code, key, key, null);
        }
        return new BulkItemResult(id, false, code, e.getMessageKey(), e.getMessageKey(), e.getParams());
    }
}
