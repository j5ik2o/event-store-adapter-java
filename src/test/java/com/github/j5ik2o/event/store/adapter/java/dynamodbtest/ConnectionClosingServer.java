package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Accepts real TCP connections, reads the request head, and closes without a response. */
final class ConnectionClosingServer implements AutoCloseable {
  private final ServerSocket socket;
  private final Thread acceptor;
  private final AtomicInteger accepted = new AtomicInteger();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();

  private ConnectionClosingServer(ServerSocket socket) {
    this.socket = socket;
    this.acceptor = new Thread(this::acceptConnections, "connection-closing-server");
    this.acceptor.setDaemon(true);
  }

  static ConnectionClosingServer start() throws IOException {
    ConnectionClosingServer server =
        new ConnectionClosingServer(new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")));
    server.acceptor.start();
    return server;
  }

  int port() {
    return socket.getLocalPort();
  }

  int acceptedConnections() {
    return accepted.get();
  }

  private void acceptConnections() {
    while (true) {
      try (Socket connection = socket.accept()) {
        accepted.incrementAndGet();
        connection.setSoTimeout(5000);
        readRequestHead(connection.getInputStream());
      } catch (SocketException closed) {
        if (socket.isClosed()) return;
        failure.compareAndSet(null, closed);
        return;
      } catch (IOException | RuntimeException error) {
        failure.compareAndSet(null, error);
        return;
      }
    }
  }

  private static void readRequestHead(InputStream input) throws IOException {
    int matched = 0;
    int value;
    while ((value = input.read()) >= 0) {
      matched = value == "\r\n\r\n".charAt(matched) ? matched + 1 : (value == '\r' ? 1 : 0);
      if (matched == 4) return;
    }
  }

  @Override
  public void close() throws IOException {
    socket.close();
    try {
      acceptor.join(TimeUnit.SECONDS.toMillis(5));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while closing the server", interrupted);
    }
    if (acceptor.isAlive()) throw new IllegalStateException("The accept thread did not stop");
    Throwable error = failure.get();
    if (error != null) throw new IllegalStateException("The server failed", error);
  }
}
