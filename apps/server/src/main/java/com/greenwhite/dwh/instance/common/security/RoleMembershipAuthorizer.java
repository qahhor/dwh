package com.greenwhite.dwh.instance.common.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RoleMembershipAuthorizer {

    private final JdbcClient jdbcClient;

    public RoleMembershipAuthorizer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean hasActiveRole(Long userId, String rolePcode) {
        return jdbcClient.sql("""
                select exists (
                    select 1
                    from md_user_roles ur
                    join md_roles r on r.id = ur.role_id
                    where ur.user_id = :userId
                      and r.pcode = :rolePcode
                      and r.state = 'A'
                )
                """)
                .param("userId", userId)
                .param("rolePcode", rolePcode)
                .query(Boolean.class)
                .single();
    }
}
