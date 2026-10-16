package com.smartup24.cms.instance.common.entity;

import static com.smartup24.cms.platform.api.entity.field.EntityFields.file;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.image;
import static com.smartup24.cms.platform.api.entity.field.EntityFields.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.query.QueryListRepository;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import com.smartup24.cms.instance.mf.repository.MfRecordFileRepository;
import com.smartup24.cms.instance.mf.service.MfAttachments;
import com.smartup24.cms.instance.support.TestDatabases;
import com.smartup24.cms.platform.api.entity.Entity;
import com.smartup24.cms.platform.api.entity.EntityDefinition;
import com.smartup24.cms.platform.api.entity.EntityScope;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import com.smartup24.cms.spi.storage.StorageProvider;
import com.zaxxer.hikari.HikariDataSource;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** ADR-0032, 5.2: attachment reads and reuse obey the current field rights, including revocation. */
class EntityFileAccessIntegrationTest {

    private static final String CODE = "test.file_access";
    private static HikariDataSource database;
    private static JdbcClient jdbc;
    private static long actor;
    private static long owner;

    private MfAttachments attachments;
    private StorageProvider storage;
    private UUID uploaded;

    @BeforeAll
    static void database() {
        database = (HikariDataSource) TestDatabases.migratedCopy("file_access");
        jdbc = JdbcClient.create(database);
        actor = user("file_actor");
        owner = user("file_owner");
    }

    @AfterAll
    static void closeDatabase() {
        database.close();
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("delete from mf_record_files where entity = :entity")
                .param("entity", CODE)
                .update();
        storage = mock(StorageProvider.class);
        when(storage.download(anyString(), anyString()))
                .thenAnswer(
                        call -> new FileDownloadStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "image/png"));
        attachments = new MfAttachments(
                new MfRecordFileRepository(jdbc),
                new MfFileRepository(jdbc, new QueryListRepository(jdbc)),
                storage,
                mock(AuditLogService.class));
        uploaded = upload(owner);
        signIn(false);
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContext.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revocationClosesKnownFileAndImageUrls(boolean isImage) throws Exception {
        EntityDefinition entity = entity(isImage);
        attachments.attach(CODE, 1, "restrictedFile", uploaded);
        signIn(true);
        assertDownload(entity);
        signIn(false);

        assertThat(EntityFieldRights.hidden(entity)).contains("restrictedFile");
        assertNotFound(() -> controller(entity).download(CODE, 1, uploaded));
    }

    @Test
    void aHiddenForeignAttachmentCannotBeMovedIntoAnOpenField() {
        EntityDefinition entity = entity(false);
        attachments.attach(CODE, 1, "restrictedFile", uploaded);
        EntityFieldValues values = values(entity);

        assertThatThrownBy(() -> values.prepare(
                        entity,
                        Map.of("visible", uploaded.toString()),
                        Map.of("title", "Record", "restrictedFile", uploaded.toString()),
                        1L,
                        actor))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.getFieldErrors()).anySatisfy(error -> {
                            assertThat(error.field()).isEqualTo("visible");
                            assertThat(error.code()).isEqualTo("not_found");
                        }));
        assertThat(jdbc.sql("select count(*) from mf_record_files where entity = :entity and field_key = 'visible'")
                        .param("entity", CODE)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void anOpenSharedAttachmentAndReadOnlySourceRemainReadable() throws Exception {
        EntityDefinition entity = entity(false);
        attachments.attach(CODE, 1, "restrictedFile", uploaded);
        attachments.attach(CODE, 1, "readonly", uploaded);

        assertDownload(entity);
        assertThat(values(entity).lookupProblems(entity, Map.of("visible", uploaded.toString()), Map.of(), 1L, actor))
                .isEmpty();

        attachments.attach(CODE, 1, "readonly", null);
        assertNotFound(() -> controller(entity).download(CODE, 1, uploaded));
    }

    @ParameterizedTest
    @ValueSource(strings = {"removed", "title"})
    void staleAndNonFileFieldKeysDoNotAuthorizeDownloadOrReuse(String key) {
        EntityDefinition entity = entity(false);
        attachments.attach(CODE, 1, key, uploaded);

        assertNotFound(() -> controller(entity).download(CODE, 1, uploaded));
        assertThat(values(entity).lookupProblems(entity, Map.of("visible", uploaded.toString()), Map.of(), 1L, actor))
                .anySatisfy(error -> assertThat(error.code()).isEqualTo("not_found"));
        verifyNoInteractions(storage);
    }

    @Test
    void ownUploadsAndUnchangedValuesStillWork() {
        EntityDefinition entity = entity(false);
        UUID own = upload(actor);

        assertThat(values(entity).lookupProblems(entity, Map.of("visible", own.toString()), Map.of(), null, actor))
                .isEmpty();
        assertThat(values(entity)
                        .lookupProblems(
                                entity,
                                Map.of("restrictedFile", uploaded.toString()),
                                Map.of("restrictedFile", uploaded.toString()),
                                1L,
                                actor))
                .isEmpty();
    }

    @Test
    void anOpenAttachmentOfAnotherRecordDoesNotAuthorizeThisRecord() {
        EntityDefinition entity = entity(false);
        attachments.attach(CODE, 2, "visible", uploaded);

        assertNotFound(() -> controller(entity).download(CODE, 1, uploaded));
        assertThat(values(entity).lookupProblems(entity, Map.of("visible", uploaded.toString()), Map.of(), 1L, actor))
                .anySatisfy(error -> assertThat(error.code()).isEqualTo("not_found"));
        verifyNoInteractions(storage);
    }

    @Test
    void noVisibleFileFieldsDenyAttachmentsButKeepOwnUploads() {
        attachments.attach(CODE, 1, "restrictedFile", uploaded);
        UUID own = upload(actor);

        assertThat(attachments.attached(CODE, 1, uploaded, Set.of())).isEmpty();
        assertThat(attachments.attachable(uploaded, CODE, 1L, actor, Set.of())).isEmpty();
        assertThat(attachments.attachable(own, CODE, 1L, actor, Set.of())).isPresent();
        assertThat(attachments.attachable(own, CODE, null, actor, Set.of())).isPresent();
        verifyNoInteractions(storage);
    }

    private void assertDownload(EntityDefinition entity) throws Exception {
        var response = controller(entity).download(CODE, 1, uploaded);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        try (var stream = response.getBody().getInputStream()) {
            assertThat(stream.readAllBytes()).containsExactly(1, 2, 3);
        }
    }

    private EntityFileController controller(EntityDefinition entity) {
        EntityRecords records = new EntityRecords() {
            @Override
            public String entity() {
                return CODE;
            }

            @Override
            public void requireVisible(long id) {
                if (id != 1) throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found");
            }
        };
        return new EntityFileController(EntityFeaturesTest.registry(List.of(entity), List.of(), records), attachments);
    }

    private EntityFieldValues values(EntityDefinition entity) {
        return new EntityFieldValues(
                new EntityEnums(List.of(entity), jdbc, (org.springframework.cache.CacheManager) null),
                attachments,
                jdbc,
                Clock.systemUTC());
    }

    private static EntityDefinition entity(boolean isImage) {
        return Entity.define(CODE, "test_file_access")
                .table("test_file_access", "f")
                .scope(EntityScope.all())
                .field(text("title", "test.title").column("title"))
                .field((isImage ? image("restrictedFile", "test.secret") : file("restrictedFile", "test.secret"))
                        .column("restricted_file_id")
                        .requires("md.modules", "view"))
                .field(file("visible", "test.visible").column("visible_id"))
                .field(file("readonly", "test.readonly").column("readonly_id").readonlyUnless("md.modules", "view"))
                .section("main", "test.main", "title", "restrictedFile", "visible", "readonly")
                .defaultSort("title", Entity.Sort.ASC)
                .build();
    }

    private static void signIn(boolean fieldRight) {
        Set<String> permissions = new HashSet<>(Set.of("test_file_access.view", "md.profile.view"));
        if (fieldRight) permissions.add("md.modules.view");
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                actor, "probe", "probe@example.invalid", 1L, false, permissions, 1L, false, 1L, null));
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static long user(String login) {
        return jdbc.sql("insert into md_users (name, login, email) values (:name, :name, :email) returning id")
                .param("name", login)
                .param("email", login + "@example.invalid")
                .query(Long.class)
                .single();
    }

    private static UUID upload(long userId) {
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "insert into mf_files (id, sha256, original_name, size_bytes, mime_type, storage_bucket, storage_key, created_by)"
                                + " values (:id, :sha, 'probe.png', 3, 'image/png', 'b', :key, :owner)")
                .param("id", id)
                .param("sha", id.toString().replace("-", ""))
                .param("key", "probe/" + id)
                .param("owner", userId)
                .update();
        return id;
    }
}
