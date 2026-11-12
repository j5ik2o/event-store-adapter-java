package com.github.j5ik2o.event.store.adapter.java.coreonly;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.BlockingExecution;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class BlockingExecutionCoreOnlyTest {

  @Test
  void runsOnItsOwnThreadInSubmitOrderAndRejectsAfterDrain() throws Exception {
    String callerThread = Thread.currentThread().getName();
    BlockingExecution execution = BlockingExecution.singleWorker(2);
    List<Integer> executed = Collections.synchronizedList(new ArrayList<>());
    try {
      List<CompletableFuture<String>> futures = new ArrayList<>();
      for (int seqNr = 1; seqNr <= 3; seqNr++) {
        int submittedSeqNr = seqNr;
        futures.add(
            execution.submit(() -> {
              executed.add(submittedSeqNr);
              return Thread.currentThread().getName();
            }));
      }

      List<String> threadNames = new ArrayList<>();
      for (CompletableFuture<String> future : futures) {
        threadNames.add(future.get(10, TimeUnit.SECONDS));
      }

      assertEquals(List.of(1, 2, 3), executed, "results completed in submit order");
      assertEquals(threadNames.get(0), threadNames.get(1), "one worker thread");
      assertEquals(threadNames.get(0), threadNames.get(2), "one worker thread");
      assertNotEquals(callerThread, threadNames.get(0), "not the caller's thread");

      execution.close();
      assertDoesNotThrow(() -> execution.drained().toCompletableFuture().get(10, TimeUnit.SECONDS));

      CompletableFuture<String> rejected = execution.submit(() -> "not admitted after close");
      ExecutionException failure =
          assertThrows(
              ExecutionException.class, () -> rejected.get(10, TimeUnit.SECONDS), "closed lane");
      assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    } finally {
      execution.close();
    }
  }

  @Test
  void exposesCoreButNotTheOtherDriversOrTheirSdks() {
    ClassLoader loader = BlockingExecutionCoreOnlyTest.class.getClassLoader();
    assertDoesNotThrow(() ->
        Class.forName(
            "com.github.j5ik2o.event.store.adapter.java.core.BlockingExecution", false, loader));
    for (String unavailableClass :
        List.of(
            "com.google.cloud.spanner.Spanner",
            "com.google.cloud.bigtable.data.v2.BigtableDataClient",
            "org.sqlite.JDBC")) {
      assertThrows(
          ClassNotFoundException.class, () -> Class.forName(unavailableClass, false, loader));
    }
  }
}
