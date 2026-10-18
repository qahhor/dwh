package com.smartup24.cms.instance.webhook.api;

import com.smartup24.cms.instance.common.web.Revisioned;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The requests and answers of the webhook subscriptions (ADR-0032, 6.9; ADR-0041, owner decisions of 2026-10-18): a
 * subscription names its target, its events and its scope — {@code creator}, the records its creator may read now
 * (the default), or {@code all}, every record of the entity.
 */
public final class WebhookDtos {
    private WebhookDtos() {}

    /** Every record of the entity is sent. */
    public static final String SCOPE_ALL = "all";

    /** Only a record its creator may read when the event is written: active, holding the view right, in scope. */
    public static final String SCOPE_CREATOR = "creator";

    /** The scopes a subscription takes. */
    public static final List<String> SCOPES = List.of(SCOPE_CREATOR, SCOPE_ALL);

    public record CreateSubscriptionDto(
            @NotBlank String name,
            @NotBlank String targetUrl,
            @NotEmpty List<String> subscribedEvents,

            @Schema(
                    description = "Which records are sent: creator (default) or all",
                    allowableValues = {SCOPE_CREATOR, SCOPE_ALL})
            @Nullable
            String scope) {}

    public record UpdateSubscriptionDto(
            @Nullable String name,
            @Nullable String targetUrl,
            @Nullable List<String> subscribedEvents,
            @Nullable String state,

            @Schema(
                    description = "Which records are sent: creator or all",
                    allowableValues = {SCOPE_CREATOR, SCOPE_ALL})
            @Nullable
            String scope) {}

    public record SubscriptionView(
            Long id,
            String name,
            String targetUrl,
            List<String> subscribedEvents,
            String state,

            @Schema(allowableValues = {SCOPE_CREATOR, SCOPE_ALL})
            String scope,

            Instant createdAt,
            Long createdBy,
            long revision)
            implements Revisioned {}

    public record CreatedSubscription(
            Long id,
            String name,
            String targetUrl,
            String secretToken,
            List<String> subscribedEvents,
            String state,

            @Schema(allowableValues = {SCOPE_CREATOR, SCOPE_ALL})
            String scope,

            Instant createdAt,
            Long createdBy) {}

    public record WebhookEventView(
            String code,
            String event,
            @Nullable String entity,
            @Nullable String form,
            @Nullable String action,
            @Nullable String nameKey,
            @Nullable String descKey) {
        public WebhookEventView(
                String code,
                @Nullable String entity,
                @Nullable String form,
                @Nullable String action,
                @Nullable String nameKey,
                @Nullable String descKey) {
            this(code, code, entity, form, action, nameKey, descKey);
        }
    }
}
