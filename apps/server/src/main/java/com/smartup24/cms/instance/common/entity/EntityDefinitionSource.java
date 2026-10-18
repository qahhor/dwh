package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.platform.api.entity.EntityDefinition;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Immutable declarations loaded together from an authoritative source (ADR-0038). */
public interface EntityDefinitionSource {
    /** The declarations served now: a declaration that fails to load is left out, never fails the others. */
    Map<String, Declaration> snapshot();

    /**
     * The declarations whose rights stay in the permission catalog while they are not served — a switched-off
     * module, a quarantined form — so a restart does not deprecate them (ADR-0038).
     */
    default Map<String, Declaration> registered() {
        return snapshot();
    }

    /**
     * A loaded declaration, its dictionary entries by language, the key of the field that names a record ({@code
     * titleField}, ADR-0041), when the source says one, and its form logic in formulas ({@code logic}, ADR-0042).
     */
    record Declaration(
            EntityDefinition entity,
            Map<String, Map<String, String>> messages,
            @Nullable String titleField,
            EntityLogic logic) {
        public Declaration {
            Map<String, Map<String, String>> copy = new LinkedHashMap<>();
            messages.forEach((language, values) -> copy.put(language, Map.copyOf(values)));
            messages = Map.copyOf(copy);
        }

        /** A declaration without form logic. */
        public Declaration(
                EntityDefinition entity, Map<String, Map<String, String>> messages, @Nullable String titleField) {
            this(entity, messages, titleField, EntityLogic.NONE);
        }

        /** A declaration whose records the screens name as they do any entity's. */
        public Declaration(EntityDefinition entity, Map<String, Map<String, String>> messages) {
            this(entity, messages, null);
        }
    }
}
