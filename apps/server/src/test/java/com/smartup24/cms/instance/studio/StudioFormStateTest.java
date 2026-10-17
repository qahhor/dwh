package com.smartup24.cms.instance.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.common.cluster.ClusterNotices;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Declaration;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.DeliveryPackage;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Field;
import com.smartup24.cms.instance.md.api.MdSimpleFormDtos.Labels;
import com.smartup24.cms.instance.md.service.MdSimpleFormDeclarations;
import com.smartup24.cms.instance.studio.repository.StudioFormRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository;
import com.smartup24.cms.instance.studio.repository.StudioFormVersionRepository.Head;
import com.smartup24.cms.instance.studio.service.StudioActivationService;
import com.smartup24.cms.instance.studio.service.StudioArchiveLock;
import com.smartup24.cms.instance.studio.service.StudioDefinitions;
import com.smartup24.cms.instance.studio.service.StudioDefinitions.Broken;
import com.smartup24.cms.instance.studio.service.StudioEntityCompiler;
import com.smartup24.cms.instance.support.TestDatabases;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The stored versions and state of live forms (ADR-0041): immutable versions, a state that names only versions that
 * exist and is never removed, records stamped with the active version, archived forms served read-only outside the
 * limit of active forms, and a catalog version that follows every switch of a version or a lifecycle.
 */
class StudioFormStateTest {
    private final ObjectMapper json = JsonMapper.shared();
    private HikariDataSource database;
    private JdbcClient jdbc;
    private StudioFormRepository forms;
    private StudioFormVersionRepository versions;
    private long author;

    @BeforeEach
    void database() {
        database = (HikariDataSource) TestDatabases.migratedCopy("studio_state");
        jdbc = JdbcClient.create(database);
        forms = new StudioFormRepository(jdbc, json);
        versions = new StudioFormVersionRepository(jdbc, json);
        author = jdbc.sql("insert into md_users(name, login, email) "
                        + "values ('Test', 'studio_state', 'state@example.invalid') returning id")
                .query(Long.class)
                .single();
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    void versionsAreImmutableAndTheStateNamesOnlyAVersionThatExistsAndStays() {
        long id = insert("alpha");
        Head head = versions.head("studio.alpha").orElseThrow();
        assertThat(head.revision()).isEqualTo(1);
        assertThat(head.activeVersion()).isEqualTo(1);
        assertThat(head.archived()).isFalse();
        assertThatThrownBy(() -> versions.saveState(head, 2, false, author)).hasMessageContaining("Unknown live form");

        DeliveryPackage next = pack(
                "alpha",
                fields ->
                        fields.add(new Field("extra", "bool", new Labels("Да", "Ha", "Yes"), false, true, null, null)));
        versions.insertVersion(id, 2, next.declaration(), next.checksum(), author);
        assertThat(versions.saveState(head, 2, false, author)).isEqualTo(2);
        Head second = versions.head("studio.alpha").orElseThrow();
        assertThat(second.activeVersion()).isEqualTo(2);
        assertThat(second.versions()).isEqualTo(2);
        assertThat(second.checksum()).isEqualTo(next.checksum());
        assertThat(versions.versions(id))
                .extracting(StudioFormVersionRepository.VersionRow::version)
                .containsExactly(1, 2);
        assertThatThrownBy(() -> jdbc.sql("update studio_form_versions set checksum = repeat('c', 64)")
                        .update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("delete from studio_form_versions").update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.sql("delete from studio_form_state").update())
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> versions.insertVersion(id, 2, next.declaration(), next.checksum(), author))
                .hasMessageContaining("studio_form_versions_uk_form_version");
        assertThatThrownBy(() -> versions.insertVersion(id, 101, next.declaration(), next.checksum(), author))
                .hasMessageContaining("studio_form_versions_ck_version");
    }

    @Test
    void recordsKeepTheVersionTheyWereSavedWithAndEveryWriteTakesTheActiveOne() {
        long id = insert("alpha");
        long record = record(id, "first");
        assertThat(versionOf(record)).isEqualTo(1);
        DeliveryPackage next = pack("alpha", fields -> {});
        Declaration changed = new Declaration(
                2,
                "simple-form",
                next.declaration().module(),
                next.declaration().entity(),
                next.declaration().fields());
        versions.insertVersion(id, 2, changed, "d".repeat(64), author);
        versions.saveState(versions.head("studio.alpha").orElseThrow(), 2, false, author);
        assertThat(versionOf(record)).isEqualTo(1);
        long second = record(id, "second");
        assertThat(versionOf(second)).isEqualTo(2);
        jdbc.sql("update studio_records set name = 'renamed' where id = :id")
                .param("id", record)
                .update();
        assertThat(versionOf(record)).isEqualTo(2);
    }

    @Test
    void anArchivedFormIsServedReadOnlyOutsideTheLimitAndTheCatalogFollowsEverySwitch() {
        long alpha = insert("alpha");
        insert("beta");
        List<String> published = new ArrayList<>();
        var definitions = new StudioDefinitions(
                forms, new StudioEntityCompiler(), module -> true, Set::of, Set::of, notices(published));
        assertThat(definitions.snapshot()).containsOnlyKeys("studio.alpha", "studio.beta");
        assertThat(definitions.snapshot().get("studio.alpha").entity().menu()).isNotNull();
        assertThat(forms.count()).isEqualTo(2);

        versions.saveState(versions.head("studio.alpha").orElseThrow(), 1, true, author);
        assertThat(forms.count()).isEqualTo(1);
        assertThat(forms.stored()).isEqualTo(2);
        // The version check alone sees the switch: no notice is needed for correctness.
        var alphaServed = definitions.snapshot().get("studio.alpha").entity();
        assertThat(alphaServed.menu()).isNull();
        assertThat(alphaServed.action("create")).isEmpty();
        assertThat(alphaServed.action("archive")).isPresent();
        assertThat(definitions.archived("studio.alpha")).isTrue();
        assertThat(definitions.archived("studio.beta")).isFalse();
        // The rights of an archived form stay those of every form.
        assertThat(definitions.registered().get("studio.alpha").entity().action("update"))
                .isPresent();
        var lock = new StudioArchiveLock(definitions);
        assertThat(lock.refusal(alphaServed)).isNotNull();
        assertThat(lock.refusal(definitions.snapshot().get("studio.beta").entity()))
                .isNull();

        forms.quarantine(alpha, "Hold", author);
        assertThat(definitions.snapshot()).containsOnlyKeys("studio.beta");
        assertThat(definitions.archived("studio.alpha")).isFalse();
        forms.release(alpha);
        versions.saveState(versions.head("studio.alpha").orElseThrow(), 1, false, author);
        assertThat(definitions.snapshot().get("studio.alpha").entity().action("create"))
                .isPresent();
        definitions.changed();
        assertThat(published).containsExactly(StudioDefinitions.NOTICE);
    }

    @Test
    void archivedFormsDoNotTakeAPlaceAmongTheHundredActiveForms() {
        for (int i = 0; i < StudioActivationService.MAX_FORMS + 1; i++) insert("bulk" + i);
        var definitions = new StudioDefinitions(forms, new StudioEntityCompiler());
        assertThat(definitions.current().broken()).containsExactly(new Broken("studio.bulk100", "form_limit"));
        versions.saveState(versions.head("studio.bulk3").orElseThrow(), 1, true, author);
        assertThat(definitions.current().broken()).isEmpty();
        assertThat(definitions.snapshot()).hasSize(StudioActivationService.MAX_FORMS + 1);
        assertThat(forms.count()).isEqualTo(StudioActivationService.MAX_FORMS);
    }

    private ClusterNotices notices(List<String> published) {
        return new ClusterNotices() {
            @Override
            public void publish(String name) {
                published.add(name);
            }

            @Override
            public void onNotice(String name, Runnable handler) {}

            @Override
            public void send(String topic, String message) {}

            @Override
            public void onMessage(String topic, Consumer<String> handler) {}
        };
    }

    private long record(long formId, String name) {
        return jdbc.sql("insert into studio_records (form_id, name, code, created_by, modified_by)"
                        + " values (:form, :name, :name, :author, :author) returning id")
                .param("form", formId)
                .param("name", name)
                .param("author", author)
                .query(Long.class)
                .single();
    }

    private int versionOf(long record) {
        return jdbc.sql("select form_version from studio_records where id = :id")
                .param("id", record)
                .query(Integer.class)
                .single();
    }

    private long insert(String name) {
        DeliveryPackage value = pack(name, fields -> {});
        return forms.insert("studio." + name, value.declaration(), value.checksum(), author);
    }

    private DeliveryPackage pack(String name, Consumer<List<Field>> change) {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/studio/simple-form.json"))) {
            ObjectNode declaration = (ObjectNode) json.readTree(input.readAllBytes());
            ((ObjectNode) declaration.path("module")).put("code", "studio");
            ((ObjectNode) declaration.path("entity")).put("name", name);
            var prepared = new MdSimpleFormDeclarations(json).prepare(json.writeValueAsBytes(declaration));
            List<Field> fields = new ArrayList<>(prepared.declaration().fields());
            change.accept(fields);
            Declaration changed = new Declaration(
                    prepared.declaration().formatVersion(),
                    "simple-form",
                    prepared.declaration().module(),
                    prepared.declaration().entity(),
                    fields);
            return new DeliveryPackage(1, "simple-form-package", changed, prepared.checksum());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
