package com.smartup24.cms.instance.support.entity;

import static com.smartup24.cms.instance.support.entity.KitWorld.ifMatch;
import static com.smartup24.cms.instance.support.entity.KitWorld.number;
import static com.smartup24.cms.instance.support.entity.KitWorld.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartup24.cms.instance.common.entity.EntityCapability;
import com.smartup24.cms.instance.common.entity.EntityDefinition;
import com.smartup24.cms.instance.common.entity.field.EntityField;
import com.smartup24.cms.instance.support.TestSession;
import com.smartup24.cms.instance.support.entity.KitWorld.Created;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The record's life (ADR-0032, 11.2, "CRUD", "revision", "archive"): created with 201, {@code Location} and
 * {@code ETag} and read back as written; listed; changed only from the revision it names (428 without, 409 stale);
 * archived out of the default list and still read by id; deleted to a 404 like a missing id.
 */
final class KitCrudChecks {

    private final KitWorld world;

    KitCrudChecks(KitWorld world) {
        this.world = world;
    }

    List<DynamicTest> crud() {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest("create answers 201 with Location and ETag, and the record reads back", this::create));
        tests.add(dynamicTest("the created record is in the creator's list", this::listed));
        tests.add(dynamicTest("an update from the current revision changes the record and raises it", this::update));
        tests.add(dynamicTest("a create repeated with its Idempotency-Key answers the same record", this::repeated));
        if (world.declares(EntityDefinition.DELETE)) {
            tests.add(dynamicTest("delete answers 204, then the record reads as a missing one", this::delete));
        }
        return tests;
    }

    List<DynamicTest> revision() {
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(dynamicTest("an update without If-Match is 428", () -> {
            Created record = world.create(world.owner);
            assertThat(world.update(world.owner, record.id(), world.updateValues(), null)
                            .getStatus())
                    .isEqualTo(428);
        }));
        tests.add(dynamicTest("an update from a stale revision is 409 and changes nothing", this::staleUpdate));
        if (world.has(EntityCapability.ARCHIVE)) {
            tests.add(dynamicTest("archiving without If-Match is 428, from a stale revision 409", this::staleArchive));
        }
        if (world.declares(EntityDefinition.DELETE) && world.transport.deleteTakesIfMatch()) {
            tests.add(dynamicTest("a delete from a stale revision is 409 and keeps the record", this::staleDelete));
        }
        return tests;
    }

    List<DynamicTest> archive() {
        if (!world.has(EntityCapability.ARCHIVE)) return List.of();
        return List.of(dynamicTest(
                "an archived record leaves the default list, is listed by the filter, reads by id and comes back",
                this::archived));
    }

    private void create() throws Exception {
        Created record = world.create(world.owner);
        MockHttpServletResponse response = record.response();
        assertThat(response.getHeader("Location"))
                .as("Location of the created record")
                .endsWith(world.transport.record(record.id()));
        assertThat(response.getHeader("ETag")).isEqualTo(ifMatch(record.revision()));
        MockHttpServletResponse read = world.read(world.owner, record.id());
        assertThat(read.getStatus()).isEqualTo(200);
        assertThat(read.getHeader("ETag")).isEqualTo(ifMatch(record.revision()));
        assertWritten(TestSession.object(read), record.values());
    }

    private void listed() throws Exception {
        Created record = world.create(world.owner);
        assertThat(world.listed(world.owner, record.id(), null))
                .as("record %d in the list", record.id())
                .isTrue();
    }

    private void update() throws Exception {
        Created record = world.create(world.owner);
        Map<String, Object> change = world.updateValues();
        MockHttpServletResponse updated = world.update(world.owner, record.id(), change, ifMatch(record.revision()));
        assertThat(updated.getStatus()).as(updated.getContentAsString()).isEqualTo(200);
        assertThat(updated.getHeader("ETag")).isEqualTo(ifMatch(record.revision() + 1));
        Map<String, Object> read = world.readOk(world.owner, record.id());
        assertThat(number(read.get("revision"))).isEqualTo(record.revision() + 1);
        assertWritten(read, change);
    }

    private void repeated() throws Exception {
        Map<String, Object> values = world.validValues(world.owner);
        UUID key = UUID.randomUUID();
        TestSession session = world.session(world.owner);
        MockHttpServletResponse first = session.send(post(world.transport.collection()), values, key);
        MockHttpServletResponse second = session.send(post(world.transport.collection()), values, key);
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
        assertThat(second.getStatus()).as(second.getContentAsString()).isEqualTo(201);
        assertThat(second.getHeader("Location")).isEqualTo(first.getHeader("Location"));
        assertThat(TestSession.object(second).get("id"))
                .isEqualTo(TestSession.object(first).get("id"));
    }

    private void delete() throws Exception {
        Created record = world.create(world.owner);
        MockHttpServletResponse deleted = world.delete(world.owner, record.id(), null);
        assertThat(deleted.getStatus()).as(deleted.getContentAsString()).isEqualTo(204);
        MockHttpServletResponse gone = world.read(world.owner, record.id());
        assertThat(gone.getStatus()).isEqualTo(404);
        assertThat(problem(gone)).isEqualTo(problem(world.read(world.owner, KitWorld.MISSING)));
        assertThat(world.listed(world.owner, record.id(), null)).isFalse();
    }

    private void staleUpdate() throws Exception {
        Created record = world.create(world.owner);
        long id = record.id();
        assertThat(world.update(world.owner, id, world.updateValues(), ifMatch(record.revision()))
                        .getStatus())
                .isEqualTo(200);
        Map<String, Object> before = world.readOk(world.owner, id);
        MockHttpServletResponse stale = world.update(world.owner, id, world.updateValues(), ifMatch(record.revision()));
        assertThat(stale.getStatus()).as(stale.getContentAsString()).isEqualTo(409);
        assertThat(TestSession.object(stale)).containsEntry("code", "revision_conflict");
        assertThat(world.readOk(world.owner, id)).isEqualTo(before);
    }

    private void staleArchive() throws Exception {
        Created record = world.create(world.owner);
        long id = record.id();
        assertThat(world.archive(world.owner, id, true, null).getStatus()).isEqualTo(428);
        assertThat(world.update(world.owner, id, world.updateValues(), ifMatch(record.revision()))
                        .getStatus())
                .isEqualTo(200);
        MockHttpServletResponse stale = world.archive(world.owner, id, true, ifMatch(record.revision()));
        assertThat(stale.getStatus()).as(stale.getContentAsString()).isEqualTo(409);
        assertThat(world.readOk(world.owner, id)).containsEntry("archived", false);
    }

    private void staleDelete() throws Exception {
        Created record = world.create(world.owner);
        long id = record.id();
        assertThat(world.update(world.owner, id, world.updateValues(), ifMatch(record.revision()))
                        .getStatus())
                .isEqualTo(200);
        MockHttpServletResponse stale = world.delete(world.owner, id, ifMatch(record.revision()));
        assertThat(stale.getStatus()).as(stale.getContentAsString()).isEqualTo(409);
        assertThat(world.read(world.owner, id).getStatus()).isEqualTo(200);
        assertThat(world.delete(world.owner, id, ifMatch(record.revision() + 1)).getStatus())
                .isEqualTo(204);
    }

    private void archived() throws Exception {
        Created record = world.create(world.owner);
        long id = record.id();
        MockHttpServletResponse done = world.archive(world.owner, id, true, ifMatch(record.revision()));
        assertThat(done.getStatus()).as(done.getContentAsString()).isEqualTo(200);
        assertThat(TestSession.object(done)).containsEntry("archived", true);
        assertThat(world.listed(world.owner, id, null))
                .as("in the default list")
                .isFalse();
        assertThat(world.listed(world.owner, id, KitWorld.ARCHIVED_ONLY))
                .as("in the archive")
                .isTrue();
        assertThat(world.readOk(world.owner, id)).containsEntry("archived", true);

        long revision = number(TestSession.object(done).get("revision"));
        MockHttpServletResponse restored = world.archive(world.owner, id, false, ifMatch(revision));
        assertThat(restored.getStatus()).as(restored.getContentAsString()).isEqualTo(200);
        assertThat(world.listed(world.owner, id, null))
                .as("back in the default list")
                .isTrue();
    }

    /** Every written value reads back as the server keeps it. */
    private void assertWritten(Map<String, Object> record, Map<String, Object> written) {
        for (EntityField field : world.writable()) {
            if (!written.containsKey(field.key())) continue;
            Object sent = written.get(field.key());
            Object read = KitWorld.value(record, field);
            assertThat(EntitySamples.same(world.form(field), sent, read))
                    .as("%s: sent %s, read back %s", field.key(), sent, read)
                    .isTrue();
        }
    }
}
