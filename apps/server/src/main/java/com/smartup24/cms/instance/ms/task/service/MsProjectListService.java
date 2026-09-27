package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pages of the project list through the registry ({@code ms.projects}): filter, sort (by progress too) and
 * search {@code q}, with the task counts of each project over the tasks the viewer may see.
 */
@Service
public class MsProjectListService {

    /** A project as the list answers it; the counts are empty for someone who may not view tasks. */
    public record ProjectListItem(Long id, String name, String description, String state, Map<String, Object> attributes,
                                  Instant createdAt, Long createdBy, Integer totalTasks, Integer doneTasks,
                                  Integer progress) {
    }

    private final QueryListRepository lists;
    private final QueryListRegistry registry;
    private final MsProjectRepository projects;
    private final MsTaskRepository tasks;
    private final MdScopeService scopes;

    public MsProjectListService(QueryListRepository lists, QueryListRegistry registry, MsProjectRepository projects,
                                MsTaskRepository tasks, MdScopeService scopes) {
        this.lists = lists;
        this.registry = registry;
        this.projects = projects;
        this.tasks = tasks;
        this.scopes = scopes;
    }

    /** @param state the old flat filter (A or P); part of the cursor's fingerprint */
    @Transactional(readOnly = true)
    public KeysetPage<ProjectListItem> page(Long viewerId, Integer limit, String cursor, String filter, String sort,
                                            String search, String state) {
        String narrowing = state == null || state.isBlank() ? null : "state=" + state.strip();
        QueryPlan plan = QueryCompiler.compile(registry.resolve(MsProjectQuery.LIST), filter, sort, limit, cursor,
                search, narrowing);
        var scope = scopes.filterForTasks(viewerId);
        StringBuilder sql = new StringBuilder();
        Map<String, Object> params = new LinkedHashMap<>();
        // The progress fields' subqueries use the viewer's task scope (MsProjectQuery.progressFields).
        if (scope.bindsUserId()) params.put("scopeUserId", scope.userId());
        if (narrowing != null) {
            sql.append(" and p.state = :state");
            params.put("state", state.strip());
        }
        var page = lists.page(plan, projects::mapRecord, new QueryPlan.SqlFragment(sql.toString(), params));

        boolean counts = plan.shows("progress");
        Map<Long, MsTaskRepository.ProjectTaskStats> stats = new HashMap<>();
        if (counts && !page.items().isEmpty()) {
            tasks.getProjectTaskStats(scope).forEach(entry -> stats.put(entry.projectId(), entry));
        }
        List<ProjectListItem> items = page.items().stream().map(project -> {
            var entry = counts ? stats.get(project.id()) : null;
            Integer total = counts ? (entry == null ? 0 : entry.totalTasks()) : null;
            Integer done = counts ? (entry == null ? 0 : entry.doneTasks()) : null;
            Integer progress = counts ? (total == 0 ? 0 : (int) Math.round(done * 100.0 / total)) : null;
            return new ProjectListItem(project.id(), project.name(), project.description(), project.state(),
                    project.attributes(), project.createdAt(), project.createdBy(), total, done, progress);
        }).toList();
        return new KeysetPage<>(items, page.nextCursor(), page.hasMore(), page.totalEstimated());
    }
}
