package com.smartup24.cms.instance.mf.scan;

import com.smartup24.cms.spi.storage.FileScanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

@Component
@ConditionalOnProperty(name = "smc.files.scanner.clamav.enabled", havingValue = "true")
public class ClamAvFileScanner implements FileScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvFileScanner.class);

    /** One INSTREAM chunk; each is far below any StreamMaxLength, so only the stream's total meets the limit. */
    static final int CHUNK_BYTES = 8 * 1024;

    /** The default stream cap: a 50 MiB upload (FR-FILE-02) with a margin, below production's StreamMaxLength. */
    public static final DataSize DEFAULT_MAX_STREAM_SIZE = DataSize.ofMegabytes(60);

    private static final int MAX_RESPONSE_BYTES = 4 * 1024;

    private final String host;
    private final int port;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final long maxStreamBytes;
    private final MeterRegistry meterRegistry;

    public ClamAvFileScanner(
            MeterRegistry meterRegistry, String host, int port, Duration connectTimeout, Duration readTimeout) {
        this(meterRegistry, host, port, connectTimeout, readTimeout, DEFAULT_MAX_STREAM_SIZE);
    }

    /**
     * The scanner of the daemon at {@code host:port}. A stream larger than {@code maxStreamSize} is refused by the
     * scanner itself (plan 10/10, item 7.6) before clamd would cut it at its StreamMaxLength, so the outcome of an
     * oversized object is the same fail-closed error whatever the daemon's configuration.
     */
    @Autowired
    public ClamAvFileScanner(
            MeterRegistry meterRegistry,
            @Value("${smc.files.scanner.clamav.host:clamav}") String host,
            @Value("${smc.files.scanner.clamav.port:3310}") int port,
            @Value("${smc.files.scanner.clamav.connect-timeout:3s}") Duration connectTimeout,
            @Value("${smc.files.scanner.clamav.read-timeout:60s}") Duration readTimeout,
            @Value("${smc.files.scanner.clamav.max-stream-size:60MB}") DataSize maxStreamSize) {
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
        if (maxStreamSize.toBytes() <= 0) throw new IllegalArgumentException("ClamAV stream cap must be positive");
        this.maxStreamBytes = maxStreamSize.toBytes();
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
            if (sizeBytes > maxStreamBytes) throw tooLarge();
            socket.connect(new InetSocketAddress(host, port), timeoutMillis(connectTimeout));
            socket.setSoTimeout(timeoutMillis(readTimeout));

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] chunk = new byte[CHUNK_BYTES];
            long sent = 0;
            int count;
            while ((count = content.read(chunk)) >= 0) {
                if (count == 0) continue;
                sent += count;
                if (sent > maxStreamBytes) throw tooLarge();
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
            Timer.builder("smc.file.scanner")
                    .description("End-to-end file scanner latency")
                    .tag("provider", getProviderCode())
                    .tag("outcome", outcome)
                    .publishPercentiles(0.95, 0.99)
                    .register(meterRegistry)
                    .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedAt)));
        }
    }

    /**
     * Whether the daemon answers: PING must come back as PONG within the connect timeout. Used by the readiness
     * group (plan 10/10, item 0.7).
     */
    public boolean ping() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis(connectTimeout));
            socket.setSoTimeout(timeoutMillis(connectTimeout));
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return "PONG".equals(readResponse(socket.getInputStream()));
        } catch (IOException exception) {
            log.debug("clamav_ping_failed error={}", exception.toString());
            return false;
        }
    }

    private IllegalStateException tooLarge() {
        return new IllegalStateException("The object exceeds the ClamAV stream cap of " + maxStreamBytes + " bytes");
    }

    private static ScanResult parseResponse(String response) {
        if (response.endsWith(" OK")) {
            return ScanResult.clean();
        }
        String prefix = "stream: ";
        String suffix = " FOUND";
        if (response.startsWith(prefix) && response.endsWith(suffix)) {
            String threat = response.substring(prefix.length(), response.length() - suffix.length())
                    .trim();
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
