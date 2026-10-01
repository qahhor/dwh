package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.core.pagination.KeysetPage;
import com.smartup24.cms.instance.common.query.QueryCompiler;
import com.smartup24.cms.instance.common.query.QueryListRegistry;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.md.api.MdUserDtos.UserListFilters;
import com.smartup24.cms.instance.md.repository.MdRoleRepository;
import com.smartup24.cms.instance.md.repository.MdUserListSql;
import com.smartup24.cms.instance.md.repository.MdUserListSql.LegacyUserFilters;
import com.smartup24.cms.instance.md.repository.MdUserRepository;
import com.smartup24.cms.instance.md.repository.MdUserRepository.UserRecord;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pages of the user list through the registry ({@code iam.users}): filter, sort and search {@code q}, narrowed by
 * the viewer's data scope (ADR-0013) and the flat filters the list took before.
 */
@Service
public class MdUserListService {

    private final QueryListRepository lists;
    private final MdUserRepository userRepository;
    private final MdScopeService scopeService;
    private final MdRoleRepository roleRepository;
    private final QueryListRegistry registry;

    @Autowired
    public MdUserListService(
            QueryListRepository lists,
            MdUserRepository userRepository,
            MdScopeService scopeService,
            MdRoleRepository roleRepository,
            QueryListRegistry registry) {
        this.lists = lists;
        this.userRepository = userRepository;
        this.scopeService = scopeService;
        this.roleRepository = roleRepository;
        this.registry = registry;
    }

    /** Without the registry: the declared fields only, no custom fields. */
    public MdUserListService(
            QueryListRepository lists,
            MdUserRepository userRepository,
            MdScopeService scopeService,
            MdRoleRepository roleRepository) {
        this(lists, userRepository, scopeService, roleRepository, null);
    }

    /** {@link #pageViews(Long, Integer, String, String, String, String, LegacyUserFilters)} for the API filters. */
    @Transactional(readOnly = true)
    public KeysetPage<MdUserView> pageViews(
            Long viewerId,
            Integer limit,
            String cursor,
            String filter,
            String sort,
            String search,
            UserListFilters legacy) {
        return pageViews(
                viewerId,
                limit,
                cursor,
                filter,
                sort,
                search,
                new LegacyUserFilters(legacy.state(), legacy.roleId(), legacy.managerId(), legacy.is2faEnabled()));
    }

    /** The page as the API answers it: safe views with each user's roles. The screen and the export share it. */
    @Transactional(readOnly = true)
    public KeysetPage<MdUserView> pageViews(
            Long viewerId,
            Integer limit,
            String cursor,
            String filter,
            String sort,
            String search,
            LegacyUserFilters legacy) {
        KeysetPage<UserRecord> page = page(viewerId, limit, cursor, filter, sort, search, legacy);
        var roles = roleRepository.getUsersRoleIds(
                page.items().stream().map(UserRecord::id).toList());
        return page.map(u -> MdUserView.from(u, roles.getOrDefault(u.id(), List.of())));
    }

    /**
     * @param search free search {@code q}; the old {@code search} parameter is its alias
     * @param legacy the old flat filters; they narrow the list and are part of the cursor's fingerprint
     */
    @Transactional(readOnly = true)
    public KeysetPage<UserRecord> page(
            Long viewerId,
            Integer limit,
            String cursor,
            String filter,
            String sort,
            String search,
            LegacyUserFilters legacy) {
        var list = registry == null ? MdUserQuery.LIST : registry.resolve(MdUserQuery.LIST);
        var plan = QueryCompiler.compile(list, filter, sort, limit, cursor, search, legacy.canonical());
        var scope = scopeService.filterForUsers(viewerId, "md_users.id");
        return lists.page(plan, userRepository::mapUser, MdUserListSql.listPredicate(scope, legacy));
    }
}
