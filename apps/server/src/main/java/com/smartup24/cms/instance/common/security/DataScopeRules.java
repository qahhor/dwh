package com.smartup24.cms.instance.common.security;

import java.util.List;
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

    /** The rule of the viewer's own rows only (ADR-0013, 2.2). */
    public static final String RULE_SELF = "SELF";

    private final JdbcClient jdbcClient;

    public DataScopeRules(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * The user's effective rule and, for {@code SUBTREE}/{@code UNITS}, the units of their materialized scope
     * (ADR-0013, 2.3): what a search query filters the scope keys of the documents by (ADR-0032, 10.3).
     */
    public Viewer viewer(long userId) {
        String rule = jdbcClient
                .sql("select rule from md_user_scope where user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional()
                .orElse(RULE_ALL);
        List<Long> units = RULE_ALL.equals(rule) || RULE_SELF.equals(rule)
                ? List.of()
                : jdbcClient
                        .sql("select org_unit_id from md_effective_scope where user_id = :userId order by org_unit_id")
                        .param("userId", userId)
                        .query((rs, row) -> rs.getLong(1))
                        .list();
        return new Viewer(rule, units);
    }

    /**
     * A viewer's data scope: the rule ({@code ALL}, {@code SUBTREE}, {@code UNITS} or {@code SELF}) and the units it
     * opens.
     */
    public record Viewer(String rule, List<Long> units) {
        public Viewer {
            units = List.copyOf(units);
        }

        /** Whether the rule restricts no row. */
        public boolean unrestricted() {
            return RULE_ALL.equals(rule);
        }

        /** Whether only the viewer's own rows are visible. */
        public boolean self() {
            return RULE_SELF.equals(rule);
        }
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
