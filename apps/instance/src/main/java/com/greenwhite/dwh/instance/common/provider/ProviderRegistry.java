package com.greenwhite.dwh.instance.common.provider;

import com.greenwhite.dwh.spi.common.ProviderHealth;
import com.greenwhite.dwh.spi.mail.MailProvider;
import com.greenwhite.dwh.spi.messenger.MessengerProvider;
import com.greenwhite.dwh.spi.sms.SmsProvider;
import com.greenwhite.dwh.spi.storage.StorageProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service Provider Interface (SPI) Registry (ADR-0004, TRD-03).
 * Manages registered SPI implementations (Storage, Mail, SMS, Messenger)
 * and exposes active provider resolution and unified health aggregation.
 */
@Component
public class ProviderRegistry {

    private final Map<String, StorageProvider> storageProviders;
    private final Map<String, MailProvider> mailProviders;
    private final Map<String, SmsProvider> smsProviders;
    private final Map<String, MessengerProvider> messengerProviders;

    private final String activeStorageCode;
    private final String activeMailCode;
    private final String activeSmsCode;
    private final String activeMessengerCode;

    public ProviderRegistry(
            List<StorageProvider> storageList,
            List<MailProvider> mailList,
            List<SmsProvider> smsList,
            List<MessengerProvider> messengerList,
            @Value("${dwh.providers.storage:local}") String activeStorageCode,
            @Value("${dwh.providers.mail:console_mail}") String activeMailCode,
            @Value("${dwh.providers.sms:console_sms}") String activeSmsCode,
            @Value("${dwh.providers.messenger:telegram}") String activeMessengerCode) {

        this.storageProviders = storageList.stream()
                .collect(Collectors.toMap(StorageProvider::getProviderCode, Function.identity(), (a, b) -> a));
        this.mailProviders = mailList.stream()
                .collect(Collectors.toMap(MailProvider::getProviderCode, Function.identity(), (a, b) -> a));
        this.smsProviders = smsList.stream()
                .collect(Collectors.toMap(SmsProvider::getProviderCode, Function.identity(), (a, b) -> a));
        this.messengerProviders = messengerList.stream()
                .collect(Collectors.toMap(MessengerProvider::getProviderCode, Function.identity(), (a, b) -> a));

        this.activeStorageCode = activeStorageCode;
        this.activeMailCode = activeMailCode;
        this.activeSmsCode = activeSmsCode;
        this.activeMessengerCode = activeMessengerCode;
    }

    public StorageProvider getActiveStorageProvider() {
        StorageProvider provider = storageProviders.get(activeStorageCode);
        if (provider != null) {
            return provider;
        }
        return storageProviders.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No StorageProvider registered in application context"));
    }

    public MailProvider getActiveMailProvider() {
        MailProvider provider = mailProviders.get(activeMailCode);
        if (provider != null) {
            return provider;
        }
        return mailProviders.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No MailProvider registered in application context"));
    }

    public SmsProvider getActiveSmsProvider() {
        SmsProvider provider = smsProviders.get(activeSmsCode);
        if (provider != null) {
            return provider;
        }
        return smsProviders.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No SmsProvider registered in application context"));
    }

    public MessengerProvider getActiveMessengerProvider() {
        MessengerProvider provider = messengerProviders.get(activeMessengerCode);
        if (provider != null) {
            return provider;
        }
        return messengerProviders.values().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No MessengerProvider registered in application context"));
    }

    public Optional<StorageProvider> getStorageProvider(String code) {
        return Optional.ofNullable(storageProviders.get(code));
    }

    public Optional<MailProvider> getMailProvider(String code) {
        return Optional.ofNullable(mailProviders.get(code));
    }

    public Optional<SmsProvider> getSmsProvider(String code) {
        return Optional.ofNullable(smsProviders.get(code));
    }

    public Optional<MessengerProvider> getMessengerProvider(String code) {
        return Optional.ofNullable(messengerProviders.get(code));
    }

    /**
     * Aggregates health status of all registered SPI providers.
     */
    public Map<String, ProviderHealth> checkAllHealth() {
        Map<String, ProviderHealth> healthMap = new HashMap<>();

        storageProviders.values().forEach(p -> healthMap.put("storage:" + p.getProviderCode(), p.checkHealth()));
        mailProviders.values().forEach(p -> healthMap.put("mail:" + p.getProviderCode(), p.checkHealth()));
        smsProviders.values().forEach(p -> healthMap.put("sms:" + p.getProviderCode(), p.checkHealth()));
        messengerProviders.values().forEach(p -> healthMap.put("messenger:" + p.getProviderCode(), p.checkHealth()));

        return healthMap;
    }
}
