package com.greenwhite.dwh.instance.mf.scan;

import com.greenwhite.dwh.spi.storage.FileScanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "dwh.files.scanner.clamav.enabled", havingValue = "true")
public class ClamAvFileScanner implements FileScanner {

    private static final int CHUNK_BYTES = 8 * 1024;
    private static final int MAX_RESPONSE_BYTES = 4 * 1024;

    private final String host;
    private final int port;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final MeterRegistry meterRegistry;

    public ClamAvFileScanner(
            MeterRegistry meterRegistry,
            @Value("${dwh.files.scanner.clamav.host:clamav}") String host,
            @Value("${dwh.files.scanner.clamav.port:3310}") int port,
            @Value("${dwh.files.scanner.clamav.connect-timeout:3s}") Duration connectTimeout,
            @Value("${dwh.files.scanner.clamav.read-timeout:60s}") Duration readTimeout) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("ClamAV host is required");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("ClamAV port is invalid");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("ClamAV connect timeout must be positive");
        }
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("ClamAV read timeout must be positive");
        }
        this.meterRegistry = meterRegistry;
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public String getProviderCode() {
        return "clamav";
    }

    @Override
    public ScanResult scan(InputStream content, long sizeBytes, String contentType) {
        long startedAt = System.nanoTime();
        String outcome = "error";
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis(connectTimeout));
            socket.setSoTimeout(timeoutMillis(readTimeout));

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[CHUNK_BYTES];
            int count;
            while ((count = content.read(chunk)) >= 0) {
                if (count == 0) continue;
                output.writeInt(count);
                output.write(chunk, 0, count);
            }
            output.writeInt(0);
            output.flush();

            ScanResult result = parseResponse(readResponse(socket.getInputStream()));
            outcome = result.verdict().name().toLowerCase(Locale.ROOT);
            return result;
        } catch (IOException exception) {
            throw new IllegalStateException("ClamAV scan failed", exception);
        } finally {
            Timer.builder("dwh.file.scanner")
                    .description("End-to-end file scanner latency")
                    .tag("provider", getProviderCode())
                    .tag("outcome", outcome)
                    .publishPercentiles(0.95, 0.99)
                    .register(meterRegistry)
                    .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)));
        }
    }

    private static ScanResult parseResponse(String response) {
        if (response.endsWith(" OK")) {
            return ScanResult.clean();
        }
        String prefix = "stream: ";
        String suffix = " FOUND";
        if (response.startsWith(prefix) && response.endsWith(suffix)) {
            String threat = response.substring(prefix.length(), response.length() - suffix.length()).trim();
            return ScanResult.infected(threat.isBlank() ? "unknown" : threat);
        }
        throw new IllegalStateException("Unexpected ClamAV response");
    }

    private static String readResponse(InputStream input) throws IOException {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        for (int i = 0; i < MAX_RESPONSE_BYTES; i++) {
            int next = input.read();
            if (next < 0 || next == 0 || next == '\n') break;
            response.write(next);
        }
        if (response.size() == 0 || response.size() == MAX_RESPONSE_BYTES) {
            throw new IOException("Invalid ClamAV response length");
        }
        return response.toString(StandardCharsets.US_ASCII).trim();
    }

    private static int timeoutMillis(Duration timeout) {
        return Math.toIntExact(Math.min(timeout.toMillis(), Integer.MAX_VALUE));
    }
}
