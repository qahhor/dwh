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
                        "title", "SmartupCMS Core API",
                        "description", "Single-Tenant Core Platform Backend API with Idempotency, RFC 9457 Problem Details, Keyset Pagination, Scoped RBAC, Search, and Auditing.",
                        "version", "2.0.0"
                ),
                "servers", List.of(
                        Map.of("url", "/", "description", "Current Deployment Host")
                ),
                "components", Map.of(
                        "securitySchemes", Map.of(
                                "BearerAuth", Map.of(
                                        "type", "http",
                                        "scheme", "bearer",
                                        "description", "Opaque Personal API Bearer token starting with dwh_"
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
                                                "type", Map.of("type", "string", "example", "https://smartupcms.io/errors/not_found"),
                                                "title", Map.of("type", "string", "example", "NOT_FOUND"),
                                                "status", Map.of("type", "integer", "example", 404),
                                                "code", Map.of("type", "string", "example", "user_not_found"),
                                                "detail", Map.of("type", "string", "example", "Пользователь не найден"),
                                                "instance", Map.of("type", "string", "example", "/api/v1/iam/users/999"),
                                                "timestamp", Map.of("type", "string", "format", "date-time")
                                        ),
                                        "required", List.of("type", "title", "status", "code", "detail")
                                ),
                                "TaskItem", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "integer", "format", "int64"),
                                                "title", Map.of("type", "string"),
                                                "priority", Map.of("type", "string", "enum", List.of("low", "medium", "high", "critical")),
                                                "statusId", Map.of("type", "integer", "format", "int64"),
                                                "revision", Map.of("type", "integer", "format", "int64", "description", "Monotonic OCC revision counter")
                                        )
                                )
                        )
                ),
                "paths", Map.ofEntries(
                        // Authentication
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
                                        "responses", Map.of("200", Map.of("description", "Current authenticated user profile"))
                                )
                        )),
                        Map.entry("/api/v1/auth/logout", Map.of(
                                "post", Map.of(
                                        "summary", "Terminate current session",
                                        "tags", List.of("Authentication"),
                                        "responses", Map.of("200", Map.of("description", "Session invalidated"))
                                )
                        )),
                        Map.entry("/api/v1/auth/sessions", Map.of(
                                "get", Map.of(
                                        "summary", "List active user sessions",
                                        "tags", List.of("Authentication"),
                                        "responses", Map.of("200", Map.of("description", "List of user sessions"))
                                )
                        )),

                        // Tasks & Projects
                        Map.entry("/api/v1/tasks/items", Map.of(
                                "get", Map.of(
                                        "summary", "List tasks with Keyset pagination and scope filters",
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
                        Map.entry("/api/v1/tasks/items/{id}", Map.of(
                                "get", Map.of(
                                        "summary", "Get task detail by ID",
                                        "tags", List.of("Tasks"),
                                        "responses", Map.of("200", Map.of("description", "Task details"), "404", Map.of("description", "Task not found"))
                                ),
                                "patch", Map.of(
                                        "summary", "Partially update task with Optimistic Concurrency Control (OCC)",
                                        "tags", List.of("Tasks"),
                                        "responses", Map.of(
                                                "200", Map.of("description", "Task updated"),
                                                "409", Map.of("description", "TASK_REVISION_CONFLICT: stale expectedRevision")
                                        )
                                ),
                                "delete", Map.of(
                                        "summary", "Delete task",
                                        "tags", List.of("Tasks"),
                                        "responses", Map.of("204", Map.of("description", "Task deleted"))
                                )
                        )),
                        Map.entry("/api/v1/tasks/items/{id}/status", Map.of(
                                "post", Map.of(
                                        "summary", "Transition task status with OCC validation",
                                        "tags", List.of("Tasks"),
                                        "responses", Map.of(
                                                "200", Map.of("description", "Status transitioned"),
                                                "409", Map.of("description", "TASK_REVISION_CONFLICT: stale expectedRevision")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/tasks/projects", Map.of(
                                "get", Map.of(
                                        "summary", "List projects",
                                        "tags", List.of("Tasks"),
                                        "responses", Map.of("200", Map.of("description", "List of projects"))
                                )
                        )),

                        // Storage & Files
                        Map.entry("/api/v1/files/upload", Map.of(
                                "post", Map.of(
                                        "summary", "Upload file with quota check, malware scan, and concurrency bounding",
                                        "tags", List.of("Storage"),
                                        "responses", Map.of(
                                                "200", Map.of("description", "File uploaded successfully"),
                                                "413", Map.of("description", "Company or user storage quota exceeded"),
                                                "429", Map.of("description", "RATE_LIMITED: concurrent upload limit exceeded")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/files/{id}", Map.of(
                                "get", Map.of(
                                        "summary", "Get file metadata",
                                        "tags", List.of("Storage"),
                                        "responses", Map.of("200", Map.of("description", "File metadata"), "404", Map.of("description", "Not found"))
                                ),
                                "delete", Map.of(
                                        "summary", "Delete file",
                                        "tags", List.of("Storage"),
                                        "responses", Map.of("204", Map.of("description", "File deleted"))
                                )
                        )),
                        Map.entry("/api/v1/files/{id}/download", Map.of(
                                "get", Map.of(
                                        "summary", "Download file binary content with scope check",
                                        "tags", List.of("Storage"),
                                        "responses", Map.of("200", Map.of("description", "File stream content"))
                                )
                        )),

                        // Audit
                        Map.entry("/api/v1/audit/logs", Map.of(
                                "get", Map.of(
                                        "summary", "Query immutable audit trail records with keyset pagination",
                                        "tags", List.of("Audit"),
                                        "parameters", List.of(
                                                Map.of("name", "limit", "in", "query", "required", false,
                                                        "description", "Page size, capped at 200", "schema", Map.of("type", "integer", "default", 50, "maximum", 200)),
                                                Map.of("name", "cursor", "in", "query", "required", false,
                                                        "description", "Opaque nextCursor returned by preceding page", "schema", Map.of("type", "string"))
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of("description", "KeysetPage of audit records; credentials redacted"),
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
                                                        "description", "Opaque nextCursor returned by preceding page", "schema", Map.of("type", "string"))
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of("description", "KeysetPage of security events; credentials redacted"),
                                                "400", Map.of("description", "Malformed cursor")
                                        )
                                )
                        )),
                        // Field registry (ADR-0016)
                        Map.entry("/api/v1/query-meta/{code}", Map.of(
                                "get", Map.of(
                                        "summary", "Fields of a registry list: types, filter operations, sorting and page size",
                                        "tags", List.of("Query"),
                                        "parameters", List.of(
                                                Map.of("name", "code", "in", "path", "required", true,
                                                        "description", "List code, e.g. upl.sources", "schema", Map.of("type", "string"))
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of("description", "List metadata without SQL"),
                                                "401", Map.of("description", "Not signed in"),
                                                "404", Map.of("description", "QUERY_LIST_NOT_FOUND: unknown list or no right to view it")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/tasks/bulk", Map.of(
                                "post", Map.of(
                                        "summary", "One action on up to 100 tasks (status, priority), each in its own transaction",
                                        "tags", List.of("Tasks"),
                                        "responses", Map.of(
                                                "200", Map.of("description", "Result per task: ok, or the code and message of the single operation"),
                                                "403", Map.of("description", "No right to change tasks"),
                                                "422", Map.of("description", "BULK_INVALID or BULK_ACTION_UNKNOWN")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/list-views/{code}", Map.of(
                                "get", Map.of(
                                        "summary", "The signed-in user's saved views of a registry list",
                                        "tags", List.of("Query"),
                                        "responses", Map.of(
                                                "200", Map.of("description", "Views with columns, sort and filter"),
                                                "404", Map.of("description", "QUERY_LIST_NOT_FOUND")
                                        )
                                ),
                                "post", Map.of(
                                        "summary", "Save a view; its state is checked against the field registry",
                                        "tags", List.of("Query"),
                                        "responses", Map.of(
                                                "201", Map.of("description", "Saved view in canonical form"),
                                                "422", Map.of("description", "LIST_VIEW_INVALID, LIST_VIEW_NAME_TAKEN or LIST_VIEW_LIMIT")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/list-views/{code}/{id}", Map.of(
                                "put", Map.of(
                                        "summary", "Change an own view (lockVersion required)",
                                        "tags", List.of("Query"),
                                        "responses", Map.of(
                                                "200", Map.of("description", "Changed view"),
                                                "404", Map.of("description", "LIST_VIEW_NOT_FOUND"),
                                                "409", Map.of("description", "STALE_VERSION")
                                        )
                                ),
                                "delete", Map.of(
                                        "summary", "Delete an own view",
                                        "tags", List.of("Query"),
                                        "responses", Map.of("204", Map.of("description", "Deleted"),
                                                "404", Map.of("description", "LIST_VIEW_NOT_FOUND"))
                                )
                        )),
                        Map.entry("/api/v1/upl/sources", Map.of(
                                "get", Map.of(
                                        "summary", "UPL sources through the field registry, keyset paginated",
                                        "tags", List.of("UPL"),
                                        "parameters", List.of(
                                                Map.of("name", "limit", "in", "query", "required", false,
                                                        "description", "Page size, 1 to 200", "schema", Map.of("type", "integer", "default", 50, "maximum", 200)),
                                                Map.of("name", "cursor", "in", "query", "required", false,
                                                        "description", "Opaque nextCursor of the same filter and sort", "schema", Map.of("type", "string")),
                                                Map.of("name", "filter", "in", "query", "required", false,
                                                        "description", "JSON array of {field, op, value} conditions joined with and (ADR-0016)", "schema", Map.of("type", "string")),
                                                Map.of("name", "sort", "in", "query", "required", false,
                                                        "description", "Sortable field key, minus for descending", "schema", Map.of("type", "string"))
                                        ),
                                        "responses", Map.of(
                                                "200", Map.of("description", "KeysetPage of sources"),
                                                "422", Map.of("description", "QUERY_INVALID, INVALID_LIMIT or INVALID_CURSOR with addressed errors")
                                        )
                                )
                        )),
                        Map.entry("/api/v1/audit/stats", Map.of(
                                "get", Map.of(
                                        "summary", "Get coalesced audit statistics (15-second snapshot cache with computedAt)",
                                        "tags", List.of("Audit"),
                                        "responses", Map.of("200", Map.of("description", "Cached audit statistics"))
                                )
                        )),

                        // Reports & Exports
                        Map.entry("/api/v1/reports/tasks-export-csv", Map.of(
                                "get", Map.of(
                                        "summary", "Stream tasks export in CSV format with formula neutralization and max-rows limit",
                                        "tags", List.of("Reports"),
                                        "responses", Map.of("200", Map.of("description", "Streaming CSV stream"))
                                )
                        )),
                        Map.entry("/api/v1/reports/tasks-export-xml", Map.of(
                                "get", Map.of(
                                        "summary", "Stream tasks export in XML format with scope enforcement and max-rows limit",
                                        "tags", List.of("Reports"),
                                        "responses", Map.of("200", Map.of("description", "Streaming XML stream"))
                                )
                        )),

                        // Search
                        Map.entry("/api/v1/search", Map.of(
                                "get", Map.of(
                                        "summary", "Unified full-text search across tasks, files, and notes with scope enforcement",
                                        "tags", List.of("Search"),
                                        "responses", Map.of("200", Map.of("description", "Search results matching query"))
                                )
                        )),
                        Map.entry("/api/v1/search/management/status", Map.of(
                                "get", Map.of(
                                        "summary", "Check Typesense search cluster status and collection health",
                                        "tags", List.of("Search"),
                                        "responses", Map.of("200", Map.of("description", "Search index status"))
                                )
                        )),
                        Map.entry("/api/v1/search/management/reconcile", Map.of(
                                "post", Map.of(
                                        "summary", "Trigger background reconciliation between PostgreSQL and Typesense",
                                        "tags", List.of("Search"),
                                        "responses", Map.of("200", Map.of("description", "Reconciliation triggered"))
                                )
                        )),

                        // Notes
                        Map.entry("/api/v1/notes", Map.of(
                                "get", Map.of(
                                        "summary", "List notes for current user",
                                        "tags", List.of("Notes"),
                                        "responses", Map.of("200", Map.of("description", "List of user notes"))
                                ),
                                "post", Map.of(
                                        "summary", "Create a note",
                                        "tags", List.of("Notes"),
                                        "responses", Map.of("200", Map.of("description", "Note created"))
                                )
                        )),

                        // Settings & Modules
                        Map.entry("/api/v1/settings", Map.of(
                                "get", Map.of(
                                        "summary", "Get effective settings hierarchy (Defaults -> Instance -> User)",
                                        "tags", List.of("Settings"),
                                        "responses", Map.of("200", Map.of("description", "Key-value dictionary of effective settings"))
                                )
                        )),
                        Map.entry("/api/v1/modules", Map.of(
                                "get", Map.of(
                                        "summary", "List registered modules and active status",
                                        "tags", List.of("Modules"),
                                        "responses", Map.of("200", Map.of("description", "List of modules in registry"))
                                )
                        )),

                        // System
                        Map.entry("/api/v1/system/info", Map.of(
                                "get", Map.of(
                                        "summary", "Get sanitized system health, backup status, and version",
                                        "tags", List.of("System"),
                                        "responses", Map.of("200", Map.of("description", "System health info"))
                                )
                        ))
                )
        );

        return ResponseEntity.ok(spec);
    }
}
