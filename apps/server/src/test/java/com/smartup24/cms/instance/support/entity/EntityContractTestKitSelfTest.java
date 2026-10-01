package com.smartup24.cms.instance.support.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.ms.note.service.MsNoteEntity;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.web.context.WebApplicationContext;

/**
 * The kit fails an entity that breaks its contract (plan 10/10, item 6.2): run against deliberately broken endpoints
 * ({@link KitProbes}), exactly the broken rule fails and every other case passes — a read that skips the scope check
 * (another person's note answers 200), a read that tells a foreign record from a missing one (403 instead of 404), and a
 * read that leaks a field the viewer has no right to. The same kit passes the correct endpoints of an entity with field
 * rights, so the field rights group is proven both ways.
 */
@Import(KitProbes.class)
class EntityContractTestKitSelfTest extends EmbeddedPostgresTest {

    private static final String SCOPE_READ =
            "scope: 404, not 403 (ADR-0013) / a record outside the scope: read is 404, the same answer as a missing id";

    @Autowired
    private WebApplicationContext wac;

    @Test
    @DisplayName("6.2: a read without the scope check fails the kit's scope case, and only it")
    void aLeakingReadFailsTheScopeCase() {
        assertThat(failures(notes(id -> KitProbes.PATH + "/leaky-notes/" + id))).containsExactly(SCOPE_READ);
    }

    @Test
    @DisplayName("6.2: a read that answers 403 for a foreign record fails the kit's scope case, and only it")
    void aForbiddingReadFailsTheScopeCase() {
        assertThat(failures(notes(id -> KitProbes.PATH + "/forbidding-notes/" + id)))
                .containsExactly(SCOPE_READ);
    }

    @Test
    @DisplayName("6.2: an entity with field rights passes the kit through endpoints that keep them")
    void fieldRightsPassWhenKept() {
        assertThat(failures(secretNotes(false))).isEmpty();
    }

    @Test
    @DisplayName("6.2: a read that leaks a field without its right fails the kit's field rights case, and only it")
    void aLeakedFieldFailsTheFieldRightsCase() {
        assertThat(failures(secretNotes(true)))
                .containsExactly("field rights (ADR-0032, 5.2) / contentMd: absent from the records and the list read"
                        + " without its right");
    }

    /** The note kit with the read of one record sent to a probe instead of the notes controller. */
    private static EntityContractTestKit notes(LongFunction<String> read) {
        EntityTransport notes = EntityTransport.module("/api/v1/notes")
                .action("pin", HttpMethod.PUT, id -> "/api/v1/notes/" + id + "/pin", Map.of("pinned", true));
        return kit(MsNoteEntity.CODE, new ReadElsewhere(notes, read));
    }

    /** The kit of the probe entity with field rights; its read leaks the hidden field when asked to. */
    private static EntityContractTestKit secretNotes(boolean leaky) {
        EntityTransport secret = EntityTransport.module(KitProbes.PATH + "/secret-notes");
        return kit(
                KitProbes.SECRET_CODE,
                leaky ? new ReadElsewhere(secret, id -> KitProbes.PATH + "/secret-notes/" + id + "/leaky") : secret);
    }

    /** A kit made here, not a class of its own: the coverage test and the test runner never see it. */
    private static EntityContractTestKit kit(String code, EntityTransport transport) {
        return new EntityContractTestKit() {
            @Override
            protected String entity() {
                return code;
            }

            @Override
            protected EntityTransport transport() {
                return transport;
            }
        };
    }

    /** Runs every case of the kit, as JUnit would, and names the ones that fail. */
    private List<String> failures(EntityContractTestKit kit) {
        wac.getAutowireCapableBeanFactory().autowireBean(kit);
        List<String> failed = new ArrayList<>();
        kit.contract()
                .forEach(group -> group.getChildren().forEach(node -> {
                    DynamicTest test = (DynamicTest) node;
                    try {
                        test.getExecutable().execute();
                    } catch (Throwable failure) {
                        failed.add(group.getDisplayName() + " / " + test.getDisplayName());
                    }
                }));
        return failed;
    }

    /** A transport that reads one record from another path and does everything else as {@code base}. */
    private record ReadElsewhere(EntityTransport base, LongFunction<String> readPath) implements EntityTransport {

        @Override
        public String collection() {
            return base.collection();
        }

        @Override
        public String read(long id) {
            return readPath.apply(id);
        }

        @Override
        public HttpMethod updateMethod() {
            return base.updateMethod();
        }

        @Override
        public String archive(long id) {
            return base.archive(id);
        }

        @Override
        public boolean deleteTakesIfMatch() {
            return base.deleteTakesIfMatch();
        }

        @Override
        public int withoutView() {
            return base.withoutView();
        }

        @Override
        public boolean strictBody() {
            return base.strictBody();
        }

        @Override
        public Optional<RecordAction> action(String code) {
            return base.action(code);
        }
    }
}
