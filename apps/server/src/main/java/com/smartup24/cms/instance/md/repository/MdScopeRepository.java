package com.smartup24.cms.instance.md.repository;

import com.smartup24.cms.instance.common.web.Revisions;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Data scope: a role's visibility rule, the user's position in the tree and
 * materialization of the effective scope (ADR-0013).
 *
 * Materialization deliberately mirrors the md_effective_permissions mechanism:
 * the "is the unit visible" check goes into every list query, and a recursive
 * tree walk on every query is the case where the correct model
 * kills responsiveness.
 */
@Repository
public class MdScopeRepository {

    /** Rule breadth order: the larger, the wider the visibility. */
    private static final List<String> RULES_WIDEST_FIRST = List.of("ALL", "SUBTREE", "UNITS", "SELF");

    private final JdbcClient jdbcClient;

    public MdScopeRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Database-local IAM scope writer ordering; released by the caller's transaction. */
    public void lockScopeMutation() {
        jdbcClient
                .sql("select pg_advisory_xact_lock(129632, 1)")
                .query((rs, rowNum) -> true)
                .single();
    }

    // ----------------------------------------------------------------- roles

    public void setRoleRule(Long roleId, String rule) {
        jdbcClient.sql("""
                        insert into md_role_scope_rules (role_id, rule, modified_at)
                        values (:roleId, :rule, now())
                        on conflict (role_id) do update set rule = excluded.rule, modified_at = now()
                        """).param("roleId", roleId).param("rule", rule).update();
    }

    public String getRoleRule(Long roleId) {
        return jdbcClient
                .sql("select rule from md_role_scope_rules where role_id = :roleId")
                .param("roleId", roleId)
                .query(String.class)
                .optional()
                .orElse("ALL");
    }

    /** The revision of the role, which a change of its scope rule names (plan 10/10, item 3.6). */
    public long roleRevision(Long roleId) {
        return jdbcClient
                .sql("select revision from md_roles where id = :roleId")
                .param("roleId", roleId)
                .query(Long.class)
                .single();
    }

    /**
     * Claims the next revision of a role for a change of its scope rule: the rule is part of the role, like its
     * rights, so a change made from an older revision is refused (plan 10/10, item 3.6).
     */
    public long nextRoleRevision(Long roleId, long expectedRevision) {
        return jdbcClient
                .sql("""
                update md_roles set modified_at = now(), revision = revision + 1
                where id = :roleId and revision = :expectedRevision
                returning revision
                """)
                .param("roleId", roleId)
                .param("expectedRevision", expectedRevision)
                .query(Long.class)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    public boolean roleExists(Long roleId) {
        return jdbcClient
                .sql("select exists (select 1 from md_roles where id = :roleId)")
                .param("roleId", roleId)
                .query(Boolean.class)
                .single();
    }

    // ------------------------------------------------------------------ user

    /** The revision of the user, which a change of the user's org units names (plan 10/10, item 3.6). */
    public long userRevision(Long userId) {
        return jdbcClient
                .sql("select revision from md_users where id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /**
     * Claims the next revision of a user for a change of the user's org units: they are part of the user, like the
     * roles, so a change made from an older revision is refused (plan 10/10, item 3.6).
     */
    public long nextUserRevision(Long userId, long expectedRevision) {
        return jdbcClient
                .sql("""
                update md_users set modified_at = now(), revision = revision + 1
                where id = :userId and revision = :expectedRevision
                returning revision
                """)
                .param("userId", userId)
                .param("expectedRevision", expectedRevision)
                .query(Long.class)
                .optional()
                .orElseThrow(Revisions::conflict);
    }

    public Set<Long> getUserOrgUnitIds(Long userId) {
        return Set.copyOf(jdbcClient
                .sql("select org_unit_id from md_user_org_units where user_id = :userId order by org_unit_id")
                .param("userId", userId)
                .query(Long.class)
                .list());
    }

    /** Replaces the user's whole set of units, with the same PUT semantics as for roles. */
    public void replaceUserOrgUnits(Long userId, List<Long> orgUnitIds) {
        jdbcClient
                .sql("delete from md_user_org_units where user_id = :userId")
                .param("userId", userId)
                .update();
        for (Long unitId : orgUnitIds) {
            jdbcClient.sql("""
                            insert into md_user_org_units (user_id, org_unit_id)
                            values (:userId, :unitId)
                            on conflict do nothing
                            """).param("userId", userId).param("unitId", unitId).update();
        }
    }

    public String getUserRule(Long userId) {
        return jdbcClient
                .sql("select rule from md_user_scope where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse("ALL");
    }

    public Set<Long> getEffectiveScope(Long userId) {
        return Set.copyOf(jdbcClient
                .sql("select org_unit_id from md_effective_scope where user_id = :userId order by org_unit_id")
                .param("userId", userId)
                .query(Long.class)
                .list());
    }

    // ------------------------------------------------------- materialization

    /**
     * Recalculates the effective scope. Called in the SAME transaction as the
     * change of roles, position or tree; otherwise there is a window between the change
     * and the recalculation in which data access is wrong.
     *
     * @return the rule the user ended up with
     */
    public String recalculateEffectiveScope(Long userId) {
        String rule = resolveWidestRule(userId);

        jdbcClient.sql("""
                        insert into md_user_scope (user_id, rule, recalculated_at)
                        values (:userId, :rule, now())
                        on conflict (user_id) do update set rule = excluded.rule, recalculated_at = now()
                        """).param("userId", userId).param("rule", rule).update();

        jdbcClient
                .sql("delete from md_effective_scope where user_id = :userId")
                .param("userId", userId)
                .update();

        // ALL restricts nothing and SELF does not rely on the tree, so there is nothing to materialize.
        switch (rule) {
            case "UNITS" -> jdbcClient.sql("""
                            insert into md_effective_scope (user_id, org_unit_id)
                            select uou.user_id, uou.org_unit_id
                            from md_user_org_units uou
                            join md_org_units u on u.id = uou.org_unit_id and u.state = 'A'
                            where uou.user_id = :userId
                            on conflict do nothing
                            """).param("userId", userId).update();

            // A passive unit cuts off the whole branch: the unit is off together with everything
            // under it, otherwise "turning off a branch office" would not turn off its departments.
            case "SUBTREE" -> jdbcClient.sql("""
                            with recursive subtree as (
                                select u.id
                                from md_org_units u
                                join md_user_org_units uou on uou.org_unit_id = u.id
                                where uou.user_id = :userId and u.state = 'A'
                                union
                                select c.id
                                from md_org_units c
                                join subtree s on c.parent_id = s.id
                                where c.state = 'A'
                            )
                            insert into md_effective_scope (user_id, org_unit_id)
                            select :userId, id from subtree
                            on conflict do nothing
                            """).param("userId", userId).update();

            default -> {
                /* ALL, SELF: no materialization needed */
            }
        }

        return rule;
    }

    /**
     * The widest rule among the user's active roles.
     * A role without an explicit rule counts as ALL: that is how the whole instance
     * behaves today, and narrowing must be a deliberate administrator action.
     */
    private String resolveWidestRule(Long userId) {
        List<String> rules =
                jdbcClient.sql("""
                        select coalesce(sr.rule, 'ALL')
                        from md_user_roles ur
                        join md_roles r on r.id = ur.role_id and r.state = 'A'
                        left join md_role_scope_rules sr on sr.role_id = r.id
                        where ur.user_id = :userId
                        """).param("userId", userId).query(String.class).list();

        if (rules.isEmpty()) {
            return "ALL";
        }
        return RULES_WIDEST_FIRST.stream().filter(rules::contains).findFirst().orElse("ALL");
    }

    /** The role's users: whose scope must be recalculated after its rule changes. */
    public List<Long> getUserIdsByRole(Long roleId) {
        return jdbcClient
                .sql("select user_id from md_user_roles where role_id = :roleId order by user_id")
                .param("roleId", roleId)
                .query(Long.class)
                .list();
    }

    /** Users assigned to this node, its descendants, or its ancestors (including managers). */
    public List<Long> getUserIdsAffectedByUnit(Long orgUnitId) {
        return jdbcClient.sql("""
                        with recursive subtree as (
                            select id from md_org_units where id = :unitId
                            union
                            select c.id from md_org_units c join subtree s on c.parent_id = s.id
                        ), ancestors as (
                            select id, parent_id from md_org_units where id = :unitId
                            union
                            select p.id, p.parent_id from md_org_units p join ancestors a on p.id = a.parent_id
                        ), affected_units as (
                            select id from subtree
                            union
                            select id from ancestors
                        )
                        select distinct uou.user_id
                        from md_user_org_units uou
                        join affected_units s on s.id = uou.org_unit_id
                        order by uou.user_id
                        """).param("unitId", orgUnitId).query(Long.class).list();
    }

    public Optional<Long> findUserOrgUnit(Long userId) {
        return jdbcClient
                .sql("select org_unit_id from md_users where id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .optional();
    }

    /** True when at least one target position intersects the viewer's materialized scope. */
    public boolean isUserInEffectiveScope(Long viewerId, Long targetUserId) {
        return jdbcClient
                .sql("""
                        select exists (
                            select 1
                            from md_users target
                            where target.id = :targetUserId
                              and (
                                   target.org_unit_id in (
                                       select org_unit_id
                                       from md_effective_scope
                                       where user_id = :viewerId
                                   )
                                or exists (
                                       select 1
                                       from md_user_org_units target_uou
                                       join md_effective_scope viewer_scope
                                         on viewer_scope.org_unit_id = target_uou.org_unit_id
                                        and viewer_scope.user_id = :viewerId
                                       where target_uou.user_id = target.id
                                   )
                              )
                        )
                        """)
                .param("viewerId", viewerId)
                .param("targetUserId", targetUserId)
                .query(Boolean.class)
                .single();
    }

    public boolean userExists(Long userId) {
        return jdbcClient
                .sql("select exists (select 1 from md_users where id = :userId)")
                .param("userId", userId)
                .query(Boolean.class)
                .single();
    }
}
