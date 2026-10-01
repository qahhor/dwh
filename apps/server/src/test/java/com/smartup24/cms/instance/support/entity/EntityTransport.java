package com.smartup24.cms.instance.support.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * The endpoints the entity contract kit reaches an entity's records through (ADR-0032, 11): the module's own
 * controller today ({@link #module}), the general runtime {@code /api/v1/entities/{code}} once plan 10/10, item 5.4
 * serves it ({@link #runtime}). The checks are the same over both; only the paths and the differences of the two
 * written down here change.
 */
public interface EntityTransport {

    /** The collection: {@code GET} lists, {@code POST} creates. */
    String collection();

    /** One record: {@code GET} reads it, the update method changes it, {@code DELETE} removes it. */
    default String record(long id) {
        return collection() + "/" + id;
    }

    /** The read of one record: the record itself, unless the module reads it elsewhere. */
    default String read(long id) {
        return record(id);
    }

    /** {@code PUT} for a module whose update takes the fields to change, {@code PATCH} for the runtime. */
    HttpMethod updateMethod();

    /** The archive switch of a record ({@code PUT … {"archived": true}} with If-Match), used when it is archivable. */
    String archive(long id);

    /** Whether a delete may name the revision it removes in If-Match (ADR-0032, 5.3). */
    boolean deleteTakesIfMatch();

    /**
     * The status of a request to the entity's own endpoints by someone without its {@code view} right: 403 from
     * {@code @RequiresPermission} on a module's controller, 404 on the runtime, which does not tell that the entity
     * exists (ADR-0032, 6.3).
     */
    int withoutView();

    /**
     * Whether the body is read field by field, so an unknown property or a value of the wrong JSON type is a 422 on
     * the field (the runtime, ADR-0032, 6.3); a module's typed request refuses a wrong type with 400 and ignores an
     * unknown property.
     */
    boolean strictBody();

    /** The endpoint of a declared action other than create, update, archive and delete, or empty when there is none. */
    Optional<RecordAction> action(String code);

    /** An action on one record: its method, its path for a record id and the body it sends. */
    record RecordAction(
            HttpMethod method,
            LongFunction<String> path,
            @Nullable Object body) {}

    /** A module's own controller at {@code collection}: {@code PUT} updates, the archive switch under the record. */
    static Module module(String collection) {
        return new Module(collection);
    }

    /** The general runtime of plan 10/10, item 5.4 (ADR-0032, 6.1). */
    static EntityTransport runtime(String code) {
        return new EntityTransport() {
            @Override
            public String collection() {
                return "/api/v1/entities/" + code;
            }

            @Override
            public HttpMethod updateMethod() {
                return HttpMethod.PATCH;
            }

            @Override
            public String archive(long id) {
                return record(id) + "/archived";
            }

            @Override
            public boolean deleteTakesIfMatch() {
                return true;
            }

            @Override
            public int withoutView() {
                return 404;
            }

            @Override
            public boolean strictBody() {
                return true;
            }

            @Override
            public Optional<RecordAction> action(String action) {
                return Optional.of(
                        new RecordAction(HttpMethod.POST, id -> record(id) + "/actions/" + action, Map.of()));
            }
        };
    }

    /** {@link #module(String)}, with the module's own actions and differences. */
    final class Module implements EntityTransport {

        private final String collection;
        private final Map<String, RecordAction> actions = new HashMap<>();
        private HttpMethod update = HttpMethod.PUT;
        private boolean deleteIfMatch = true;

        private Module(String collection) {
            this.collection = collection;
        }

        /** The module's endpoint of a declared action ({@code pin} of notes). */
        public Module action(String code, HttpMethod method, LongFunction<String> path, @Nullable Object body) {
            actions.put(code, new RecordAction(method, path, body));
            return this;
        }

        /** The module updates with another method than {@code PUT}. */
        public Module updateWith(HttpMethod method) {
            update = method;
            return this;
        }

        /** The module's delete does not take If-Match. */
        public Module deleteWithoutIfMatch() {
            deleteIfMatch = false;
            return this;
        }

        @Override
        public String collection() {
            return collection;
        }

        @Override
        public HttpMethod updateMethod() {
            return update;
        }

        @Override
        public String archive(long id) {
            return record(id) + "/archived";
        }

        @Override
        public boolean deleteTakesIfMatch() {
            return deleteIfMatch;
        }

        @Override
        public int withoutView() {
            return 403;
        }

        @Override
        public boolean strictBody() {
            return false;
        }

        @Override
        public Optional<RecordAction> action(String code) {
            return Optional.ofNullable(actions.get(code));
        }
    }
}
