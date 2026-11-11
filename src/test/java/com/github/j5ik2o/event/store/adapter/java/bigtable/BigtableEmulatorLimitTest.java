package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BigtableEmulatorLimitTest {
  @Test
  void aReadFailsNamingTheWaitWhenTheEmulatorStopsAnswering() throws IOException {
    Duration limit = Duration.ofSeconds(2);
    // The kernel accepts the client's connection into the backlog, and nothing ever answers.
    try (ServerSocket silent = new ServerSocket(0);
        BigtableDataClient data =
            BigtableEmulator.newDataClient("localhost", silent.getLocalPort(), null)) {
      IllegalStateException failure =
          assertTimeoutPreemptively(
              Duration.ofSeconds(20),
              () ->
                  assertThrows(
                      IllegalStateException.class,
                      () ->
                          BigtableEmulator.row(
                              data, limit, "esa_journal", ByteString.copyFromUtf8("k"))));
      assertTrue(
          failure.getMessage().contains("ReadRow on esa_journal")
              && failure.getMessage().contains("within 2 s"),
          failure.getMessage());
    }
  }
}
