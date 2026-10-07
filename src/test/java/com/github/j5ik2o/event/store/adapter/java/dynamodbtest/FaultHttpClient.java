package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;

final class FaultHttpClient implements SdkHttpClient {
  private final SdkHttpClient delegate;
  private final DynamoDbRequestRecorder recorder;

  FaultHttpClient(SdkHttpClient delegate, DynamoDbRequestRecorder recorder) {
    this.delegate = delegate;
    this.recorder = recorder;
  }

  @Override
  public ExecutableHttpRequest prepareRequest(HttpExecuteRequest request) {
    AtomicBoolean aborted = new AtomicBoolean();
    AtomicReference<ExecutableHttpRequest> pending = new AtomicReference<>();
    return new ExecutableHttpRequest() {
      @Override
      public HttpExecuteResponse call() throws IOException {
        if (aborted.get()) throw new IOException("Request aborted");
        HttpReply replacement = recorder.replacement(request.httpRequest());
        if (replacement != null) return replacement.sync();
        try (InputStream body = request.contentStreamProvider().orElseThrow().newStream()) {
          recorder.transmitted(request.httpRequest(), body.readAllBytes());
        }
        ExecutableHttpRequest actual = delegate.prepareRequest(request);
        pending.set(actual);
        if (aborted.get()) {
          actual.abort();
          throw new IOException("Request aborted");
        }
        return actual.call();
      }

      @Override
      public void abort() {
        aborted.set(true);
        if (pending.get() != null) pending.get().abort();
      }
    };
  }

  @Override
  public void close() {
    delegate.close();
  }
}
