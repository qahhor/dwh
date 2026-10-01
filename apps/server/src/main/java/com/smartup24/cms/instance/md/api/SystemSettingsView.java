package com.smartup24.cms.instance.md.api;

import com.smartup24.cms.instance.common.web.Revisioned;
import java.util.Map;

/**
 * The system settings as the API answers them: every key with its value (defaults included) and the revision of the
 * set, which a save of any of them names in {@code If-Match} (plan 10/10, item 3.6, ADR-0024).
 */
public record SystemSettingsView(Map<String, String> values, long revision) implements Revisioned {

    public SystemSettingsView {
        values = Map.copyOf(values);
    }
}
