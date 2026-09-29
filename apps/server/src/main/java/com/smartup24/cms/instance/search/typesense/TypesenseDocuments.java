package com.smartup24.cms.instance.search.typesense;

import com.smartup24.cms.instance.search.service.SearchMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** Typesense documents: single upserts and deletes, bounded batch imports, and the metadata export stream. */
@Component
public class TypesenseDocuments {

    private final TypesenseClient client;
    private final TypesenseCollections collections;
    private SearchMetrics metrics = SearchMetrics.unmetered();

    @Autowired
    public TypesenseDocuments(
            TypesenseClient client, TypesenseCollections collections, Optional<SearchMetrics> metrics) {
        this(client, collections);
        this.metrics = metrics.orElseGet(SearchMetrics::unmetered);
    }

    public TypesenseDocuments(TypesenseClient client, TypesenseCollections collections) {
        this.client = client;
        this.collections = collections;
    }

    public boolean isEnabled() {
        return client.isEnabled();
    }

    public record ImportAck(String id, boolean success, String errorCode) {}

    public TypesenseDocumentStream openDocumentMetadata(String collection) {
        if (!client.isEnabled()) throw TypesenseException.uninitialized();
        try {
            return client.rest()
                    .get()
                    .uri("/collections/{collection}/documents/export", collection)
                    .exchange(
                            (request, response) -> {
                                if (!response.getStatusCode().is2xxSuccessful()) {
                                    response.getBody().close();
                                    response.close();
                                    throw TypesenseException.unavailable();
                                }
                                try {
                                    return new TypesenseDocumentStream(response, client.mapper());
                                } catch (Exception failure) {
                                    response.close();
                                    throw failure;
                                }
                            },
                            false);
        } catch (Exception failure) {
            throw TypesenseException.unavailable();
        }
    }

    public void forEachDocumentMetadata(
            String collection, Consumer<TypesenseDocumentStream.DocumentMetadata> consumer) {
        try (var stream = openDocumentMetadata(collection)) {
            while (!stream.exhausted()) stream.readPage(100, 1_048_576).forEach(consumer);
        }
    }

    public List<ImportAck> importDocuments(String collection, List<Map<String, Object>> documents) {
        if (!client.isEnabled()) throw TypesenseException.uninitialized();
        var result = new ArrayList<ImportAck>();
        var batch = new java.io.ByteArrayOutputStream();
        var ids = new ArrayList<String>();
        for (var document : documents) {
            if (Thread.currentThread().isInterrupted()) throw TypesenseException.unavailable();
            Object rawId = document.get("id");
            if (!(rawId instanceof String id) || id.isBlank()) throw TypesenseException.invalidResponse();
            var encoded = new LimitedOutput(1_048_575);
            try {
                client.mapper().writeValue(encoded, document);
            } catch (RuntimeException failure) {
                if (!encoded.exceeded) throw TypesenseException.invalidResponse();
            }
            if (encoded.exceeded) {
                if (!ids.isEmpty()) {
                    result.addAll(sendImport(collection, batch.toByteArray(), ids));
                    batch.reset();
                    ids.clear();
                }
                result.add(new ImportAck(id, false, "DOCUMENT_TOO_LARGE"));
                metrics.imported(false, 1);
                continue;
            }
            byte[] line = encoded.bytes.toByteArray();
            if (ids.size() == 100 || batch.size() + line.length + 1 > 1_048_576) {
                result.addAll(sendImport(collection, batch.toByteArray(), ids));
                batch.reset();
                ids.clear();
            }
            batch.writeBytes(line);
            batch.write('\n');
            ids.add(id);
        }
        if (!ids.isEmpty()) result.addAll(sendImport(collection, batch.toByteArray(), ids));
        return List.copyOf(result);
    }

    private List<ImportAck> sendImport(String collection, byte[] body, List<String> ids) {
        try {
            if (Thread.currentThread().isInterrupted()) throw TypesenseException.unavailable();
            var result = client.rest()
                    .post()
                    .uri("/collections/{collection}/documents/import?action=upsert", collection)
                    .contentType(MediaType.parseMediaType("text/plain; charset=UTF-8"))
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) throw TypesenseException.unavailable();
                        return readAcknowledgements(new java.io.BufferedInputStream(response.getBody()), ids);
                    });
            long succeeded = result.stream().filter(ImportAck::success).count();
            metrics.imported(true, succeeded);
            metrics.imported(false, result.size() - succeeded);
            return result;
        } catch (TypesenseException safe) {
            metrics.imported(false, ids.size());
            throw safe;
        } catch (Exception failure) {
            metrics.imported(false, ids.size());
            throw TypesenseException.invalidResponse();
        }
    }

    /** One acknowledgement line per sent id, in order, and nothing after the last one. */
    private List<ImportAck> readAcknowledgements(java.io.InputStream input, List<String> ids)
            throws java.io.IOException {
        var acknowledgements = new ArrayList<ImportAck>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TypesenseClient.READ_TIMEOUT_MS);
        for (String id : ids) {
            String line = boundedLine(input, deadline);
            if (line == null) throw TypesenseException.invalidResponse();
            var ack = client.mapper().readTree(line);
            if (ack == null || !ack.isObject() || !ack.path("success").isBoolean())
                throw TypesenseException.invalidResponse();
            boolean success = ack.path("success").asBoolean();
            acknowledgements.add(new ImportAck(id, success, success ? null : "IMPORT_REJECTED"));
        }
        if (boundedLine(input, deadline) != null) throw TypesenseException.invalidResponse();
        return List.copyOf(acknowledgements);
    }

    static String boundedLine(java.io.InputStream input, long deadline) throws java.io.IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        for (; ; ) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                throw TypesenseException.unavailable();
            int value = input.read();
            if (value == -1) break;
            if (value == '\n') return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (bytes.size() == 1_048_576) throw TypesenseException.invalidResponse();
            bytes.write(value);
        }
        return bytes.size() == 0 ? null : bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static final class LimitedOutput extends java.io.OutputStream {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final int limit;
        private boolean exceeded;

        private LimitedOutput(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws java.io.IOException {
            if (bytes.size() >= limit) {
                exceeded = true;
                throw new java.io.IOException("Document size limit");
            }
            bytes.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) throws java.io.IOException {
            if (length > limit - bytes.size()) {
                exceeded = true;
                throw new java.io.IOException("Document size limit");
            }
            bytes.write(values, offset, length);
        }
    }

    public void upsertDocument(String collection, Map<String, Object> document) {
        if (!client.isEnabled()) throw TypesenseException.uninitialized();
        try {
            client.rest()
                    .post()
                    .uri("/collections/{collection}/documents?action=upsert", collection)
                    .body(document)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            throw TypesenseException.unavailable();
        }
    }

    public void deleteDocument(String collection, String documentId) {
        if (!client.isEnabled()) throw TypesenseException.uninitialized();
        try {
            client.rest()
                    .delete()
                    .uri("/collections/{collection}/documents/{id}", collection, documentId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound missing) {
            // A document 404 is idempotent success only while its collection still exists.
            if (!collections.collectionExists(collection)) throw TypesenseException.uninitialized();
        } catch (Exception e) {
            throw TypesenseException.unavailable();
        }
    }
}
