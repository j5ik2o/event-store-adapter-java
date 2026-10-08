package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.InterceptorContext;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.internal.interceptor.DefaultFailedExecutionContext;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.*;
import software.amazon.awssdk.http.async.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

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
  void syncPrepareFailureDoesNotRecordTransmissionAndKeepsOriginalError() {
    Fixture fixture = new Fixture();
    RuntimeException original = new IllegalStateException("SPI prepare failed");
    AtomicBoolean bodyClosed = new AtomicBoolean();
    AtomicBoolean closed = new AtomicBoolean();
    SdkHttpClient delegate =
        new SdkHttpClient() {
          @Override
          public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            assertTrue(bodyClosed.get(), "Observed request body is closed before prepare");
            throw original;
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    RuntimeException thrown;
    try (FaultHttpClient wrapper = new FaultHttpClient(delegate, fixture.recorder)) {
      ExecutableHttpRequest request =
          wrapper.prepareRequest(
              HttpExecuteRequest.builder()
                  .request(fixture.http)
                  .contentStreamProvider(
                      () ->
                          new java.io.ByteArrayInputStream(fixture.body) {
                            @Override
                            public void close() throws IOException {
                              super.close();
                              bodyClosed.set(true);
                            }
                          })
                  .build());
      thrown = assertThrows(RuntimeException.class, request::call);
      fixture.recorder.onExecutionFailure(
          DefaultFailedExecutionContext.builder()
              .interceptorContext(fixture.context)
              .exception(thrown)
              .build(),
          fixture.attributes);
    }
    DynamoDbRequestRecorder.Request observed = fixture.recorder.requests().get(0);
    boolean terminated = fixture.recorder.requestsFinished(fixture.operation).isDone();
    int pending = fixture.faults.pending(fixture.operation);
    String status = fixture.faults.finish(fixture.operation).status;
    System.out.println(
        "Sync prepare rejection attempts="
            + observed.httpAttempts
            + " transmissions="
            + observed.transmissions
            + " payload="
            + observed.transmitted
            + " originalSame="
            + (original == thrown)
            + " terminated="
            + terminated
            + " pending="
            + pending
            + " finish="
            + status
            + " closed="
            + closed.get()
            + " bodyClosed="
            + bodyClosed.get());
    assertAll(
        () -> assertEquals(1, observed.httpAttempts),
        () -> assertEquals(0, observed.transmissions),
        () -> assertNull(observed.transmitted),
        () -> assertSame(original, thrown),
        () -> assertTrue(terminated),
        () -> assertEquals(0, pending),
        () -> assertEquals("passed", status),
        () -> assertTrue(closed.get()),
        () -> assertTrue(bodyClosed.get()));
  }

  @Test
  void syncAcceptedCallFailureKeepsTransmissionAndOriginalError() {
    Fixture fixture = new Fixture();
    IOException original = new IOException("accepted transport failed");
    AtomicBoolean closed = new AtomicBoolean();
    SdkHttpClient delegate =
        new SdkHttpClient() {
          @Override
          public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
              @Override
              public HttpExecuteResponse call() throws IOException {
                throw original;
              }

              @Override
              public void abort() {
                fail("Unexpected abort");
              }
            };
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    IOException thrown;
    try (FaultHttpClient wrapper = new FaultHttpClient(delegate, fixture.recorder)) {
      ExecutableHttpRequest request =
          wrapper.prepareRequest(
              HttpExecuteRequest.builder()
                  .request(fixture.http)
                  .contentStreamProvider(() -> new java.io.ByteArrayInputStream(fixture.body))
                  .build());
      thrown = assertThrows(IOException.class, request::call);
      fixture.recorder.onExecutionFailure(
          DefaultFailedExecutionContext.builder()
              .interceptorContext(fixture.context)
              .exception(thrown)
              .build(),
          fixture.attributes);
    }
    DynamoDbRequestRecorder.Request observed = fixture.recorder.requests().get(0);
    boolean terminated = fixture.recorder.requestsFinished(fixture.operation).isDone();
    int pending = fixture.faults.pending(fixture.operation);
    String status = fixture.faults.finish(fixture.operation).status;
    System.out.println(
        "Sync accepted call failure attempts="
            + observed.httpAttempts
            + " transmissions="
            + observed.transmissions
            + " payload="
            + observed.transmitted
            + " originalSame="
            + (original == thrown)
            + " terminated="
            + terminated
            + " pending="
            + pending
            + " finish="
            + status
            + " closed="
            + closed.get());
    assertAll(
        () -> assertEquals(1, observed.httpAttempts),
        () -> assertEquals(1, observed.transmissions),
        () -> assertEquals(DynamoDbJson.read(fixture.body), observed.transmitted),
        () -> assertSame(original, thrown),
        () -> assertTrue(terminated),
        () -> assertEquals(0, pending),
        () -> assertEquals("passed", status),
        () -> assertTrue(closed.get()));
  }

  @Test
  void syncSuccessfulCallKeepsTransmissionAndResponse() throws IOException {
    Fixture fixture = new Fixture();
    HttpExecuteResponse response =
        HttpExecuteResponse.builder()
            .response(SdkHttpResponse.builder().statusCode(200).build())
            .build();
    AtomicBoolean closed = new AtomicBoolean();
    SdkHttpClient delegate =
        new SdkHttpClient() {
          @Override
          public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            return new ExecutableHttpRequest() {
              @Override
              public HttpExecuteResponse call() {
                return response;
              }

              @Override
              public void abort() {
                fail("Unexpected abort");
              }
            };
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    HttpExecuteResponse returned;
    try (FaultHttpClient wrapper = new FaultHttpClient(delegate, fixture.recorder)) {
      ExecutableHttpRequest request =
          wrapper.prepareRequest(
              HttpExecuteRequest.builder()
                  .request(fixture.http)
                  .contentStreamProvider(() -> new java.io.ByteArrayInputStream(fixture.body))
                  .build());
      returned = request.call();
      fixture.recorder.afterExecution(fixture.context, fixture.attributes);
    }
    DynamoDbRequestRecorder.Request observed = fixture.recorder.requests().get(0);
    boolean terminated = fixture.recorder.requestsFinished(fixture.operation).isDone();
    int pending = fixture.faults.pending(fixture.operation);
    String status = fixture.faults.finish(fixture.operation).status;
    System.out.println(
        "Sync successful call attempts="
            + observed.httpAttempts
            + " transmissions="
            + observed.transmissions
            + " payload="
            + observed.transmitted
            + " statusCode="
            + returned.httpResponse().statusCode()
            + " terminated="
            + terminated
            + " pending="
            + pending
            + " finish="
            + status
            + " closed="
            + closed.get());
    assertAll(
        () -> assertEquals(1, observed.httpAttempts),
        () -> assertEquals(1, observed.transmissions),
        () -> assertEquals(DynamoDbJson.read(fixture.body), observed.transmitted),
        () -> assertEquals(response.httpResponse(), returned.httpResponse()),
        () -> assertTrue(terminated),
        () -> assertEquals(0, pending),
        () -> assertEquals("passed", status),
        () -> assertTrue(closed.get()));
  }

  @Test
  void syncAbortDuringPrepareAbortsAcceptedRequestWithoutTransmission() {
    Fixture fixture = new Fixture();
    AtomicReference<ExecutableHttpRequest> pendingRequest = new AtomicReference<>();
    AtomicBoolean actualAborted = new AtomicBoolean();
    AtomicBoolean actualCalled = new AtomicBoolean();
    AtomicBoolean closed = new AtomicBoolean();
    SdkHttpClient delegate =
        new SdkHttpClient() {
          @Override
          public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
            pendingRequest.get().abort();
            return new ExecutableHttpRequest() {
              @Override
              public HttpExecuteResponse call() {
                actualCalled.set(true);
                throw new AssertionError("Aborted request must not be called");
              }

              @Override
              public void abort() {
                actualAborted.set(true);
              }
            };
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
      pendingRequest.set(request);
      IOException thrown = assertThrows(IOException.class, request::call);
      fixture.recorder.onExecutionFailure(
          DefaultFailedExecutionContext.builder()
              .interceptorContext(fixture.context)
              .exception(thrown)
              .build(),
          fixture.attributes);
    }
    DynamoDbRequestRecorder.Request observed = fixture.recorder.requests().get(0);
    boolean terminated = fixture.recorder.requestsFinished(fixture.operation).isDone();
    int pending = fixture.faults.pending(fixture.operation);
    String status = fixture.faults.finish(fixture.operation).status;
    System.out.println(
        "Sync abort during prepare attempts="
            + observed.httpAttempts
            + " transmissions="
            + observed.transmissions
            + " payload="
            + observed.transmitted
            + " actualAborted="
            + actualAborted.get()
            + " actualCalled="
            + actualCalled.get()
            + " terminated="
            + terminated
            + " pending="
            + pending
            + " finish="
            + status
            + " closed="
            + closed.get());
    assertAll(
        () -> assertEquals(1, observed.httpAttempts),
        () -> assertEquals(0, observed.transmissions),
        () -> assertNull(observed.transmitted),
        () -> assertTrue(actualAborted.get()),
        () -> assertFalse(actualCalled.get()),
        () -> assertTrue(terminated),
        () -> assertEquals(0, pending),
        () -> assertEquals("passed", status),
        () -> assertTrue(closed.get()));
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
      assertEquals(0, fixture.recorder.requests().get(0).transmissions);
      assertNull(fixture.recorder.requests().get(0).transmitted);
    }
    assertTrue(closed.get());
    fixture.finish();
    assertTrue(fixture.recorder.requestsFinished(fixture.operation).isDone());
    assertEquals(0, fixture.faults.pending(fixture.operation));
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
      DynamoDbRequestRecorder.Request observed = fixture.recorder.requests().get(0);
      System.out.println(
          "Synchronous SPI throw attempts="
              + observed.httpAttempts
              + " transmissions="
              + observed.transmissions
              + " payload="
              + observed.transmitted);
      fixture.recorder.onExecutionFailure(
          DefaultFailedExecutionContext.builder()
              .interceptorContext(fixture.context)
              .exception(original)
              .build(),
          fixture.attributes);
      assertTrue(fixture.recorder.requestsFinished(fixture.operation).isDone());
      assertEquals(0, fixture.faults.pending(fixture.operation));
      assertAll(
          () -> assertEquals(1, observed.httpAttempts),
          () -> assertEquals(0, observed.transmissions),
          () -> assertNull(observed.transmitted));
    }
    fixture.finish();
  }

  @Test
  void asyncAcceptedFutureFailureKeepsTransmissionAndOriginalError() {
    Fixture fixture = new Fixture();
    RuntimeException original = new IllegalStateException("accepted transport failed");
    CompletableFuture<Void> actual = new CompletableFuture<>();
    Handler handler = new Handler();
    SdkAsyncHttpClient delegate =
        new SdkAsyncHttpClient() {
          @Override
          public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            return actual;
          }

          @Override
          public void close() {}
        };
    try (FaultAsyncHttpClient wrapper = new FaultAsyncHttpClient(delegate, fixture.recorder)) {
      CompletableFuture<Void> result = wrapper.execute(fixture.asyncRequest(handler));
      assertEquals(1, fixture.recorder.requests().get(0).transmissions);
      assertEquals(DynamoDbJson.read(fixture.body), fixture.recorder.requests().get(0).transmitted);
      handler.onError(original);
      actual.completeExceptionally(original);
      assertSame(original, assertThrows(CompletionException.class, result::join).getCause());
      assertSame(original, handler.error.get());
    }
    fixture.finish();
  }

  @Test
  void asyncImmediateSdkResponseKeepsPartialBatchApplication() throws Exception {
    FaultRegistry faults = new FaultRegistry();
    DynamoDbRequestRecorder recorder =
        new DynamoDbRequestRecorder(
            faults, new DynamoDbRequestTargets("journal", "snapshot", "head", "history"));
    FaultRegistry.Fault fault =
        faults.register(
            1,
            "retention-delete",
            1,
            FaultRegistry.Injection.REPLACE_REQUEST,
            DynamoDbFaultEffects.unprocessedFirst(1));
    FaultRegistry.Operation operation = faults.begin(1, true);
    AtomicBoolean closed = new AtomicBoolean();
    AtomicBoolean responseBeforeReturn = new AtomicBoolean();
    ExecutionInterceptor observer =
        new ExecutionInterceptor() {
          @Override
          public SdkResponse modifyResponse(
              Context.ModifyResponse context, ExecutionAttributes attrs) {
            responseBeforeReturn.set(true);
            return context.response();
          }
        };
    SdkAsyncHttpClient delegate =
        new SdkAsyncHttpClient() {
          @Override
          public CompletableFuture<Void> execute(AsyncExecuteRequest request) {
            HttpReply reply = new HttpReply(200, DynamoDbJson.object());
            request.responseHandler().onHeaders(reply.headers());
            request.responseHandler().onStream(AsyncRequestBody.fromBytes(reply.body()));
            assertTrue(
                responseBeforeReturn.get(), "SDK modifyResponse reached before execute return");
            return CompletableFuture.completedFuture(null);
          }

          @Override
          public void close() {
            closed.set(true);
          }
        };
    WriteRequest first =
        WriteRequest.builder()
            .deleteRequest(
                DeleteRequest.builder()
                    .key(
                        Map.of(
                            "aid",
                            AttributeValue.fromS("User-A"),
                            "skey",
                            AttributeValue.fromN("1")))
                    .build())
            .build();
    WriteRequest second =
        WriteRequest.builder()
            .deleteRequest(
                DeleteRequest.builder()
                    .key(
                        Map.of(
                            "aid",
                            AttributeValue.fromS("User-A"),
                            "skey",
                            AttributeValue.fromN("2")))
                    .build())
            .build();
    try (FaultAsyncHttpClient wrapper = new FaultAsyncHttpClient(delegate, recorder);
        DynamoDbAsyncClient client =
            DynamoDbTestClients.observedAsync(
                URI.create("http://localhost:12345"), recorder, wrapper, observer)) {
      BatchWriteItemResponse response =
          client
              .batchWriteItem(
                  BatchWriteItemRequest.builder()
                      .requestItems(Map.of("snapshot", List.of(first, second)))
                      .build())
              .get(10, TimeUnit.SECONDS);
      recorder.requestsFinished(operation).get(10, TimeUnit.SECONDS);
      DynamoDbRequestRecorder.Request observed = recorder.requests().get(0);
      String status = faults.finish(operation).status;
      System.out.println(
          "Immediate SDK callback response="
              + response
              + " transmissions="
              + observed.transmissions
              + " applications="
              + faults.applications(fault)
              + " reservations="
              + faults.reservations(fault)
              + " pending="
              + faults.pending(operation)
              + " finish="
              + status);
      assertAll(
          () -> assertEquals(List.of(first), response.unprocessedItems().get("snapshot")),
          () -> assertEquals(1, observed.transmissions),
          () ->
              assertEquals(
                  DynamoDbJson.sdk(List.of(second)),
                  observed.transmitted.path("RequestItems").path("snapshot")),
          () -> assertEquals(1, faults.applications(fault)),
          () -> assertEquals(0, faults.reservations(fault)),
          () -> assertEquals(0, faults.pending(operation)),
          () -> assertEquals("passed", status));
    }
    assertTrue(closed.get());
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
