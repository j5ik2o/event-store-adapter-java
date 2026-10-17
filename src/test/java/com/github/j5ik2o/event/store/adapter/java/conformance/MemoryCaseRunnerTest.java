package com.github.j5ik2o.event.store.adapter.java.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.memory.ConformanceMemoryOperations;
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryEventStore;
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage;
import com.github.j5ik2o.event.store.adapter.java.memory.OwnDefaultConfigurationOpener;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class MemoryCaseRunnerTest {
  @TestFactory
  Stream<DynamicTest> commonMemoryScenariosExecuteIncludingFaultsHistoryAndNotifications()
      throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.file().startsWith("scenarios/core/"))
        .filter(c -> !c.timePrecision().map("milliseconds"::equals).orElse(false))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult result = CaseClassifier.classify(c, Backend.MEMORY);
                      assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
                    }));
  }

  @Test
  void incorrectReadExpectationCannotBeUsedToGenerateStoredState() throws IOException {
    ConformanceCase original = find("core-replay-without-snapshot");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/steps/4/expect")).put("head_seq_nr", 7);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void incorrectHistoryExpectationIsRejected() throws IOException {
    ConformanceCase original = find("core-retention-delete-1");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/history/active")).removeAll().add(1);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void historyDeclaredAbsentMustNotRemainActive() throws IOException {
    ConformanceCase original = find("core-retention-failure-after-commit");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/history/absent")).add(1);

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void emptyExpectedNotificationsCannotHideRetentionFailure() throws IOException {
    ConformanceCase original = find("core-retention-failure-after-commit");
    ObjectNode changed = original.materialized().deepCopy();
    ((ArrayNode) changed.at("/steps/1/observe/notifications")).removeAll();

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void registeredFaultThatNeverFiresCannotPass() throws IOException {
    ConformanceCase original = find("core-storage-commit-failure");
    ObjectNode changed = original.materialized().deepCopy();
    // The second operation is a read, so this commit fault cannot fire.
    ((ObjectNode) changed.at("/faults/0")).put("operation", 2);
    ObjectNode expectation = (ObjectNode) changed.at("/steps/0/expect");
    expectation.remove("error");
    expectation.put("result", "success");
    ((ObjectNode) changed.at("/steps/1/expect"))
        .put("result", "snapshot")
        .put("head_seq_nr", 1)
        .put("snapshot", "s1");
    ((ArrayNode) changed.at("/steps/2/expect/events")).add("e1");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
    assertEquals(2, result.failedOperation());
  }

  @TestFactory
  Stream<DynamicTest> nanosecondTimeCasesExecuteOnMemoryAndRemainUnverifiedOnDynamoDb()
      throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.operation().map("validateOccurredAt"::equals).orElse(false))
        .filter(c -> !c.timePrecision().map("milliseconds"::equals).orElse(false))
        .map(
            c ->
                dynamicTest(
                    c.id(),
                    () -> {
                      CaseResult memory = CaseClassifier.classify(c, Backend.MEMORY);
                      assertEquals(ConformanceStatus.PASSED, memory.status(), memory.reason());
                      assertEquals(
                          ConformanceStatus.UNVERIFIED,
                          CaseClassifier.classify(c, Backend.DYNAMODB).status());
                    }));
  }

  @Test
  void finiteFaultCountMustEqualActualApplications() throws IOException {
    ConformanceCase original = find("core-storage-commit-failure");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/faults/0/repeat")).put("count", 2);
    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);
    assertEquals(ConformanceStatus.FAILED, result.status());
    assertEquals(1, result.failedOperation());
    assertEquals(1, result.actual().at("/faults/0/applications").intValue());
  }

  @TestFactory
  Stream<DynamicTest> unconnectedObservationsAndFaultDetailsRemainUnverified() {
    return Stream.of("requests", "unknown-history", "unknown-fault-detail")
        .map(
            field ->
                dynamicTest(
                    field,
                    () -> {
                      ConformanceCase original = find("core-retention-failure-after-commit");
                      ObjectNode changed = original.materialized().deepCopy();
                      if (field.equals("requests"))
                        ((ObjectNode) changed.at("/steps/1/observe")).putArray(field);
                      else if (field.equals("unknown-history"))
                        ((ObjectNode) changed.at("/steps/1/observe/history")).putArray(field);
                      else ((ObjectNode) changed.at("/faults/0/details")).put(field, true);
                      CaseResult result =
                          CaseClassifier.classify(copy(original, changed), Backend.MEMORY);
                      assertEquals(ConformanceStatus.UNVERIFIED, result.status());
                      org.junit.jupiter.api.Assertions.assertFalse(
                          result.actual().has("initialization"));
                    }));
  }

  @Test
  void ttlRetentionCaseFailsMemoryStoreCreationAsConfigurationError() throws IOException {
    CaseResult result = CaseClassifier.classify(find("memory-ttl-unsupported"), Backend.MEMORY);

    assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
  }

  @Test
  void ttlRetentionCaseExpectingAnotherErrorCategoryIsRejected() throws IOException {
    ConformanceCase original = find("memory-ttl-unsupported");
    ObjectNode changed = original.materialized().deepCopy();
    ((ObjectNode) changed.at("/initialization/expect/error")).put("category", "storage");

    CaseResult result = CaseClassifier.classify(copy(original, changed), Backend.MEMORY);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void changeFeedCaseIsReportedUnrepresentableWithReason() throws IOException {
    CaseResult result =
        CaseClassifier.classify(find("memory-change-feed-unsupported"), Backend.MEMORY);

    assertEquals(ConformanceStatus.UNREPRESENTABLE, result.status());
    org.junit.jupiter.api.Assertions.assertNotNull(result.reason());
    org.junit.jupiter.api.Assertions.assertFalse(result.reason().isBlank());
  }

  private static final List<String> NEW_MEMORY_CASES =
      List.of(
          "memory-store-new-independent",
          "memory-store-shared",
          "memory-store-shared-configuration",
          "memory-payload-written-mutation",
          "memory-payload-read-mutation");

  @TestFactory
  Stream<DynamicTest> storeCreationAndPayloadMutationCasesPassOnTheRealMemoryStore() {
    return NEW_MEMORY_CASES.stream()
        .map(
            id ->
                dynamicTest(
                    id,
                    () -> {
                      CaseResult result = MemoryCaseRunner.run(find(id));
                      assertEquals(ConformanceStatus.PASSED, result.status(), result.reason());
                    }));
  }

  @Test
  void storeSharingFakeThatIgnoresNewStorageIsRejectedByTheIndependentScenario()
      throws IOException {
    AtomicReference<MemoryStorage> first = new AtomicReference<>();
    ConformanceMemoryOperations.StoreOpener sharing =
        (storage, config) -> {
          first.compareAndSet(null, storage);
          return MemoryEventStore.create(first.get(), config);
        };

    CaseResult result = MemoryCaseRunner.run(find("memory-store-new-independent"), sharing);

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void sharedStoreThatKeepsItsOwnDefaultConfigurationIsRejectedBySharedConfigurationScenario()
      throws IOException {
    CaseResult result =
        MemoryCaseRunner.run(
            find("memory-store-shared-configuration"), new OwnDefaultConfigurationOpener());

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  @Test
  void storeThatReturnsWrittenPayloadWithoutCopyingIsRejectedByBothPayloadScenarios()
      throws IOException {
    ConformanceMemoryOperations.StoreOpener aliasing =
        (storage, config) -> new AliasingStore(MemoryEventStore.create(storage, config));

    for (String id : List.of("memory-payload-written-mutation", "memory-payload-read-mutation")) {
      CaseResult result = MemoryCaseRunner.run(find(id), aliasing);
      assertEquals(ConformanceStatus.FAILED, result.status(), id + ": " + result.reason());
    }
  }

  @Test
  void mutationAtANonexistentPositionIsRejected() throws IOException {
    ConformanceCase original = find("memory-payload-written-mutation");
    ObjectNode changed = original.materialized().deepCopy();
    JsonNode steps = changed.path("steps");
    ObjectNode mutation = null;
    for (JsonNode step : steps) {
      if ("mutateWrittenPayload".equals(step.path("op").asText())) {
        mutation = (ObjectNode) step.path("arguments");
      }
    }
    assertNotNull(mutation);
    mutation.put("pointer", "/items/99/qty");

    CaseResult result = MemoryCaseRunner.run(copy(original, changed));

    assertEquals(ConformanceStatus.FAILED, result.status(), result.reason());
  }

  /** 書き込まれた実体を複製せずに保持し、読み取りでそのまま返す偽の保存先。 */
  private static final class AliasingStore implements EventStore<JsonNode, JsonNode> {
    private final EventStore<JsonNode, JsonNode> delegate;
    private final List<EventEnvelope<JsonNode>> kept = new ArrayList<>();

    AliasingStore(EventStore<JsonNode, JsonNode> delegate) {
      this.delegate = delegate;
    }

    @Override
    public void persistEvent(EventEnvelope<JsonNode> event) {
      delegate.persistEvent(event);
      kept.add(event);
    }

    @Override
    public void persistEventAndSnapshot(
        EventEnvelope<JsonNode> event, SnapshotEnvelope<JsonNode> snapshot) {
      delegate.persistEventAndSnapshot(event, snapshot);
      kept.add(event);
    }

    @Override
    public Optional<SnapshotReadResult<JsonNode>> getLatestSnapshotById(AggregateId aggregateId) {
      return delegate.getLatestSnapshotById(aggregateId);
    }

    @Override
    public List<EventEnvelope<JsonNode>> getEventsByIdSinceSeqNr(
        AggregateId aggregateId, long seqNr) {
      List<EventEnvelope<JsonNode>> result = new ArrayList<>();
      for (EventEnvelope<JsonNode> real : delegate.getEventsByIdSinceSeqNr(aggregateId, seqNr)) {
        EventEnvelope<JsonNode> same = real;
        for (EventEnvelope<JsonNode> k : kept) {
          if (k.aggregateId().equals(real.aggregateId()) && k.seqNr() == real.seqNr()) {
            same = k;
          }
        }
        result.add(same);
      }
      return result;
    }
  }

  private static ConformanceCase find(String id) throws IOException {
    return ConformanceDataLoader.load(ConformanceTestFiles.REAL_ROOT).cases().stream()
        .filter(c -> c.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError(id));
  }

  private static ConformanceCase copy(ConformanceCase original, ObjectNode changed) {
    return new ConformanceCase(
        original.id(),
        original.file(),
        original.format(),
        original.rules(),
        changed.deepCopy(),
        changed);
  }
}
