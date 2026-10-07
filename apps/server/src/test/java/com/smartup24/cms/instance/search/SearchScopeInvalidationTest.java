package com.smartup24.cms.instance.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.config.db.FlywayUtcConfiguration;
import com.smartup24.cms.instance.search.repository.SearchScopeInvalidationRepository;
import com.smartup24.cms.instance.search.service.EntitySearchListener;
import com.smartup24.cms.instance.search.service.SearchChangePublisher;
import com.smartup24.cms.instance.search.service.SearchEntities;
import com.smartup24.cms.platform.api.entity.event.EntityChanged;
import com.smartup24.cms.platform.api.entity.event.EntityEventType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies that changes in user org units or project tasks invalidate and reindex
 * affected search documents (ADR-0032, 10.3.1 item C7; plan 10/10, item 5.8).
 */
@Testcontainers
class SearchScopeInvalidationTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("search_scope_invalidation")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static TransactionTemplate tx;
    static SearchChangePublisher publisher;
    static EntitySearchListener listener;
    static SearchEntities entities;
    static long user1;
    static long user2;

    @BeforeAll
    static void setUpAll() {
        var database =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(database)
                .load()
                .migrate();
        jdbc = JdbcClient.create(database);
        var manager = new DataSourceTransactionManager(database);
        tx = new TransactionTemplate(manager);
        var invalidation = new SearchScopeInvalidationRepository(jdbc);
        publisher = proxied(new SearchChangePublisher(jdbc, invalidation), manager);
        entities = SearchTestEntities.unscoped();
        listener = new EntitySearchListener(entities, publisher);

        user1 = createUser("user1", "user1@example.invalid");
        user2 = createUser("user2", "user2@example.invalid");
    }

    @BeforeEach
    void clearProjections() {
        tx.executeWithoutResult(
                status -> jdbc.sql("delete from search_projection_versions").update());
    }

    @Test
    @DisplayName("Invalidating user scope marks all tasks the user participates in")
    void invalidatingUserScopeMarksTasksOfUser() {
        long taskCreated = createTask(user1, user2, null);
        long taskReported = createTask(user2, user1, null);
        long taskMember = createTask(user2, user2, null);
        addTaskMember(taskMember, user1);
        long otherTask = createTask(user2, user2, null);

        tx.executeWithoutResult(status -> publisher.invalidateUserScope(user1));

        List<Long> indexedTasks = indexedIds("ms.tasks");
        assertThat(indexedTasks).contains(taskCreated, taskReported, taskMember);
        assertThat(indexedTasks).doesNotContain(otherTask);
    }

    @Test
    @DisplayName("Invalidating user scope marks projects where user is author, member or task participant")
    void invalidatingUserScopeMarksProjectsOfUser() {
        long projectAuthor = createProject("P-Author", user1);
        long projectMember = createProject("P-Member", user2);
        addProjectMember(projectMember, user1);
        long projectTask = createProject("P-Task", user2);
        createTask(user2, user1, projectTask);
        long otherProject = createProject("P-Other", user2);

        tx.executeWithoutResult(status -> publisher.invalidateUserScope(user1));

        List<Long> indexedProjects = indexedIds("ms.projects");
        assertThat(indexedProjects).contains(projectAuthor, projectMember, projectTask);
        assertThat(indexedProjects).doesNotContain(otherProject);
    }

    @Test
    @DisplayName("Invalidating task project marks the owning project for reindexing")
    void invalidatingTaskProjectMarksOwningProject() {
        long project = createProject("P-Owning", user2);
        long taskWithProject = createTask(user1, user2, project);
        long taskWithoutProject = createTask(user1, user2, null);

        tx.executeWithoutResult(status -> publisher.invalidateTaskProject(taskWithProject));
        assertThat(indexedIds("ms.projects")).containsExactly(project);

        clearProjections();
        tx.executeWithoutResult(status -> publisher.invalidateTaskProject(taskWithoutProject));
        assertThat(indexedIds("ms.projects")).isEmpty();
    }

    @Test
    @DisplayName("EntitySearchListener invalidates task project when ms.tasks changes")
    void entitySearchListenerInvalidatesTaskProjectOnTaskChange() {
        long project = createProject("P-Listener", user2);
        long task = createTask(user1, user2, project);

        tx.executeWithoutResult(status -> listener.changed(new EntityChanged(
                "ms.tasks",
                "tasks.items",
                task,
                1L,
                EntityEventType.UPDATED,
                null,
                List.of("title"),
                user1,
                Instant.now(),
                UUID.randomUUID())));

        assertThat(indexedIds("ms.tasks")).contains(task);
        assertThat(indexedIds("ms.projects")).contains(project);
    }

    @Test
    @DisplayName("EntitySearchListener invalidates user scope when md.users orgUnitId changes")
    void entitySearchListenerInvalidatesUserScopeOnOrgUnitChange() {
        long task = createTask(user1, user2, null);
        long project = createProject("P-UserScope", user1);

        tx.executeWithoutResult(status -> listener.changed(new EntityChanged(
                "md.users",
                "md.users",
                user1,
                2L,
                EntityEventType.UPDATED,
                null,
                List.of("orgUnitId"),
                user1,
                Instant.now(),
                UUID.randomUUID())));

        assertThat(indexedIds("md.users")).contains(user1);
        assertThat(indexedIds("ms.tasks")).contains(task);
        assertThat(indexedIds("ms.projects")).contains(project);
    }

    private static long createUser(String login, String email) {
        return java.util.Objects.requireNonNull(jdbc.sql("""
                insert into md_users(name, login, email, password_hash, state, language, timezone)
                values (:login, :login, :email, 'x', 'A', 'ru', 'UTC') returning id
                """)
                .param("login", login)
                .param("email", email)
                .query(Long.class)
                .single());
    }

    private static long createProject(String name, long createdBy) {
        return java.util.Objects.requireNonNull(jdbc.sql("""
                insert into ms_task_projects (name, description, created_by, modified_by)
                values (:name, 'desc', :user, :user) returning id
                """)
                .param("name", name)
                .param("user", createdBy)
                .query(Long.class)
                .single());
    }

    private static void addProjectMember(long projectId, long userId) {
        jdbc.sql("""
                insert into ms_task_project_members (project_id, user_id, access_kind)
                values (:projectId, :userId, 'R')
                """).param("projectId", projectId).param("userId", userId).update();
    }

    private static long createTask(long createdBy, long reporterId, Long projectId) {
        return java.util.Objects.requireNonNull(jdbc.sql("""
                insert into ms_tasks (title, description_markdown, priority, status_code, type_code,
                                      created_by, reporter_id, project_id)
                values ('Task', 'desc', 'medium', 'new', 'task', :createdBy, :reporterId, :projectId)
                returning id
                """)
                .param("createdBy", createdBy)
                .param("reporterId", reporterId)
                .param("projectId", projectId)
                .query(Long.class)
                .single());
    }

    private static void addTaskMember(long taskId, long userId) {
        jdbc.sql("""
                insert into ms_task_members (task_id, user_id, involve_kind, position)
                values (:taskId, :userId, 'E', 0)
                """).param("taskId", taskId).param("userId", userId).update();
    }

    private List<Long> indexedIds(String entityType) {
        return jdbc.sql("select entity_id from search_projection_versions where entity_type = :type order by entity_id")
                .param("type", entityType)
                .query(Long.class)
                .list();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxied(T target, DataSourceTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
