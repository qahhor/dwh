package com.greenwhite.dwh.instance.ms.notify.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.messenger.MessengerMessage;
import com.greenwhite.dwh.spi.messenger.MessengerProvider;
import com.greenwhite.dwh.spi.messenger.MessengerSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-NOTIF-4: доставка через Telegram Bot API.
 *
 * Бин создаётся только когда {@code dwh.telegram.bot-token} задан непустым: без токена
 * отправлять некуда, и активным останется {@link ConsoleMessengerProvider}.
 *
 * Токен — секрет: он не попадает ни в журнал, ни в сообщение об ошибке
 * (в URL Telegram он неизбежен, поэтому URL наружу тоже не отдаём).
 */
@Component
@ConditionalOnExpression("'${dwh.telegram.bot-token:}'.trim().length() > 0")
public class TelegramBotMessengerProvider implements MessengerProvider {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotMessengerProvider.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String apiBase;

    public TelegramBotMessengerProvider(@Value("${dwh.telegram.bot-token}") String botToken,
                                        @Value("${dwh.telegram.api-url:https://api.telegram.org}") String apiUrl) {
        this.apiBase = apiUrl + "/bot" + botToken;
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory();
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
            body.put("reply_markup", Map.of("inline_keyboard",
                    List.of(List.of(Map.of("text", message.inlineButtonText(), "url", message.inlineButtonUrl())))));
        }

        try {
            Map<?, ?> response = restClient.post()
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
            log.warn("Telegram: сообщение не принято API: {}", description);
            return MessengerSendResult.failure("telegram_rejected", description, elapsedMs(startedAt));

        } catch (Exception ex) {
            // chat_id — идентификатор получателя, в журнал не пишем (логи без ПДн).
            log.warn("Telegram: отправка не удалась: {}", ex.getMessage());
            return MessengerSendResult.failure("telegram_send_failed", ex.getMessage(), elapsedMs(startedAt));
        }
    }

    @Override
    public ProviderHealth checkHealth() {
        long startedAt = System.nanoTime();
        try {
            Map<?, ?> response = restClient.get()
                    .uri(apiBase + "/getMe")
                    .retrieve()
                    .body(Map.class);

            if (response != null && Boolean.TRUE.equals(response.get("ok"))) {
                return ProviderHealth.healthy(getProviderCode(), elapsedMs(startedAt));
            }
            return ProviderHealth.unhealthy(getProviderCode(), "Telegram API отклонил getMe", elapsedMs(startedAt));
        } catch (Exception ex) {
            return ProviderHealth.unhealthy(getProviderCode(),
                    "Telegram API недоступен: " + ex.getMessage(), elapsedMs(startedAt));
        }
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
