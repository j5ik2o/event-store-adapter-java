package com.github.j5ik2o.event.store.adapter.java.spanner;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.cloud.NoCredentials;
import com.google.cloud.spanner.Spanner;
import com.google.cloud.spanner.SpannerOptions;
import io.grpc.HandlerRegistry;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import javax.annotation.Nullable;
import org.junit.jupiter.api.Test;

class SpannerEmulatorLimitTest {
  @Test
  void ddlFailsNamingTheWaitWhenTheEmulatorStopsAnswering() throws IOException {
    Duration limit = Duration.ofSeconds(2);
    try (SilentEmulator silent = new SilentEmulator()) {
      IllegalStateException failure =
          assertTimeoutPreemptively(
              Duration.ofSeconds(20),
              () ->
                  assertThrows(
                      IllegalStateException.class,
                      () ->
                          SpannerEmulator.ddl(
                              silent.spanner, limit, "CREATE TABLE t (k INT64) PRIMARY KEY (k)")));
      assertTrue(
          failure.getMessage().contains("DDL (1 statement)")
              && failure.getMessage().contains("within 2 s"),
          failure.getMessage());
    }
  }

  /**
   * Answers the connection check a Spanner client makes when it is built, then leaves every other
   * call open without a response, like an emulator that disappeared behind a kept connection.
   */
  private static final class SilentEmulator implements AutoCloseable {
    private static final String CONNECTION_CHECK =
        "google.spanner.admin.instance.v1.InstanceAdmin/ListInstanceConfigs";
    private static final MethodDescriptor.Marshaller<byte[]> BYTES =
        new MethodDescriptor.Marshaller<byte[]>() {
          @Override
          public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
          }

          @Override
          public byte[] parse(InputStream stream) {
            try {
              return stream.readAllBytes();
            } catch (IOException failure) {
              throw new IllegalStateException(failure);
            }
          }
        };

    private final Server server;
    final Spanner spanner;

    SilentEmulator() throws IOException {
      server =
          ServerBuilder.forPort(0)
              .fallbackHandlerRegistry(
                  new HandlerRegistry() {
                    @Override
                    public ServerMethodDefinition<?, ?> lookupMethod(
                        String methodName, @Nullable String authority) {
                      MethodDescriptor<byte[], byte[]> descriptor =
                          MethodDescriptor.<byte[], byte[]>newBuilder()
                              .setType(MethodDescriptor.MethodType.UNKNOWN)
                              .setFullMethodName(methodName)
                              .setRequestMarshaller(BYTES)
                              .setResponseMarshaller(BYTES)
                              .build();
                      return ServerMethodDefinition.create(
                          descriptor,
                          (ServerCall<byte[], byte[]> call, Metadata headers) -> {
                            call.request(Integer.MAX_VALUE);
                            if (methodName.equals(CONNECTION_CHECK)) {
                              call.sendHeaders(new Metadata());
                              call.sendMessage(new byte[0]);
                              call.close(Status.OK, new Metadata());
                            }
                            return new ServerCall.Listener<byte[]>() {};
                          });
                    }
                  })
              .build()
              .start();
      spanner =
          SpannerOptions.newBuilder()
              .setProjectId("test-project")
              .setEmulatorHost("localhost:" + server.getPort())
              .setCredentials(NoCredentials.getInstance())
              .build()
              .getService();
    }

    @Override
    public void close() {
      // Ending the calls left open first lets the client close without waiting on them.
      server.shutdownNow();
      spanner.close();
    }
  }
}
