package com.smartup24.cms.instance.upl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import com.smartup24.cms.instance.jobs.runner.JobRunner;
import com.smartup24.cms.instance.kauth.pref.KauthPref;
import com.smartup24.cms.instance.md.service.MdAuditActors;
import com.smartup24.cms.instance.md.service.MdUserService;
import com.smartup24.cms.instance.support.EmbeddedPostgresTest;
import com.smartup24.cms.instance.upl.format.UplSourceService;
import com.smartup24.cms.instance.upl.upload.UplPackageModel;
import com.smartup24.cms.instance.upl.upload.UplUploadService;
import com.smartup24.cms.instance.upl.upload.UplUploadService.Upload;
import com.smartup24.cms.instance.warehouse.WarehousePref;
import jakarta.servlet.http.Cookie;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * Plan 10/10, item 3.9, acceptance: a 50 MB file of a million rows is uploaded, checked by the parse job and applied
 * through {@code POST /apply} and the apply job, with the whole application in a 512 MB heap; the apply answers in
 * under a second. It runs for two files: one with 2,000 distinct names and one with a name per row, a million distinct
 * shared strings, which the reader keeps in memory as it reads them.
 *
 * <p>Tagged {@code large}: the everyday suite skips it (it writes a 50 MB file and a million raw rows, minutes of
 * work); {@link UplApplyStreamingTest} proves the streaming there. Run it in its own JVM capped at 512 MB:
 *
 * <pre>
 * mvn -pl apps/server test -Pupl-large
 * </pre>
 *
 * <p>The profile runs only this tag with {@code -Xmx512m}; an OutOfMemoryError anywhere in the path fails it. The test
 * also samples the heap and prints the peak, the file size and the durations.
 */
@Tag("large")
class UplLargeApplyTest extends EmbeddedPostgresTest {

    private static final Logger log = LoggerFactory.getLogger(UplLargeApplyTest.class);

    private static final String BASE = "/api/v1/upl/packages";
    private static final String PASSWORD = "StrongPassword2026!";
    private static final int ROWS = 1_000_000;
    private static final long MB = 1024 * 1024;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private MdUserService users;

    @Autowired
    private UplSourceService sources;

    @Autowired
    private UplUploadService uploads;

    @Autowired
    private JobRunner jobs;

    @Autowired
    private MdAuditActors actors;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    @Qualifier(WarehousePref.QUALIFIER)
    private JdbcClient dwhJdbc;

    @Autowired
    private TransactionTemplate tx;

    @Test
    @DisplayName("3.9: файл 50 МБ на миллион строк применяется в куче 512 МБ, ответ на «Применить» — меньше секунды")
    void millionRowFileAppliesInHalfAGigabyte(@TempDir Path dir) throws Exception {
        applyMillionRows(dir, false);
    }

    /**
     * The same file with a name of its own in every row: a million distinct shared strings. The reader keeps the
     * shared strings it has read in memory, so this is the case where the heap grows with the file.
     */
    @Test
    @DisplayName("3.9: миллион строк с уникальными строками (1 000 000 разных) применяется в куче 512 МБ")
    void millionDistinctStringsApplyInHalfAGigabyte(@TempDir Path dir) throws Exception {
        applyMillionRows(dir, true);
    }

    private void applyMillionRows(Path dir, boolean uniqueNames) throws Exception {
        String variant = uniqueNames ? "unique strings" : "repeated strings";
        MockMvc mvc =
                MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        long systemUserId = jdbc.sql("select id from md_users where login = 'system'")
                .query(Long.class)
                .single();
        tx.executeWithoutResult(status -> {
            actors.apply(actors.system());
            jdbc.sql("delete from fnd_job_queue").update();
        });
        long sourceId = UplLargeWorkbook.publishedSource(sources, systemUserId, LocalDate.of(2026, 1, 1));
        String login = "upl-large-" + UUID.randomUUID().toString().substring(0, 8);
        users.createUser(
                "TEST " + login,
                login,
                login + "@test.local",
                null,
                PASSWORD,
                null,
                "ru",
                "UTC",
                null,
                Map.of(),
                false,
                false,
                List.of(jdbc.sql("select id from md_roles where pcode = 'chief_admin'")
                        .query(Long.class)
                        .single()),
                systemUserId);

        long started = System.nanoTime();
        Path file = UplLargeWorkbook.write(dir.resolve("TEST-large.xlsx"), ROWS, uniqueNames);
        long fileBytes = Files.size(file);
        report(
                variant,
                "file",
                "%d bytes (%d MB), %d rows, written in %s",
                fileBytes,
                fileBytes / MB,
                ROWS,
                since(started));
        assertThat(fileBytes).isBetween(40 * MB, UplLimits.MAX_FILE_BYTES);

        try (HeapSampler heap = new HeapSampler()) {
            Session admin = login(mvc, login);

            // The upload goes through the service from disk: a mock multipart request would hold the 50 MB in the
            // heap, where a servlet container spools it to a file
            started = System.nanoTime();
            String id = uploads.receive(
                            new Upload(
                                    String.valueOf(sourceId),
                                    "2026-03-01",
                                    "2026-03-31",
                                    true,
                                    "TEST-large.xlsx",
                                    UplPackageTestData.XLSX_MIME,
                                    fileBytes,
                                    new FileSystemResource(file)),
                            systemUserId)
                    .publicId()
                    .toString();
            report(variant, "upload", "received in %s", since(started));

            started = System.nanoTime();
            assertThat(jobs.runQueued()).isEqualTo(1);
            MockHttpServletResponse parsed = read(mvc, admin, BASE + "/" + id);
            assertThat((String) JsonPath.read(parsed.getContentAsString(), "$.status"))
                    .as(parsed.getContentAsString())
                    .isEqualTo(UplPackageModel.VERIFIED);
            assertThat((Integer) JsonPath.read(parsed.getContentAsString(), "$.rowsTotal"))
                    .isEqualTo(ROWS);
            report(variant, "parse", "verified in %s", since(started));

            started = System.nanoTime();
            MockHttpServletResponse queued = send(mvc, admin, post(BASE + "/" + id + "/apply"));
            Duration answer = Duration.ofNanos(System.nanoTime() - started);
            assertThat(queued.getStatus()).as(queued.getContentAsString()).isEqualTo(202);
            assertThat((String) JsonPath.read(queued.getContentAsString(), "$.status"))
                    .isEqualTo(UplPackageModel.APPLYING);
            report(variant, "apply request", "202 in %d ms", answer.toMillis());
            assertThat(answer).isLessThan(Duration.ofSeconds(1));

            started = System.nanoTime();
            assertThat(jobs.runQueued()).isEqualTo(1);
            MockHttpServletResponse applied = read(mvc, admin, queued.getHeader("Location"));
            String body = applied.getContentAsString();
            assertThat((String) JsonPath.read(body, "$.status")).as(body).isEqualTo(UplPackageModel.APPLIED);
            assertThat((Integer) JsonPath.read(body, "$.rawRows")).isEqualTo(ROWS);
            long loadId = ((Number) JsonPath.read(body, "$.loadId")).longValue();
            assertThat(dwhJdbc.sql("select count(*) from raw.rows where load_id = :id")
                            .param("id", loadId)
                            .query(Long.class)
                            .single())
                    .isEqualTo(ROWS);
            report(variant, "apply job", "applied in %s", since(started));
            report(
                    variant,
                    "heap",
                    "max %d MB, peak used %d MB",
                    Runtime.getRuntime().maxMemory() / MB,
                    heap.peak() / MB);
        }
    }

    private static void report(String variant, String step, String format, Object... args) {
        log.info("[upl-large] {} {}: {}", variant, step, String.format(format, args));
    }

    private static String since(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis() + " ms";
    }

    private record Session(Cookie session, Cookie csrf) {}

    private static Session login(MockMvc mvc, String login) throws Exception {
        var response = mvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(new ObjectMapper()
                                .writeValueAsString(
                                        Map.of("login", login, "password", PASSWORD, "deviceInfo", "test"))))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        Cookie session = response.getCookie(KauthPref.SESSION_COOKIE_NAME);
        Cookie csrf = response.getCookie("XSRF-TOKEN");
        if (csrf == null) {
            csrf = mvc.perform(get("/api/v1/auth/me").cookie(session))
                    .andReturn()
                    .getResponse()
                    .getCookie("XSRF-TOKEN");
        }
        return new Session(session, csrf);
    }

    private static MockHttpServletResponse send(
            MockMvc mvc, Session session, AbstractMockHttpServletRequestBuilder<?> request) throws Exception {
        request.cookie(session.session(), session.csrf());
        request.header("X-XSRF-TOKEN", session.csrf().getValue());
        return mvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletResponse read(MockMvc mvc, Session session, String url) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(url).cookie(session.session(), session.csrf()))
                .andReturn()
                .getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response;
    }

    /** Samples the heap in use every few milliseconds on a daemon thread and keeps the highest value seen. */
    private static final class HeapSampler implements AutoCloseable {
        private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        private final AtomicLong peak = new AtomicLong();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        HeapSampler() {
            thread = Thread.ofPlatform().daemon().name("upl-large-heap").start(() -> {
                while (running.get()) {
                    peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                }
            });
        }

        long peak() {
            return peak.get();
        }

        @Override
        public void close() {
            running.set(false);
            thread.interrupt();
        }
    }
}
