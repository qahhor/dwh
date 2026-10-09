package com.smartup24.cms.instance.config.idempotency;

import com.smartup24.cms.core.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Runs a request {@link IdempotencyFilter} has accepted under a reserved key and records its answer for a replay.
 * Split from the filter, which decides whether a request takes part at all (plan 10/10, items 3.10 and 3.12).
 */
final class IdempotentExecution {

    // The category stays the filter's so existing log routing keeps matching.
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    private final IdempotencyService idempotencyService;
    private final IdempotencyAnswers answers;
    /**
     * Runs an accepted request and records its answer in one transaction; absent in slice tests, where the answer
     * is recorded after the request as before item 3.12.
     */
    private final @Nullable PlatformTransactionManager transactions;

    IdempotentExecution(
            IdempotencyService idempotencyService,
            IdempotencyAnswers answers,
            @Nullable PlatformTransactionManager transactions) {
        this.idempotencyService = idempotencyService;
        this.answers = answers;
        this.transactions = transactions;
    }

    /** Runs the request under the reservation and records or frees the key by its outcome. */
    void run(
            HttpServletRequest request,
            ContentCachingResponseWrapper responseWrapper,
            FilterChain filterChain,
            UUID key,
            UUID reservationToken)
            throws ServletException, IOException {
        if (transactions == null) {
            runAndRecordAfter(request, responseWrapper, filterChain, key, reservationToken);
        } else {
            runAndRecordAtomically(transactions, request, responseWrapper, filterChain, key, reservationToken);
        }
    }

    /**
     * Plan 10/10, item 3.12: the request and the record of its answer commit together. The business writes join the
     * transaction opened here (propagation REQUIRED), and the answer is stored in it before the commit, so a process
     * that dies after the commit leaves a stored answer for the retry instead of running the operation twice.
     * A 5xx rolls the request back and frees the key, so a corrected retry runs again. When the business code has
     * rolled back (a refusal thrown from a transactional method), nothing of the request is committed and only the
     * answer is recorded. The answer reaches the client only after the commit.
     */
    private void runAndRecordAtomically(
            PlatformTransactionManager transactionManager,
            HttpServletRequest request,
            ContentCachingResponseWrapper responseWrapper,
            FilterChain filterChain,
            UUID key,
            UUID reservationToken)
            throws ServletException, IOException {
        var definition = new DefaultTransactionDefinition();
        definition.setName("idempotent-request");
        TransactionStatus tx = transactionManager.getTransaction(definition);
        try {
            filterChain.doFilter(request, responseWrapper);
        } catch (IOException | ServletException | RuntimeException e) {
            transactionManager.rollback(tx);
            idempotencyService.release(key, reservationToken);
            responseWrapper.copyBodyToResponse();
            throw e;
        }
        int status = responseWrapper.getStatus();
        byte[] body = responseWrapper.getContentAsByteArray();
        if (status < 500 && !tx.isRollbackOnly()) {
            commitWithAnswer(transactionManager, tx, request, responseWrapper, key, reservationToken);
        } else if (status < 400) {
            // The handler reports success although something in the request rolled back (a failure swallowed on
            // the way): nothing was written, so a success must not reach the client or be replayed.
            log.error("idempotent_success_rolled_back key={} uri={} status={}", key, request.getRequestURI(), status);
            transactionManager.rollback(tx);
            answerInternalError(request, responseWrapper, key, reservationToken);
        } else {
            transactionManager.rollback(tx);
            record(request, key, reservationToken, responseWrapper, status, body);
        }
        responseWrapper.copyBodyToResponse();
    }

    /**
     * Stores the answer in the request's transaction and commits both. A failed commit replaces the buffered answer
     * with a 500. A hook that fails after a successful commit does not: the operation and its stored answer are
     * committed, so the client gets the buffered answer, as a replay of the key would give it.
     */
    private void commitWithAnswer(
            PlatformTransactionManager transactionManager,
            TransactionStatus tx,
            HttpServletRequest request,
            ContentCachingResponseWrapper responseWrapper,
            UUID key,
            UUID reservationToken)
            throws IOException {
        CommitWatch commit = CommitWatch.register();
        try {
            // An answer that cannot be kept (too large, not JSON) still commits; only its replay is lost.
            record(
                    request,
                    key,
                    reservationToken,
                    responseWrapper,
                    responseWrapper.getStatus(),
                    responseWrapper.getContentAsByteArray());
            transactionManager.commit(tx);
        } catch (RuntimeException e) {
            if (commit.committed()) {
                log.warn("idempotent_after_commit_failed key={} uri={}", key, request.getRequestURI(), e);
                return;
            }
            // The commit failed after the handler answered: the operation did not happen, so the buffered
            // success must not reach the client.
            log.error("idempotent_commit_failed key={} uri={}", key, request.getRequestURI(), e);
            if (!tx.isCompleted()) {
                transactionManager.rollback(tx);
            }
            answerInternalError(request, responseWrapper, key, reservationToken);
        }
    }

    /** Frees the key and replaces the buffered answer with a 500: nothing of the request was committed. */
    private void answerInternalError(
            HttpServletRequest request, ContentCachingResponseWrapper responseWrapper, UUID key, UUID reservationToken)
            throws IOException {
        idempotencyService.release(key, reservationToken);
        responseWrapper.resetBuffer();
        answers.problem(
                request,
                responseWrapper,
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "error.internal_error");
    }

    /** Stores the answer for a replay when it can be kept, otherwise frees the key. */
    private void record(
            HttpServletRequest request,
            UUID key,
            UUID reservationToken,
            HttpServletResponse response,
            int status,
            byte[] body) {
        if (IdempotencyAnswers.storable(response, status, body, IdempotencyAnswers.answerLimit(request))) {
            idempotencyService.complete(key, reservationToken, IdempotencyAnswers.answer(response, status, body));
        } else {
            idempotencyService.release(key, reservationToken);
        }
    }

    /** Without a transaction manager (web slices): the answer is recorded after the request, as before 3.12. */
    private void runAndRecordAfter(
            HttpServletRequest request,
            ContentCachingResponseWrapper responseWrapper,
            FilterChain filterChain,
            UUID key,
            UUID reservationToken)
            throws ServletException, IOException {
        boolean chainCompleted = false;
        try {
            filterChain.doFilter(request, responseWrapper);
            chainCompleted = true;
        } finally {
            try {
                if (chainCompleted) {
                    record(
                            request,
                            key,
                            reservationToken,
                            responseWrapper,
                            responseWrapper.getStatus(),
                            responseWrapper.getContentAsByteArray());
                } else {
                    idempotencyService.release(key, reservationToken);
                }
            } finally {
                responseWrapper.copyBodyToResponse();
            }
        }
    }
}
