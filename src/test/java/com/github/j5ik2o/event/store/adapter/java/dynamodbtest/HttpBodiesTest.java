package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.async.AsyncRequestBody;

class HttpBodiesTest {
  @Test
  void collectionCopiesBuffersAndPropagatesStreamFailure() {
    ByteBuffer buffer = ByteBuffer.wrap(new byte[] {0, 1, 2});
    buffer.position(1);
    CompletableFuture<byte[]> copied =
        HttpBodies.collect(
            subscriber ->
                subscriber.onSubscribe(
                    new Subscription() {
                      @Override
                      public void request(long demand) {
                        subscriber.onNext(buffer);
                        subscriber.onComplete();
                      }

                      @Override
                      public void cancel() {}
                    }));
    assertArrayEquals(new byte[] {1, 2}, copied.join());
    assertEquals(1, buffer.position());
    RuntimeException failure = new IllegalStateException("stream failure");
    CompletableFuture<byte[]> failed =
        HttpBodies.collect(subscriber -> subscriber.onError(failure));
    assertSame(failure, assertThrows(CompletionException.class, failed::join).getCause());
  }

  @Test
  void cancellationReachesSubscriptionEvenWhenItArrivesLater() {
    AtomicReference<Subscriber<? super ByteBuffer>> subscriber = new AtomicReference<>();
    CompletableFuture<byte[]> body = HttpBodies.collect(subscriber::set);
    assertTrue(body.cancel(true));
    AtomicBoolean cancelled = new AtomicBoolean();
    subscriber
        .get()
        .onSubscribe(
            new Subscription() {
              @Override
              public void request(long demand) {
                fail("Cancelled body must not request data");
              }

              @Override
              public void cancel() {
                cancelled.set(true);
              }
            });
    assertTrue(cancelled.get());
  }

  @Test
  void httpReplyOwnsItsBodyAndCanBeReadByBothTransportStyles() throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode source =
        DynamoDbJson.object().put("value", "original");
    HttpReply reply = new HttpReply(200, source);
    source.put("value", "changed");
    byte[] returned = reply.body();
    returned[0] = 0;
    try (java.io.InputStream body = reply.sync().responseBody().orElseThrow()) {
      assertEquals("original", DynamoDbJson.read(body.readAllBytes()).path("value").asText());
    }
    assertArrayEquals(
        reply.body(), HttpBodies.collect(AsyncRequestBody.fromBytes(reply.body())).join());
    assertEquals(200, reply.headers().statusCode());
  }
}
