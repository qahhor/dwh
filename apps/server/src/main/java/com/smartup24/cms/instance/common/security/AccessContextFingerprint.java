package com.smartup24.cms.instance.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/** Binds retained responses to the permissions and credential generation used to read them (ADR-0006). */
public final class AccessContextFingerprint {
    private AccessContextFingerprint() {}

    public static String current() {
        var principal = SecurityContext.getPrincipal();
        if (principal == null) return "anonymous";
        String value = principal.userId() + ":" + principal.permissionVersion() + ":"
                + principal.authenticationVersion() + ":" + principal.forcePasswordChange() + ":"
                + String.join("\n", new TreeSet<>(principal.effectivePermissions()));
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
