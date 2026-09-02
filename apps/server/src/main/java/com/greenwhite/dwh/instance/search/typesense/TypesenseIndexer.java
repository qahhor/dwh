package com.greenwhite.dwh.instance.search.typesense;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class TypesenseIndexer {

    private static final Logger log = LoggerFactory.getLogger(TypesenseIndexer.class);

    private final TypesenseClient typesenseClient;
    private final JdbcClient jdbcClient;

    public TypesenseIndexer(TypesenseClient typesenseClient, JdbcClient jdbcClient) {
        this.typesenseClient = typesenseClient;
        this.jdbcClient = jdbcClient;
    }

    @Async
    public void indexTask(Long taskId) {
        if (!typesenseClient.isEnabled() || taskId == null) return;
        try {
            var task = jdbcClient.sql("""
                    select t.id, t.title, t.description_markdown, t.priority,
                           s.name as status_name, t.project_id, p.name as project_name
                    from ms_tasks t
                    left join ms_task_statuses s on s.id = t.status_id
                    left join ms_task_projects p on p.id = t.project_id
                    where t.id = :id
                    """)
                    .param("id", taskId)
                    .query().listOfRows();

            if (task.isEmpty()) return;

            var t = task.get(0);
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
        } catch (Exception e) {
            log.debug("Typesense: Ошибка асинхронной индексации задачи #{}: {}", taskId, e.getMessage());
        }
    }

    @Async
    public void deleteTask(Long taskId) {
        if (!typesenseClient.isEnabled() || taskId == null) return;
        typesenseClient.deleteDocument(TypesenseClient.COL_TASKS, String.valueOf(taskId));
    }

    @Async
    public void indexProject(Long projectId) {
        if (!typesenseClient.isEnabled() || projectId == null) return;
        try {
            var project = jdbcClient.sql("""
                    select id, name, description, state
                    from ms_task_projects
                    where id = :id
                    """)
                    .param("id", projectId)
                    .query().listOfRows();

            if (project.isEmpty()) return;

            var p = project.get(0);
            Map<String, Object> doc = new HashMap<>();
            doc.put("id", String.valueOf(p.get("id")));
            doc.put("project_id", ((Number) p.get("id")).longValue());
            doc.put("name", p.get("name"));
            doc.put("description", p.get("description") != null ? p.get("description") : "");
            doc.put("state", p.get("state"));

            typesenseClient.upsertDocument(TypesenseClient.COL_PROJECTS, doc);
        } catch (Exception e) {
            log.debug("Typesense: Ошибка асинхронной индексации проекта #{}: {}", projectId, e.getMessage());
        }
    }

    @Async
    public void deleteProject(Long projectId) {
        if (!typesenseClient.isEnabled() || projectId == null) return;
        typesenseClient.deleteDocument(TypesenseClient.COL_PROJECTS, String.valueOf(projectId));
    }

    @Async
    public void indexUser(Long userId) {
        if (!typesenseClient.isEnabled() || userId == null) return;
        try {
            var user = jdbcClient.sql("""
                    select id, name, login, email, phone, state
                    from md_users
                    where id = :id
                    """)
                    .param("id", userId)
                    .query().listOfRows();

            if (user.isEmpty()) return;

            var u = user.get(0);
            Map<String, Object> doc = new HashMap<>();
            doc.put("id", String.valueOf(u.get("id")));
            doc.put("user_id", ((Number) u.get("id")).longValue());
            doc.put("name", u.get("name"));
            doc.put("login", u.get("login"));
            doc.put("email", u.get("email"));
            doc.put("phone", u.get("phone") != null ? u.get("phone") : "");
            doc.put("state", u.get("state"));

            typesenseClient.upsertDocument(TypesenseClient.COL_USERS, doc);
        } catch (Exception e) {
            log.debug("Typesense: Ошибка асинхронной индексации пользователя #{}: {}", userId, e.getMessage());
        }
    }

    @Async
    public void deleteUser(Long userId) {
        if (!typesenseClient.isEnabled() || userId == null) return;
        typesenseClient.deleteDocument(TypesenseClient.COL_USERS, String.valueOf(userId));
    }
}
