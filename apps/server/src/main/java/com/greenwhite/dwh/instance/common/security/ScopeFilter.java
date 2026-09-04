package com.greenwhite.dwh.instance.common.security;

/**
 * Ограничение выборки строк по скоупу данных (ADR-0013).
 *
 * Возвращается как готовый фрагмент SQL, а не как список идентификаторов:
 * фильтрация после выборки ломает пагинацию (страница в 50 строк после
 * фильтра станет короче) и счётчики, а на витринах Этапа 2 — ещё и скорость.
 *
 * Фрагмент дописывается к запросу вида {@code ... where 1=1}, поэтому всегда
 * начинается с {@code and}. Пустой фрагмент означает «ограничений нет»
 * (правило ALL) — это самый частый случай, и он не стоит ничего.
 */
public record ScopeFilter(String sql, boolean bindsUserId, Long userId) {

    private static final ScopeFilter UNRESTRICTED = new ScopeFilter("", false, null);

    /** Правило ALL: запрос не меняется. */
    public static ScopeFilter unrestricted() {
        return UNRESTRICTED;
    }

    /** Правила SUBTREE и UNITS: строка видна, если её узел материализован в скоупе. */
    public static ScopeFilter byOrgUnit(String orgUnitColumn, Long userId) {
        return new ScopeFilter(
                " and " + orgUnitColumn + " in ("
                        + "select org_unit_id from md_effective_scope where user_id = :scopeUserId)",
                true, userId);
    }

    /** Правило SELF: видны только собственные строки. */
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

    /** UNITS/SUBTREE for files: scoped owner or attachment to a scoped task. */
    public static ScopeFilter fileByOwnerOrTaskOrgUnit(Long userId) {
        return scoped("""
                 and (
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
                   or exists (
                        select 1
                        from ms_task_files scope_tf
                        join ms_tasks t on t.id = scope_tf.task_id
                        where scope_tf.file_id = f.id
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
                   )
                   or exists (
                        select 1
                        from ms_task_comment_files scope_cf
                        join ms_task_comments scope_c on scope_c.id = scope_cf.comment_id
                        join ms_tasks t on t.id = scope_c.task_id
                        where scope_cf.file_id = f.id
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
                   )
                 )
                """, userId);
    }

    private static ScopeFilter scoped(String sql, Long userId) {
        return new ScopeFilter(sql, true, userId);
    }

    public boolean isUnrestricted() {
        return sql.isEmpty();
    }
}
