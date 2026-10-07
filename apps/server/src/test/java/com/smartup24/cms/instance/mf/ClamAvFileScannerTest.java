package com.smartup24.cms.instance.mf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartup24.cms.instance.mf.scan.ClamAvFileScanner;
import com.smartup24.cms.spi.storage.FileScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ClamAvFileScannerTest {

    @Test
    void streamsTheWholeObjectAndReturnsTheInfectedVerdict() throws Exception {
        byte[] content = "%PDF-1.7\nEICAR".getBytes(StandardCharsets.US_ASCII);

        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> receiveScan(server));
            var meterRegistry = new SimpleMeterRegistry();
            var scanner = new ClamAvFileScanner(
                    meterRegistry, "127.0.0.1", server.getLocalPort(), Duration.ofSeconds(1), Duration.ofSeconds(1));

            FileScanner.ScanResult result =
                    scanner.scan(new ByteArrayInputStream(content), content.length, "application/pdf");

            assertThat(result.verdict()).isEqualTo(FileScanner.Verdict.INFECTED);
            assertThat(result.threatName()).isEqualTo("Eicar-Test-Signature");
            assertThat(received.get(1, TimeUnit.SECONDS)).containsExactly(content);
            assertThat(meterRegistry
                            .get("smc.file.scanner")
                            .tag("provider", "clamav")
                            .tag("outcome", "infected")
                            .timer()
                            .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void aFiftyMebibyteObjectIsStreamedWholeInSmallChunks() throws Exception {
        long size = 50L * 1024 * 1024;
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<long[]> received = CompletableFuture.supplyAsync(() -> countChunks(server));
            var scanner = new ClamAvFileScanner(
                    new SimpleMeterRegistry(),
                    "127.0.0.1",
                    server.getLocalPort(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(30),
                    ClamAvFileScanner.DEFAULT_MAX_STREAM_SIZE);

            FileScanner.ScanResult result = scanner.scan(new PatternStream(size), size, "application/octet-stream");

            assertThat(result.verdict()).isEqualTo(FileScanner.Verdict.CLEAN);
            long[] chunks = received.get(30, TimeUnit.SECONDS);
            assertThat(chunks[0]).as("bytes received").isEqualTo(size);
            assertThat(chunks[1]).as("largest chunk").isLessThanOrEqualTo(8 * 1024);
        }
    }

    @Test
    void anObjectAboveTheStreamCapIsRefusedBeforeTheDaemonIsAsked() {
        var scanner = new ClamAvFileScanner(
                new SimpleMeterRegistry(),
                "127.0.0.1",
                1,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                DataSize.ofMegabytes(1));

        assertThatThrownBy(() -> scanner.scan(new ByteArrayInputStream(new byte[0]), 2L * 1024 * 1024, "text/plain"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream cap");
    }

    @Test
    void aStreamLongerThanDeclaredStopsAtTheCap() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<long[]> received = CompletableFuture.supplyAsync(() -> countChunks(server));
            var scanner = new ClamAvFileScanner(
                    new SimpleMeterRegistry(),
                    "127.0.0.1",
                    server.getLocalPort(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5),
                    DataSize.ofKilobytes(64));

            assertThatThrownBy(() -> scanner.scan(new PatternStream(1024 * 1024), 10, "text/plain"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("stream cap");
            assertThat(received.get(5, TimeUnit.SECONDS)[0]).isLessThanOrEqualTo(64 * 1024);
        }
    }

    /** Drains an INSTREAM session; returns the bytes received and the largest chunk, then answers clean. */
    private static long[] countChunks(ServerSocket server) {
        try (var socket = server.accept();
                var input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            assertThat(readZeroTerminated(input)).isEqualTo("zINSTREAM");
            long total = 0;
            long largest = 0;
            byte[] buffer = new byte[64 * 1024];
            try {
                int chunkLength;
                while ((chunkLength = input.readInt()) != 0) {
                    largest = Math.max(largest, chunkLength);
                    input.readFully(buffer, 0, chunkLength);
                    total += chunkLength;
                }
            } catch (EOFException cut) {
                return new long[] {total, largest};
            }
            socket.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new long[] {total, largest};
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** {@code size} bytes of a repeating pattern, generated as read: the test holds no 50 MiB array. */
    private static final class PatternStream extends InputStream {
        private final long size;
        private long position;

        PatternStream(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            return position < size ? (int) (position++ % 251) : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= size) return -1;
            int count = (int) Math.min(length, size - position);
            for (int i = 0; i < count; i++) buffer[offset + i] = (byte) (position++ % 251);
            return count;
        }
    }

    private static byte[] receiveScan(ServerSocket server) {
        try (var socket = server.accept();
                var input = new DataInputStream(socket.getInputStream());
                var payload = new ByteArrayOutputStream()) {
            assertThat(readZeroTerminated(input)).isEqualTo("zINSTREAM");
            int chunkLength;
            while ((chunkLength = input.readInt()) != 0) {
                payload.write(input.readNBytes(chunkLength));
            }
            socket.getOutputStream().write("stream: Eicar-Test-Signature FOUND\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return payload.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readZeroTerminated(DataInputStream input) throws Exception {
        var value = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) > 0) {
            value.write(next);
        }
        return value.toString(StandardCharsets.US_ASCII);
    }
}
