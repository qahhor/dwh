package com.greenwhite.dwh.instance.audit.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Array;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Removes credential material before audit data crosses either the persistence
 * boundary or the administrative API boundary.
 */
@Component
public class AuditDataRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final Set<String> SAFE_TOKEN_METADATA_KEYS = Set.of("tokenprefix");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Map<String, Object> redact(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        boolean semanticValueIsCredential = source.entrySet().stream()
                .filter(entry -> isSemanticNameKey(entry.getKey()))
                .map(Map.Entry::getValue)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .anyMatch(this::isCredentialKey);
        source.forEach((key, value) -> {
            boolean sensitiveValue = isCredentialKey(key)
                    || (semanticValueIsCredential && "value".equals(normalizeKey(key)));
            result.put(key, sensitiveValue ? REDACTED : redactValue(value));
        });
        return Collections.unmodifiableMap(result);
    }

    private Object redactValue(Object value) {
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> stringKeyed = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) ->
                    stringKeyed.put(String.valueOf(key), nestedValue));
            return redact(stringKeyed);
        }
        if (value instanceof Iterable<?> iterable) {
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            iterable.forEach(item -> result.add(redactValue(item)));
            return Collections.unmodifiableList(result);
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            java.util.ArrayList<Object> result = new java.util.ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                result.add(redactValue(Array.get(value, index)));
            }
            return Collections.unmodifiableList(result);
        }
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>) {
            return value;
        }

        try {
            Object jsonValue = objectMapper.readValue(objectMapper.writeValueAsString(value), Object.class);
            return redactValue(jsonValue);
        } catch (Exception ignored) {
            // Unknown objects must never cross the audit boundary uninspected.
            return REDACTED;
        }
    }

    private boolean isCredentialKey(String key) {
        String normalized = normalizeKey(key);

        if (SAFE_TOKEN_METADATA_KEYS.contains(normalized)
                || normalized.endsWith("passwordchangedat")
                || normalized.endsWith("passwordlength")
                || normalized.endsWith("passwordpolicy")
                || normalized.endsWith("authorizationurl")
                || normalized.endsWith("tokenurl")) {
            return false;
        }

        return normalized.startsWith("password")
                || normalized.endsWith("password")
                || normalized.startsWith("passwd")
                || normalized.endsWith("passwd")
                || normalized.equals("pwd")
                || normalized.contains("passphrase")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("clientassertion")
                || normalized.equals("authorization")
                || normalized.equals("authheader")
                || normalized.equals("proxyauthorization")
                || normalized.endsWith("authorizationheader")
                || normalized.endsWith("apikey")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("signingkey")
                || normalized.endsWith("encryptionkey")
                || normalized.equals("cookie")
                || normalized.equals("setcookie")
                || normalized.equals("otp")
                || normalized.contains("passcode")
                || normalized.endsWith("verificationcode")
                || normalized.endsWith("onetimecode")
                || normalized.equals("recoverycode")
                || normalized.equals("recoverycodehash")
                || normalized.equals("codehash")
                || normalized.equals("jwt")
                || normalized.equals("bearer")
                || normalized.endsWith("signature");
    }

    private boolean isSemanticNameKey(String key) {
        String normalized = normalizeKey(key);
        return "key".equals(normalized) || "name".equals(normalized);
    }

    private String normalizeKey(String key) {
        return key == null
                ? ""
                : key.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }
}
