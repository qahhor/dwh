package com.greenwhite.dwh.instance.mf;

import com.greenwhite.dwh.instance.mf.scan.ClamAvFileScanner;
import com.greenwhite.dwh.spi.storage.FileScanner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ClamAvFileScannerTest {

    @Test
    void streamsTheWholeObjectAndReturnsTheInfectedVerdict() throws Exception {
        byte[] content = "%PDF-1.7\nEICAR".getBytes(StandardCharsets.US_ASCII);

        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<byte[]> received = CompletableFuture.supplyAsync(() -> receiveScan(server));
            var meterRegistry = new SimpleMeterRegistry();
            var scanner = new ClamAvFileScanner(
                    meterRegistry,
                    "127.0.0.1",
                    server.getLocalPort(),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1));

            FileScanner.ScanResult result = scanner.scan(
                    new ByteArrayInputStream(content), content.length, "application/pdf");

            assertThat(result.verdict()).isEqualTo(FileScanner.Verdict.INFECTED);
            assertThat(result.threatName()).isEqualTo("Eicar-Test-Signature");
            assertThat(received.get(1, TimeUnit.SECONDS)).containsExactly(content);
            assertThat(meterRegistry.get("dwh.file.scanner")
                    .tag("provider", "clamav")
                    .tag("outcome", "infected")
                    .timer().count()).isEqualTo(1);
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
            socket.getOutputStream().write(
                    "stream: Eicar-Test-Signature FOUND\0".getBytes(StandardCharsets.US_ASCII));
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
