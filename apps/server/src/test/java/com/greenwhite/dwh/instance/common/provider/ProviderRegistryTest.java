package com.greenwhite.dwh.instance.common.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.mail.MailMessage;
import com.greenwhite.dwh.spi.mail.MailProvider;
import com.greenwhite.dwh.spi.mail.MailSendResult;
import com.greenwhite.dwh.spi.messenger.MessengerMessage;
import com.greenwhite.dwh.spi.messenger.MessengerProvider;
import com.greenwhite.dwh.spi.messenger.MessengerSendResult;
import com.greenwhite.dwh.spi.sms.SmsMessage;
import com.greenwhite.dwh.spi.sms.SmsProvider;
import com.greenwhite.dwh.spi.sms.SmsSendResult;
import com.greenwhite.dwh.spi.storage.FileDownloadStream;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import com.greenwhite.dwh.spi.storage.StoredFileMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRegistryTest {

    private final StorageProvider mockStorage = new DummyStorageProvider("local");
    private final MailProvider mockMail = new DummyMailProvider("console_mail");
    private final SmsProvider mockSms = new DummySmsProvider("console_sms");
    private final MessengerProvider mockMessenger = new DummyMessengerProvider("telegram");

    @Test
    @DisplayName("Реестр провайдеров должен возвращать активные провайдеры по кодам конфигурации")
    void shouldResolveActiveProviders() {
        ProviderRegistry registry = new ProviderRegistry(
                List.of(mockStorage),
                List.of(mockMail),
                List.of(mockSms),
                List.of(mockMessenger),
                "local", "console_mail", "console_sms", "telegram"
        );

        assertThat(registry.getActiveStorageProvider().getProviderCode()).isEqualTo("local");
        assertThat(registry.getActiveMailProvider().getProviderCode()).isEqualTo("console_mail");
        assertThat(registry.getActiveSmsProvider().getProviderCode()).isEqualTo("console_sms");
        assertThat(registry.getActiveMessengerProvider().getProviderCode()).isEqualTo("telegram");
    }

    @Test
    @DisplayName("Реестр должен агрегировать здоровье всех зарегистрированных SPI провайдеров")
    void shouldAggregateProviderHealth() {
        ProviderRegistry registry = new ProviderRegistry(
                List.of(mockStorage),
                List.of(mockMail),
                List.of(mockSms),
                List.of(mockMessenger),
                "local", "console_mail", "console_sms", "telegram"
        );

        Map<String, ProviderHealth> healthMap = registry.checkAllHealth();

        assertThat(healthMap).containsKeys("storage:local", "mail:console_mail", "sms:console_sms", "messenger:telegram");
        assertThat(healthMap.get("storage:local").isHealthy()).isTrue();
    }

    static class DummyStorageProvider implements StorageProvider {
        private final String code;
        DummyStorageProvider(String code) { this.code = code; }
        @Override public String getProviderCode() { return code; }
        @Override public StoredFileMetadata upload(String bucket, String key, InputStream contentStream, long sizeBytes, String contentType) { return null; }
        @Override public FileDownloadStream download(String bucket, String key) { return null; }
        @Override public void delete(String bucket, String key) {}
        @Override public boolean exists(String bucket, String key) { return true; }
        @Override public ProviderHealth checkHealth() { return ProviderHealth.healthy(code, 1); }
    }

    static class DummyMailProvider implements MailProvider {
        private final String code;
        DummyMailProvider(String code) { this.code = code; }
        @Override public String getProviderCode() { return code; }
        @Override public MailSendResult send(MailMessage message) { return MailSendResult.success("id-1", 1); }
        @Override public ProviderHealth checkHealth() { return ProviderHealth.healthy(code, 1); }
    }

    static class DummySmsProvider implements SmsProvider {
        private final String code;
        DummySmsProvider(String code) { this.code = code; }
        @Override public String getProviderCode() { return code; }
        @Override public SmsSendResult send(SmsMessage message) { return SmsSendResult.success("sms-1", 1); }
        @Override public ProviderHealth checkHealth() { return ProviderHealth.healthy(code, 1); }
    }

    static class DummyMessengerProvider implements MessengerProvider {
        private final String code;
        DummyMessengerProvider(String code) { this.code = code; }
        @Override public String getProviderCode() { return code; }
        @Override public MessengerSendResult send(MessengerMessage message) { return MessengerSendResult.success("msg-1", 1); }
        @Override public ProviderHealth checkHealth() { return ProviderHealth.healthy(code, 1); }
    }
}
