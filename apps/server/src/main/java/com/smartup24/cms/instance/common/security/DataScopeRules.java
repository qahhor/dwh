package com.smartup24.cms.instance.common.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The caller's effective data-scope rule (ADR-0013, 2.2-2.3) for code that may not depend on the md module, the way
 * {@link RoleMembershipAuthorizer} reads role membership. It reads {@code md_user_scope.rule} exactly as md's own
 * scope service does: a user without a materialized row has the rule {@code ALL}, so every scoped list and this check
 * agree on who is unrestricted.
 */
@Component
public class DataScopeRules {

    /** The rule that does not restrict rows (ADR-0013, 2.2). */
    public static final String RULE_ALL = "ALL";

    private final JdbcClient jdbcClient;

    public DataScopeRules(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Whether the user's effective rule is {@code ALL}: every row of every scoped entity is visible to them. */
    public boolean isUnrestricted(Long userId) {
        if (userId == null) {
            return false;
        }
        String rule = jdbcClient
                .sql("select rule from md_user_scope where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse(RULE_ALL);
        return RULE_ALL.equals(rule);
    }
}
