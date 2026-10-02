package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.common.entity.hook.EntityHooks;
import com.smartup24.cms.instance.common.entity.hook.EntityOperation;
import com.smartup24.cms.instance.common.entity.hook.EntitySave;
import com.smartup24.cms.instance.common.entity.hook.EntityValues;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.pref.MsTaskPref;
import com.smartup24.cms.instance.ms.task.repository.MsTaskTreeRepository;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The rules of a task the declaration cannot say (ADR-0032, 6.5; FR-TASK-4, FR-TASK-8), in the transaction of every
 * save of {@code ms.tasks}:
 *
 * <ul>
 *   <li>before the save, every new participant is someone the author may see (ADR-0013) and a new parent closes no
 *       cycle; the status a new task starts in is locked against a concurrent rename for the search;
 *   <li>after the save, the participation rows follow the record (the author once, the responsible person as the
 *       participant {@code R}; the executors and observers are written by the runtime in the participants table), the
 *       people who join or leave, the members of a task whose deadline moved or whose status changed are told, and the
 *       task is indexed again for the search.
 * </ul>
 */
@Component
public class MsTaskHooks implements EntityHooks {

    private final MdScopeService scopes;
    private final MsTaskTreeRepository tree;
    private final MsTaskMemberService members;
    private final SearchChangePublisher search;

    public MsTaskHooks(
            MdScopeService scopes,
            MsTaskTreeRepository tree,
            MsTaskMemberService members,
            SearchChangePublisher search) {
        this.scopes = scopes;
        this.tree = tree;
        this.members = members;
        this.search = search;
    }

    @Override
    public String entity() {
        return MsTaskEntity.CODE;
    }

    @Override
    public void beforeSave(EntitySave save) {
        long actor = save.actor().userId();
        EntityValues values = save.values();
        Long responsible = values.ref("responsibleId");
        if (responsible != null && save.changed("responsibleId")) {
            requireVisible(save, actor, "responsibleId", List.of(responsible));
        }
        for (String key : List.of("executorIds", "observerIds")) {
            if (save.changed(key)) requireVisible(save, actor, key, values.refs(key));
        }
        Long parent = values.ref("parentTaskId");
        Long id = save.id();
        if (parent != null
                && id != null
                && save.changed("parentTaskId")
                && (parent.equals(id) || tree.isDescendantOf(parent, id))) {
            save.reject("parentTaskId", "cycle", "error.task.parent_cycle", Map.of());
        }
        if (save.operation() == EntityOperation.CREATE) {
            search.lockStatusMembership(Objects.requireNonNull(values.text("statusCode")));
        }
    }

    @Override
    public void afterSave(EntitySave save) {
        long id = Objects.requireNonNull(save.id());
        long actor = save.actor().userId();
        EntityValues now = save.values();
        EntityValues was = save.before();
        String title = Objects.requireNonNull(now.text("title"));
        if (save.operation() == EntityOperation.CREATE) {
            members.joinAuthor(id, Objects.requireNonNull(now.ref("reporterId")));
        }
        if (save.changed("responsibleId")) {
            members.followResponsible(
                    id, title, was == null ? null : was.ref("responsibleId"), now.ref("responsibleId"), actor);
        }
        tellList(save, id, title, "executorIds", MsTaskPref.INVOLVE_EXECUTOR, actor);
        tellList(save, id, title, "observerIds", MsTaskPref.INVOLVE_OBSERVER, actor);
        if (was != null && save.changed("endTime")) {
            members.tellDeadline(id, title, instant(was.text("endTime")), instant(now.text("endTime")), actor);
        }
        if (was != null && save.changed("statusCode")) {
            members.tellStatus(id, title, Objects.requireNonNull(now.text("statusCode")), actor);
        }
        search.changed("TASK", id);
    }

    private void tellList(EntitySave save, long id, String title, String key, String kind, long actor) {
        if (!save.changed(key)) return;
        EntityValues was = save.before();
        members.tellListChange(
                id,
                title,
                was == null ? List.of() : was.refs(key),
                save.values().refs(key),
                kind,
                actor);
    }

    /** Refuses on the field each new participant the author may not see, as a missing user. */
    private void requireVisible(EntitySave save, long actor, String key, List<Long> userIds) {
        for (Long userId : userIds) {
            if (!scopes.canAccessUser(actor, userId)) {
                save.reject(key, "not_found", "error.task.assignee_unavailable", Map.of());
                return;
            }
        }
    }

    /** A moment of the record as the runtime reads it, ISO text with an offset; null without one. */
    private static @Nullable Instant instant(@Nullable String text) {
        return text == null || text.isBlank() ? null : Instant.parse(text.strip());
    }
}
