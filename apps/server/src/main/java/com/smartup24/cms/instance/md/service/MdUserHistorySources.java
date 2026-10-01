package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.history.RecordHistorySource;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.pref.MdPref;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The master-data module's records with a history tab (ADR-0017): users. */
@Configuration
public class MdUserHistorySources {

    @Bean
    RecordHistorySource userHistorySource(MdUserService userService) {
        return new RecordHistorySource() {
            public String key() {
                return "users";
            }

            public String tableName() {
                return "md_users";
            }

            public String form() {
                return MdPref.FORM_USERS;
            }

            public String action() {
                return "view";
            }

            /** The same data scope as opening the user's card (ADR-0013). */
            public void requireVisible(String recordId) {
                try {
                    userService.requireVisible(SecurityContext.getCurrentUserId(), Long.valueOf(recordId));
                } catch (NumberFormatException e) {
                    throw new ApiException(ErrorCode.USER_NOT_FOUND);
                }
            }

            public Map<String, String> fieldLabels() {
                return Map.of(
                        "name", "iam.common.full_name",
                        "login", "analytics.login",
                        "email", "iam.email",
                        "phone", "iam.common.phone",
                        "language", "iam.common.language",
                        "timezone", "iam.common.time_zone",
                        "state", "common.status",
                        "forcePasswordChange", "iam.common.force_password_change",
                        "is2faEnabled", "iam.common.two_factor_status");
            }
        };
    }
}
