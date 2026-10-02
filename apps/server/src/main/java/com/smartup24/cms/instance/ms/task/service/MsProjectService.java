package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.core.error.FieldErrorItem;
import com.smartup24.cms.core.pagination.CursorUtils;
import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.entity.EntityRegistry;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.TimePage;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.api.ProjectMemberView;
import com.smartup24.cms.instance.ms.task.api.ProjectProgressView;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the module reads of a project besides its record, which the general runtime keeps ({@link MsProjectEntity}):
 * its members a page at a time, and its progress over the tasks the viewer may see (ADR-0013). A project is opened here
 * only as the runtime opens it: outside the viewer's scope it answers 404, like a missing one.
 */
@Service
public class MsProjectService {

    static final int DEFAULT_MEMBERS = 50;
    static final int MAX_MEMBERS = 200;

    /** The most projects one progress read names: a page of the project list. */
    public static final int MAX_PROGRESS = 200;

    private final MsProjectRepository projectRepository;
    private final MsTaskStatsRepository statsRepository;
    private final EntityRegistry entities;
    private final MdScopeService scopeService;

    public MsProjectService(
            MsProjectRepository projectRepository,
            MsTaskStatsRepository statsRepository,
            EntityRegistry entities,
            MdScopeService scopeService) {
        this.projectRepository = projectRepository;
        this.statsRepository = statsRepository;
        this.entities = entities;
        this.scopeService = scopeService;
    }

    /** Throws the runtime's 404 when the project is missing or outside the viewer's scope (ADR-0013). */
    @Transactional(readOnly = true)
    public void requireVisible(long projectId) {
        entities.records(MsProjectEntity.CODE).orElseThrow().requireVisible(projectId);
    }

    /**
     * The progress of the projects the viewer may see among {@code projectIds}, each over the tasks the viewer may see;
     * a project the viewer may not see is left out, as a missing one is.
     */
    @Transactional(readOnly = true)
    public List<ProjectProgressView> progress(List<Long> projectIds, long viewerId) {
        if (projectIds.size() > MAX_PROGRESS) {
            throw ApiException.validation(
                    "error.common.record_fields_invalid",
                    List.of(FieldErrorItem.keyed(
                            "ids", "too_many", "error.field.too_many", Map.of("max", MAX_PROGRESS))));
        }
        Set<Long> ids = new LinkedHashSet<>(projectIds);
        if (ids.isEmpty()) return List.of();
        List<Long> visible = projectRepository.visible(ids, scopeService.filterForProjects(viewerId));
        return statsRepository.getProjectTaskStats(visible, scopeService.filterForTasks(viewerId)).stream()
                .map(stats -> new ProjectProgressView(
                        stats.projectId(),
                        stats.totalTasks(),
                        stats.doneTasks(),
                        stats.totalTasks() == 0 ? 0 : (int) Math.round(stats.doneTasks() * 100.0 / stats.totalTasks())))
                .toList();
    }

    /**
     * A page of the members of a project by name (plan 10/10, item 3.5): {@code limit} 1 to {@link #MAX_MEMBERS}
     * (else 422), {@code cursor} the {@code nextCursor} of the previous page (422 when it is not one).
     */
    @Transactional(readOnly = true)
    public KeysetPage<ProjectMemberView> pageProjectMembers(long projectId, Integer limit, String cursor) {
        requireVisible(projectId);
        int size = TimePage.limit(limit, DEFAULT_MEMBERS, MAX_MEMBERS);
        var after = cursor == null || cursor.isBlank() ? null : decodeMember(projectId, cursor);
        var rows = projectRepository.getMembers(projectId, after, size + 1);
        boolean hasMore = rows.size() > size;
        var items = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore ? encodeMember(items.getLast()) : null;
        return new KeysetPage<>(
                MsTaskViews.all(items, MsTaskViews::projectMember),
                next,
                hasMore,
                items.size(),
                after == null && !hasMore);
    }

    private static String encodeMember(MsProjectRepository.ProjectMemberRecord member) {
        return CursorUtils.encode(member.userId() + "|" + member.userName());
    }

    /** The user id goes first: a name may hold the separator. */
    private static MsProjectRepository.ProjectMemberRecord decodeMember(long projectId, String cursor) {
        String raw = CursorUtils.decode(cursor);
        int bar = raw == null ? -1 : raw.indexOf('|');
        if (bar <= 0) {
            throw TimePage.invalidCursor();
        }
        try {
            long userId = Long.parseLong(raw.substring(0, bar));
            return new MsProjectRepository.ProjectMemberRecord(projectId, userId, raw.substring(bar + 1), null, null);
        } catch (NumberFormatException notOurs) {
            throw TimePage.invalidCursor();
        }
    }
}
