package com.greenwhite.dwh.instance.config.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.Callable;

/** Тестовый эндпоинт для проверки CSRF/аутентификации/заголовков (только test-classpath). */
@RestController
@RequestMapping("/api/v1/security-test")
class SecurityTestController {

    @GetMapping
    String read() {
        return "ok";
    }

    @PostMapping
    String mutate() {
        return "ok";
    }

    @GetMapping("/async")
    Callable<String> asyncRead() {
        return () -> "ok";
    }
}
