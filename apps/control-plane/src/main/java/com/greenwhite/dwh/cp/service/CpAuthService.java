package com.greenwhite.dwh.cp.service;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.repository.CpSessionRepository;
import com.greenwhite.dwh.cp.repository.CpUserRepository;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Service
public class CpAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CpUserRepository userRepository;
    private final CpSessionRepository sessionRepository;
    private final CpPasswordHasher hasher;

    public CpAuthService(CpUserRepository userRepository,
                         CpSessionRepository sessionRepository,
                         CpPasswordHasher hasher) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.hasher = hasher;
    }

    @Transactional
    public LoginResult login(String login, String password, String ip, String userAgent) {
        var user = userRepository.findByLogin(login).orElse(null);

        // Одинаковый ответ на «нет пользователя» и «неверный пароль»:
        // иначе форма входа превращается в проверку существования логинов.
        if (user == null || !hasher.verifyPassword(password, user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Неверный логин или пароль");
        }
        if (!CpPref.STATE_ACTIVE.equals(user.state())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Учётная запись заблокирована");
        }

        String rawToken = newToken();
        sessionRepository.create(user.id(), rawToken, ip, userAgent);
        return new LoginResult(rawToken, user.login(), user.name(), userRepository.getRoles(user.id()));
    }

    @Transactional
    public void logout(Long sessionId) {
        if (sessionId != null) {
            sessionRepository.close(sessionId);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record LoginResult(String rawToken, String login, String name, Set<String> roles) {}
}
