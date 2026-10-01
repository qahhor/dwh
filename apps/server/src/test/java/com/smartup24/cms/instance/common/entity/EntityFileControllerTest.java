package com.smartup24.cms.instance.common.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityFiles.FileFacts;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Plan 10/10, item 5.2 (ADR-0032, 4.7): the file of a record's file field is read through the record — the entity's
 * right, the record's scope and the attachment decide, and every refusal is the same 404.
 */
class EntityFileControllerTest {

    private static final UUID PHOTO = UUID.fromString("0b6f4b0e-0c43-4a6f-9d0e-5c1c8a3e2f10");
    private static final UUID OTHER = UUID.fromString("7d0a9c35-5b1e-4f6c-8a2b-3e4d5c6b7a80");

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void aVisibleRecordGivesItsAttachedFile() {
        signIn("test_field_types.view");

        var answer = controller(files()).download(FieldTypesFixture.CODE, 1L, PHOTO);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline; filename*=UTF-8''shelf%20photo.png");
        assertThat(answer.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(answer.getHeaders().getContentLength()).isEqualTo(3);
    }

    @Test
    void everythingElseIsTheSameNotFound() {
        signIn("test_field_types.view");
        EntityFileController controller = controller(files());

        assertNotFound(() -> controller.download(FieldTypesFixture.CODE, 2L, PHOTO));
        assertNotFound(() -> controller.download(FieldTypesFixture.CODE, 1L, OTHER));
        assertNotFound(() -> controller.download("test.unknown", 1L, PHOTO));
        assertNotFound(() -> controller(null).download(FieldTypesFixture.CODE, 1L, PHOTO));
        signIn("md.profile.view");
        assertNotFound(() -> controller.download(FieldTypesFixture.CODE, 1L, PHOTO));
    }

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    private static EntityFileController controller(@Nullable EntityFiles files) {
        EntityRecords records = new EntityRecords() {
            @Override
            public String entity() {
                return FieldTypesFixture.CODE;
            }

            @Override
            public void requireVisible(long id) {
                if (id != 1L) throw ApiException.notFound(ErrorCode.NOT_FOUND, "error.common.record_not_found");
            }
        };
        EntityRegistry registry =
                EntityFeaturesTest.registry(List.of(FieldTypesFixture.DEFINITION), List.of(), records);
        return new EntityFileController(registry, files);
    }

    private static EntityFiles files() {
        return new EntityFiles() {
            @Override
            public Optional<FileFacts> attachable(UUID fileId, String entity, @Nullable Long recordId, long userId) {
                return Optional.empty();
            }

            @Override
            public void attach(String entity, long recordId, String fieldKey, @Nullable UUID fileId) {}

            @Override
            public void detachAll(String entity, long recordId) {}

            @Override
            public Optional<FileFacts> attached(String entity, long recordId, UUID fileId) {
                return recordId == 1L && fileId.equals(PHOTO)
                        ? Optional.of(new FileFacts(PHOTO, "shelf photo.png", 3, "image/png"))
                        : Optional.empty();
            }

            @Override
            public FileDownloadStream open(UUID fileId) {
                return new FileDownloadStream(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "image/png");
            }
        };
    }

    private static void signIn(String permission) {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                1L,
                "ann",
                "ann@test.local",
                1L,
                false,
                new HashSet<>(List.of(permission, "md.profile.view")),
                1L,
                false,
                1L,
                null));
    }
}
