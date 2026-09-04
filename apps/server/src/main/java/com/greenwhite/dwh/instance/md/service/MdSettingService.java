package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.md.repository.MdSettingRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class MdSettingService {

    public static final Map<String, String> DEFAULT_INSTANCE_SETTINGS = Map.of(
            "system.company_name", "Smartup DWH Platform",
            "system.default_language", "ru",
            "system.default_timezone", "Asia/Tashkent",
            "system.date_format", "dd.MM.yyyy HH:mm",
            "security.min_password_length", "10",
            "security.require_2fa", "false",
            "security.session_lifetime_hours", "720",
            "storage.default_user_quota_mb", "1024",
            "ui.theme", "dark"
    );

    private final MdSettingRepository settingRepository;
    private final MdUserRepository userRepository;
    private final MdI18nService i18nService;
    private final AuditLogService auditLogService;

    public MdSettingService(MdSettingRepository settingRepository,
                            MdUserRepository userRepository,
                            MdI18nService i18nService,
                            AuditLogService auditLogService) {
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
        this.i18nService = i18nService;
        this.auditLogService = auditLogService;
    }

    @Transactional(readOnly = true)
    public Map<String, String> getInstanceSettings() {
        Map<String, String> result = new HashMap<>(DEFAULT_INSTANCE_SETTINGS);
        result.putAll(settingRepository.getAllInstanceSettings());
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, String> getUserSettings(Long userId) {
        if (userId == null) {
            return Map.of();
        }
        return settingRepository.getAllUserSettings(userId);
    }

    @Transactional(readOnly = true)
    public Map<String, String> getEffectiveSettings(Long userId) {
        Map<String, String> effective = new HashMap<>(DEFAULT_INSTANCE_SETTINGS);
        effective.putAll(settingRepository.getAllInstanceSettings());
        if (userId != null) {
            effective.putAll(settingRepository.getAllUserSettings(userId));
        }
        return effective;
    }

    @Transactional
    public void updateInstanceSettings(Map<String, String> settings) {
        if (settings != null && !settings.isEmpty()) {
            Map<String, String> existing = getInstanceSettings();
            settings.forEach((k, v) -> {
                settingRepository.setInstanceSetting(k, v);
                String oldVal = existing.get(k);
                if (oldVal == null || !oldVal.equals(v)) {
                    auditLogService.logChange("md_settings", k, "U",
                            List.of("value"),
                            Map.of("key", k, "value", oldVal != null ? oldVal : ""),
                            Map.of("key", k, "value", v != null ? v : ""));
                }
            });
        }
    }

    @Transactional
    public void updateUserSettings(Long userId, Map<String, String> settings) {
        if (userId != null && settings != null) {
            Map<String, String> normalized = new HashMap<>(settings);
            String language = null;
            if (normalized.containsKey("user.language")) {
                language = i18nService.requireActiveLanguageCode(normalized.get("user.language"));
                normalized.put("user.language", language);
            }

            normalized.forEach((k, v) -> settingRepository.setUserSetting(userId, k, v));
            if (language != null) {
                // /auth/me reads md_users.language. Keep it synchronized in this transaction
                // so the selected language survives refreshes and new sessions.
                userRepository.updateLanguage(userId, language, userId);
            }
        }
    }

    @Transactional
    public void updateUserSetting(Long userId, String key, String value) {
        if (userId != null && key != null) {
            updateUserSettings(userId, Map.of(key, value));
        }
    }
}

