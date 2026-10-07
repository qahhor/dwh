package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.support.entity.KitWorld.ifMatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.entity.KitWorld.Created;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import com.smartup24.cms.platform.api.entity.EntityCapability;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.field.EntityField;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The events of the entity's changes (ADR-0032, 6.9 and 11.2, "events"): with a subscription to them, a create and an
 * update each write one row of {@code <form>.created} and {@code <form>.updated} to the webhook outbox, in the
 * transaction of the change — the envelope names the record and its revision, and its data holds no field that needs a
 * right — and a refused change writes none. Only the general runtime publishes them: no module code does.
 */
final class KitEventChecks {

    private final KitWorld world;

    KitEventChecks(KitWorld world) {
        this.world = world;
    }

    List<DynamicTest> events() {
        if (!world.transport.strictBody()) return List.of();
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest(
                "a create and an update each write one event to the webhook outbox, without restricted fields",
                this::changesAreEvents));
        if (world.declares("update")) {
            tests.add(dynamicTest("a refused change writes no event", this::aRefusedChangeIsNoEvent));
        }
        tests.add(dynamicTest(
                "the webhook events catalog names the events of every declared action", this::catalogNamesTheEvents));
        return tests;
    }

    /** A subscription may name only what the catalog holds (422 otherwise): every event the runtime publishes. */
    private void catalogNamesTheEvents() {
        String form = world.entity.form();
        List<String> expected = new ArrayList<>();
        if (world.declares("create")) expected.add(form + ".created");
        if (world.declares("update")) expected.add(form + ".updated");
        if (world.declares(EntityDefinition.DELETE)) expected.add(form + ".deleted");
        if (world.has(EntityCapability.ARCHIVE)) {
            expected.add(form + ".archived");
            expected.add(form + ".restored");
        }
        world.ownActions().forEach(action -> expected.add(form + "." + action));
        var catalog = world.wac.getBean(WebhookService.class).knownEventCodes();
        assertThat(catalog).containsAll(expected);
    }

    private void changesAreEvents() throws Exception {
        String form = world.entity.form();
        long subscription = subscribe(List.of(form + ".created", form + ".updated"));
        try {
            Created record = world.create(world.owner);
            assertThat(rows(subscription, form + ".created", record.id()))
                    .as("created")
                    .hasSize(1);
            if (world.declares("update")) {
                MockHttpServletResponse updated =
                        world.update(world.owner, record.id(), world.updateValues(), ifMatch(record.revision()));
                assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
                List<Map<String, Object>> events = rows(subscription, form + ".updated", record.id());
                assertThat(events).as("updated").hasSize(1);
                Map<String, Object> event = events.getFirst();
                assertThat(event)
                        .containsEntry("entity", world.entity.code())
                        .containsEntry("type", form + ".updated")
                        .containsEntry("revision", (int) record.revision() + 1);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) event.get("data");
                assertThat(data).as("the data of a webhook").containsKey("id");
                for (EntityField field : world.model.fields()) {
                    if (field.access().restricted()) {
                        assertThat(data).as("the data of a webhook").doesNotContainKey(field.key());
                    }
                }
            }
        } finally {
            unsubscribe(subscription);
        }
    }

    private void aRefusedChangeIsNoEvent() throws Exception {
        String form = world.entity.form();
        long subscription = subscribe(List.of(form + ".updated"));
        try {
            Created record = world.create(world.owner);
            Map<String, Object> change = world.updateValues();
            change.put("kitUnknown", "x");
            MockHttpServletResponse refused =
                    world.update(world.owner, record.id(), change, ifMatch(record.revision()));
            assertThat(refused.getStatus()).as(refused.getContentAsString()).isEqualTo(422);
            assertThat(rows(subscription, form + ".updated", record.id())).isEmpty();
        } finally {
            unsubscribe(subscription);
        }
    }

    private long subscribe(List<String> events) {
        long creator = world.jdbc
                .sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        return world.wac
                .getBean(WebhookSubscriptionRepository.class)
                .create("kit " + world.token(), "https://hooks.example.test/kit", "kit-secret", events, creator)
                .id();
    }

    private void unsubscribe(long subscription) {
        world.jdbc
                .sql("delete from kwh_subscriptions where id = :id")
                .param("id", subscription)
                .update();
    }

    /** The envelopes of one subscription's events of this record. */
    private List<Map<String, Object>> rows(long subscription, String type, long recordId) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String payload : world.jdbc
                .sql("""
                        select payload::text from kwh_outbox
                        where subscription_id = :subscription and event_type = :type
                        order by id
                        """)
                .param("subscription", subscription)
                .param("type", type)
                .query(String.class)
                .list()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> envelope = TestSession.JSON.readValue(payload, Map.class);
            if (((Number) envelope.get("recordId")).longValue() == recordId) rows.add(envelope);
        }
        return rows;
    }
}
