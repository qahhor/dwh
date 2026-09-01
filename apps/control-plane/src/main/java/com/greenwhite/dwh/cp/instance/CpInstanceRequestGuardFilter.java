package com.greenwhite.dwh.cp.instance;

import com.greenwhite.dwh.cp.error.CpRequestTraceFilter;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CpInstanceRequestGuardFilter extends OncePerRequestFilter {

    private static final String INSTANCE_API_PREFIX = "/api/v1/instances/";
    private static final String HEARTBEAT_PATH = "/api/v1/instances/heartbeat";

    private final ObjectMapper objectMapper;
    private final long maxBodyBytes;
    private final int heartbeatCapacity;
    private final Duration heartbeatRefill;
    private final Map<Long, Bucket> heartbeatBuckets = new ConcurrentHashMap<>();

    public CpInstanceRequestGuardFilter(
            ObjectMapper objectMapper,
            @Value("${dwh.cp.instance-api.max-body-bytes:16384}") long maxBodyBytes,
            @Value("${dwh.cp.instance-api.heartbeat-capacity:2}") int heartbeatCapacity,
            @Value("${dwh.cp.instance-api.heartbeat-refill:1m}") Duration heartbeatRefill) {
        if (maxBodyBytes <= 0 || heartbeatCapacity <= 0 || heartbeatRefill.isZero()
                || heartbeatRefill.isNegative()) {
            throw new IllegalArgumentException("Instance API limits must be positive");
        }
        this.objectMapper = objectMapper;
        this.maxBodyBytes = maxBodyBytes;
        this.heartbeatCapacity = heartbeatCapacity;
        this.heartbeatRefill = heartbeatRefill;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
                || !request.getRequestURI().startsWith(INSTANCE_API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            writeProblem(request, response, HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "instance_payload_too_large",
                    "Instance request body exceeds the configured limit");
            return;
        }

        HttpServletRequest limitedRequest = new SizeLimitedRequest(request, maxBodyBytes);
        if (HEARTBEAT_PATH.equals(request.getRequestURI()) && !tryConsumeHeartbeatToken()) {
            response.setHeader("Retry-After", String.valueOf(Math.max(1, heartbeatRefill.toSeconds())));
            writeProblem(request, response, HttpStatus.TOO_MANY_REQUESTS.value(),
                    "instance_rate_limited",
                    "Instance heartbeat rate limit exceeded");
            return;
        }

        try {
            filterChain.doFilter(limitedRequest, response);
        } catch (PayloadTooLargeException error) {
            writeProblem(request, response, HttpStatus.PAYLOAD_TOO_LARGE.value(),
                    "instance_payload_too_large",
                    "Instance request body exceeds the configured limit");
        }
    }

    private boolean tryConsumeHeartbeatToken() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof CpInstancePrincipal instance)) {
            return true;
        }
        Bucket bucket = heartbeatBuckets.computeIfAbsent(instance.instanceId(), ignored -> newHeartbeatBucket());
        return bucket.tryConsume(1);
    }

    private Bucket newHeartbeatBucket() {
        Bandwidth limit = Bandwidth.classic(
                heartbeatCapacity,
                Refill.intervally(heartbeatCapacity, heartbeatRefill));
        return Bucket.builder().addLimit(limit).build();
    }

    private void writeProblem(HttpServletRequest request,
                              HttpServletResponse response,
                              int status,
                              String errorCode,
                              String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        Object traceId = request.getAttribute(CpRequestTraceFilter.TRACE_ID_ATTRIBUTE);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://api.dwh.internal/errors/" + errorCode);
        problem.put("title", status == HttpStatus.PAYLOAD_TOO_LARGE.value()
                ? "Payload Too Large" : "Too Many Requests");
        problem.put("status", status);
        problem.put("errorCode", errorCode);
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("traceId", traceId != null ? traceId.toString() : "");

        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    private static final class SizeLimitedRequest extends HttpServletRequestWrapper {

        private final long maxBodyBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        private SizeLimitedRequest(HttpServletRequest request, long maxBodyBytes) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (reader != null) {
                throw new IllegalStateException("getReader() has already been called");
            }
            if (inputStream == null) {
                inputStream = new CountingServletInputStream(super.getInputStream(), maxBodyBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader != null) {
                return reader;
            }
            if (inputStream != null) {
                throw new IllegalStateException("getInputStream() has already been called");
            }
            inputStream = new CountingServletInputStream(super.getInputStream(), maxBodyBytes);
            reader = new BufferedReader(new InputStreamReader(inputStream,
                    getCharacterEncoding() != null
                            ? java.nio.charset.Charset.forName(getCharacterEncoding())
                            : StandardCharsets.UTF_8));
            return reader;
        }
    }

    private static final class CountingServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBodyBytes;
        private long bytesRead;

        private CountingServletInputStream(ServletInputStream delegate, long maxBodyBytes) {
            this.delegate = delegate;
            this.maxBodyBytes = maxBodyBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                account(count);
            }
            return count;
        }

        private void account(int count) throws PayloadTooLargeException {
            bytesRead += count;
            if (bytesRead > maxBodyBytes) {
                throw new PayloadTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }

    public static final class PayloadTooLargeException extends IOException {
        public PayloadTooLargeException() {
            super("Instance request body exceeds the configured limit");
        }
    }
}
