package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.instance.common.query.QueryField;
import com.greenwhite.dwh.instance.common.query.QueryFieldType;
import com.greenwhite.dwh.instance.common.query.QueryList;
import com.greenwhite.dwh.instance.md.pref.MdPref;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * The user list in the field registry (ADR-0016, roadmap item 48): {@code GET /api/v1/iam/users} and
 * {@code /api/v1/query-meta/iam.users}. Sorted by name by default, 20 rows a page as before. Login and phone
 * are shown inside the user and contacts cells, so they are filters and search fields without a column of their own.
 */
@Configuration
public class MdUserQuery {

    public static final QueryList LIST = new QueryList(
            "iam.users",
            MdPref.FORM_USERS,
            "view",
            MdUserRepository.LIST_COLUMNS,
            "md_users",
            "md_users.id",
            List.of(
                    QueryField.of("name", "iam.users.col.name", QueryFieldType.TEXT, "md_users.name")
                            .asSortable().asSearchable(),
                    QueryField.of("login", "iam.users.col.login", QueryFieldType.TEXT, "md_users.login")
                            .asSortable().asSearchable().asHidden(),
                    QueryField.of("email", "iam.users.col.email", QueryFieldType.TEXT, "md_users.email")
                            .asSortable().asSearchable(),
                    QueryField.of("phone", "iam.users.col.phone", QueryFieldType.TEXT, "md_users.phone")
                            .asNullable().asSearchable().asHidden(),
                    QueryField.enumeration("state", "iam.users.col.state", "md_users.state",
                            List.of("A", "P"), "iam.users.state."),
                    QueryField.of("is2faEnabled", "iam.users.col.two_factor", QueryFieldType.BOOLEAN,
                            "md_users.is_2fa_enabled"),
                    QueryField.of("createdAt", "iam.users.col.created_at", QueryFieldType.INSTANT,
                            "md_users.created_at").asSortable()),
            "name",
            false,
            20,
            QueryList.MAX_LIMIT);

    @Bean
    public QueryList iamUsersQueryList() {
        return LIST;
    }
}
