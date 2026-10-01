package com.smartup24.cms.instance.common.security;

import org.jspecify.annotations.Nullable;

/**
 * Restricts selected rows by data scope (ADR-0013).
 *
 * Returned as a ready SQL fragment, not as a list of ids: filtering after
 * the query breaks pagination (a 50-row page gets shorter after filtering)
 * and counters, and on large data marts it also hurts performance.
 *
 * The fragment is appended to a query of the form {@code ... where 1=1}, so it
 * always starts with {@code and}. An empty fragment means "no restriction"
 * (rule ALL); that is the most frequent case and it costs nothing.
 */
public record ScopeFilter(
        String sql, boolean bindsUserId, @Nullable Long userId) {

    private static final ScopeFilter UNRESTRICTED = new ScopeFilter("", false, null);

    /** Rule ALL: the query is unchanged. */
    public static ScopeFilter unrestricted() {
        return UNRESTRICTED;
    }

    /** Rules SUBTREE and UNITS: a row is visible if its unit is materialized in the scope. */
    public static ScopeFilter byOrgUnit(String orgUnitColumn, Long userId) {
        return new ScopeFilter(
                " and " + orgUnitColumn + " in ("
                        + "select org_unit_id from md_effective_scope where user_id = :scopeUserId)",
                true,
                userId);
    }

    /** Rule SELF: only the user's own rows are visible. */
    public static ScopeFilter byOwner(String ownerColumn, Long userId) {
        return new ScopeFilter(" and " + ownerColumn + " = :scopeUserId", true, userId);
    }

    /**
     * SELF for tasks: creator/reporter and every explicit membership kind are
     * participants. The task alias is intentionally fixed to {@code t}; this
     * keeps one reviewed authorization predicate shared by every repository
     * query instead of duplicating subtly different versions of it.
     */
    public static ScopeFilter taskSelf(Long userId) {
        return scoped("""
                 and (
                      t.created_by = :scopeUserId
                   or t.reporter_id = :scopeUserId
                   or exists (
                        select 1
                        from ms_task_members scope_tm
                        where scope_tm.task_id = t.id
                          and scope_tm.user_id = :scopeUserId
                   )
                 )
                """, userId);
    }

    /**
     * UNITS/SUBTREE for tasks: at least one participant belongs to a visible
     * organization unit. Both the legacy primary unit and the many-to-many
     * assignment are honoured while the former still exists in the schema.
     */
    public static ScopeFilter taskByParticipantOrgUnit(Long userId) {
        return scoped("""
                 and exists (
                     select 1
                     from (
                         select t.created_by as user_id
                         union
                         select t.reporter_id
                         union
                         select scope_tm.user_id
                         from ms_task_members scope_tm
                         where scope_tm.task_id = t.id
                     ) scope_participant
                     join md_users scope_u on scope_u.id = scope_participant.user_id
                     where scope_u.org_unit_id in (
                               select org_unit_id
                               from md_effective_scope
                               where user_id = :scopeUserId
                           )
                        or exists (
                               select 1
                               from md_user_org_units scope_uou
                               join md_effective_scope scope_es
                                 on scope_es.org_unit_id = scope_uou.org_unit_id
                                and scope_es.user_id = :scopeUserId
                               where scope_uou.user_id = scope_participant.user_id
                           )
                 )
                """, userId);
    }

    /** SELF for files: own upload or attachment to a visible task/comment. */
    public static ScopeFilter fileSelf(Long userId) {
        return scoped("""
                 and (
                      f.created_by = :scopeUserId
                   or exists (
                        select 1
                        from ms_task_files scope_tf
                        join ms_tasks t on t.id = scope_tf.task_id
                        where scope_tf.file_id = f.id
                          and (
                               t.created_by = :scopeUserId
                            or t.reporter_id = :scopeUserId
                            or exists (
                                 select 1
                                 from ms_task_members scope_tm
                                 where scope_tm.task_id = t.id
                                   and scope_tm.user_id = :scopeUserId
                            )
                          )
                   )
                   or exists (
                        select 1
                        from ms_task_comment_files scope_cf
                        join ms_task_comments scope_c on scope_c.id = scope_cf.comment_id
                        join ms_tasks t on t.id = scope_c.task_id
                        where scope_cf.file_id = f.id
                          and (
                               t.created_by = :scopeUserId
                            or t.reporter_id = :scopeUserId
                            or exists (
                                 select 1
                                 from ms_task_members scope_tm
                                 where scope_tm.task_id = t.id
                                   and scope_tm.user_id = :scopeUserId
                            )
                          )
                   )
                 )
                """, userId);
    }

    /** The file's author is in the viewer's scope, by home unit or by an additional unit. */
    private static final String FILE_OWNER_IN_SCOPE = """
            exists (
                select 1
                from md_users scope_owner
                where scope_owner.id = f.created_by
                  and (
                       scope_owner.org_unit_id in (
                           select org_unit_id
                           from md_effective_scope
                           where user_id = :scopeUserId
                       )
                    or exists (
                           select 1
                           from md_user_org_units scope_owner_uou
                           join md_effective_scope scope_owner_es
                             on scope_owner_es.org_unit_id = scope_owner_uou.org_unit_id
                            and scope_owner_es.user_id = :scopeUserId
                           where scope_owner_uou.user_id = scope_owner.id
                       )
                  )
            )
            """;

    /** A participant of task {@code t} (author, reporter or member) is in the viewer's scope. */
    private static final String TASK_PARTICIPANT_IN_SCOPE = """
            exists (
                select 1
                from (
                    select t.created_by as user_id
                    union
                    select t.reporter_id
                    union
                    select scope_tm.user_id
                    from ms_task_members scope_tm
                    where scope_tm.task_id = t.id
                ) scope_participant
                join md_users scope_u on scope_u.id = scope_participant.user_id
                where scope_u.org_unit_id in (
                          select org_unit_id
                          from md_effective_scope
                          where user_id = :scopeUserId
                      )
                   or exists (
                          select 1
                          from md_user_org_units scope_uou
                          join md_effective_scope scope_es
                            on scope_es.org_unit_id = scope_uou.org_unit_id
                           and scope_es.user_id = :scopeUserId
                          where scope_uou.user_id = scope_participant.user_id
                      )
            )
            """;

    /** The file is attached to a task {@code t} with a participant in the viewer's scope. */
    private static final String TASK_FILE = """
            exists (
                select 1
                from ms_task_files scope_tf
                join ms_tasks t on t.id = scope_tf.task_id
                where scope_tf.file_id = f.id
                  and
            """;

    /** The file is attached to a comment of such a task. */
    private static final String COMMENT_FILE = """
            exists (
                select 1
                from ms_task_comment_files scope_cf
                join ms_task_comments scope_c on scope_c.id = scope_cf.comment_id
                join ms_tasks t on t.id = scope_c.task_id
                where scope_cf.file_id = f.id
                  and
            """;

    /** UNITS/SUBTREE for files: scoped owner or attachment to a scoped task (directly or through a comment). */
    public static ScopeFilter fileByOwnerOrTaskOrgUnit(Long userId) {
        return scoped(
                " and (" + FILE_OWNER_IN_SCOPE
                        + " or " + TASK_FILE + TASK_PARTICIPANT_IN_SCOPE + ")"
                        + " or " + COMMENT_FILE + TASK_PARTICIPANT_IN_SCOPE + ")"
                        + ")",
                userId);
    }

    /**
     * UNITS/SUBTREE for users: the user's home unit or one of their additional units is in the viewer's scope,
     * the rule {@code MdScopeService.canAccessUser} answers for one user. The list and the card share it, so a user
     * the list hides is missing by id too (ADR-0013, 404 rather than 403).
     */
    public static ScopeFilter userByOrgUnit(String userIdColumn, Long userId) {
        return scoped(" and " + userInScope(userIdColumn), userId);
    }

    /**
     * SELF for projects (alias {@code p}): the viewer created the project, is its member, or takes part in one of
     * its tasks.
     */
    public static ScopeFilter projectSelf(Long userId) {
        return scoped(
                " and (p.created_by = :scopeUserId"
                        + " or exists (select 1 from ms_task_project_members scope_pm"
                        + " where scope_pm.project_id = p.id and scope_pm.user_id = :scopeUserId)"
                        + " or exists (select 1 from ms_tasks t where t.project_id = p.id"
                        + taskSelf(userId).sql() + "))",
                userId);
    }

    /**
     * UNITS/SUBTREE for projects (alias {@code p}): the creator or a member is in the viewer's scope, or one of its
     * tasks is visible by participation.
     */
    public static ScopeFilter projectByParticipantOrgUnit(Long userId) {
        return scoped(
                " and (" + userInScope("p.created_by")
                        + " or exists (select 1 from ms_task_project_members scope_pm"
                        + " where scope_pm.project_id = p.id and " + userInScope("scope_pm.user_id") + ")"
                        + " or exists (select 1 from ms_tasks t where t.project_id = p.id and "
                        + TASK_PARTICIPANT_IN_SCOPE + "))",
                userId);
    }

    /** The user named by {@code userIdColumn} has a home or an additional unit in the viewer's scope. */
    private static String userInScope(String userIdColumn) {
        return """
                exists (
                    select 1
                    from md_users scope_su
                    where scope_su.id = %s
                      and (
                           scope_su.org_unit_id in (
                               select org_unit_id
                               from md_effective_scope
                               where user_id = :scopeUserId
                           )
                        or exists (
                               select 1
                               from md_user_org_units scope_suou
                               join md_effective_scope scope_ses
                                 on scope_ses.org_unit_id = scope_suou.org_unit_id
                                and scope_ses.user_id = :scopeUserId
                               where scope_suou.user_id = scope_su.id
                           )
                      )
                )
                """.formatted(userIdColumn);
    }

    private static ScopeFilter scoped(String sql, Long userId) {
        return new ScopeFilter(sql, true, userId);
    }

    public boolean isUnrestricted() {
        return sql.isEmpty();
    }
}
