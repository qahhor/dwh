package com.greenwhite.dwh.instance.config.openapi;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class OpenApiController {

    @GetMapping(value = {"/api/v1/openapi.json", "/v3/api-docs"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getOpenApiSpec() {
        Map<String, Object> spec = Map.of(
                "openapi", "3.1.0",
                "info", Map.of(
                        "title", "Smartup DWH Platform Instance API",
                        "description", "Single-Tenant Instance Core Backend API with Idempotency, RFC 9457 Problem Details, Keyset Pagination, and RBAC.",
                        "version", "1.0.0"
                ),
                "servers", List.of(
                        Map.of("url", "http://localhost:8080", "description", "Local Development Server")
                ),
                "components", Map.of(
                        "securitySchemes", Map.of(
                                "BearerAuth", Map.of(
                                        "type", "http",
                                        "scheme", "bearer",
                                        "bearerFormat", "JWT / Token",
                                        "description", "Personal API Bearer token starting with dwh_"
                                ),
                                "SessionCookie", Map.of(
                                        "type", "apiKey",
                                        "in", "cookie",
                                        "name", "DWH_SESSION",
                                        "description", "HTTP-Only session cookie"
                                )
                        ),
                        "schemas", Map.of(
                                "ProblemDetail", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "type", Map.of("type", "string", "example", "https://api.dwh.internal/errors/not_found"),
                                                "title", Map.of("type", "string", "example", "NOT_FOUND"),
                                                "status", Map.of("type", "integer", "example", 404),
                                                "code", Map.of("type", "string", "example", "user_not_found"),
                                                "detail", Map.of("type", "string", "example", "Пользователь не найден"),
                                                "instance", Map.of("type", "string", "example", "/api/v1/iam/users/999"),
                                                "timestamp", Map.of("type", "string", "format", "date-time")
                                        ),
                                        "required", List.of("type", "title", "status", "code", "detail")
                                )
                        )
                ),
                "paths", Map.ofEntries(
                        Map.entry("/api/v1/auth/login", Map.of(
                                "post", Map.of(
                                        "summary", "User authentication",
                                        "tags", List.of("Authentication"),
                                        "responses", Map.of(
                                                "200", Map.of("description", "Successfully authenticated"),
                                                "401", Map.of("description", "Invalid credentials"),
                                                "423", Map.of("description", "Account locked due to brute-force protection")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/auth/me", Map.of(
                                "get", Map.of(
                                        "summary", "Get current user profile and effective permissions",
                                        "tags", List.of("Authentication"),
                                        "responses", Map.of("200", Map.of("description", "Current authenticated user"))
                                )
                        )),
                        Map.entry("/api/v1/tasks/items", Map.of(
                                "get", Map.of(
                                        "summary", "List tasks with Keyset pagination and filters",
                                        "tags", List.of("Tasks"),
                                        "responses", Map.of("200", Map.of("description", "Paginated list of tasks"))
                                ),
                                "post", Map.of(
                                        "summary", "Create new task with dynamic attributes and Idempotency-Key support",
                                        "tags", List.of("Tasks"),
                                        "parameters", List.of(
                                                Map.of("name", "Idempotency-Key", "in", "header", "required", false, "schema", Map.of("type", "string", "format", "uuid"))
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of("description", "Task created successfully"),
                                                "409", Map.of("description", "Conflict or Idempotency payload mismatch")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/files/upload", Map.of(
                                "post", Map.of(
                                        "summary", "Upload file with quota checking and SHA-256 deduplication",
                                        "tags", List.of("Storage"),
                                        "responses", Map.of(
                                                "200", Map.of("description", "File uploaded successfully"),
                                                "413", Map.of("description", "Company or user storage quota exceeded")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/audit/logs", Map.of(
                                "get", Map.of(
                                        "summary", "Query immutable audit trail records with keyset pagination",
                                        "tags", List.of("Audit"),
                                        "parameters", List.of(
                                                Map.of("name", "limit", "in", "query", "required", false,
                                                        "description", "Page size, capped at 200", "schema", Map.of("type", "integer", "default", 50, "maximum", 200)),
                                                Map.of("name", "cursor", "in", "query", "required", false,
                                                        "description", "Opaque nextCursor returned by the preceding page", "schema", Map.of("type", "string"))
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of("description", "KeysetPage of mutation logs; credential fields are server-redacted"),
                                                "400", Map.of("description", "Malformed cursor")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/audit/security-events", Map.of(
                                "get", Map.of(
                                        "summary", "Query security events with keyset pagination",
                                        "tags", List.of("Audit"),
                                        "parameters", List.of(
                                                Map.of("name", "limit", "in", "query", "required", false,
                                                        "description", "Page size, capped at 200", "schema", Map.of("type", "integer", "default", 50, "maximum", 200)),
                                                Map.of("name", "cursor", "in", "query", "required", false,
                                                        "description", "Opaque nextCursor returned by the preceding page", "schema", Map.of("type", "string"))
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of("description", "KeysetPage of security events; credential fields are server-redacted"),
                                                "400", Map.of("description", "Malformed cursor")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/settings", Map.of(
                                "get", Map.of(
                                        "summary", "Get effective settings hierarchy (Defaults -> Instance -> User)",
                                        "tags", List.of("Settings"),
                                        "responses", Map.of("200", Map.of("description", "Key-value dictionary of effective settings"))
                                )
                        ))
                )
        );

        return ResponseEntity.ok(spec);
    }
}
