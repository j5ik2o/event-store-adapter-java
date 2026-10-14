package com.github.j5ik2o.event.store.adapter.java.apisurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 形をわざとずらしたクラスを、照合がそれぞれ分かりやすく報告し、関係のないずれは報告しないことを確かめる。 */
class ApiSurfaceNegativeTest {

  private static final String FIXTURES =
      "com.github.j5ik2o.event.store.adapter.java.apisurface.fixtures.";

  @Test
  void reportsEventEnvelopeWithoutOccurredAt() throws IOException {
    List<String> problems = check(envelopeFrom("missingoccurredat"));

    assertReported(
        problems,
        "event_envelope.occurred_at",
        "event_envelope.occurred_at: EventEnvelope has no accessor occurredAt()");
  }

  @Test
  void reportsRenamedManifestAccessor() throws IOException {
    List<String> problems = check(envelopeFrom("renamedmanifest"));

    assertReported(
        problems,
        "event_envelope.manifest",
        "event_envelope.manifest: EventEnvelope has no accessor manifest()");
  }

  @Test
  void reportsSeqNrReturningInt() throws IOException {
    List<String> problems = check(envelopeFrom("intseqnr"));

    assertReported(
        problems,
        "event_envelope.seq_nr",
        "event_envelope.seq_nr: EventEnvelope.seqNr() returns int, want long");
  }

  @Test
  void reportsBuilderThatBuildsWithoutSeqNr() throws IOException {
    List<String> problems = check(envelopeFrom("optionalseqnr"));

    assertReported(
        problems,
        "event_envelope.seq_nr",
        "event_envelope.seq_nr: build() without seq_nr succeeded; want ContractViolationException");
  }

  @Test
  void reportsGetEventsReturningPayloadsInsteadOfEnvelopes() throws IOException {
    Map<String, String> overrides = new HashMap<>();
    overrides.put("EventStore", FIXTURES + "eventsreturnpayloads.EventStore");

    List<String> problems = check(overrides);

    assertReported(
        problems,
        "get_events_by_id_since_seq_nr",
        "get_events_by_id_since_seq_nr: EventStore.getEventsByIdSinceSeqNr returns java.util.List<P>,"
            + " want java.util.List<EventEnvelope<P>>");
  }

  @Test
  void reportsMissingStorageException() throws IOException {
    Map<String, String> overrides = new HashMap<>();
    overrides.put("StorageException", FIXTURES + "missingstorage.StorageException");

    List<String> problems = check(overrides);

    assertReported(problems, "storage", "storage: class StorageException not found");
  }

  @Test
  void reportsTypeParameterNarrowedByExtends() throws IOException {
    List<String> problems = check(envelopeFrom("narrowedtypeparameter"));

    assertReported(
        problems,
        "event_envelope",
        "event_envelope: type parameter P of EventEnvelope is bounded by "
            + "[java.lang.Comparable<P>]; want no bound");
  }

  private static Map<String, String> envelopeFrom(String fixturePackage) {
    Map<String, String> overrides = new HashMap<>();
    overrides.put("EventEnvelope", FIXTURES + fixturePackage + ".EventEnvelope");
    overrides.put("EventEnvelope$Builder", FIXTURES + fixturePackage + ".EventEnvelope$Builder");
    return overrides;
  }

  private static List<String> check(Map<String, String> overrides) throws IOException {
    JsonNode mapping = ApiSurfaceFiles.mapping();
    ClassLookup lookup =
        ClassLookup.inPackage(mapping.path("package").asText()).withOverrides(overrides);
    return ApiSurfaceChecker.check(ApiSurfaceFiles.surface(), mapping, lookup);
  }

  /** 期待するメッセージがちょうど 1 件あり、ずらした項目（surface の経路）以外のずれがないこと。 */
  private static void assertReported(
      List<String> problems, String shiftedPath, String expectedMessage) {
    assertTrue(
        problems.contains(expectedMessage),
        "expected: " + expectedMessage + "\nactual: " + problems);
    for (String problem : problems) {
      assertTrue(
          problem.equals(shiftedPath)
              || problem.startsWith(shiftedPath + ":")
              || problem.startsWith(shiftedPath + "."),
          "unrelated problem reported: " + problem);
    }
    assertEquals(1, problems.size(), "exactly one problem expected: " + problems);
  }
}
