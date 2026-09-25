package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.md.repository.MdSettingRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.instance.common.error.ApiException;

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
            "security.idle_lock_minutes", "30",
            "storage.default_user_quota_mb", "1024",
            "ui.theme", "dark"
    );

    public static final String IDLE_LOCK_MINUTES = "security.idle_lock_minutes";
    /** A day at most; 0 switches the lock off. */
    static final int MAX_IDLE_LOCK_MINUTES = 1440;
    public static final String SETTING_INVALID = "SETTING_INVALID";
    public static final String SETTING_NOT_PERSONAL = "SETTING_NOT_PERSONAL";
    /** A person's own settings; everything else (security, system, storage) is the instance's. */
    private static final List<String> PERSONAL_PREFIXES = List.of("user.", "ui.");

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
        // Only personal keys: a stale foreign one would come back in the next save and be rejected.
        Map<String, String> personal = new HashMap<>();
        settingRepository.getAllUserSettings(userId).forEach((key, value) -> {
            if (isPersonal(key)) personal.put(key, value);
        });
        return personal;
    }

    @Transactional(readOnly = true)
    public Map<String, String> getEffectiveSettings(Long userId) {
        Map<String, String> effective = new HashMap<>(DEFAULT_INSTANCE_SETTINGS);
        effective.putAll(settingRepository.getAllInstanceSettings());
        if (userId != null) {
            // A stored personal value never shadows an instance setting such as the security ones.
            settingRepository.getAllUserSettings(userId).forEach((key, value) -> {
                if (isPersonal(key)) effective.put(key, value);
            });
        }
        return effective;
    }

    @Transactional
    public void updateInstanceSettings(Map<String, String> settings) {
        if (settings != null && settings.containsKey(IDLE_LOCK_MINUTES)) {
            parseIdleLock(settings.get(IDLE_LOCK_MINUTES), true);
        }
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
            List<FieldErrorItem> foreign = settings.keySet().stream().filter(key -> !isPersonal(key))
                    .map(key -> new FieldErrorItem(key, SETTING_NOT_PERSONAL, "not a personal setting: " + key)).toList();
            if (!foreign.isEmpty()) {
                throw ApiException.validation(SETTING_NOT_PERSONAL, foreign);
            }
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

    /**
     * Minutes of inactivity after which a session is closed (roadmap item 28); 0 — never. Read from the
     * instance only: nobody can switch their own lock off.
     */
    @Transactional(readOnly = true)
    public int idleLockMinutes() {
        return parseIdleLock(getInstanceSettings().get(IDLE_LOCK_MINUTES), false);
    }

    private static int parseIdleLock(String value, boolean strict) {
        try {
            int minutes = Integer.parseInt(value == null ? "" : value.strip());
            if (minutes >= 0 && minutes <= MAX_IDLE_LOCK_MINUTES) return minutes;
        } catch (NumberFormatException ignored) {
            // falls through
        }
        if (strict) {
            throw ApiException.validation(SETTING_INVALID, List.of(new FieldErrorItem(IDLE_LOCK_MINUTES, SETTING_INVALID,
                    "minutes from 0 to " + MAX_IDLE_LOCK_MINUTES)));
        }
        return Integer.parseInt(DEFAULT_INSTANCE_SETTINGS.get(IDLE_LOCK_MINUTES));
    }

    private static boolean isPersonal(String key) {
        return key != null && PERSONAL_PREFIXES.stream().anyMatch(key::startsWith);
    }

    @Transactional
    public void updateUserSetting(Long userId, String key, String value) {
        if (userId != null && key != null) {
            updateUserSettings(userId, Map.of(key, value));
        }
    }
}

