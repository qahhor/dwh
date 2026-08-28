package com.greenwhite.dwh.cp.bootstrap;

import com.greenwhite.dwh.cp.pref.CpPref;
import com.greenwhite.dwh.cp.repository.CpUserRepository;
import com.greenwhite.dwh.cp.security.CpPasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Первый сотрудник control plane создаётся из конфигурации развёртывания,
 * не миграцией — тот же принцип, что для админа экземпляра (AUDIT-03 C-1):
 * никаких известных паролей в репозитории. Идемпотентно.
 */
@Component
@Profile("!migrate")
public class CpBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CpBootstrap.class);

    private final CpUserRepository userRepository;
    private final CpPasswordHasher hasher;
    private final String adminLogin;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public CpBootstrap(CpUserRepository userRepository,
                       CpPasswordHasher hasher,
                       @Value("${dwh.cp.admin-login:}") String adminLogin,
                       @Value("${dwh.cp.admin-email:}") String adminEmail,
                       @Value("${dwh.cp.admin-password:}") String adminPassword,
                       @Value("${dwh.cp.admin-name:Platform Administrator}") String adminName) {
        this.userRepository = userRepository;
        this.hasher = hasher;
        this.adminLogin = adminLogin;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        if (adminLogin.isBlank() || adminEmail.isBlank() || adminPassword.isBlank()) {
            // Не падаем: control plane может подниматься до того, как решили,
            // кто им управляет. Но без учётки в панель не войти — предупреждаем явно.
            log.warn("Control plane без пользователей: задайте dwh.cp.admin-login/-email/-password "
                    + "в конфигурации, иначе вход в панель невозможен");
            return;
        }
        Long id = userRepository.create(adminName, adminLogin, adminEmail,
                hasher.hashPassword(adminPassword));
        userRepository.assignRole(id, CpPref.ROLE_ADMIN);
        log.info("Создан первый администратор control plane: login={}", adminLogin);
    }
}
