package com.smartup24.cms.instance.ms.notify.provider;

import com.smartup24.cms.spi.common.ProviderHealth;
import com.smartup24.cms.spi.messenger.MessengerMessage;
import com.smartup24.cms.spi.messenger.MessengerProvider;
import com.smartup24.cms.spi.messenger.MessengerSendResult;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * FR-NOTIF-4: delivery through the Telegram Bot API.
 *
 * The bean is created only when {@code smc.telegram.bot-token} is set and not blank: without
 * a token there is nowhere to send, and {@link ConsoleMessengerProvider} stays active.
 *
 * The token is a secret: it gets neither into the log nor into an error message
 * (it is unavoidable in the Telegram URL, so the URL is not exposed either).
 */
@Component
@ConditionalOnExpression("'${smc.telegram.bot-token:}'.trim().length() > 0")
public class TelegramBotMessengerProvider implements MessengerProvider {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotMessengerProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String apiBase;

    public TelegramBotMessengerProvider(
            @Value("${smc.telegram.bot-token}") String botToken,
            @Value("${smc.telegram.api-url:https://api.telegram.org}") String apiUrl) {
        this.apiBase = apiUrl + "/bot" + botToken;
        var factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public String getProviderCode() {
        return "telegram";
    }

    @Override
    public MessengerSendResult send(MessengerMessage message) {
        long startedAt = System.nanoTime();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", message.recipientChatId());
        body.put("text", message.textMarkdown());
        body.put("parse_mode", "Markdown");

        if (message.inlineButtonText() != null && message.inlineButtonUrl() != null) {
            body.put(
                    "reply_markup",
                    Map.of(
                            "inline_keyboard",
                            List.of(List.of(
                                    Map.of("text", message.inlineButtonText(), "url", message.inlineButtonUrl())))));
        }

        try {
            Map<?, ?> response = restClient
                    .post()
                    .uri(apiBase + "/sendMessage")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                String messageId = null;
                if (response.get("result") instanceof Map<?, ?> result && result.get("message_id") != null) {
                    messageId = String.valueOf(result.get("message_id"));
                }
                return MessengerSendResult.success(
                        messageId != null ? messageId : message.idempotencyKey(), elapsedMs(startedAt));
            }

            String description = response != null ? String.valueOf(response.get("description")) : "empty response";
            log.warn("telegram_message_rejected description={}", description);
            return MessengerSendResult.failure("telegram_rejected", description, elapsedMs(startedAt));

        } catch (Exception ex) {
            // chat_id identifies the recipient and is not logged (no personal data in logs).
            log.warn("telegram_send_failed error={}", ex.getMessage());
            return MessengerSendResult.failure("telegram_send_failed", ex.getMessage(), elapsedMs(startedAt));
        }
    }

    @Override
    public ProviderHealth checkHealth() {
        long startedAt = System.nanoTime();
        try {
            Map<?, ?> response =
                    restClient.get().uri(apiBase + "/getMe").retrieve().body(Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                return ProviderHealth.healthy(getProviderCode(), elapsedMs(startedAt));
            }
            return ProviderHealth.unhealthy(getProviderCode(), "Telegram API refused getMe", elapsedMs(startedAt));
        } catch (Exception ex) {
            return ProviderHealth.unhealthy(
                    getProviderCode(), "Telegram API is unavailable: " + ex.getMessage(), elapsedMs(startedAt));
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
