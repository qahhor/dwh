package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.search.dto.SearchManagementDtos.VerificationSummary;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository.Generation;
import com.smartup24.cms.instance.search.repository.SearchProjectionReader;
import com.smartup24.cms.instance.search.repository.SearchReconcileRepository;
import com.smartup24.cms.instance.search.typesense.TypesenseCollections;
import com.smartup24.cms.instance.search.typesense.TypesenseDocumentStream;
import com.smartup24.cms.instance.search.typesense.TypesenseDocuments;
import com.smartup24.cms.instance.search.typesense.TypesenseException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

@Service
public class SearchReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(SearchReconciliationService.class);
    private final DataSource source;
    private final SearchProjectionReader reader;
    private final TypesenseCollections collections;
    private final TypesenseDocuments documents;
    private final SearchReconcileRepository sql;

    /** A service built by hand, for tests: the proof's SQL has no state of its own. */
    public SearchReconciliationService(
            DataSource source,
            SearchProjectionReader reader,
            TypesenseCollections collections,
            TypesenseDocuments documents) {
        this(source, reader, collections, documents, new SearchReconcileRepository());
    }

    @Autowired
    public SearchReconciliationService(
            DataSource source,
            SearchProjectionReader reader,
            TypesenseCollections collections,
            TypesenseDocuments documents,
            SearchReconcileRepository sql) {
        this.source = source;
        this.reader = reader;
        this.collections = collections;
        this.documents = documents;
        this.sql = sql;
    }

    public Proof begin(Generation generation) {
        return new Proof(source, reader, collections, documents, sql, generation);
    }

    /** Session-owned TEMP objects are dropped before returning this dedicated connection to its pool. */
    public static final class Proof implements AutoCloseable {
        private static final List<String> TYPES = List.of("TASK", "PROJECT", "USER");
        private final Connection connection;
        private final JdbcClient jdbc;
        private final SearchReconcileRepository sql;
        private final SearchProjectionReader reader;
        private final TypesenseCollections collections;
        private final TypesenseDocuments documents;
        private final Generation generation;
        private final Set<String> absent = new HashSet<>();
        private TypesenseDocumentStream stream;
        private int schemaStep, exportStep, sourceStep;
        private long after, processed;
        private boolean schemasMatch = true, closed, indexCreated, sourceCreated;
        private VerificationSummary summary;

        private Proof(
                DataSource source,
                SearchProjectionReader reader,
                TypesenseCollections collections,
                TypesenseDocuments documents,
                SearchReconcileRepository sql,
                Generation generation) {
            this.sql = sql;
            this.reader = reader;
            this.collections = collections;
            this.documents = documents;
            this.generation = generation;
            Connection acquired = null;
            try {
                acquired = source.getConnection();
                acquired.setAutoCommit(true);
            } catch (SQLException failure) {
                if (acquired != null)
                    try {
                        acquired.close();
                    } catch (SQLException closeFailed) {
                        /* acquisition failed */
                        log.debug("search_reconcile_close_failed error={}", closeFailed.toString());
                    }
                throw new IllegalStateException("RECONCILIATION_STORAGE_UNAVAILABLE");
            }
            connection = acquired;
            jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
            try {
                sql.createIndexTable(jdbc);
                indexCreated = true;
                sql.createSourceTable(jdbc);
                sourceCreated = true;
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
        }

        /** Exactly one schema request, export page, or source keyset page per coordinator cycle. */
        public boolean advance() {
            if (closed) throw new IllegalStateException("RECONCILIATION_CLOSED");
            if (summary != null) return true;
            if (schemaStep < TYPES.size()) {
                String type = TYPES.get(schemaStep++);
                var observed = collections.observeCollection(
                        generation.collections().get(type), type, generation.schemaProfile());
                if ("COLLECTION_MISSING".equals(observed.errorCode())) absent.add(type);
                else if (observed.schemaMatches() == null) throw TypesenseException.unavailable();
                schemasMatch &= Boolean.TRUE.equals(observed.schemaMatches());
                return false;
            }
            if (exportStep < TYPES.size()) {
                String type = TYPES.get(exportStep);
                if (absent.contains(type)) exportStep++;
                else exportPage(type);
                return false;
            }
            if (sourceStep < TYPES.size()) {
                sourcePage(TYPES.get(sourceStep));
                return false;
            }
            summary = sql.summary(jdbc, schemasMatch);
            return true;
        }

        private void exportPage(String type) {
            if (stream == null)
                stream = documents.openDocumentMetadata(generation.collections().get(type));
            var page = stream.readPage(100, 1_048_576);
            for (var document : page) {
                sql.insertIndexed(
                        jdbc,
                        type,
                        document.id(),
                        document.revision(),
                        document.fingerprint(),
                        document.contentFingerprint());
            }
            processed += page.size();
            if (stream.exhausted()) {
                stream.close();
                stream = null;
                exportStep++;
            }
        }

        private void sourcePage(String type) {
            var ids = reader.reconciliationIds(type, after, 100);
            for (long id : ids) {
                var value = reader.readForReconciliation(type, id).orElseThrow();
                sql.insertSource(
                        jdbc,
                        type,
                        id,
                        value.revision(),
                        value.fingerprint(),
                        value.document() != null,
                        generation.id());
                after = id;
            }
            processed += ids.size();
            if (ids.size() < 100) {
                sourceStep++;
                after = 0;
            }
        }

        public long processed() {
            return processed;
        }

        public VerificationSummary summary() {
            if (summary == null) throw new IllegalStateException("RECONCILIATION_INCOMPLETE");
            return summary;
        }

        public boolean revisionsUnchanged() {
            if (summary == null || closed) return false;
            return sql.revisionsUnchanged(jdbc);
        }

        /** Caller performs only PostgreSQL barrier/CAS/audit work here; never transport or source discovery. */
        public <T> T transaction(Function<JdbcClient, T> action) {
            if (closed || summary == null) throw new IllegalStateException("RECONCILIATION_INCOMPLETE");
            try {
                connection.setAutoCommit(false);
                try {
                    sql.limitTransaction(jdbc);
                    T result = action.apply(jdbc);
                    connection.commit();
                    return result;
                } catch (RuntimeException | Error | SQLException failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailed) {
                        log.warn("search_reconcile_rollback_failed error={}", rollbackFailed.toString());
                        try {
                            connection.abort(Runnable::run);
                        } catch (SQLException abortFailed) {
                            /* do not commit a failed transaction */
                            log.warn("search_reconcile_abort_failed error={}", abortFailed.toString());
                        }
                    }
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException("RECONCILIATION_CHECKPOINT_FAILED");
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (RuntimeException closeFailed) {
                        /* still release task-owned database resources */
                        log.warn("search_reconcile_stream_close_failed error={}", closeFailed.toString());
                    }
                    stream = null;
                }
                if (indexCreated) sql.dropIndexTable(jdbc);
                if (sourceCreated) sql.dropSourceTable(jdbc);
            } catch (RuntimeException failure) {
                log.warn("search_reconcile_cleanup_failed error={}", failure.toString());
                try {
                    connection.abort(Runnable::run);
                } catch (SQLException abortFailed) {
                    /* pool must discard this connection */
                    log.warn("search_reconcile_abort_failed error={}", abortFailed.toString());
                }
            } finally {
                try {
                    connection.close();
                } catch (SQLException closeFailed) {
                    /* task-owned connection */
                    log.debug("search_reconcile_connection_close_failed error={}", closeFailed.toString());
                }
            }
        }
    }
}
