package com.greenwhite.dwh.instance.md;

import com.greenwhite.dwh.instance.audit.repository.AuditLogRepository;
import com.greenwhite.dwh.instance.audit.service.AuditLogService;
import com.greenwhite.dwh.instance.audit.service.AuditDataRedactor;
import com.greenwhite.dwh.instance.common.error.ApiException;
import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.md.repository.MdOrgUnitRepository;
import com.greenwhite.dwh.instance.md.repository.MdPermissionRepository;
import com.greenwhite.dwh.instance.md.repository.MdRoleRepository;
import com.greenwhite.dwh.instance.md.repository.MdScopeRepository;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.md.service.MdOrgUnitService;
import com.greenwhite.dwh.instance.md.service.MdPermissionService;
import com.greenwhite.dwh.instance.md.service.MdScopeService;
import com.greenwhite.dwh.instance.mf.repository.MfFileRepository;
import com.greenwhite.dwh.instance.ms.task.repository.MsTaskRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Скоуп данных (ADR-0013): правило видимости у роли, позиция у пользователя,
 * материализованный эффективный скоуп и предикат в SQL.
 *
 * Ревизия 30.08: в модели доступа не было измерения данных — любой с правом
 * «просмотр пользователей» видел всех. Дашборды Этапа 3 на такой модели
 * показывать нельзя: первая же выгрузка покажет одному клиенту цифры другого.
 */
@Testcontainers
class MdScopeServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("dwh_scope_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    static JdbcClient jdbc;
    static MdScopeService scopeService;
    static MdOrgUnitService orgUnitService;
    static MdScopeRepository scopeRepository;
    static MdOrgUnitRepository orgUnitRepository;
    static MdRoleRepository roleRepository;
    static MdUserRepository userRepository;
    static MdPermissionService permissionService;
    static MsTaskRepository taskRepository;
    static MfFileRepository fileRepository;

    static Long company;
    static Long regionTashkent;
    static Long branchYunusabad;
    static Long regionSamarkand;

    @BeforeAll
    static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure())
                .dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);

        var auditLogService = new AuditLogService(new AuditLogRepository(jdbc, new ObjectMapper()), null,
                new AuditDataRedactor());
        scopeRepository = new MdScopeRepository(jdbc);
        orgUnitRepository = new MdOrgUnitRepository(jdbc);
        roleRepository = new MdRoleRepository(jdbc);
        userRepository = new MdUserRepository(jdbc, new ObjectMapper());
        permissionService = new MdPermissionService(new MdPermissionRepository(jdbc));
        taskRepository = new MsTaskRepository(jdbc, new ObjectMapper());
        fileRepository = new MfFileRepository(jdbc);

        scopeService = new MdScopeService(scopeRepository, orgUnitRepository, permissionService, auditLogService);
        orgUnitService = new MdOrgUnitService(orgUnitRepository, scopeService, auditLogService);

        company = orgUnitService.create(null, "HQ", "Компания", "company", 10).id();
        regionTashkent = orgUnitService.create(company, "R-TAS", "Регион Ташкент", "region", 10).id();
        branchYunusabad = orgUnitService.create(regionTashkent, "B-YUN", "Филиал Юнусабад", "branch", 10).id();
        regionSamarkand = orgUnitService.create(company, "R-SAM", "Регион Самарканд", "region", 20).id();
    }

    // ------------------------------------------------------------ правила

    @Test
    @DisplayName("SUBTREE разворачивает узел вместе с потомками и не задевает соседнюю ветку")
    void subtreeRuleExpandsDownwards() {
        Long userId = createUser("scope_subtree", null);
        Long roleId = createRole("Региональный менеджер", MdScopeService.RULE_SUBTREE);
        assignRole(userId, roleId);

        scopeService.assignUserOrgUnits(userId, List.of(regionTashkent));

        var scope = scopeService.getUserScope(userId);
        assertThat(scope.rule()).isEqualTo(MdScopeService.RULE_SUBTREE);
        assertThat(scope.visibleOrgUnitIds())
                .containsExactlyInAnyOrder(regionTashkent, branchYunusabad)
                .doesNotContain(regionSamarkand, company);
    }

    @Test
    @DisplayName("UNITS даёт только сам узел, без потомков")
    void unitsRuleDoesNotExpand() {
        Long userId = createUser("scope_units", null);
        Long roleId = createRole("Супервайзер региона", MdScopeService.RULE_UNITS);
        assignRole(userId, roleId);

        scopeService.assignUserOrgUnits(userId, List.of(regionTashkent));

        assertThat(scopeService.getUserScope(userId).visibleOrgUnitIds())
                .containsExactly(regionTashkent);
    }

    @Test
    @DisplayName("ALL ничего не материализует: ограничивать нечего")
    void allRuleMaterializesNothing() {
        Long userId = createUser("scope_all", null);
        assignRole(userId, roleRepository.findByPcode("admin").orElseThrow().id());
        scopeService.assignUserOrgUnits(userId, List.of(regionTashkent));

        var scope = scopeService.getUserScope(userId);
        assertThat(scope.rule()).isEqualTo(MdScopeService.RULE_ALL);
        assertThat(scope.visibleOrgUnitIds()).isEmpty();
    }

    @Test
    @DisplayName("При нескольких ролях берётся самое широкое правило")
    void widestRuleWinsAcrossRoles() {
        Long userId = createUser("scope_widest", null);
        assignRole(userId, createRole("Узкая роль", MdScopeService.RULE_UNITS));
        assignRole(userId, createRole("Широкая роль", MdScopeService.RULE_SUBTREE));

        scopeService.assignUserOrgUnits(userId, List.of(regionTashkent));

        assertThat(scopeService.getUserScope(userId).rule()).isEqualTo(MdScopeService.RULE_SUBTREE);
    }

    @Test
    @DisplayName("Выключенный узел обрывает ветку целиком, а не только себя")
    void passiveUnitCutsWholeBranch() {
        Long userId = createUser("scope_passive", null);
        assignRole(userId, createRole("Менеджер выключаемой ветки", MdScopeService.RULE_SUBTREE));
        Long region = orgUnitService.create(company, "R-TMP", "Временный регион", "region", 30).id();
        Long branch = orgUnitService.create(region, "B-TMP", "Временный филиал", "branch", 10).id();
        scopeService.assignUserOrgUnits(userId, List.of(region));

        assertThat(scopeService.getUserScope(userId).visibleOrgUnitIds())
                .containsExactlyInAnyOrder(region, branch);

        orgUnitService.update(region, company, null, null, "P", null);

        assertThat(scopeService.getUserScope(userId).visibleOrgUnitIds())
                .as("выключение региона обязано унести и его филиал").isEmpty();
    }

    // ----------------------------------------------------- инварианты дерева

    @Test
    @DisplayName("I-ORG-1: узел нельзя перенести под собственного потомка")
    void cannotMoveNodeUnderItsOwnDescendant() {
        assertThatThrownBy(() -> orgUnitService.update(regionTashkent, branchYunusabad, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("потомка");
    }

    @Test
    @DisplayName("Корень в экземпляре один: второй отклоняется с понятной ошибкой")
    void secondRootIsRejected() {
        assertThatThrownBy(() -> orgUnitService.create(null, "HQ-2", "Вторая компания", "company", 10))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Корень оргструктуры уже существует");
    }

    @Test
    @DisplayName("I-ORG-2: узел с сотрудниками не удаляется молча")
    void cannotDeleteUnitWithAssignedUsers() {
        Long unit = orgUnitService.create(company, "R-DEL", "Удаляемый регион", "region", 40).id();
        Long userId = createUser("scope_delete_guard", null);
        scopeService.assignUserOrgUnits(userId, List.of(unit));

        assertThatThrownBy(() -> orgUnitService.delete(unit))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("сотрудники");
    }

    // -------------------------------------------------- применение предиката

    @Test
    @DisplayName("Предикат действительно режет выборку: видны только пользователи своей ветки")
    void predicateRestrictsUserList() {
        Long viewer = createUser("scope_viewer", regionTashkent);
        assignRole(viewer, createRole("Менеджер Ташкента", MdScopeService.RULE_SUBTREE));
        scopeService.assignUserOrgUnits(viewer, List.of(regionTashkent));

        Long inBranch = createUser("scope_in_branch", branchYunusabad);
        Long inOtherRegion = createUser("scope_in_samarkand", regionSamarkand);

        var filter = scopeService.filterFor(viewer, "md_users.org_unit_id", "md_users.id");
        var visible = userRepository.listUsers(100, null, null, null, null, null, null, filter)
                .stream().map(MdUserRepository.UserRecord::id).toList();

        assertThat(visible).contains(viewer, inBranch);
        assertThat(visible).as("соседний регион виден быть не должен").doesNotContain(inOtherRegion);
    }

    @Test
    @DisplayName("Правило ALL не меняет запрос: видны все, включая строки без узла")
    void unrestrictedFilterKeepsQueryIntact() {
        Long admin = createUser("scope_admin_viewer", null);
        assignRole(admin, roleRepository.findByPcode("admin").orElseThrow().id());
        scopeService.recalculateFor(admin);

        Long other = createUser("scope_other_for_admin", regionSamarkand);

        var filter = scopeService.filterFor(admin, "md_users.org_unit_id", "md_users.id");
        assertThat(filter.isUnrestricted()).isTrue();

        var visible = userRepository.listUsers(200, null, null, null, null, null, null, filter)
                .stream().map(MdUserRepository.UserRecord::id).toList();
        assertThat(visible).contains(admin, other);
    }

    @Test
    @DisplayName("I-P2: изменение скоупа двигает версию прав — иначе кэш доступа не узнает")
    void scopeChangeBumpsPermissionVersion() {
        Long userId = createUser("scope_version", null);
        assignRole(userId, createRole("Роль для версии", MdScopeService.RULE_SUBTREE));
        long before = permissionService.getPermissionVersion(userId);

        scopeService.assignUserOrgUnits(userId, List.of(regionTashkent));

        assertThat(permissionService.getPermissionVersion(userId)).isGreaterThan(before);
    }

    @Test
    @DisplayName("Смена правила у роли пересчитывает скоуп всем её носителям")
    void roleRuleChangeRecalculatesItsUsers() {
        Long userId = createUser("scope_rule_change", null);
        Long roleId = createRole("Меняющая правило роль", MdScopeService.RULE_UNITS);
        assignRole(userId, roleId);
        scopeService.assignUserOrgUnits(userId, List.of(regionTashkent));

        assertThat(scopeService.getUserScope(userId).visibleOrgUnitIds()).containsExactly(regionTashkent);

        scopeService.setRoleRule(roleId, MdScopeService.RULE_SUBTREE);

        assertThat(scopeService.getUserScope(userId).visibleOrgUnitIds())
                .containsExactlyInAnyOrder(regionTashkent, branchYunusabad);
    }

    @Test
    @DisplayName("SUBTREE ограничивает задачи участниками ветки, а файлы — владельцем или видимой задачей")
    void subtreeRestrictsTasksAndFilesAcrossOrganizationBranches() {
        Long viewer = createUser("scope_task_viewer", regionTashkent);
        assignRole(viewer, createRole("Менеджер задач Ташкента", MdScopeService.RULE_SUBTREE));
        scopeService.assignUserOrgUnits(viewer, List.of(regionTashkent));

        Long inBranch = createUser("scope_task_in_branch", branchYunusabad);
        scopeService.assignUserOrgUnits(inBranch, List.of(branchYunusabad));
        Long outsideBranch = createUser("scope_task_outside", regionSamarkand);
        scopeService.assignUserOrgUnits(outsideBranch, List.of(regionSamarkand));

        Long visibleTask = createTask("Видимая задача", inBranch);
        Long hiddenTask = createTask("Чужая задача", outsideBranch);
        UUID linkedToVisibleTask = createFile("linked-visible.txt", outsideBranch);
        UUID linkedThroughComment = createFile("comment-visible.txt", outsideBranch);
        UUID visibleByOwnerUnit = createFile("branch-owned.txt", inBranch);
        UUID hiddenStandalone = createFile("outside-standalone.txt", outsideBranch);
        attachFile(visibleTask, linkedToVisibleTask);
        attachFileToComment(visibleTask, inBranch, linkedThroughComment);

        assertThat(visibleTaskIds(viewer)).contains(visibleTask).doesNotContain(hiddenTask);
        assertThat(visibleFileIds(viewer))
                .contains(linkedToVisibleTask, linkedThroughComment, visibleByOwnerUnit)
                .doesNotContain(hiddenStandalone);
        assertThat(scopeService.canAccessUser(viewer, inBranch)).isTrue();
        assertThat(scopeService.canAccessUser(viewer, outsideBranch)).isFalse();
    }

    @Test
    @DisplayName("SELF видит собственные и назначенные задачи, но не соседние строки")
    void selfRestrictsTasksAndFilesToOwnershipAndParticipation() {
        Long viewer = createUser("scope_self_viewer", null);
        assignRole(viewer, createRole("Исполнитель только своих задач", MdScopeService.RULE_SELF));
        Long colleague = createUser("scope_self_colleague", null);

        Long ownTask = createTask("Своя задача", viewer);
        Long assignedTask = createTask("Назначенная задача", colleague);
        addTaskMember(assignedTask, viewer, "E");
        Long unrelatedTask = createTask("Посторонняя задача", colleague);
        UUID ownFile = createFile("own.txt", viewer);
        UUID linkedFile = createFile("linked.txt", colleague);
        UUID hiddenFile = createFile("hidden.txt", colleague);
        attachFile(assignedTask, linkedFile);

        assertThat(visibleTaskIds(viewer)).contains(ownTask, assignedTask).doesNotContain(unrelatedTask);
        assertThat(visibleFileIds(viewer)).contains(ownFile, linkedFile).doesNotContain(hiddenFile);
        assertThat(scopeService.canAccessUser(viewer, viewer)).isTrue();
        assertThat(scopeService.canAccessUser(viewer, colleague)).isFalse();
    }

    @Test
    @DisplayName("ALL сохраняет совместимость и видит задачи и файлы всей установки")
    void allKeepsTaskAndFileVisibilityUnrestricted() {
        Long viewer = createUser("scope_all_resources", null);
        assignRole(viewer, roleRepository.findByPcode("admin").orElseThrow().id());
        Long other = createUser("scope_all_other", regionSamarkand);
        Long task = createTask("Общая задача", other);
        UUID file = createFile("shared.txt", other);

        assertThat(scopeService.filterForTasks(viewer).isUnrestricted()).isTrue();
        assertThat(scopeService.filterForFiles(viewer).isUnrestricted()).isTrue();
        assertThat(visibleTaskIds(viewer)).contains(task);
        assertThat(visibleFileIds(viewer)).contains(file);
    }

    @Test
    @DisplayName("Репозитории применяют scope к list, detail и агрегатам, а не фильтруют результат в памяти")
    void repositoriesApplyScopeToListsDetailsAndCounts() {
        Long viewer = createUser("scope_repository_viewer", null);
        assignRole(viewer, createRole("Repository SELF", MdScopeService.RULE_SELF));
        Long other = createUser("scope_repository_other", null);
        Long visibleTask = createTask("Repository visible", viewer);
        Long hiddenTask = createTask("Repository hidden", other);
        UUID visibleFile = createFile("repository-visible.txt", viewer);
        UUID hiddenFile = createFile("repository-hidden.txt", other);

        var taskScope = scopeService.filterForTasks(viewer);
        var taskIds = taskRepository.listTasks(100, null, null, null, null, null, false, taskScope)
                .stream().map(MsTaskRepository.TaskRecord::id).toList();
        assertThat(taskIds).contains(visibleTask).doesNotContain(hiddenTask);
        assertThat(taskRepository.findById(visibleTask, taskScope)).isPresent();
        assertThat(taskRepository.findById(hiddenTask, taskScope)).isEmpty();

        var fileScope = scopeService.filterForFiles(viewer);
        var fileIds = fileRepository.listFiles(viewer, false, null, 100, fileScope)
                .stream().map(MfFileRepository.FileDetailRecord::id).toList();
        assertThat(fileIds).contains(visibleFile).doesNotContain(hiddenFile);
        assertThat(fileRepository.findById(visibleFile, fileScope)).isPresent();
        assertThat(fileRepository.findById(hiddenFile, fileScope)).isEmpty();
    }

    @Test
    @DisplayName("Неизвестное правило видимости отклоняется, а не пишется в базу")
    void unknownRuleIsRejected() {
        Long roleId = createRole("Роль с плохим правилом", MdScopeService.RULE_ALL);

        assertThatThrownBy(() -> scopeService.setRoleRule(roleId, "EVERYTHING"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Неизвестное правило");
    }

    // ------------------------------------------------------------- вспомогательное

    private static Long createUser(String login, Long orgUnitId) {
        Long id = jdbc.sql("""
                        insert into md_users (name, login, email, password_hash, state, language, timezone,
                                              attributes, is_2fa_enabled, force_password_change, org_unit_id)
                        values (:login, :login, :login || '@test.local', 'x', 'A', 'ru', 'UTC',
                                '{}'::jsonb, false, false, :orgUnitId)
                        returning id
                        """)
                .param("login", login)
                .param("orgUnitId", orgUnitId)
                .query(Long.class).single();
        scopeService.recalculateFor(id);
        return id;
    }

    private static Long createTask(String title, Long participantUserId) {
        Long statusId = jdbc.sql("select id from ms_task_statuses order by id limit 1")
                .query(Long.class).single();
        Long taskId = jdbc.sql("""
                        insert into ms_tasks (title, description_markdown, status_id, priority, reporter_id,
                                              attributes, created_by, modified_by)
                        values (:title, '', :statusId, 'medium', :userId, '{}'::jsonb, :userId, :userId)
                        returning id
                        """)
                .param("title", title)
                .param("statusId", statusId)
                .param("userId", participantUserId)
                .query(Long.class).single();
        addTaskMember(taskId, participantUserId, "A");
        return taskId;
    }

    private static void addTaskMember(Long taskId, Long userId, String kind) {
        jdbc.sql("""
                        insert into ms_task_members (task_id, user_id, involve_kind, is_viewed)
                        values (:taskId, :userId, :kind, false)
                        """)
                .param("taskId", taskId)
                .param("userId", userId)
                .param("kind", kind)
                .update();
    }

    private static UUID createFile(String name, Long ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into mf_files (id, sha256, original_name, size_bytes, mime_type,
                                              storage_bucket, storage_key, created_by)
                        values (:id, :sha, :name, 1, 'text/plain', 'instance-files', :key, :ownerId)
                        """)
                .param("id", id)
                .param("sha", id.toString().replace("-", ""))
                .param("name", name)
                .param("key", id.toString())
                .param("ownerId", ownerId)
                .update();
        return id;
    }

    private static void attachFile(Long taskId, UUID fileId) {
        jdbc.sql("insert into ms_task_files (task_id, file_id) values (:taskId, :fileId)")
                .param("taskId", taskId)
                .param("fileId", fileId)
                .update();
    }

    private static void attachFileToComment(Long taskId, Long authorId, UUID fileId) {
        Long commentId = jdbc.sql("""
                        insert into ms_task_comments (task_id, user_id, text_markdown)
                        values (:taskId, :authorId, 'Комментарий')
                        returning id
                        """)
                .param("taskId", taskId)
                .param("authorId", authorId)
                .query(Long.class).single();
        jdbc.sql("insert into ms_task_comment_files (comment_id, file_id) values (:commentId, :fileId)")
                .param("commentId", commentId)
                .param("fileId", fileId)
                .update();
    }

    private static List<Long> visibleTaskIds(Long viewerId) {
        var scope = scopeService.filterForTasks(viewerId);
        var query = jdbc.sql("select t.id from ms_tasks t where 1=1" + scope.sql() + " order by t.id");
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query(Long.class).list();
    }

    private static List<UUID> visibleFileIds(Long viewerId) {
        var scope = scopeService.filterForFiles(viewerId);
        var query = jdbc.sql("select f.id from mf_files f where 1=1" + scope.sql() + " order by f.id");
        if (scope.bindsUserId()) query = query.param("scopeUserId", scope.userId());
        return query.query((rs, rowNum) -> UUID.fromString(rs.getString("id"))).list();
    }

    private static Long createRole(String name, String rule) {
        var role = roleRepository.create(name, null, "A", 100);
        scopeRepository.setRoleRule(role.id(), rule);
        return role.id();
    }

    private static void assignRole(Long userId, Long roleId) {
        var current = new java.util.ArrayList<>(roleRepository.getUserRoleIds(userId));
        if (!current.contains(roleId)) {
            current.add(roleId);
        }
        roleRepository.assignRolesToUser(userId, current);
        scopeService.recalculateFor(userId);
    }
}
