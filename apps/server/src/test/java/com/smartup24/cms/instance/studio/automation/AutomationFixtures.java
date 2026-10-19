package com.smartup24.cms.instance.studio.automation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.common.entity.runtime.EntityRecordView;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.studio.repository.StudioAutomationRepository.AutomationRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/** The form, rows and collaborators the unit tests of automations share (ADR-0046). */
final class AutomationFixtures {
    static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    static final String FORM = "studio.orders";
    static final AutomationJson JSON = new AutomationJson(JsonMapper.shared());

    private static final String LABELS = "{\"ru\": \"Т\", \"uz\": \"T\", \"en\": \"T\"}";
    private static final String DECLARATION = """
            {"formatVersion": 6, "kind": "simple-form",
             "module": {"code": "studio", "title": %1$s},
             "entity": {"name": "orders", "title": {"ru": "Заказы", "uz": "Buyurtmalar", "en": "Orders"}},
             "fields": [
               {"id": "note", "type": "text", "labels": %1$s, "required": false, "listed": true, "maxLength": 50},
               {"id": "manager", "type": "user", "labels": %1$s, "required": false, "listed": true},
               {"id": "review", "type": "bool", "labels": %1$s, "required": false, "listed": true}
             ],
             "workflow": {
               "states": [
                 {"code": "draft", "labels": %1$s, "color": "gray"},
                 {"code": "pending", "labels": %1$s, "color": "blue"}
               ],
               "transitions": [
                 {"code": "submit", "labels": %1$s, "from": ["draft"], "to": "pending", "comment": "optional"}
               ]}}
            """.formatted(LABELS);

    private AutomationFixtures() {}

    static AutomationForm form(boolean archived) {
        return new AutomationForm(FORM, JsonMapper.shared().readValue(DECLARATION, Declaration.class), archived);
    }

    static AutomationRow row(long id, String kind, String trigger, boolean active) {
        return new AutomationRow(
                id, FORM, "Проба", kind, trigger, null, "[]", "UTC", active, null, 1, "Автор", 1, NOW, NOW);
    }

    static EntityRecordView view(long id, Map<String, @Nullable Object> values) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("id", id);
        record.put("revision", 2);
        values.forEach((key, value) -> {
            if (value != null) record.put(key, value);
        });
        return new EntityRecordView(record, List.of());
    }

    static AutomationRun run(boolean dry, Map<String, @Nullable Object> values) {
        AutomationRun run = new AutomationRun(
                3, "Проба", 1, "run-1", form(false), 9, 1, dry, ZoneId.of("UTC"), NOW, Map.of(), Set.of());
        run.read(view(9, values));
        return run;
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<Clock> clock() {
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any(Supplier.class))).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        return clock;
    }

    @SuppressWarnings("unchecked")
    static AutomationMetrics metrics(@Nullable MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new AutomationMetrics(provider);
    }

    static PlatformTransactionManager transactions() {
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        return transactions;
    }
}
