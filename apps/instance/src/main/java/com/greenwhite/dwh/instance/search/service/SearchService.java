package com.greenwhite.dwh.instance.search.service;

import com.greenwhite.dwh.core.error.ErrorCode;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {

    private final JdbcClient jdbcClient;

    public SearchService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional(readOnly = true)
    public SearchResult search(String query, String entityType, int limit) {
        if (query == null || query.trim().length() < 2) {
            throw ApiException.badRequest(ErrorCode.EMPTY_QUERY, "Поисковый запрос должен содержать минимум 2 символа");
        }

        String cleanQuery = query.trim();
        List<SearchHit> hits = new ArrayList<>();

        boolean searchAll = entityType == null || entityType.isBlank() || entityType.equalsIgnoreCase("ALL");

        // 1. Search Users
        if (searchAll || entityType.equalsIgnoreCase("USER")) {
            var userHits = jdbcClient.sql("""
                    select id, name, login, email, phone
                    from md_users
                    where state = 'A' and (name ilike :q or login ilike :q or email ilike :q)
                    limit :limit
                    """)
                    .param("q", "%" + cleanQuery + "%")
                    .param("limit", limit)
                    .query((rs, rowNum) -> new SearchHit(
                            "USER",
                            String.valueOf(rs.getLong("id")),
                            rs.getString("name"),
                            rs.getString("email") + " (@" + rs.getString("login") + ")",
                            "/iam/users/" + rs.getLong("id")
                    ))
                    .list();
            hits.addAll(userHits);
        }

        // 2. Search Tasks
        if (searchAll || entityType.equalsIgnoreCase("TASK")) {
            var taskHits = jdbcClient.sql("""
                    select t.id, t.title, t.priority, s.name as status_name
                    from ms_tasks t
                    join ms_task_statuses s on s.id = t.status_id
                    where t.title ilike :q or t.description_markdown ilike :q
                    limit :limit
                    """)
                    .param("q", "%" + cleanQuery + "%")
                    .param("limit", limit)
                    .query((rs, rowNum) -> new SearchHit(
                            "TASK",
                            String.valueOf(rs.getLong("id")),
                            rs.getString("title"),
                            "Статус: " + rs.getString("status_name") + " | Приоритет: " + rs.getString("priority"),
                            "/tasks/items/" + rs.getLong("id")
                    ))
                    .list();
            hits.addAll(taskHits);
        }

        // 3. Search Projects
        if (searchAll || entityType.equalsIgnoreCase("PROJECT")) {
            var projectHits = jdbcClient.sql("""
                    select id, name, description
                    from ms_task_projects
                    where state = 'A' and (name ilike :q or description ilike :q)
                    limit :limit
                    """)
                    .param("q", "%" + cleanQuery + "%")
                    .param("limit", limit)
                    .query((rs, rowNum) -> new SearchHit(
                            "PROJECT",
                            String.valueOf(rs.getLong("id")),
                            rs.getString("name"),
                            rs.getString("description") != null ? rs.getString("description") : "",
                            "/tasks/projects/" + rs.getLong("id")
                    ))
                    .list();
            hits.addAll(projectHits);
        }

        return new SearchResult(cleanQuery, hits.size(), hits);
    }

    public record SearchHit(
            String entityType,
            String id,
            String title,
            String description,
            String targetUrl
    ) {}

    public record SearchResult(
            String query,
            int totalHits,
            List<SearchHit> hits
    ) {}
}
