package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.jobs.config.JobProperties;
import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.md.service.MdAuditActors;
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
import com.smartup24.cms.instance.warehouse.api.RawRow;
import com.smartup24.cms.instance.warehouse.api.RawSource;
import com.smartup24.cms.instance.warehouse.api.RawWriter;
import com.smartup24.cms.instance.warehouse.api.WarehouseLoad;
import com.smartup24.cms.instance.warehouse.api.WarehouseUnavailableException;
import com.smartup24.cms.instance.warehouse.load.WarehouseLoadService;
import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, items 3.8 and 3.9: the apply job leaves a transient failure to the runner's retries and closes the
 * package only on the last attempt; the recovery job never closes an apply whose job is still in the queue.
 */
class UplApplyRetryTest extends EmbeddedPostgresTest {

    private static final LocalDate PERIOD_FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate PERIOD_TO = LocalDate.of(2026, 3, 31);

    @Autowired
    private UplApplyService applies;

    @Autowired
    private UplApplyRecoveryJob recovery;

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
    private WarehouseLoadService loads;

    @Autowired
    private RawWriter raw;

    @Autowired
    private MdAuditActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PlatformTransactionManager transactions;

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
        sourceId = UplPackageTestData.publishedSource(sources, userId, LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("3.8: pg-dwh away on one attempt — the package stays «применяется», the retry applies it")
    void transientRawFailureIsRetried() {
        PackageRow row = verifiedPackage();
        AtomicInteger calls = new AtomicInteger();
        JobRunner runner = runner(3, new DelegatingWriter() {
            @Override
            public long copy(long loadId, UUID sourceFileId, RawSource rows) {
                if (calls.incrementAndGet() == 1) {
                    throw new WarehouseUnavailableException(new SQLException("TEST pg-dwh away"));
                }
                return raw.copy(loadId, sourceFileId, rows);
            }
        });
        PackageRow queued = applies.request(row.publicId().toString(), userId);

        assertThat(runner.runNext()).contains(false);
        assertThat(packages.get(row.publicId().toString()).status()).isEqualTo(UplPackageModel.APPLYING);
        assertThat(loads.find(queued.loadId()))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(WarehouseLoad.PENDING));

        assertThat(runner.runQueued()).isEqualTo(1);

        PackageRow applied = packages.get(row.publicId().toString());
        assertThat(applied.status()).isEqualTo(UplPackageModel.APPLIED);
        assertThat(applied.rawRows()).isEqualTo(10);
        assertThat(runStatuses()).containsExactly("failed", "done");
    }

    @Test
    @DisplayName("3.8: pg-dwh away on every attempt — the last one closes the package with the write failure")
    void transientRawFailureOnTheLastAttemptRejects() {
        PackageRow row = verifiedPackage();
        JobRunner runner = runner(2, new DelegatingWriter() {
            @Override
            public long copy(long loadId, UUID sourceFileId, RawSource rows) {
                throw new WarehouseUnavailableException(new SQLException("TEST pg-dwh away"));
            }
        });
        PackageRow queued = applies.request(row.publicId().toString(), userId);

        assertThat(runner.runNext()).contains(false);
        assertThat(packages.get(row.publicId().toString()).status()).isEqualTo(UplPackageModel.APPLYING);
        assertThat(runner.runNext())
                .as("the last attempt closes the package and ends")
                .contains(true);

        PackageRow closed = packages.get(row.publicId().toString());
        assertThat(closed.status()).isEqualTo(UplPackageModel.REJECTED);
        assertThat(closed.rejectCode()).isEqualTo(UplApplyService.UPL_PKG_RAW_WRITE_FAILED);
        assertThat(loads.find(queued.loadId()))
                .hasValueSatisfying(load -> assertThat(load.status()).isEqualTo(WarehouseLoad.FAILED));
        assertThat(runStatuses()).containsExactly("failed", "done");
    }

    @Test
    @DisplayName("3.9: a failure a retry would not fix closes the package on the first attempt")
    void permanentRawFailureRejectsAtOnce() {
        PackageRow row = verifiedPackage();
        JobRunner runner = runner(3, new DelegatingWriter() {
            @Override
            public long copy(long loadId, UUID sourceFileId, RawSource rows) {
                throw new IllegalStateException("TEST bug");
            }
        });
        applies.request(row.publicId().toString(), userId);

        assertThat(runner.runQueued()).isEqualTo(1);

        assertThat(packages.get(row.publicId().toString()).rejectCode())
                .isEqualTo(UplApplyService.UPL_PKG_RAW_WRITE_FAILED);
        assertThat(runStatuses()).containsExactly("done");
    }

    @Test
    @DisplayName("P0: an old apply whose job is queued, waits for a retry or runs is not closed by recovery")
    void recoveryLeavesAppliesWithAJobInTheQueue() {
        PackageRow waiting = applies.request(verifiedPackage().publicId().toString(), userId);
        PackageRow retrying = applies.request(verifiedPackage().publicId().toString(), userId);
        PackageRow running = applies.request(verifiedPackage().publicId().toString(), userId);
        PackageRow outOfAttempts = applies.request(verifiedPackage().publicId().toString(), userId);
        PackageRow jobGone = applies.request(verifiedPackage().publicId().toString(), userId);
        tx.executeWithoutResult(status -> {
            actors.apply(actors.system());
            jdbc.sql("update upl_packages set modified_at = now() - interval '2 hours'")
                    .update();
        });
        queueRow(retrying, "attempts = 1, next_run_at = now() + interval '10 minutes'");
        queueRow(running, "attempts = 1, locked_by = 'TEST node', locked_until = now() + interval '5 minutes'");
        queueRow(outOfAttempts, "attempts = 5, failed_at = now()");
        jdbc.sql("delete from fnd_job_queue where args ->> 'packageId' = :id")
                .param("id", jobGone.publicId().toString())
                .update();

        tx.executeWithoutResult(status -> recovery.run(Map.of("staleMinutes", 60)));

        for (PackageRow alive : List.of(waiting, retrying, running)) {
            assertThat(packages.get(alive.publicId().toString()).status())
                    .as("package %s", alive.publicId())
                    .isEqualTo(UplPackageModel.APPLYING);
        }
        for (PackageRow interrupted : List.of(outOfAttempts, jobGone)) {
            PackageRow closed = packages.get(interrupted.publicId().toString());
            assertThat(closed.status()).isEqualTo(UplPackageModel.REJECTED);
            assertThat(closed.rejectCode()).isEqualTo(UplApplyService.UPL_PKG_APPLY_INTERRUPTED);
        }
    }

    // ---------- helpers ----------

    private JobRunner runner(int maxAttempts, RawWriter writer) {
        UplApplyJob job = new UplApplyJob(repo, sources, files, parser, loads, writer, actors, tx);
        return new JobRunner(
                jdbc,
                json,
                transactions,
                List.of(job),
                new JobProperties(maxAttempts, Duration.ZERO, Duration.ZERO, Duration.ofMinutes(1)));
    }

    private void queueRow(PackageRow row, String assignments) {
        jdbc.sql("update fnd_job_queue set " + assignments + " where args ->> 'packageId' = :id")
                .param("id", row.publicId().toString())
                .update();
    }

    private List<String> runStatuses() {
        return jdbc.sql("select status from fnd_job_runs order by id")
                .query(String.class)
                .list();
    }

    private PackageRow verifiedPackage() {
        byte[] content = UplPackageTestData.workbook(7, 3);
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
        PackageRow parsed = packages.get(row.publicId().toString());
        assertThat(parsed.status()).isEqualTo(UplPackageModel.VERIFIED);
        return parsed;
    }

    /** The real writer, with one method replaced by a test. */
    private abstract class DelegatingWriter implements RawWriter {
        @Override
        public long count(long loadId) {
            return raw.count(loadId);
        }

        @Override
        public List<RawRow> read(long loadId) {
            return raw.read(loadId);
        }
    }
}
