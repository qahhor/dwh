package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartup24.cms.instance.fnd.FndPref;
import com.smartup24.cms.instance.fnd.api.FndRawRow;
import com.smartup24.cms.instance.fnd.api.FndRawSource;
import com.smartup24.cms.instance.fnd.api.FndRawWriter;
import com.smartup24.cms.instance.fnd.load.FndLoadService;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.mf.repository.MfFileRepository.FileRecord;
import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.parse.UplParseJob;
import com.smartup24.cms.instance.upl.parse.UplXlsxParser;
import com.smartup24.cms.instance.upl.upload.UplApplyJob;
import com.smartup24.cms.instance.upl.upload.UplApplyService;
import com.smartup24.cms.instance.upl.upload.UplPackageModel;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.NewPackage;
import com.smartup24.cms.instance.upl.upload.UplPackageModel.PackageRow;
import com.smartup24.cms.instance.upl.upload.UplPackageRepository;
import com.smartup24.cms.instance.upl.upload.UplPackageService;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Plan 10/10, item 3.9, in the everyday suite: the apply streams. A file of {@value #ROWS} rows goes through the real
 * parser and the real {@code COPY}; a probe between them counts the rows handed on but not yet taken and, near the end
 * of the file, measures the heap after a full collection. A pipeline that collected the rows would hold them all at
 * that moment (well over {@value #RETAINED_LIMIT_MB} MB); a streaming one holds one row. The same apply under a
 * 512 MB heap with a million rows is {@link UplLargeApplyTest} (tag {@code large}).
 */
class UplApplyStreamingTest extends EmbeddedPostgresTest {

    private static final int ROWS = 200_000;
    private static final int PROBE_AT = ROWS - 1_000;
    private static final long RETAINED_LIMIT_MB = 48;
    private static final long MB = 1024 * 1024;

    @Autowired
    private UplApplyService applies;

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
    private MdAuditActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier(FndPref.DWH)
    private JdbcClient dwhJdbc;

    @Autowired
    private TransactionTemplate tx;

    private long userId;

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
        });
        dwhJdbc.sql("delete from raw.rows").update();
    }

    @Test
    @DisplayName("3.9: строки идут из разборщика в COPY по одной — память не растёт с числом строк")
    void rowsStreamFromParserIntoCopy(@TempDir Path dir) throws Exception {
        long sourceId = UplLargeWorkbook.publishedSource(sources, userId, LocalDate.of(2026, 1, 1));
        Path file = UplLargeWorkbook.write(dir.resolve("TEST-stream.xlsx"), ROWS);
        PackageRow row = verifiedPackage(sourceId, file);
        assertThat(row.rowsTotal()).isEqualTo(ROWS);
        PackageRow queued = applies.request(row.publicId().toString(), userId);

        Probe probe = new Probe();
        long baseline = probe.liveHeap();
        new UplApplyJob(repo, sources, files, parser, loads, probe, actors, tx)
                .run(Map.of("packageId", queued.publicId().toString(), "userId", userId));

        PackageRow applied = packages.get(row.publicId().toString());
        assertThat(applied.status()).as("%s", applied.rejectCode()).isEqualTo(UplPackageModel.APPLIED);
        assertThat(applied.rawRows()).isEqualTo(ROWS);
        assertThat(dwhJdbc.sql("select count(*) from raw.rows where load_id = :id")
                        .param("id", applied.loadId())
                        .query(Long.class)
                        .single())
                .isEqualTo(ROWS);
        assertThat(probe.handedOn).isEqualTo(ROWS);
        assertThat(probe.maxInFlight)
                .as("rows handed on and not yet taken by COPY")
                .isEqualTo(1);
        long retainedMb = (probe.heapAtProbe - baseline) / MB;
        assertThat(retainedMb)
                .as("heap held at row %d of %d, MB (baseline %d MB)", PROBE_AT, ROWS, baseline / MB)
                .isLessThan(RETAINED_LIMIT_MB);
    }

    private PackageRow verifiedPackage(long sourceId, Path file) throws Exception {
        FileRecord stored;
        try (InputStream content = Files.newInputStream(file)) {
            stored = files.uploadFile(
                    "TEST-stream.xlsx", UplPackageTestData.XLSX_MIME, content, Files.size(file), userId);
        }
        PackageRow row = packages.register(new NewPackage(
                sourceId,
                1,
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 31),
                stored.id(),
                stored.originalName(),
                stored.sha256(),
                stored.sizeBytes(),
                userId));
        parseJob.run(Map.of("packageId", row.publicId().toString()));
        PackageRow parsed = packages.get(row.publicId().toString());
        assertThat(parsed.status())
                .as("%s %s", parsed.rejectCode(), parsed.rejectParams())
                .isEqualTo(UplPackageModel.VERIFIED);
        return parsed;
    }

    /** The real writer with a probe on the rows between the parser and COPY. */
    private final class Probe implements FndRawWriter {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private long handedOn;
        private long taken;
        private long maxInFlight;
        private long heapAtProbe;

        @Override
        public long copy(long loadId, UUID sourceFileId, FndRawSource rows) {
            return raw.copy(
                    loadId,
                    sourceFileId,
                    sink -> rows.emit(row -> {
                        handedOn++;
                        maxInFlight = Math.max(maxInFlight, handedOn - taken);
                        if (handedOn == PROBE_AT) {
                            heapAtProbe = liveHeap();
                        }
                        sink.accept(row);
                        taken++;
                    }));
        }

        /** Heap in use after full collections: what is still reachable, not what waits for the collector. */
        long liveHeap() {
            memory.gc();
            memory.gc();
            return memory.getHeapMemoryUsage().getUsed();
        }

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
