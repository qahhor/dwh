package com.greenwhite.dwh.instance.search.typesense;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Profile("!migrate")
@Order(20)
public class TypesenseSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TypesenseSyncRunner.class);

    private final TypesenseClient typesenseClient;
    private final TypesenseProperties properties;
    private final JdbcClient jdbcClient;

    public TypesenseSyncRunner(TypesenseClient typesenseClient, TypesenseProperties properties, JdbcClient jdbcClient) {
        this.typesenseClient = typesenseClient;
        this.properties = properties;
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Typesense поиск отключён в конфигурации.");
            return;
        }

        performInitialSync();
    }

    @Async
    public void performInitialSync() {
        try {
            // Подождем 2 секунды перед первичной проверкой и синхронизацией
            Thread.sleep(2000);

            if (!typesenseClient.isHealthy()) {
                log.warn("Typesense не готов при старте, синхронизация будет отложена.");
                return;
            }

            typesenseClient.initCollections();

            if (!properties.syncOnStartup()) {
                return;
            }

            // 1. Sync Users
            var users = jdbcClient.sql("""
                    select id, name, login, email, phone, state
                    from md_users
                    where state = 'A'
                    """).query().listOfRows();

            for (var u : users) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", String.valueOf(u.get("id")));
                doc.put("user_id", ((Number) u.get("id")).longValue());
                doc.put("name", u.get("name"));
                doc.put("login", u.get("login"));
                doc.put("email", u.get("email"));
                doc.put("phone", u.get("phone") != null ? u.get("phone") : "");
                doc.put("state", u.get("state"));
                typesenseClient.upsertDocument(TypesenseClient.COL_USERS, doc);
            }

            // 2. Sync Projects
            var projects = jdbcClient.sql("""
                    select id, name, description, state
                    from ms_task_projects
                    where state = 'A'
                    """).query().listOfRows();

            for (var p : projects) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", String.valueOf(p.get("id")));
                doc.put("project_id", ((Number) p.get("id")).longValue());
                doc.put("name", p.get("name"));
                doc.put("description", p.get("description") != null ? p.get("description") : "");
                doc.put("state", p.get("state"));
                typesenseClient.upsertDocument(TypesenseClient.COL_PROJECTS, doc);
            }

            // 3. Sync Tasks
            var tasks = jdbcClient.sql("""
                    select t.id, t.title, t.description_markdown, t.priority,
                           s.name as status_name, t.project_id, p.name as project_name
                    from ms_tasks t
                    left join ms_task_statuses s on s.id = t.status_id
                    left join ms_task_projects p on p.id = t.project_id
                    """).query().listOfRows();

            for (var t : tasks) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", String.valueOf(t.get("id")));
                doc.put("task_id", ((Number) t.get("id")).longValue());
                doc.put("title", t.get("title"));
                doc.put("description_markdown", t.get("description_markdown") != null ? t.get("description_markdown") : "");
                doc.put("status_name", t.get("status_name") != null ? t.get("status_name") : "Новая");
                doc.put("priority", t.get("priority") != null ? t.get("priority") : "medium");
                if (t.get("project_id") != null) {
                    doc.put("project_id", ((Number) t.get("project_id")).longValue());
                }
                doc.put("project_name", t.get("project_name") != null ? t.get("project_name") : "");
                typesenseClient.upsertDocument(TypesenseClient.COL_TASKS, doc);
            }

            log.info("Typesense: Первичная синхронизация завершена (Users: {}, Projects: {}, Tasks: {}).",
                    users.size(), projects.size(), tasks.size());
        } catch (Exception e) {
            log.warn("Typesense: Ошибка при фоновой синхронизации: {}", e.getMessage());
        }
    }
}
