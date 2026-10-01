package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.fnd.FndActors;
import com.smartup24.cms.instance.fnd.FndPref;
import com.smartup24.cms.instance.fnd.api.FndLoad;
import com.smartup24.cms.instance.fnd.api.FndRawRow;
import com.smartup24.cms.instance.fnd.api.FndRawSource;
import com.smartup24.cms.instance.fnd.api.FndRawWriter;
import com.smartup24.cms.instance.fnd.jobs.FndJobRunner;
import com.smartup24.cms.instance.fnd.load.FndLoadService;
import com.smartup24.cms.instance.mf.repository.MfFileRepository.FileRecord;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.parse.UplParseJob;
import com.smartup24.cms.instance.upl.parse.UplXlsxParser;
import com.smartup24.cms.instance.upl.upload.UplApplyJob;
import com.smartup24.cms.instance.upl.upload.UplApplyRecoveryJob;
import com.smartup24.cms.instance.upl.upload.UplApplyService;
import com.smartup24.cms.instance.upl.upload.UplPackageModel;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.NewPackage;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import com.smartup24.cms.instance.upl.upload.UplPackageRepository;
import com.smartup24.cms.instance.upl.upload.UplPackageService;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applying a verified package, asynchronous (plan 10/10, item 3.9): the request opens the load and
 * queues the job, the job streams the rows into raw, reconciles and closes the package.
 */
class UplApplyServiceTest extends EmbeddedPostgresTest {

    private static final LocalDate PERIOD_FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate PERIOD_TO = LocalDate.of(2026, 3, 31);

    @Autowired
    private UplApplyService applies;

    @Autowired
    private UplApplyJob applyJob;

    @Autowired
    private UplApplyRecoveryJob recovery;

    @Autowired
    private FndJobRunner jobs;

    @Autowired
    private UplPackageService packages;

    @Autowired
    private UplPackageRepository repo;

    @Autowired
    private UplParseJob parseJob;

    @Autowired
    private UplSourceService sources;

    @Autowired
    private MfFileService files;

    @Autowired
    private UplXlsxParser parser;

    @Autowired
    private FndLoadService loads;

    @Autowired
    private FndRawWriter raw;

    @Autowired
    private FndActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier(FndPref.DWH)
    private JdbcClient dwhJdbc;

    @Autowired
    private TransactionTemplate tx;

    private long userId;
    private long sourceId;

    @BeforeEach
    void setUp() {
        userId = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        tx.executeWithoutResult(status -> {
            actors.apply(actors.system());
            jdbc.sql("delete from upl_package_errors").update();
            jdbc.sql("delete from upl_packages").update();
            jdbc.sql("delete from fnd_job_queue").update();
            jdbc.sql("delete from fnd_job_runs").update();
        });
        dwhJdbc.sql("delete from raw.rows").update();
        sourceId = UplPackageTestData.publishedSource(sources, userId, LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("3.9: запрос только открывает загрузку и ставит задание — строк в raw нет, пакет «применяется»")
    void requestQueuesTheJobAndWritesNothing() {
        PackageRow row = verifiedPackage(UplPackageTestData.workbook(7, 3));

        PackageRow queued = applies.request(row.publicId().toString(), userId);

        assertThat(queued.status()).isEqualTo(UplPackageModel.APPLYING);
        assertThat(queued.loadId()).isNotNull();
        assertThat(queued.rawRows()).isNull();
        assertThat(rawCount(queued.loadId())).isZero();
        assertThat(loads.find(queued.loadId()))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(FndLoad.PENDING));
        assertThat(jdbc.sql("select handler, args->>'packageId' from fnd_job_queue")
                        .query((rs, n) -> rs.getString(1) + " " + rs.getString(2))
                        .list())
                .containsExactly(UplPref.JOB_APPLY + " " + row.publicId());
        assertThat(packages.get(row.publicId().toString()).status()).isEqualTo(UplPackageModel.APPLYING);
    }

    @Test
    @DisplayName("AC-10: все строки данных файла легли в raw с листом и номером строки, пакет «применён»")
    void applyWritesAllDataRowsWithSourceAddress() {
        PackageRow row = verifiedPackage(UplPackageTestData.workbook(7, 3));

        PackageRow applied = apply(row);

        assertThat(applied.status()).isEqualTo(UplPackageModel.APPLIED);
        assertThat(applied.loadId()).isNotNull();
        assertThat(applied.rawRows()).isEqualTo(10);
        assertThat(applied.rowsTotal()).isEqualTo(10);
        long loadId = applied.loadId();
        assertThat(rawCount(loadId)).isEqualTo(10);
        assertThat(dwhJdbc.sql("select count(*) from raw.rows where load_id = :id and sheet = :sheet"
                                + " and source_row_no is not null and source_file_id = :file")
                        .param("id", loadId)
                        .param("sheet", UplPackageTestData.SHEET)
                        .param("file", row.fileId())
                        .query(Long.class)
                        .single())
                .isEqualTo(10);
        assertThat(raw.read(loadId))
                .extracting(FndRawRow::rowNo)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(loads.find(loadId))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(FndLoad.APPLIED));
        assertThat(jdbc.sql("select count(*) from fnd_load_log where load_id = :id and event = 'applied'")
                        .param("id", loadId)
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("select status from fnd_job_runs")
                        .query(String.class)
                        .list())
                .containsExactly("done");
    }

    @Test
    @DisplayName("AC-10: второй пакет того же файла и периода получает свою загрузку, первый остаётся «применён»")
    void secondPackageSamePeriodGetsOwnLoad() {
        byte[] content = UplPackageTestData.workbook(7, 3);
        PackageRow first = verifiedPackage(content);
        PackageRow second = verifiedPackage(content);

        PackageRow firstApplied = apply(first);
        PackageRow secondApplied = apply(second);

        assertThat(secondApplied.status()).isEqualTo(UplPackageModel.APPLIED);
        assertThat(secondApplied.loadId()).isNotNull().isNotEqualTo(firstApplied.loadId());
        assertThat(packages.get(first.publicId().toString()).status()).isEqualTo(UplPackageModel.APPLIED);
        assertThat(rawCount(firstApplied.loadId())).isEqualTo(10);
    }

    @Test
    @DisplayName("AC-12: применить можно только «проверен»; «применяется» и «применён» — 409, нет пакета — 404")
    void onlyVerifiedPackageCanBeApplied() {
        PackageRow row = verifiedPackage(UplPackageTestData.workbook(7, 3));
        PackageRow queued = applies.request(row.publicId().toString(), userId);
        assertConflict(() -> applies.request(row.publicId().toString(), userId), "error.upl.pkg_not_verified");
        assertThat(jobs.runQueued()).isEqualTo(1);

        assertConflict(() -> applies.request(row.publicId().toString(), userId), "error.upl.pkg_not_verified");
        assertThat(rawCount(queued.loadId())).isEqualTo(10);
        assertThat(dwhJdbc.sql("select count(*) from raw.rows")
                        .query(Long.class)
                        .single())
                .isEqualTo(10);

        PackageRow broken = parsedPackage(UplPackageTestData.brokenStructure());
        assertThat(broken.status()).isEqualTo(UplPackageModel.REJECTED);
        assertConflict(() -> applies.request(broken.publicId().toString(), userId), "error.upl.pkg_not_verified");

        assertNotFound(() -> applies.request(UUID.randomUUID().toString(), userId));
        assertNotFound(() -> applies.request("abc", userId));
    }

    @Test
    @DisplayName("AC-12: в загрузке нет принятых строк — применить нельзя, прежняя загрузка периода не заменяется")
    void packageWithoutAcceptedRowsCannotBeApplied() {
        PackageRow good = verifiedPackage(UplPackageTestData.workbook(7, 3));
        PackageRow applied = apply(good);

        for (byte[] content : List.of(UplPackageTestData.workbook(0, 3), UplPackageTestData.workbook(0, 0))) {
            String id = verifiedPackage(content).publicId().toString();

            assertConflict(() -> applies.request(id, userId), "error.upl.pkg_nothing_to_apply");
            assertThat(packages.get(id).status()).isEqualTo(UplPackageModel.VERIFIED);
            assertThat(packages.get(id).loadId()).isNull();
        }

        assertThat(jdbc.sql("select count(*) from fnd_job_queue")
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(dwhJdbc.sql("select count(*) from raw.rows")
                        .query(Long.class)
                        .single())
                .isEqualTo(10);
        assertThat(loads.find(applied.loadId()))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(FndLoad.APPLIED));
        assertThat(loads.appliedLoadIds(good.sourceCode())).containsExactly(applied.loadId());
    }

    @Test
    @DisplayName("AC-11: в raw легло меньше строк, чем в пакете — пакет «отклонён системой», загрузка неудачна")
    void reconciliationMismatchRejectsPackage() {
        PackageRow row = verifiedPackage(UplPackageTestData.workbook(7, 3));
        FndRawWriter losingLastRow = new DelegatingWriter() {
            @Override
            public long copy(long loadId, UUID sourceFileId, FndRawSource rows) {
                // One row behind the source: the last one never reaches the copy
                return raw.copy(loadId, sourceFileId, sink -> {
                    FndRawRow[] held = {null};
                    rows.emit(next -> {
                        if (held[0] != null) {
                            sink.accept(held[0]);
                        }
                        held[0] = next;
                    });
                });
            }
        };

        PackageRow result =
                runWith(losingLastRow, applies.request(row.publicId().toString(), userId));

        assertThat(result.status()).isEqualTo(UplPackageModel.REJECTED);
        assertThat(result.rejectCode()).isEqualTo(UplApplyService.UPL_PKG_RECONCILIATION);
        assertThat(result.rejectParams()).containsEntry("fileRows", 10).containsEntry("rawRows", 9);
        assertThat(result.rawRows()).isEqualTo(9);
        assertThat(loads.find(result.loadId()))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(FndLoad.FAILED));
    }

    @Test
    @DisplayName("AC-11: запись в raw упала — пакет «отклонён системой», загрузка неудачна, задание не падает")
    void rawWriteFailureRejectsPackage() {
        PackageRow row = verifiedPackage(UplPackageTestData.workbook(7, 3));
        FndRawWriter failing = new DelegatingWriter() {
            @Override
            public long copy(long loadId, UUID sourceFileId, FndRawSource rows) {
                throw new IllegalStateException("TEST");
            }
        };

        PackageRow result = runWith(failing, applies.request(row.publicId().toString(), userId));

        assertThat(result.status()).isEqualTo(UplPackageModel.REJECTED);
        assertThat(result.rejectCode()).isEqualTo(UplApplyService.UPL_PKG_RAW_WRITE_FAILED);
        assertThat(loads.find(result.loadId()))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(FndLoad.FAILED));
    }

    @Test
    @DisplayName("3.9: узел упал после коммита raw — повтор задания закрывает пакет их числом, строки не дублируются")
    void retryAfterCommittedRowsClosesWithoutSecondWrite() {
        PackageRow row = verifiedPackage(UplPackageTestData.workbook(7, 3));
        PackageRow queued = applies.request(row.publicId().toString(), userId);
        FndRawWriter diesAfterCommit = new DelegatingWriter() {
            @Override
            public long copy(long loadId, UUID sourceFileId, FndRawSource rows) {
                raw.copy(loadId, sourceFileId, rows);
                throw new AssertionError("TEST: процесс упал после коммита pg-dwh");
            }
        };
        assertThatThrownBy(() -> job(diesAfterCommit).run(args(queued))).isInstanceOf(AssertionError.class);
        assertThat(packages.get(row.publicId().toString()).status()).isEqualTo(UplPackageModel.APPLYING);
        assertThat(rawCount(queued.loadId())).isEqualTo(10);

        FndRawWriter mustNotWrite = new DelegatingWriter() {
            @Override
            public long copy(long loadId, UUID sourceFileId, FndRawSource rows) {
                throw new AssertionError("TEST: повтор не должен писать строки второй раз");
            }
        };
        job(mustNotWrite).run(args(queued));

        PackageRow applied = packages.get(row.publicId().toString());
        assertThat(applied.status()).isEqualTo(UplPackageModel.APPLIED);
        assertThat(applied.rawRows()).isEqualTo(10);
        assertThat(rawCount(queued.loadId())).isEqualTo(10);
    }

    @Test
    @DisplayName("3.9: пакет уже закрыт (восстановлением или прежней попыткой) — задание ничего не делает")
    void jobLeavesClosedPackageAlone() {
        PackageRow row = verifiedPackage(UplPackageTestData.workbook(7, 3));
        PackageRow queued = applies.request(row.publicId().toString(), userId);
        backdate(queued);
        jobOutOfAttempts(queued);
        tx.executeWithoutResult(status -> recovery.run(Map.of("staleMinutes", 60)));

        // An operator puts the failed apply job back; it runs from the queue and leaves the closed package alone.
        jdbc.sql("update fnd_job_queue set failed_at = null, attempts = 0").update();
        assertThat(jobs.runQueued()).isEqualTo(1);

        PackageRow closed = packages.get(row.publicId().toString());
        assertThat(closed.status()).isEqualTo(UplPackageModel.REJECTED);
        assertThat(closed.rejectCode()).isEqualTo(UplApplyService.UPL_PKG_APPLY_INTERRUPTED);
        assertThat(rawCount(queued.loadId())).isZero();

        PackageRow done = apply(verifiedPackage(UplPackageTestData.workbook(2, 0)));
        applyJob.run(args(done));
        assertThat(packages.get(done.publicId().toString())).isEqualTo(done);
        assertThat(rawCount(done.loadId())).isEqualTo(2);
    }

    @Test
    @DisplayName("P0: применение не закрылось дольше порога — задание восстановления закрывает пакет и загрузку")
    void interruptedApplyIsClosedByRecoveryJob() {
        PackageRow stale = applies.request(
                verifiedPackage(UplPackageTestData.workbook(7, 3)).publicId().toString(), userId);
        PackageRow fresh = applies.request(
                verifiedPackage(UplPackageTestData.workbook(7, 3)).publicId().toString(), userId);
        backdate(stale);
        jobOutOfAttempts(stale);

        tx.executeWithoutResult(status -> recovery.run(Map.of("staleMinutes", 60)));

        PackageRow closed = packages.get(stale.publicId().toString());
        assertThat(closed.status()).isEqualTo(UplPackageModel.REJECTED);
        assertThat(closed.rejectCode()).isEqualTo(UplApplyService.UPL_PKG_APPLY_INTERRUPTED);
        assertThat(loads.find(closed.loadId()))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(FndLoad.FAILED));
        assertThat(jdbc.sql("select count(*) from fnd_load_log where package_ref = :ref and event = 'failed'")
                        .param("ref", closed.publicId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
        assertConflict(() -> applies.request(closed.publicId().toString(), userId), "error.upl.pkg_not_verified");

        // An apply younger than the threshold may still be running, so its job is left alone
        PackageRow running = packages.get(fresh.publicId().toString());
        assertThat(running.status()).isEqualTo(UplPackageModel.APPLYING);
        assertThat(loads.find(running.loadId()))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(FndLoad.PENDING));
    }

    @Test
    @DisplayName("P0: задание восстановления стоит в расписании")
    void recoveryJobIsScheduled() {
        assertThat(jdbc.sql("select interval_sec from fnd_job_schedule where code = :code and enabled")
                        .param("code", UplApplyRecoveryJob.CODE)
                        .query(Integer.class)
                        .optional())
                .hasValue(900);
    }

    // ---------- helpers ----------

    /** Asks to apply and runs the queue, as the worker would; the package as the client then reads it. */
    private PackageRow apply(PackageRow row) {
        applies.request(row.publicId().toString(), userId);
        assertThat(jobs.runQueued()).isEqualTo(1);
        return packages.get(row.publicId().toString());
    }

    private PackageRow runWith(FndRawWriter writer, PackageRow queued) {
        job(writer).run(args(queued));
        return packages.get(queued.publicId().toString());
    }

    private UplApplyJob job(FndRawWriter writer) {
        return new UplApplyJob(repo, sources, files, parser, loads, writer, actors, tx);
    }

    private Map<String, Object> args(PackageRow row) {
        return Map.of("packageId", row.publicId().toString(), "userId", userId);
    }

    private void backdate(PackageRow row) {
        tx.executeWithoutResult(status -> {
            actors.apply(actors.system());
            jdbc.sql("update upl_packages set modified_at = now() - interval '2 hours' where id = :id")
                    .param("id", row.id())
                    .update();
        });
    }

    /** The apply job of the package ran out of attempts: the runner marked its queue row failed. */
    private void jobOutOfAttempts(PackageRow row) {
        jdbc.sql("update fnd_job_queue set failed_at = now() where args ->> 'packageId' = :id")
                .param("id", row.publicId().toString())
                .update();
    }

    private PackageRow verifiedPackage(byte[] content) {
        PackageRow row = parsedPackage(content);
        assertThat(row.status()).isEqualTo(UplPackageModel.VERIFIED);
        return row;
    }

    private PackageRow parsedPackage(byte[] content) {
        FileRecord file = files.uploadFile(
                "TEST.xlsx", UplPackageTestData.XLSX_MIME, new ByteArrayInputStream(content), content.length, userId);
        PackageRow row = packages.register(new NewPackage(
                sourceId,
                1,
                PERIOD_FROM,
                PERIOD_TO,
                file.id(),
                file.originalName(),
                file.sha256(),
                file.sizeBytes(),
                userId));
        parseJob.run(Map.of("packageId", row.publicId().toString()));
        return packages.get(row.publicId().toString());
    }

    private long rawCount(long loadId) {
        return dwhJdbc.sql("select count(*) from raw.rows where load_id = :id")
                .param("id", loadId)
                .query(Long.class)
                .single();
    }

    private static void assertConflict(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(e.getErrorCode().getDefaultStatus()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo(code);
        });
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(e.getMessageKey()).isEqualTo("error.upl.pkg_not_found");
        });
    }

    /** The real writer, with one method replaced by a test. */
    private abstract class DelegatingWriter implements FndRawWriter {
        @Override
        public long count(long loadId) {
            return raw.count(loadId);
        }

        @Override
        public List<FndRawRow> read(long loadId) {
            return raw.read(loadId);
        }
    }
}
