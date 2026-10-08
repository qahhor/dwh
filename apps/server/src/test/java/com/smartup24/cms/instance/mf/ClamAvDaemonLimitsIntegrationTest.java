package com.smartup24.cms.instance.mf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.mf.scan.ClamAvFileScanner;
import com.smartup24.cms.spi.storage.FileScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.yaml.snakeyaml.Yaml;

/**
 * Plan 10/10, item 7.6 (NFR-SEC-05): the hardened ClamAV image of the release, built here from
 * {@code deploy/images/clamav/Dockerfile}, with the production clamd options of
 * {@code deploy/compose/docker-compose.prod.yml} scans a 50 MiB upload whole. The daemon starts on a single test
 * signature instead of the public database, so the test downloads no signatures: a marker in the last bytes of a
 * 50 MiB stream is found, the same stream without it is clean, and a stream past the server's cap fails closed.
 */
@Testcontainers(disabledWithoutDocker = true)
class ClamAvDaemonLimitsIntegrationTest {

    private static final Path PRODUCTION_COMPOSE = Path.of("../../deploy/compose/docker-compose.prod.yml");
    private static final Path HARDENED_IMAGE = Path.of("../../deploy/images/clamav");
    private static final String RELEASE_IMAGE = "/clamav:${APP_VERSION";
    private static final String MARKER = "SMC-TEST-MARKER-7-6";
    private static final long FIFTY_MIB = 50L * 1024 * 1024;

    @Container
    static GenericContainer<?> clamd = daemon();

    @Test
    void aFiftyMebibyteUploadIsScannedToItsLastByte() {
        ClamAvFileScanner scanner = scanner(ClamAvFileScanner.DEFAULT_MAX_STREAM_SIZE);

        FileScanner.ScanResult clean = scanner.scan(new Upload(FIFTY_MIB, false), FIFTY_MIB, "application/pdf");
        FileScanner.ScanResult marked = scanner.scan(new Upload(FIFTY_MIB, true), FIFTY_MIB, "application/pdf");

        assertThat(clean.verdict()).isEqualTo(FileScanner.Verdict.CLEAN);
        assertThat(marked.verdict()).isEqualTo(FileScanner.Verdict.INFECTED);
        assertThat(marked.threatName()).startsWith("SmcTest.Marker");
    }

    @Test
    void aStreamPastTheDaemonsLimitFailsClosed() {
        long tooLong = 70L * 1024 * 1024;
        ClamAvFileScanner withoutOwnCap = scanner(DataSize.ofMegabytes(100));

        assertThatThrownBy(() -> withoutOwnCap.scan(new Upload(tooLong, false), tooLong, "application/pdf"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ClamAvFileScanner scanner(DataSize cap) {
        return new ClamAvFileScanner(
                new SimpleMeterRegistry(),
                clamd.getHost(),
                clamd.getMappedPort(3310),
                Duration.ofSeconds(5),
                Duration.ofSeconds(60),
                cap);
    }

    private static GenericContainer<?> daemon() {
        Map<String, Object> clamav = productionService();
        GenericContainer<?> container = new GenericContainer<>(hardenedImage(clamav))
                .withCopyToContainer(Transferable.of(signature(), 0644), "/var/lib/clamav/smc-test.ndb")
                // An argument starting with "-" makes the image's /init exec clamd alone, after it has applied the
                // CLAMD_CONF_* options: no freshclam, no network.
                .withCommand("--foreground")
                .withExposedPorts(3310)
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));
        productionOptions(clamav).forEach(container::withEnv);
        return container;
    }

    private static String signature() {
        return "SmcTest.Marker:0:*:" + HexFormat.of().formatHex(MARKER.getBytes(StandardCharsets.US_ASCII)) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> productionService() {
        try (Reader compose = Files.newBufferedReader(PRODUCTION_COMPOSE, StandardCharsets.UTF_8)) {
            Map<String, Object> root = new Yaml().load(compose);
            return (Map<String, Object>) ((Map<String, Object>) root.get("services")).get("clamav");
        } catch (IOException e) {
            throw new IllegalStateException("The production Compose file cannot be read", e);
        }
    }

    /** Production runs the release's own ClamAV image; this builds the same Dockerfile. */
    private static ImageFromDockerfile hardenedImage(Map<String, Object> clamav) {
        String image = String.valueOf(clamav.get("image"));
        if (!image.contains(RELEASE_IMAGE)) {
            throw new IllegalStateException("Production does not run the hardened ClamAV image: " + image);
        }
        return new ImageFromDockerfile("smartupcms/clamav-it", true).withFileFromPath(".", HARDENED_IMAGE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> productionOptions(Map<String, Object> clamav) {
        Map<String, String> options = new LinkedHashMap<>();
        Object environment = clamav.get("environment");
        if (environment instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).forEach((key, value) -> {
                if (key.startsWith("CLAMD_CONF_")) options.put(key, String.valueOf(value));
            });
        }
        assertThat(options).as("production clamd options").containsKey("CLAMD_CONF_StreamMaxLength");
        return options;
    }

    /** {@code size} bytes of a varied pattern made as read, ending with the marker when asked. */
    private static final class Upload extends InputStream {
        private final long size;
        private final byte[] tail;
        private long position;

        Upload(long size, boolean marked) {
            this.size = size;
            this.tail = marked ? MARKER.getBytes(StandardCharsets.US_ASCII) : new byte[0];
        }

        @Override
        public int read() {
            if (position >= size) return -1;
            return byteAt(position++) & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= size) return -1;
            int count = (int) Math.min(length, size - position);
            for (int i = 0; i < count; i++) buffer[offset + i] = byteAt(position++);
            return count;
        }

        private byte byteAt(long index) {
            long fromTail = index - (size - tail.length);
            if (fromTail >= 0) return tail[(int) fromTail];
            return (byte) ((index * 31 + (index >>> 9)) % 253);
        }
    }
}
