package com.github.j5ik2o.event.store.adapter.java.bigtable;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.HandlerRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import io.grpc.Status;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * A gRPC relay placed between the client under test and the real emulator. It records every call
 * (method, the {@code authorization} header, the request bytes) so authentication and request
 * counts are observed on the server side, where the transport layer has already attached the
 * credentials. It can also cut a ReadRows stream after a number of messages and drop the response
 * of one CheckAndMutateRow after the emulator applied it.
 */
public final class BigtableRecordingProxy implements AutoCloseable {
  static final String READ_ROWS = "google.bigtable.v2.Bigtable/ReadRows";
  static final String CHECK_AND_MUTATE = "google.bigtable.v2.Bigtable/CheckAndMutateRow";
  static final String GET_TABLE = "google.bigtable.admin.v2.BigtableTableAdmin/GetTable";

  /** One relayed call. */
  public static final class Call {
    public final String method;
    @Nullable public final String authorization;
    public final List<byte[]> requests = Collections.synchronizedList(new ArrayList<>());

    Call(String method, @Nullable String authorization) {
      this.method = method;
      this.authorization = authorization;
    }
  }

  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

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

  private final ManagedChannel upstream;
  private final Server server;
  private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger readRowsMessagesBeforeCut = new AtomicInteger(-1);
  private final AtomicInteger readRowsCallsToSkipBeforeCut = new AtomicInteger();
  private final AtomicInteger dropCheckAndMutateResponses = new AtomicInteger();

  public BigtableRecordingProxy(String upstreamHost, int upstreamPort) throws IOException {
    this.upstream =
        ManagedChannelBuilder.forAddress(upstreamHost, upstreamPort).usePlaintext().build();
    this.server =
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
                    return ServerMethodDefinition.create(descriptor, new Relay(descriptor));
                  }
                })
            .build()
            .start();
  }

  public int port() {
    return server.getPort();
  }

  /** Every call relayed so far, oldest first. */
  public List<Call> calls() {
    synchronized (calls) {
      return new ArrayList<>(calls);
    }
  }

  public List<Call> callsTo(String method) {
    List<Call> result = new ArrayList<>();
    for (Call call : calls()) if (call.method.equals(method)) result.add(call);
    return result;
  }

  /**
   * After {@code callsToSkip} further ReadRows calls pass untouched, the next ReadRows stream is
   * cut with UNAVAILABLE once this many response messages were delivered. A journal range read
   * follows the single-row head read, so tests skip one call.
   */
  public void cutReadRowsAfterMessages(int callsToSkip, int messages) {
    readRowsCallsToSkipBeforeCut.set(callsToSkip);
    readRowsMessagesBeforeCut.set(messages);
  }

  /** The next CheckAndMutateRow is applied by the emulator but its response never arrives. */
  public void dropNextCheckAndMutateResponse() {
    dropCheckAndMutateResponses.incrementAndGet();
  }

  @Override
  public void close() {
    server.shutdownNow();
    upstream.shutdownNow();
  }

  private final class Relay implements ServerCallHandler<byte[], byte[]> {
    private final MethodDescriptor<byte[], byte[]> descriptor;

    Relay(MethodDescriptor<byte[], byte[]> descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public ServerCall.Listener<byte[]> startCall(ServerCall<byte[], byte[]> in, Metadata headers) {
      Call call = new Call(descriptor.getFullMethodName(), headers.get(AUTHORIZATION));
      calls.add(call);
      boolean cutThisStream =
          call.method.equals(READ_ROWS)
              && readRowsMessagesBeforeCut.get() >= 0
              && readRowsCallsToSkipBeforeCut.getAndUpdate(n -> Math.max(0, n - 1)) == 0;
      int cutAfter = cutThisStream ? readRowsMessagesBeforeCut.getAndSet(-1) : -1;
      boolean dropResponse =
          call.method.equals(CHECK_AND_MUTATE)
              && dropCheckAndMutateResponses.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
      ClientCall<byte[], byte[]> out = upstream.newCall(descriptor, CallOptions.DEFAULT);
      AtomicInteger delivered = new AtomicInteger();
      out.start(
          new ClientCall.Listener<byte[]>() {
            @Override
            public void onHeaders(Metadata responseHeaders) {
              in.sendHeaders(responseHeaders);
            }

            @Override
            public void onMessage(byte[] message) {
              if (dropResponse) {
                in.close(
                    Status.UNAVAILABLE.withDescription("response dropped by the proxy"),
                    new Metadata());
                out.cancel("response dropped", null);
                return;
              }
              if (cutAfter >= 0 && delivered.get() >= cutAfter) {
                in.close(
                    Status.UNAVAILABLE.withDescription("stream cut by the proxy"), new Metadata());
                out.cancel("stream cut", null);
                return;
              }
              delivered.incrementAndGet();
              in.sendMessage(message);
              out.request(1);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              if (dropResponse || (cutAfter >= 0 && delivered.get() >= cutAfter)) {
                return;
              }
              in.close(status, trailers);
            }
          },
          headers);
      out.request(1);
      in.request(1);
      return new ServerCall.Listener<byte[]>() {
        @Override
        public void onMessage(byte[] message) {
          call.requests.add(message);
          out.sendMessage(message);
          in.request(1);
        }

        @Override
        public void onHalfClose() {
          out.halfClose();
        }

        @Override
        public void onCancel() {
          out.cancel("client cancelled", null);
        }
      };
    }
  }
}
