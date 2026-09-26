package com.greenwhite.dwh.instance.ms.task.service;

import com.greenwhite.dwh.core.pagination.KeysetPage;
import com.greenwhite.dwh.instance.common.query.QueryCompiler;
import com.greenwhite.dwh.instance.common.query.QueryListRepository;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository.LegacyTaskFilters;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pages of the task list through the registry ({@code ms.tasks}): filter, sort and search {@code q}, narrowed by
 * the viewer's data scope (ADR-0013) and the flat filters the list took before.
 */
@Service
public class MsTaskListService {

    private final QueryListRepository lists;
    private final MsTaskRepository taskRepository;
    private final MdScopeService scopeService;
    private final com.greenwhite.dwh.instance.common.query.QueryListRegistry registry;

    @org.springframework.beans.factory.annotation.Autowired
    public MsTaskListService(QueryListRepository lists, MsTaskRepository taskRepository, MdScopeService scopeService,
                             com.greenwhite.dwh.instance.common.query.QueryListRegistry registry) {
        this.lists = lists;
        this.taskRepository = taskRepository;
        this.scopeService = scopeService;
        this.registry = registry;
    }

    /** Without the registry: the declared fields only, no custom fields. */
    public MsTaskListService(QueryListRepository lists, MsTaskRepository taskRepository, MdScopeService scopeService) {
        this(lists, taskRepository, scopeService, null);
    }

    /**
     * @param search free search {@code q} in title and description; the old {@code search} parameter is its alias
     * @param legacy the old flat filters; they narrow the list and are part of the cursor's fingerprint
     */
    @Transactional(readOnly = true)
    public KeysetPage<TaskRecord> page(Long viewerId, Integer limit, String cursor, String filter, String sort,
                                       String search, LegacyTaskFilters legacy) {
        var list = registry == null ? MsTaskQuery.LIST : registry.resolve(MsTaskQuery.LIST);
        var plan = QueryCompiler.compile(list, filter, sort, limit, cursor, search, legacy.canonical());
        return lists.page(plan, taskRepository::mapRecord,
                MsTaskRepository.listPredicate(scopeService.filterForTasks(viewerId), legacy));
    }
}
