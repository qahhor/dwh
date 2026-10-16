package com.smartup24.cms.instance.studio.llm;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Bounds the complete provider envelope before allocating its body (ADR-0039). */
final class LlmResponseBody implements HttpResponse.BodySubscriber<byte[]> {
    private static final int MAX_BYTES = 64 * 1024;
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int size;
    private boolean ended;

    @Override
    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
        subscription = value;
        delegate.onSubscribe(value);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (ended) return;
        for (ByteBuffer buffer : buffers) {
            if (buffer.remaining() > MAX_BYTES - size) {
                ended = true;
                subscription.cancel();
                delegate.onError(new IOException("LLM response exceeds limit"));
                return;
            }
            size += buffer.remaining();
        }
        delegate.onNext(buffers);
    }

    @Override
    public void onError(Throwable failure) {
        if (!ended) {
            ended = true;
            delegate.onError(failure);
        }
    }

    @Override
    public void onComplete() {
        if (!ended) {
            ended = true;
            delegate.onComplete();
        }
    }
}
