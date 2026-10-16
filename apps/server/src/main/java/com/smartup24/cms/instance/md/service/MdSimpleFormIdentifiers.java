package com.smartup24.cms.instance.md.service;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Server and CLI share the reserved identifier contract (ADR-0037). */
final class MdSimpleFormIdentifiers {
    private final Set<String> modules;
    private final Set<String> fields;
    private final Set<String> fieldFragments;

    MdSimpleFormIdentifiers(ObjectMapper json) {
        try (var input =
                MdSimpleFormIdentifiers.class.getResourceAsStream("/studio/simple-form-reserved-identifiers.json")) {
            if (input == null) throw new IllegalStateException("Simple-form identifier contract is missing");
            JsonNode root = json.readTree(input);
            var moduleNames = new HashSet<>(names(root, "javaModules"));
            moduleNames.addAll(names(root, "portableModules"));
            modules = Set.copyOf(moduleNames);
            var combined = new HashSet<>(names(root, "sqlColumns"));
            combined.addAll(names(root, "systemFields"));
            fields = Set.copyOf(combined);
            fieldFragments = names(root, "fieldFragments");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read simple-form identifier contract", failure);
        }
    }

    boolean reservedModule(String name) {
        return modules.contains(name);
    }

    boolean reservedField(String name) {
        return fields.contains(name) || fieldFragments.stream().anyMatch(name::contains);
    }

    private static Set<String> names(JsonNode root, String key) {
        JsonNode values = root.path(key);
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalStateException("Invalid simple-form identifier contract: " + key);
        }
        Set<String> result = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isString() || value.asString().isEmpty() || !result.add(value.asString())) {
                throw new IllegalStateException("Invalid simple-form identifier contract: " + key);
            }
        }
        return Set.copyOf(result);
    }
}
