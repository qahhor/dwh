package com.greenwhite.dwh.instance.search.typesense;

import com.greenwhite.dwh.instance.search.repository.SearchProjectionReader;
import org.springframework.http.client.ClientHttpResponse;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A bounded cursor owned by the single coordinator; no transaction or complete export stays in memory. */
public final class TypesenseDocumentStream implements AutoCloseable {
    private final ClientHttpResponse response;
    private final InputStream input;
    private final ObjectMapper mapper;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private boolean exhausted;
    private boolean closed;

    TypesenseDocumentStream(ClientHttpResponse response, ObjectMapper mapper) throws IOException {
        this.response = response;
        this.input = new BufferedInputStream(response.getBody());
        this.mapper = mapper;
    }

    public record DocumentMetadata(String id, long revision, String fingerprint, String contentFingerprint) {}
    public List<DocumentMetadata> readPage(int maxRows, int maxBytes) {
        if (maxRows < 1 || maxRows > 100 || maxBytes < 1 || maxBytes > 1_048_576) throw new IllegalArgumentException("Invalid stream budget");
        if (exhausted) return List.of();
        if (closed) throw TypesenseException.unavailable();
        var page = new ArrayList<DocumentMetadata>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        try {
            for (int consumed = 0; consumed < maxBytes && page.size() < maxRows; consumed++) {
                if (Thread.currentThread().isInterrupted()) throw TypesenseException.unavailable();
                // Socket read timeout bounds a stall; elapsed unit time also bounds a slow but non-stalled stream.
                if (consumed>0 && System.nanoTime()>=deadline) break;
                int value = input.read();
                if (value == -1) {
                    if (partial.size() != 0) page.add(parseLine());
                    exhausted = true; close(); break;
                }
                if (value == '\n') page.add(parseLine());
                else {
                    if (partial.size() >= 1_048_576) throw TypesenseException.invalidResponse();
                    partial.write(value);
                }
            }
            return List.copyOf(page);
        } catch (Exception failure) {
            close();
            throw TypesenseException.invalidResponse();
        }
    }

    @SuppressWarnings("unchecked")
    private DocumentMetadata parseLine() {
        var document = mapper.readTree(partial.toByteArray());
        partial.reset();
        if (document == null || !document.isObject() || !document.path("id").isString()
                || document.path("id").asString().isBlank()) throw TypesenseException.invalidResponse();
        var revision = document.path("_projection_revision");
        var fingerprint = document.path("_projection_fingerprint");
        return new DocumentMetadata(document.path("id").asString(),
                revision.isIntegralNumber() && revision.canConvertToLong() && revision.asLong() > 0 ? revision.asLong() : 0,
                fingerprint.isString() ? fingerprint.asString() : null,
                SearchProjectionReader.contentFingerprint(mapper, mapper.convertValue(document, Map.class)));
    }

    public boolean exhausted() { return exhausted; }
    @Override public void close() {
        if (closed) return;
        closed = true; partial.reset();
        try { input.close(); } catch (IOException ignored) { /* best-effort resource closure */ }
        response.close();
    }
}
