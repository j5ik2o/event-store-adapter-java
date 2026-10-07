package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.http.async.*;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/** SPI test doubles cover abort/cancellation and lifetime; real SDK/Local tests cover effects. */
class FaultTransportTest {
  private static final class Fixture {
    final FaultRegistry faults = new FaultRegistry();
    final FaultRegistry.Operation operation = faults.begin(1, false);
    final DynamoDbRequestRecorder recorder =
        new DynamoDbRequestRecorder(
            faults, new DynamoDbRequestTargets("journal", "snapshot", "head", "history"));
    final ExecutionAttributes attributes =
        new ExecutionAttributes().putAttribute(SdkExecutionAttribute.OPERATION_NAME, "Query");
    final byte[] body;
    final SdkHttpRequest http;
    final InterceptorContext context;

    Fixture() {
      QueryRequest request =
          QueryRequest.builder()
              .tableName("journal")
              .keyConditionExpression("aid=:aid")
              .expressionAttributeValues(Map.of(":aid", AttributeValue.fromS("User-A")))
              .build();
      body = DynamoDbJson.bytes(DynamoDbJson.sdk(request));
      context =
          InterceptorContext.builder()
              .request(request)
              .requestBody(RequestBody.fromBytes(body))
              .httpRequest(
                  SdkHttpFullRequest.builder()
                      .protocol("http")
                      .host("localhost")
                      .port(12345)
                      .method(SdkHttpMethod.POST)
                      .build())
              .build();
      recorder.beforeExecution(context, attributes);
      recorder.modifyRequest(context, attributes);
      recorder.afterMarshalling(context, attributes);
      http = recorder.modifyHttpRequest(context, attributes);
    }

    void finish() {
      recorder.afterExecution(context, attributes);
      assertEquals("passed", faults.finish(operation).status);
    }

    AsyncExecuteRequest asyncRequest(SdkAsyncHttpResponseHandler handler) {
      return AsyncExecuteRequest.builder()
          .request(http)
          .requestContentPublisher(
              new SdkHttpContentPublisher() {
                @Override
                public java.util.Optional<Long> contentLength() {
                  return java.util.Optional.of((long) body.length);
                }

                @Override
                public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
                  AsyncRequestBody.fromBytes(body).subscribe(subscriber);
                }
              })
          .responseHandler(handler)
          .build();
    }
  }

  @Test
  void syncAbortBeforeCallSkipsDelegateAndClosingClosesOwnedTransport() {
    Fixture fixture = new Fixture();
    AtomicBoolean closed = new AtomicBoolean();
    SdkHttpClient delegate =
        new SdkHttpClient() {
          @Override
          public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            throw new AssertionError("Aborted request must not be prepared");
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    try (FaultHttpClient wrapper = new FaultHttpClient(delegate, fixture.recorder)) {
      ExecutableHttpRequest request =
          wrapper.prepareRequest(
              HttpExecuteRequest.builder()
                  .request(fixture.http)
                  .contentStreamProvider(() -> new java.io.ByteArrayInputStream(fixture.body))
                  .build());
      request.abort();
      assertThrows(IOException.class, request::call);
      assertEquals(0, fixture.recorder.requests().get(0).httpAttempts);
    }
    assertTrue(closed.get());
    fixture.finish();
  }

  @Test
  void asyncCancellationReachesRealSpiFutureAndClosingClosesOwnedTransport() {
    Fixture fixture = new Fixture();
    CompletableFuture<Void> actual = new CompletableFuture<>();
    AtomicBoolean closed = new AtomicBoolean();
    SdkAsyncHttpClient delegate =
        new SdkAsyncHttpClient() {
          @Override
          public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            return actual;
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    try (FaultAsyncHttpClient wrapper = new FaultAsyncHttpClient(delegate, fixture.recorder)) {
      CompletableFuture<Void> result = wrapper.execute(fixture.asyncRequest(new Handler()));
      assertTrue(result.cancel(true));
      assertTrue(actual.isCancelled());
      assertEquals(1, fixture.recorder.requests().get(0).httpAttempts);
      assertEquals(1, fixture.recorder.requests().get(0).transmissions);
    }
    assertTrue(closed.get());
    fixture.finish();
  }

  @Test
  void asyncSynchronousSpiFailureIsReportedToHandlerAndFuture() {
    Fixture fixture = new Fixture();
    RuntimeException original = new IllegalStateException("SPI submission failed");
    Handler handler = new Handler();
    SdkAsyncHttpClient delegate =
        new SdkAsyncHttpClient() {
          @Override
          public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            throw original;
          }

          @Override
          public void close() {}
        };
    try (FaultAsyncHttpClient wrapper = new FaultAsyncHttpClient(delegate, fixture.recorder)) {
      CompletableFuture<Void> result = wrapper.execute(fixture.asyncRequest(handler));
      assertSame(original, assertThrows(CompletionException.class, result::join).getCause());
      assertSame(original, handler.error.get());
    }
    fixture.finish();
  }

  private static final class Handler implements SdkAsyncHttpResponseHandler {
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onHeaders(SdkHttpResponse response) {
      fail("Unexpected response headers");
    }

    @Override
    public void onStream(Publisher<ByteBuffer> stream) {
      fail("Unexpected response body");
    }

    @Override
    public void onError(Throwable failure) {
      error.set(failure);
    }
  }
}
