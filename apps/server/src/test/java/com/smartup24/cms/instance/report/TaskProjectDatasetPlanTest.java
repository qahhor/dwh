package com.smartup24.cms.instance.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.common.entity.EntityScopes;
import com.smartup24.cms.instance.common.entity.report.EntityReportDatasets;
import com.smartup24.cms.instance.common.query.QueryAggregateRepository;
import com.smartup24.cms.instance.common.query.QueryAggregateResult.AggregateRow;
import com.smartup24.cms.instance.common.query.QueryAggregates;
import com.smartup24.cms.instance.common.query.QueryPlan;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.md.repository.MdScopeRepository;
import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.TestUsers;
import com.smartup24.cms.instance.support.TestUsers.TestUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Bounded plans on ten synthetic tasks establish scope correctness, not a capacity SLO (ADR-0039). */
class TaskProjectDatasetPlanTest extends EmbeddedPostgresTest {
    private static final Map<String, Set<String>> RIGHTS =
            Map.of("tasks.items", Set.of("view"), "tasks.projects", Set.of("view"));

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityReportDatasets datasets;

    @Autowired
    private EntityScopes entityScopes;

    @Autowired
    private MdScopeService scopes;

    @Autowired
    private MdScopeRepository scopeRepository;

    @Autowired
    private ModuleRegistryService modules;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ObjectMapper json;

    private TestUser viewer;
    private long childUnit;
    private long outsideUnit;
    private String tag;

    @BeforeEach
    void setup() {
        modules.toggleModuleStatus("tasks", true);
        tag = "dataset-plan-" + UUID.randomUUID();
        var users = TestUsers.of(context);
        long parentUnit = users.unit(tag);
        childUnit = jdbc.sql("insert into md_org_units(parent_id,code,name) values(:parent,:code,:name) returning id")
                .param("parent", parentUnit)
                .param("code", tag + "-child")
                .param("name", "TEST child")
                .query(Long.class)
                .single();
        outsideUnit = users.unit(tag + "-outside");
        viewer = users.withRights(RIGHTS, parentUnit);
        var insider = users.withRights(RIGHTS, childUnit);
        var outsider = users.withRights(RIGHTS, outsideUnit);
        fixture(insider.id(), outsider.id());
        assertThat(jdbc.sql("select count(*) from ms_tasks where title=:tag")
                        .param("tag", tag)
                        .query(Long.class)
                        .single())
                .isEqualTo(10L);
        assertThat(jdbc.sql("select count(*) from ms_task_projects where name like :tag")
                        .param("tag", tag + "%")
                        .query(Long.class)
                        .single())
                .isEqualTo(6L);
    }

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @ParameterizedTest
    @CsvSource({"SELF,4", "SUBTREE,6"})
    void actualTaskProjectScopeHasExactCountsAndABoundedPostgresPlan(String rule, long activeCount) throws Exception {
        setScope(rule);
        var source = datasets.resolve("ms.tasks", "ms.tasks.projects");
        String filter = json.writeValueAsString(List.of(Map.of("field", "title", "op", "eq", "value", tag)));
        var aggregate = QueryAggregates.compile(
                source.list(), "[{\"field\":\"projectCreatedAt\",\"trunc\":\"day\"}]", "[{\"op\":\"count\"}]", filter);
        var rootScope = entityScopes.listPredicate(source.entity(), aggregate.filter(), viewer.id());
        assertThat(source.params()).containsEntry(EntityScopes.USER_PARAM, viewer.id());
        assertThat(rootScope.params()).containsEntry(EntityScopes.USER_PARAM, viewer.id());
        var params = new LinkedHashMap<>(source.params());
        params.putAll(rootScope.params());
        var scope = new QueryPlan.SqlFragment(rootScope.sql(), params);
        String explain = new TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("set local statement_timeout = '5s'").update();
            var result = new QueryAggregateRepository(jdbc).run(aggregate, scope);
            assertThat(result.truncated()).isFalse();
            assertThat(result.rows())
                    .containsExactly(
                            new AggregateRow(List.of("2026-10-01"), List.of(activeCount)),
                            new AggregateRow(Arrays.asList((Object) null), List.of(2L)));
            var query = aggregate.sql(scope);
            return jdbc.sql("explain (analyze, buffers, format json) " + query.sql())
                    .params(query.params())
                    .query(String.class)
                    .single();
        });
        assertThat(explain).contains("ms_tasks", "ms_task_projects", "Actual Rows", "Shared Hit Blocks");
        if ("SUBTREE".equals(rule)) assertThat(explain).contains("md_effective_scope");
        var plan = json.readTree(explain).get(0);
        assertThat(plan.get("Plan").get("Actual Rows").asInt()).isEqualTo(2);
        assertThat(plan.has("Execution Time")).isTrue();
        Files.writeString(
                Path.of("target", "studio06-task-project-" + rule.toLowerCase(Locale.ROOT) + "-explain.json"), explain);
    }

    private void setScope(String rule) {
        long role = jdbc.sql("select role_id from md_user_roles where user_id=:viewer")
                .param("viewer", viewer.id())
                .query(Long.class)
                .single();
        scopeRepository.setRoleRule(role, rule);
        assertThat(scopes.recalculateFor(viewer.id())).isEqualTo(rule);
        if ("SUBTREE".equals(rule)) {
            assertThat(jdbc.sql("select org_unit_id from md_effective_scope where user_id=:viewer")
                            .param("viewer", viewer.id())
                            .query(Long.class)
                            .list())
                    .contains(viewer.unit(), childUnit)
                    .doesNotContain(outsideUnit);
        }
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                viewer.id(),
                viewer.login(),
                viewer.login() + "@test.local",
                viewer.unit(),
                false,
                Set.of("tasks.items.view", "tasks.projects.view", "md.profile.view"),
                1L,
                false,
                1L,
                null));
    }

    private void fixture(long insider, long outsider) {
        long own = project("own", viewer.id());
        long member = project("member", outsider);
        long participant = project("participant", outsider);
        long child = project("child", insider);
        long outside = project("outside", outsider);
        long archived = project("archived", viewer.id());
        jdbc.sql("update ms_task_projects set archived_at=now(), archived_by=created_by where id=:id")
                .param("id", archived)
                .update();
        jdbc.sql("insert into ms_task_project_members(project_id,user_id,access_kind) values(:project,:viewer,'R')")
                .param("project", member)
                .param("viewer", viewer.id())
                .update();
        long ownTask = task(own, viewer.id(), viewer.id());
        membership(ownTask, viewer.id(), "A");
        membership(ownTask, viewer.id(), "O");
        task(own, viewer.id(), viewer.id());
        task(member, outsider, viewer.id());
        long participated = task(participant, outsider, outsider);
        membership(participated, viewer.id(), "E");
        membership(participated, viewer.id(), "O");
        task(child, insider, insider);
        task(child, insider, insider);
        task(outside, outsider, outsider);
        task(outside, outsider, outsider);
        task(archived, viewer.id(), viewer.id());
        task(null, viewer.id(), viewer.id());
    }

    private long project(String suffix, long owner) {
        return jdbc.sql("insert into ms_task_projects(name,created_by,created_at) "
                        + "values(:name,:owner,timestamptz '2026-10-01 12:00:00+00') returning id")
                .param("name", tag + "-" + suffix)
                .param("owner", owner)
                .query(Long.class)
                .single();
    }

    private long task(@Nullable Long project, long owner, long reporter) {
        return jdbc.sql("insert into ms_tasks(project_id,title,priority,reporter_id,created_by,modified_by) "
                        + "values(:project,:title,'medium',:reporter,:owner,:owner) returning id")
                .param("project", project)
                .param("title", tag)
                .param("reporter", reporter)
                .param("owner", owner)
                .query(Long.class)
                .single();
    }

    private void membership(long task, long user, String kind) {
        jdbc.sql("insert into ms_task_members(task_id,user_id,involve_kind) values(:task,:user,:kind)")
                .param("task", task)
                .param("user", user)
                .param("kind", kind)
                .update();
    }
}
