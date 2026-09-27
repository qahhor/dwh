package com.smartup24.cms.instance.fnd.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.smartup24.cms.instance.fnd.FndActor;
import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.FndPref;
import com.smartup24.cms.instance.fnd.dwh.DwhUnavailableException;
import com.smartup24.cms.instance.fnd.dwh.FndRawRow;
import com.smartup24.cms.instance.fnd.dwh.FndRawWriter;
import com.smartup24.cms.instance.fnd.error.ConstraintErrorCode;
import com.smartup24.cms.instance.fnd.error.ConstraintViolationException;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.support.fixtures.DepartmentFixture;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Блок E основы: версии загрузок и журнал (AC-25…AC-32), плюс состав колонок fnd_loads (AC-44).
 * Тесты протокола идут по двум конфигурациям ведомств А и Б ({@link DepartmentFixture}, AC-41):
 * источники, периоды и версии формата — параметр, а не знание ядра.
 */
class FndLoadServiceTest extends EmbeddedPostgresTest {

    /** Основная загрузка текущей конфигурации: источник, период, версия формата. */
    private String source;

    private LocalDate periodFrom;
    private LocalDate periodTo;
    private String format;
    private DepartmentFixture dept;

    static Stream<DepartmentFixture> departments() {
        return DepartmentFixture.departments();
    }

    private void use(DepartmentFixture fixture) {
        dept = fixture;
        source = fixture.mainLoad().source();
        periodFrom = fixture.mainLoad().periodFrom();
        periodTo = fixture.mainLoad().periodTo();
        format = fixture.mainLoad().format();
    }

    @Autowired
    private FndLoadService loads;

    @Autowired
    private FndRawWriter rawWriter;

    @Autowired
    private FndActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier(FndPref.DWH)
    private JdbcClient dwhJdbc;

    @Autowired
    private TransactionTemplate tx;

    private FndActor actor;
    private FndActor user;

    @BeforeEach
    void cleanLoads() {
        actor = actors.system();
        user = FndActor.user(userId());
        tx.executeWithoutResult(status -> {
            actors.apply(actor);
            jdbc.sql("select set_config('dwh.maintenance', 'on', true)")
                    .query(String.class)
                    .single();
            jdbc.sql("delete from fnd_load_log").update();
            jdbc.sql("update fnd_loads set superseded_by = null").update();
            jdbc.sql("delete from fnd_loads").update();
            jdbc.sql("delete from security_events where event_type = 'xdb_mismatch'")
                    .update();
        });
        dwhJdbc.sql("delete from raw.rows").update();
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-25: begin → запись строк → apply; несходящиеся счётчики строк — отказ")
    void applyProtocol(DepartmentFixture fixture) {
        use(fixture);
        UUID packageRef = UUID.randomUUID();
        long loadId = loads.begin(source, packageRef, periodFrom, periodTo, format, user);
        assertThat(loads.find(loadId).orElseThrow().status()).isEqualTo(FndLoad.PENDING);

        rawWriter.write(loadId, null, rows(3));
        loads.apply(loadId, 3, 2, 1, user);

        FndLoad applied = loads.find(loadId).orElseThrow();
        assertThat(applied.status()).isEqualTo(FndLoad.APPLIED);
        assertThat(applied.appliedAt()).isNotNull();
        assertThat(applied.appliedBy()).isEqualTo(user.name());
        assertThat(applied.rowsTotal()).isEqualTo(3);
        assertThat(applied.rowsAccepted()).isEqualTo(2);
        assertThat(applied.rowsRejected()).isEqualTo(1);
        assertThat(loads.appliedLoadIds(source)).containsExactly(loadId);

        long another = loads.begin(source, UUID.randomUUID(), periodFrom, periodTo, format, user);
        assertThat(codeOf(() -> loads.apply(another, 10, 2, 1, user))).isEqualTo(ConstraintErrorCode.FND_LOADS_CK_ROWS);
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-26: сбой записи доходит до вызывающего; fail помечает загрузку и пишет причину")
    void failedLoad(DepartmentFixture fixture) {
        use(fixture);
        UUID packageRef = UUID.randomUUID();
        long loadId = loads.begin(source, packageRef, periodFrom, periodTo, format, user);

        FndRawWriter broken = new BrokenRawWriter();
        assertThatThrownBy(() -> broken.write(loadId, null, rows(1))).isInstanceOf(DwhUnavailableException.class);

        loads.fail(loadId, "источник вернул ошибку TEST", user);
        assertThat(loads.find(loadId).orElseThrow().status()).isEqualTo(FndLoad.FAILED);
        assertThat(loads.appliedLoadIds(source)).doesNotContain(loadId);
        Map<String, Object> logRow = jdbc.sql("select event, note from fnd_load_log where package_ref = :p")
                .param("p", packageRef)
                .query()
                .singleRow();
        assertThat(logRow).containsEntry("event", "failed").containsEntry("note", "источник вернул ошибку TEST");

        long other = loads.begin(source, UUID.randomUUID(), periodFrom, periodTo, format, user);
        assertThatThrownBy(() -> loads.fail(other, "  ", user)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-27: разрешены только pending→applied, pending→failed, applied→superseded")
    void statusTransitions(DepartmentFixture fixture) {
        use(fixture);
        UUID packageRef = UUID.randomUUID();
        long loadId = loads.begin(source, packageRef, periodFrom, periodTo, format, user);
        loads.apply(loadId, 1, 1, 0, user);

        assertThat(codeOf(() -> loads.apply(loadId, 1, 1, 0, user)))
                .isEqualTo(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION);
        assertThat(codeOf(() -> loads.fail(loadId, "поздно", user)))
                .isEqualTo(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION);
        assertThat(codeOf(() -> loads.apply(-1, 1, 1, 0, user)))
                .isEqualTo(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION);
        assertThat(codeOf(() -> loads.begin(source, packageRef, periodFrom, periodTo, format, user)))
                .isEqualTo(ConstraintErrorCode.FND_LOADS_UK_PACKAGE_REF);
        assertThatThrownBy(() -> jdbc.sql("update fnd_loads set status = 'unknown' where id = :id")
                        .param("id", loadId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fnd_loads_ck_status");
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-28: повторная загрузка периода снимает предыдущую, её строки raw остаются")
    void repeatedPeriodSupersedes(DepartmentFixture fixture) {
        use(fixture);
        long first = loads.begin(source, UUID.randomUUID(), periodFrom, periodTo, format, user);
        rawWriter.write(first, null, rows(2));
        loads.apply(first, 2, 2, 0, user);

        DepartmentFixture.Load other = dept.otherPeriodLoad();
        long otherPeriod = loads.begin(source, UUID.randomUUID(), other.periodFrom(), other.periodTo(), format, user);
        loads.apply(otherPeriod, 1, 1, 0, user);
        long otherSource =
                loads.begin(dept.otherSourceLoad().source(), UUID.randomUUID(), periodFrom, periodTo, format, user);
        loads.apply(otherSource, 1, 1, 0, user);

        long second = loads.begin(source, UUID.randomUUID(), periodFrom, periodTo, format, user);
        loads.apply(second, 2, 2, 0, user);

        FndLoad superseded = loads.find(first).orElseThrow();
        assertThat(superseded.status()).isEqualTo(FndLoad.SUPERSEDED);
        assertThat(superseded.supersededBy()).isEqualTo(second);
        assertThat(rawWriter.read(first)).hasSize(2);
        assertThat(loads.appliedLoadIds(source)).containsExactlyInAnyOrder(second, otherPeriod);
        assertThat(loads.find(otherSource).orElseThrow().status()).isEqualTo(FndLoad.APPLIED);
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-29: журнал пакета только дополняется; load_id появляется после применения")
    void packageJournal(DepartmentFixture fixture) {
        use(fixture);
        UUID packageRef = UUID.randomUUID();
        String sha = "a".repeat(64);
        loads.log(packageRef, "получен", null, null, user, "файл принят TEST", sha);
        loads.log(packageRef, "проверен", null, null, user, "проверка пройдена TEST", null);
        assertThat(jdbc.sql("select count(*) from fnd_load_log where package_ref = :p and load_id is null")
                        .param("p", packageRef)
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);

        long loadId = loads.begin(source, packageRef, periodFrom, periodTo, format, user);
        loads.apply(loadId, 1, 1, 0, user);
        String longNote = "ў".repeat(4000);
        loads.log(packageRef, "применён", FndLoad.PENDING, FndLoad.APPLIED, user, longNote, null);

        List<Map<String, Object>> journal = jdbc.sql(
                        "select event, load_id, note from fnd_load_log" + " where package_ref = :p order by at, id")
                .param("p", packageRef)
                .query()
                .listOfRows();
        assertThat(journal).hasSize(3);
        assertThat(journal.get(2)).containsEntry("event", "применён").containsEntry("load_id", loadId);
        assertThat((String) journal.get(2).get("note")).hasSize(4000).startsWith("ў");

        assertThat(codeOf(() -> loads.log(packageRef, "получен", null, null, user, null, "не-hex")))
                .isEqualTo(ConstraintErrorCode.FND_LOAD_LOG_CK_FILE_SHA);
        assertThat(codeOf(() -> loads.log(packageRef, "получен", null, null, null, null, null)))
                .isEqualTo(ConstraintErrorCode.AUDIT_ACTOR_MISSING);
        assertThatThrownBy(() -> jdbc.sql(
                                "insert into fnd_load_log (package_ref, event, actor)" + " values (:p, 'получен', ' ')")
                        .param("p", packageRef)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fnd_load_log_ck_actor");

        assertThatThrownBy(() -> jdbc.sql("update fnd_load_log set note = 'правка' where package_ref = :p")
                        .param("p", packageRef)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fnd_load_log_append_only");
        assertThatThrownBy(() -> jdbc.sql("delete from fnd_load_log where package_ref = :p")
                        .param("p", packageRef)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fnd_load_log_append_only");
        // Сессия обслуживания — единственное исключение (как для audit_log каркаса)
        tx.executeWithoutResult(status -> {
            actors.apply(user); // журнал под аудитом (V106): и обслуживание идёт с актором
            jdbc.sql("select set_config('dwh.maintenance', 'on', true)")
                    .query(String.class)
                    .single();
            assertThat(jdbc.sql("delete from fnd_load_log where package_ref = :p")
                            .param("p", packageRef)
                            .update())
                    .isEqualTo(3);
        });
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-29 (M-4): две строки журнала в одной транзакции получают разное время at")
    void journalTimeIsPerRowNotPerTransaction(DepartmentFixture fixture) {
        use(fixture);
        UUID packageRef = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            loads.log(packageRef, "получен", null, null, user, "первая строка TEST", null);
            loads.log(packageRef, "проверен", null, null, user, "вторая строка TEST", null);
        });
        List<OffsetDateTime> times = jdbc.sql("select at from fnd_load_log where package_ref = :p order by id")
                .param("p", packageRef)
                .query(OffsetDateTime.class)
                .list();
        assertThat(times).hasSize(2);
        assertThat(times.get(1))
                .as("at второй строки строго позже первой (clock_timestamp, не now())")
                .isAfter(times.get(0));
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-30: строки raw и поколение кеша ссылаются на один и тот же load_id")
    void singleLoadId(DepartmentFixture fixture) {
        use(fixture);
        long loadId = loads.begin(source, UUID.randomUUID(), periodFrom, periodTo, format, user);
        rawWriter.write(loadId, null, rows(2));
        loads.apply(loadId, 2, 2, 0, user);

        dwhJdbc.sql("delete from cache.items").update();
        dwhJdbc.sql("delete from cache.generations").update();
        long generation = dwhJdbc.sql("insert into cache.generations (state, load_versions, switched_at)"
                        + " values ('current', cast(:versions as jsonb), now()) returning generation_id")
                .param("versions", "{\"" + source + "\": " + loadId + "}")
                .query(Long.class)
                .single();

        List<Long> rawLoadIds = dwhJdbc.sql("select distinct load_id from raw.rows")
                .query(Long.class)
                .list();
        String cacheVersions = dwhJdbc.sql(
                        "select load_versions::text from cache.generations" + " where generation_id = :id")
                .param("id", generation)
                .query(String.class)
                .single();
        assertThat(rawLoadIds).containsExactly(loadId);
        assertThat(cacheVersions).contains(String.valueOf(loadId));
    }

    @ParameterizedTest(name = "конфигурация {0}")
    @MethodSource("departments")
    @DisplayName("AC-32: в журналах — id пользователя или system, в audit_log — тот же актор; метки timestamptz")
    void actorAndTime(DepartmentFixture fixture) {
        use(fixture);
        long byUser = loads.begin(source, UUID.randomUUID(), periodFrom, periodTo, format, user);
        loads.apply(byUser, 1, 1, 0, user);
        long byJob = loads.begin(
                source,
                UUID.randomUUID(),
                dept.otherPeriodLoad().periodFrom(),
                dept.otherPeriodLoad().periodTo(),
                format,
                actor);
        loads.apply(byJob, 1, 1, 0, actor);

        assertThat(loads.find(byUser).orElseThrow().appliedBy()).isEqualTo(String.valueOf(user.userId()));
        assertThat(loads.find(byJob).orElseThrow().appliedBy()).isEqualTo(FndPref.SYSTEM_ACTOR);
        assertThat(jdbc.sql("select distinct changed_by from audit_log where table_name = 'fnd_loads'"
                                + " and row_pk = :id")
                        .param("id", String.valueOf(byUser))
                        .query(Long.class)
                        .list())
                .containsExactly(user.userId());
        assertThat(jdbc.sql("select distinct changed_by from audit_log where table_name = 'fnd_loads'"
                                + " and row_pk = :id")
                        .param("id", String.valueOf(byJob))
                        .query(Long.class)
                        .list())
                .containsExactly(actor.userId());

        List<String> timestampColumns = jdbc.sql("select data_type from information_schema.columns"
                        + " where table_name in ('fnd_loads', 'fnd_load_log')"
                        + " and column_name in ('applied_at', 'at')")
                .query(String.class)
                .list();
        assertThat(timestampColumns).isNotEmpty().allMatch("timestamp with time zone"::equals);
    }

    @Test
    @DisplayName("AC-44: колонки fnd_loads — ровно список 18 п.14, без канальной специфики")
    void loadsSchemaHasNoChannelColumns() {
        List<String> columns = jdbc.sql(
                        "select column_name from information_schema.columns" + " where table_name = 'fnd_loads'")
                .query(String.class)
                .list();
        assertThat(columns)
                .containsExactlyInAnyOrder(
                        "id",
                        "source_code",
                        "package_ref",
                        "period_from",
                        "period_to",
                        "format_version",
                        "applied_at",
                        "applied_by",
                        "rows_total",
                        "rows_accepted",
                        "rows_rejected",
                        "status",
                        "superseded_by");
        assertThat(jdbc.sql("select data_type from information_schema.columns where table_name = 'fnd_loads'"
                                + " and column_name = 'source_code'")
                        .query(String.class)
                        .single())
                .isEqualTo("text");
    }

    @Test
    @DisplayName("AC-27: два параллельных apply одной загрузки — один успех, второй fnd_load_status_transition")
    void concurrentApplyIsRejected() throws Exception {
        use(DepartmentFixture.departments().findFirst().orElseThrow());
        long loadId = loads.begin(source, UUID.randomUUID(), periodFrom, periodTo, format, user);
        rawWriter.write(loadId, null, rows(3));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> outcomes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                outcomes.add(pool.submit(() -> {
                    barrier.await();
                    return catchThrowable(() -> loads.apply(loadId, 3, 3, 0, user));
                }));
            }
            List<Throwable> errors = new ArrayList<>();
            for (var outcome : outcomes) {
                errors.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertThat(errors).filteredOn(Objects::isNull).hasSize(1);
            assertThat(errors)
                    .filteredOn(Objects::nonNull)
                    .singleElement()
                    .isInstanceOfSatisfying(
                            ConstraintViolationException.class,
                            e -> assertThat(e.code()).isEqualTo(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION));
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads.find(loadId).orElseThrow().status()).isEqualTo(FndLoad.APPLIED);
    }

    @Test
    @DisplayName("AC-33: apply ждёт конца незавершённой записи строк — строки не попадают в применённую загрузку")
    void applyWaitsForRunningWrite() throws Exception {
        use(DepartmentFixture.departments().findFirst().orElseThrow());
        long loadId = loads.begin(source, UUID.randomUUID(), periodFrom, periodTo, format, user);
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch gate = new CountDownLatch(1);
        Iterable<FndRawRow> slowRows = () -> new Iterator<>() {
            private final Iterator<FndRawRow> delegate = rows(3).iterator();
            private int served;

            @Override
            public boolean hasNext() {
                return delegate.hasNext();
            }

            @Override
            public FndRawRow next() {
                if (++served == 2) {
                    writeStarted.countDown();
                    try {
                        assertThat(gate.await(30, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return delegate.next();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> write = pool.submit(() -> rawWriter.write(loadId, null, slowRows));
            assertThat(writeStarted.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> apply = pool.submit(() -> loads.apply(loadId, 3, 3, 0, user));
            assertThatThrownBy(() -> apply.get(700, TimeUnit.MILLISECONDS))
                    .as("apply не должен завершиться, пока запись строк держит загрузку")
                    .isInstanceOf(TimeoutException.class);
            gate.countDown();
            write.get(30, TimeUnit.SECONDS);
            apply.get(30, TimeUnit.SECONDS);
        } finally {
            gate.countDown();
            pool.shutdownNow();
        }
        assertThat(loads.find(loadId).orElseThrow().status()).isEqualTo(FndLoad.APPLIED);
        assertThat(rawWriter.read(loadId)).hasSize(3);
        // После apply запись отвергается: статус уже не pending
        assertThat(codeOf(() -> rawWriter.write(loadId, null, rows(1))))
                .isEqualTo(ConstraintErrorCode.FND_LOAD_STATUS_TRANSITION);
    }

    // ---------- вспомогательное ----------

    private long userId() {
        return tx.execute(status -> jdbc.sql("""
                        insert into md_users (name, login, email, state, language, timezone)
                        values ('Тестовый пользователь TEST', :login, :email, 'A', 'uz', 'UTC')
                        on conflict (login) do update set name = excluded.name
                        returning id
                        """)
                .param("login", "loader-test")
                .param("email", "loader-test@localhost")
                .query(Long.class)
                .single());
    }

    private static List<FndRawRow> rows(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(number ->
                        new FndRawRow(number, "Лист1", number, Map.of("code", "TEST-" + number, "value", number)))
                .toList();
    }

    private ConstraintErrorCode codeOf(Runnable action) {
        Throwable error = catchThrowable(action::run);
        assertThat(error).isInstanceOf(ConstraintViolationException.class);
        return ((ConstraintViolationException) error).code();
    }

    /** Фасад, который всегда сообщает о недоступности pg-dwh (AC-26). */
    private static final class BrokenRawWriter implements FndRawWriter {
        @Override
        public void write(long loadId, UUID sourceFileId, Iterable<FndRawRow> rows) {
            throw new DwhUnavailableException(new java.sql.SQLException("pg-dwh недоступен TEST"));
        }

        @Override
        public List<FndRawRow> read(long loadId) {
            throw new DwhUnavailableException(new java.sql.SQLException("pg-dwh недоступен TEST"));
        }
    }
}
