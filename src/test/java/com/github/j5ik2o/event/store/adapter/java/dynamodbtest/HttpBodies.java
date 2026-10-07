package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

final class HttpBodies {
  private HttpBodies() {}

  static CompletableFuture<byte[]> collect(Publisher<ByteBuffer> publisher) {
    AtomicReference<Subscription> subscription = new AtomicReference<>();
    CompletableFuture<byte[]> result = new CompletableFuture<>();
    result.whenComplete(
        (bytes, error) -> {
          if (result.isCancelled() && subscription.get() != null) subscription.get().cancel();
        });
    publisher.subscribe(
        new Subscriber<ByteBuffer>() {
          private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

          @Override
          public void onSubscribe(Subscription value) {
            if (!subscription.compareAndSet(null, value)) {
              value.cancel();
              return;
            }
            if (result.isCancelled()) value.cancel();
            else value.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(ByteBuffer value) {
            ByteBuffer copy = value.duplicate();
            byte[] chunk = new byte[copy.remaining()];
            copy.get(chunk);
            bytes.write(chunk, 0, chunk.length);
          }

          @Override
          public void onError(Throwable error) {
            result.completeExceptionally(error);
          }

          @Override
          public void onComplete() {
            result.complete(bytes.toByteArray());
          }
        });
    return result;
  }
}
