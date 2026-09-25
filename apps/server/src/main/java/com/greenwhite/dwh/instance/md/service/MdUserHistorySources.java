package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.common.history.RecordHistorySource;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/** The master-data module's records with a history tab (ADR-0017): users. */
@Configuration
public class MdUserHistorySources {

    @Bean
    RecordHistorySource userHistorySource(MdUserService userService) {
        return new RecordHistorySource() {
            public String key() { return "users"; }
            public String tableName() { return "md_users"; }
            public String form() { return MdPref.FORM_USERS; }
            public String action() { return "view"; }

            public void requireVisible(String recordId) {
                try {
                    userService.getUserById(Long.valueOf(recordId));
                } catch (NumberFormatException e) {
                    throw ApiException.notFound(ErrorCode.USER_NOT_FOUND, "Пользователь не найден");
                }
            }

            public Map<String, String> fieldLabels() {
                return Map.of(
                        "name", "iam.fio",
                        "login", "analytics.login",
                        "email", "iam.email",
                        "phone", "iam.telefon.822f9fd",
                        "language", "iam.yazyk",
                        "timezone", "iam.chasovoy_poyas",
                        "state", "common.status",
                        "forcePasswordChange", "iam.trebovanie_smeny_parolya",
                        "is2faEnabled", "iam.status_2fa");
            }
        };
    }
}
