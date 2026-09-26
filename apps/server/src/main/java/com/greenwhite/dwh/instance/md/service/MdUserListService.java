package com.greenwhite.dwh.instance.md.service;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import com.greenwhite.dwh.instance.common.query.QueryListRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository.LegacyUserFilters;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository.UserRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    public MdUserListService(QueryListRepository lists, MdUserRepository userRepository, MdScopeService scopeService,
                             MdRoleRepository roleRepository) {
        this.lists = lists;
        this.userRepository = userRepository;
        this.scopeService = scopeService;
        this.roleRepository = roleRepository;
    }

    /** The page as the API answers it: safe views with each user's roles. The screen and the export share it. */
    @Transactional(readOnly = true)
    public KeysetPage<MdUserView> pageViews(Long viewerId, Integer limit, String cursor, String filter, String sort,
                                            String search, LegacyUserFilters legacy) {
        KeysetPage<UserRecord> page = page(viewerId, limit, cursor, filter, sort, search, legacy);
        var roles = roleRepository.getUsersRoleIds(page.items().stream().map(UserRecord::id).toList());
        return new KeysetPage<>(
                page.items().stream().map(u -> MdUserView.from(u, roles.getOrDefault(u.id(), List.of()))).toList(),
                page.nextCursor(), page.hasMore(), page.totalEstimated());
    }

    /**
     * @param search free search {@code q}; the old {@code search} parameter is its alias
     * @param legacy the old flat filters; they narrow the list and are part of the cursor's fingerprint
     */
    @Transactional(readOnly = true)
    public KeysetPage<UserRecord> page(Long viewerId, Integer limit, String cursor, String filter, String sort,
                                       String search, LegacyUserFilters legacy) {
        var plan = QueryCompiler.compile(MdUserQuery.LIST, filter, sort, limit, cursor, search, legacy.canonical());
        var scope = scopeService.filterFor(viewerId, "md_users.org_unit_id", "md_users.id");
        return lists.page(plan, userRepository::mapUser, MdUserRepository.listPredicate(scope, legacy));
    }
}
