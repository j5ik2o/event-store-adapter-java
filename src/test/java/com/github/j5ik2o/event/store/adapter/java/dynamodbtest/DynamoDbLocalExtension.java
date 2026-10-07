package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** One Local per JUnit execution, with SDK readiness and root-store shutdown. */
final class DynamoDbLocalExtension implements BeforeAllCallback {
  static final String IMAGE =
      "amazon/dynamodb-local@sha256:ff89bd48ff32cd8d9be5fee8873b65b8854dc408f1afe881be6eb00247bc0dab";
  private Server server;

  @Override
  public void beforeAll(ExtensionContext context) {
    server =
        context
            .getRoot()
            .getStore(ExtensionContext.Namespace.create(DynamoDbLocalExtension.class))
            .getOrComputeIfAbsent(Server.class, key -> new Server(), Server.class);
  }

  URI endpoint() {
    return server.endpoint;
  }

  String logs() {
    return server.container.getLogs();
  }

  private static final class Server implements ExtensionContext.Store.CloseableResource {
    private final GenericContainer<?> container =
        new GenericContainer<>(DockerImageName.parse(IMAGE))
            .withExposedPorts(8000)
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory");
    private final URI endpoint;

    private Server() {
      try {
        container.start();
        endpoint =
            URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8000));
        try (DynamoDbClient client = DynamoDbTestClients.admin(endpoint)) {
          long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
          while (true) {
            try {
              client.listTables();
              break;
            } catch (SdkException error) {
              if (System.nanoTime() >= deadline) throw error;
              try {
                Thread.sleep(50);
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Local readiness interrupted", interrupted);
              }
            }
          }
        }
      } catch (Throwable error) {
        try {
          container.stop();
        } catch (Throwable cleanup) {
          error.addSuppressed(cleanup);
        }
        throw error;
      }
    }

    @Override
    public void close() {
      container.stop();
      if (container.isRunning()) throw new AssertionError("DynamoDB Local did not stop");
    }
  }
}
