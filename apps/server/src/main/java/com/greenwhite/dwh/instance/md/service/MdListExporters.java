package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.error.FieldErrorItem;
import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.query.QueryListExporter;
import com.greenwhite.dwh.instance.common.security.SecurityContext;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository.LegacyUserFilters;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The user list as an export (ADR-0018): the viewer's data scope and the screen's flat filters (state, role,
 * manager, 2FA), as on the screen.
 */
@Configuration
public class MdListExporters {

    @Bean
    public QueryListExporter iamUsersExporter(MdUserListService users) {
        return new QueryListExporter() {
            public String code() { return MdUserQuery.LIST.code(); }

            public Set<String> options() { return Set.of("state", "role_id", "manager_id", "is_2fa_enabled"); }

            public List<FieldErrorItem> checkOptions(Map<String, String> options) {
                List<FieldErrorItem> errors = new ArrayList<>();
                for (String key : List.of("role_id", "manager_id")) {
                    if (!isNumber(options.get(key))) {
                        errors.add(new FieldErrorItem(key, "EXPORT_INVALID", "not a number: " + options.get(key)));
                    }
                }
                String state = options.get("state");
                if (state != null && !state.isBlank() && !List.of("A", "P").contains(state.strip())) {
                    errors.add(new FieldErrorItem("state", "EXPORT_INVALID", "state is A or P"));
                }
                String twoFactor = options.get("is_2fa_enabled");
                if (twoFactor != null && !twoFactor.isBlank() && !List.of("true", "false").contains(twoFactor.strip())) {
                    errors.add(new FieldErrorItem("is_2fa_enabled", "EXPORT_INVALID", "true or false"));
                }
                return errors;
            }

            public KeysetPage<?> page(int limit, String cursor, String filter, String sort, String search,
                                      Map<String, String> options) {
                return users.pageViews(SecurityContext.getCurrentUserId(), limit, cursor, filter, sort, search,
                        new LegacyUserFilters(options.get("state"), number(options.get("role_id")),
                                number(options.get("manager_id")), flag(options.get("is_2fa_enabled"))));
            }
        };
    }

    private static boolean isNumber(String value) {
        return value == null || value.isBlank() || value.strip().matches("\\d{1,18}");
    }

    /** Checked by {@code checkOptions} before the job starts. */
    private static Long number(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value.strip());
    }

    private static Boolean flag(String value) {
        return value == null || value.isBlank() ? null : Boolean.valueOf(value.strip());
    }
}
