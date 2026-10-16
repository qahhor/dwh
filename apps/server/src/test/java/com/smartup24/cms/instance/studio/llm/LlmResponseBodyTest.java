package com.smartup24.cms.instance.studio.llm;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** Streaming bounds hold across chunks and cancellation keeps the first outcome (ADR-0039). */
class LlmResponseBodyTest {
    @Test
    void exactlyBoundedBodyIsDeliveredWithoutCancellation() {
        var body = new LlmResponseBody();
        var subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[32768]), ByteBuffer.wrap(new byte[32768])));
        body.onComplete();
        assertThat(body.getBody().toCompletableFuture().join()).hasSize(65536);
        verify(subscription, never()).cancel();
    }

    @Test
    void excessAcrossChunksCancelsUpstreamAndCannotBeReplacedByLateSignals() {
        var body = new LlmResponseBody();
        var subscription = mock(Flow.Subscription.class);
        body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.wrap(new byte[65536])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        body.onNext(List.of(ByteBuffer.wrap(new byte[65536])));
        body.onError(new IOException("late"));
        body.onComplete();
        assertThatThrownBy(() -> body.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("LLM response exceeds limit");
        verify(subscription).cancel();
    }

    @Test
    void transportErrorCannotBecomeAnApparentlyCompleteResponse() {
        var body = new LlmResponseBody();
        body.onSubscribe(mock(Flow.Subscription.class));
        body.onNext(List.of(ByteBuffer.wrap(new byte[2])));
        body.onError(new IOException("truncated response"));
        body.onComplete();
        assertThatThrownBy(() -> body.getBody().toCompletableFuture().join()).hasRootCauseMessage("truncated response");
    }
}
