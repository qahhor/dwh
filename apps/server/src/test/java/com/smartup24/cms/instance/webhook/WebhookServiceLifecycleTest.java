package com.smartup24.cms.instance.webhook;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.sortable;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.example.service.ExampleRequestsEntity;
import com.smartup24.cms.instance.webhook.repository.WebhookOutboxRepository;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository;
import com.smartup24.cms.instance.webhook.repository.WebhookSubscriptionRepository.SubscriptionRecord;
import com.smartup24.cms.instance.webhook.service.WebhookProperties;
import com.smartup24.cms.instance.webhook.service.WebhookService;
import com.smartup24.cms.instance.webhook.service.WebhookService.WebhookEventView;
import com.smartup24.cms.instance.webhook.service.WebhookTargetPolicy;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The life of a subscription as the service sees it: every change is audited without the signing key, a missing
 * subscription is a 404, an event reaches the outbox once per active subscriber, and the catalog of events follows
 * the declared entities (ADR-0032, 6.9).
 */
class WebhookServiceLifecycleTest {

    private static final String TARGET = "https://93.184.216.34/events?token=private";

    private final WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
    private final WebhookOutboxRepository outbox = mock(WebhookOutboxRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final WebhookService service = new WebhookService(subscriptions, outbox, audit, policy());

    @Test
    @DisplayName("An update is audited with the old and new address redacted and returns the new revision")
    @SuppressWarnings("unchecked")
    void updateIsAuditedWithRedactedAddresses() {
        when(subscriptions.findById(7L)).thenReturn(Optional.of(record(7L, TARGET, "A")));
        when(subscriptions.update(7L, "Renamed", null, List.of("*"), "P", 3L)).thenReturn(4L);

        long revision = service.updateSubscription(7L, "Renamed", null, List.of("*"), "P", 3L);

        assertThat(revision).isEqualTo(4L);
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit)
                .logChange(eq("kwh_subscriptions"), eq("7"), eq("U"), anyList(), before.capture(), after.capture());
        assertThat(before.getValue())
                .containsEntry("name", "Orders")
                .containsEntry("target_url", "https://93.184.216.34/events")
                .containsEntry("state", "A");
        assertThat(after.getValue()).containsEntry("name", "Renamed").containsEntry("state", "P");
        assertThat(after.getValue().toString()).doesNotContain("private").doesNotContain("signing");
    }

    @Test
    @DisplayName("A stored address that no longer parses is audited as an invalid target, not as its text")
    @SuppressWarnings("unchecked")
    void unreadableStoredAddressIsRedacted() {
        when(subscriptions.findById(8L)).thenReturn(Optional.of(record(8L, "http://bad host/x", "A")));
        when(subscriptions.listSubscriptions()).thenReturn(List.of(record(8L, null, "A")));

        service.deleteSubscription(8L);

        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        verify(audit).logChange(eq("kwh_subscriptions"), eq("8"), eq("D"), anyList(), before.capture(), isNull());
        assertThat(before.getValue()).containsEntry("target_url", "invalid-webhook-target");
        verify(subscriptions).delete(8L);
        assertThat(service.listSubscriptions())
                .singleElement()
                .satisfies(view -> assertThat(view.targetUrl()).isEqualTo("invalid-webhook-target"));
    }

    @Test
    @DisplayName("Updating or deleting a missing subscription answers 404 and changes nothing")
    void missingSubscriptionIsNotFound() {
        when(subscriptions.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSubscription(9L, "x", null, null, null, 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.webhook.subscription_not_found");
        assertThatThrownBy(() -> service.deleteSubscription(9L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.webhook.subscription_not_found");
        verify(subscriptions, never()).delete(any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("An update with an unknown event is refused before the subscription is read")
    void updateWithUnknownEventIsRefused() {
        assertThatThrownBy(() -> service.updateSubscription(7L, null, null, List.of("nope.created"), null, 1L))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("messageKey", "error.webhook.event_unknown")
                .hasFieldOrPropertyWithValue("params", Map.of("event", "nope.created"));
        verifyNoInteractions(subscriptions);
    }

    @Test
    @DisplayName("Creation audits the redacted address and never the signing key")
    @SuppressWarnings("unchecked")
    void creationIsAuditedWithoutTheSecret() {
        when(subscriptions.create(eq("Orders"), eq(TARGET), any(), eq(List.of()), eq(1L)))
                .thenAnswer(call -> new SubscriptionRecord(
                        5L, "Orders", TARGET, call.getArgument(2), List.of(), "A", Instant.now(), 1L, 1L));

        var created = service.createSubscription("Orders", TARGET, List.of(), 1L);

        assertThat(created.secretToken()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(created.targetUrl()).isEqualTo("https://93.184.216.34/events");
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        verify(audit).logChange(eq("kwh_subscriptions"), eq("5"), eq("I"), anyList(), isNull(), after.capture());
        assertThat(after.getValue().toString())
                .doesNotContain(created.secretToken())
                .doesNotContain("private");
    }

    @Test
    @DisplayName("An event is written once per active subscriber; without one the payload is never built")
    void eventReachesEachActiveSubscriberOnce() {
        AtomicInteger built = new AtomicInteger();
        when(subscriptions.findActiveByEvent("tasks.created")).thenReturn(List.of());

        service.publishEvent("tasks.created", () -> {
            built.incrementAndGet();
            return Map.of("id", 1);
        });

        assertThat(built).hasValue(0);
        verifyNoInteractions(outbox);

        when(subscriptions.findActiveByEvent("tasks.created"))
                .thenReturn(List.of(record(1L, TARGET, "A"), record(2L, TARGET, "A")));
        service.publishEvent("tasks.created", Map.of("id", 2));

        verify(outbox).enqueue(1L, "tasks.created", Map.of("id", 2));
        verify(outbox).enqueue(2L, "tasks.created", Map.of("id", 2));
    }

    @Test
    @DisplayName("The catalog lists the standard events, archive events, record actions and workflow transitions")
    void catalogFollowsTheDeclaredEntities() {
        EntityDefinition hooks = Entity.define("test.hooks", "test.hooks")
                .table("t_hooks", "h")
                .scope(EntityScope.all())
                .field(text("title", "x.title").column("title").list(sortable()))
                .section("main", "entity.section.main", "title")
                .rights(
                        "test",
                        "test.hooks.rights.form",
                        Map.of(
                                "view",
                                "v",
                                "create",
                                "c",
                                "update",
                                "u",
                                "delete",
                                "d",
                                "archive",
                                "a",
                                "approve",
                                "test.hooks.rights.approve"))
                .actions("create", "update", "delete")
                .archivable()
                .action("approve", "approve")
                .defaultSort("title", Entity.Sort.ASC)
                .build();
        // The catalog reads only the declarations; the registry's own checks have tests of their own.
        EntityRegistry registry = mock(EntityRegistry.class);
        when(registry.all()).thenReturn(List.of(hooks, ExampleRequestsEntity.DEFINITION));
        var catalog = new WebhookService(subscriptions, outbox, audit, policy(), registry);

        List<WebhookEventView> events = catalog.listEvents();
        Set<String> codes = catalog.knownEventCodes();

        assertThat(events.getFirst().code()).isEqualTo("*");
        assertThat(codes)
                .contains(
                        "test.hooks.created",
                        "test.hooks.updated",
                        "test.hooks.deleted",
                        "test.hooks.archived",
                        "test.hooks.restored",
                        "test.hooks.approve",
                        ExampleRequestsEntity.DEFINITION.form() + ".submit",
                        ExampleRequestsEntity.DEFINITION.form() + ".approve")
                .doesNotContain("test.hooks.archive", "test.hooks.create");
        assertThat(events)
                .filteredOn(event -> event.code().equals("test.hooks.approve"))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.nameKey()).isEqualTo("test.hooks.rights.approve");
                    assertThat(event.entity()).isEqualTo("test.hooks");
                    assertThat(event.action()).isEqualTo("approve");
                    assertThat(event.event()).isEqualTo(event.code());
                });
    }

    @Test
    @DisplayName("The signature is a lowercase hex HMAC-SHA256 over timestamp and body")
    void signatureIsHexHmac() {
        assertThat(WebhookService.computeHmacSha256(1L, "{}", "k"))
                .matches("[0-9a-f]{64}")
                .isEqualTo(WebhookService.computeHmacSha256("1.{}", "k"))
                .isNotEqualTo(WebhookService.computeHmacSha256(2L, "{}", "k"));
    }

    private static SubscriptionRecord record(long id, String target, String state) {
        return new SubscriptionRecord(
                id, "Orders", target, "signing-secret", List.of("*"), state, Instant.now(), 1L, 3L);
    }

    private static WebhookTargetPolicy policy() {
        var properties = new WebhookProperties();
        properties.setEnabled(true);
        properties.setAllowedHosts(Set.of("93.184.216.34"));
        return new WebhookTargetPolicy(properties);
    }
}
