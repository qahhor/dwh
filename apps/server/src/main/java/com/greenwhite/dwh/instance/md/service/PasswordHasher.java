package com.greenwhite.dwh.instance.md.service;

public interface PasswordHasher {
    String hashPassword(String rawPassword);
    boolean verifyPassword(String rawPassword, String encodedHash);
    String hashToken(String rawToken);
}
