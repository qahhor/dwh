package com.greenwhite.dwh.core.pagination;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Base64 URL-safe encoding/decoding helper for Keyset pagination cursors.
 */
public final class CursorUtils {
    private CursorUtils() {}

    public static String encode(String rawCursor) {
        if (rawCursor == null || rawCursor.isBlank()) {
            return null;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawCursor.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            return null;
        }
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(encodedCursor);
            return new String(decodedBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
