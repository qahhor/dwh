package com.smartup24.cms.instance.config.security;

import java.util.concurrent.Callable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A test endpoint for checking CSRF, authentication and headers (test classpath only). */
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
