package com.smartup24.cms.instance.ms.task;

import static org.mockito.Mockito.mock;

import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.md.service.MdCustomFieldService;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.controller.MsTaskController;
import com.smartup24.cms.instance.ms.task.controller.MsTaskFileController;
import com.smartup24.cms.instance.ms.task.controller.MsTaskStatusController;
import com.smartup24.cms.instance.ms.task.repository.MsProjectRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskFileRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskMemberRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatusRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskTreeRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskTypeRepository;
import com.smartup24.cms.instance.ms.task.service.MsTaskAccess;
import com.smartup24.cms.instance.ms.task.service.MsTaskAuditTrail;
import com.smartup24.cms.instance.ms.task.service.MsTaskBulkService;
import com.smartup24.cms.instance.ms.task.service.MsTaskFileService;
import com.smartup24.cms.instance.ms.task.service.MsTaskListService;
import com.smartup24.cms.instance.ms.task.service.MsTaskMemberService;
import com.smartup24.cms.instance.ms.task.service.MsTaskReadService;
import com.smartup24.cms.instance.ms.task.service.MsTaskService;
import com.smartup24.cms.instance.ms.task.service.MsTaskStatusService;
import com.smartup24.cms.instance.ms.task.service.MsTaskStatusViewService;
import com.smartup24.cms.instance.ms.task.service.MsTaskWorkflowService;
import com.smartup24.cms.instance.search.SearchChangePublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The task services wired by hand for tests that run without a Spring context, the way the application context wires
 * them. Each service goes through {@link Proxy}, so a test decides how transactions wrap it.
 */
public record MsTaskFixture(
        MsTaskService tasks,
        MsTaskReadService reads,
        MsTaskMemberService members,
        MsTaskFileService files,
        MsTaskWorkflowService workflow,
        MsTaskStatusService statuses,
        MsTaskStatusViewService statusViews,
        MsTaskBulkService bulk) {

    /** Wraps a service, e.g. in a transaction proxy; {@link #NO_PROXY} leaves it as it is. */
    public interface Proxy {
        <T> T wrap(T target);
    }

    public static final Proxy NO_PROXY = new Proxy() {
        @Override
        public <T> T wrap(T target) {
            return target;
        }
    };

    /** The repositories of the task module. */
    public record Repositories(
            MsTaskRepository tasks,
            MsTaskTreeRepository tree,
            MsTaskFileRepository files,
            MsTaskStatsRepository stats,
            MsTaskStatusRepository statuses,
            MsTaskTypeRepository types,
            MsTaskMemberRepository members,
            MsProjectRepository projects) {

        public static Repositories jdbc(JdbcClient jdbc, ObjectMapper mapper) {
            return jdbc(new MsTaskRepository(jdbc, mapper), new MsTaskStatusRepository(jdbc), jdbc, mapper);
        }

        /** Real repositories, with the given task and status repositories (a test that keeps its own). */
        public static Repositories jdbc(
                MsTaskRepository tasks, MsTaskStatusRepository statuses, JdbcClient jdbc, ObjectMapper mapper) {
            return new Repositories(
                    tasks,
                    new MsTaskTreeRepository(jdbc, mapper),
                    new MsTaskFileRepository(jdbc),
                    new MsTaskStatsRepository(jdbc),
                    statuses,
                    new MsTaskTypeRepository(jdbc),
                    new MsTaskMemberRepository(jdbc),
                    new MsProjectRepository(jdbc, mapper));
        }

        public static Repositories mocks() {
            return new Repositories(
                    mock(MsTaskRepository.class),
                    mock(MsTaskTreeRepository.class),
                    mock(MsTaskFileRepository.class),
                    mock(MsTaskStatsRepository.class),
                    mock(MsTaskStatusRepository.class),
                    mock(MsTaskTypeRepository.class),
                    mock(MsTaskMemberRepository.class),
                    mock(MsProjectRepository.class));
        }
    }

    /** Collaborators outside the task module. */
    public record Collaborators(
            MdCustomFieldService customFields,
            MdScopeService scopes,
            MfFileService files,
            ApplicationEventPublisher events,
            SearchChangePublisher search,
            AuditLogService audit) {

        /** Everything mocked except the data scope, the search publisher and the audit a test cares about. */
        public static Collaborators with(MdScopeService scopes, SearchChangePublisher search, AuditLogService audit) {
            return new Collaborators(
                    mock(MdCustomFieldService.class),
                    scopes,
                    mock(MfFileService.class),
                    mock(ApplicationEventPublisher.class),
                    search,
                    audit);
        }
    }

    public static MsTaskFixture wire(Repositories repos, Collaborators with, Proxy proxy) {
        var access = new MsTaskAccess(repos.tasks(), repos.projects(), with.scopes());
        var audit = new MsTaskAuditTrail(with.audit());
        var statuses =
                proxy.wrap(new MsTaskStatusService(repos.statuses(), repos.types(), with.search(), with.audit()));
        var members = proxy.wrap(new MsTaskMemberService(repos.members(), access, with.events()));
        var files = proxy.wrap(new MsTaskFileService(repos.files(), access, with.files()));
        var tasks = proxy.wrap(new MsTaskService(
                repos.tasks(),
                repos.tree(),
                with.customFields(),
                access,
                members,
                statuses,
                with.events(),
                with.search(),
                audit));
        var reads =
                proxy.wrap(new MsTaskReadService(access, repos.tree(), repos.stats(), with.scopes(), members, files));
        var workflow = proxy.wrap(new MsTaskWorkflowService(
                access, repos.tasks(), repos.statuses(), members, with.events(), with.search(), audit));
        var statusViews = proxy.wrap(new MsTaskStatusViewService(statuses));
        // Not proxied: the bulk run is not transactional, each item runs on its own.
        var bulk = new MsTaskBulkService(tasks, workflow, statuses, null);
        return new MsTaskFixture(tasks, reads, members, files, workflow, statuses, statusViews, bulk);
    }

    /** The task controllers for a standalone MockMvc. */
    public Object[] controllers(MsTaskListService list) {
        return new Object[] {
            new MsTaskController(tasks, reads, list, members, workflow, bulk),
            new MsTaskStatusController(statusViews),
            new MsTaskFileController(files)
        };
    }
}
