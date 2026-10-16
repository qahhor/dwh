package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable declarations loaded together from an authoritative source (ADR-0038). */
public interface EntityDefinitionSource {
    Map<String, Declaration> snapshot();

    record Declaration(EntityDefinition entity, Map<String, Map<String, String>> messages) {
        public Declaration {
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            messages.forEach((language, values) -> copy.put(language, Map.copyOf(values)));
            messages = Map.copyOf(copy);
        }
    }
}
