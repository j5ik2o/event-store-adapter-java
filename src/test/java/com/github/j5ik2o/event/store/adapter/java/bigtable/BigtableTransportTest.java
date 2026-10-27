package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.AID;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.EMULATOR;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.config;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.event;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.headSeqNr;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.noneStore;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.prefix;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.Credentials;
import com.google.bigtable.v2.ReadRowsRequest;
import com.google.bigtable.v2.RowRange;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The authentication seam and the transport behaviour, observed on a relay in front of the real
 * emulator: caller-owned Bearer credentials reach both the data and the admin service, a failing
 * credential writes nothing, an interrupted ReadRows resumes without gaps or duplicates, and a lost
 * conditional-update response is never resent.
 */
class BigtableTransportTest {
  private static final String BEARER = "Bearer test-token";

  /** Caller-owned credentials. The library never creates credentials itself. */
  private static final class FixedBearerCredentials extends Credentials {
    final AtomicBoolean failing = new AtomicBoolean();

    @Override
    public String getAuthenticationType() {
      return "OAuth2";
    }

    @Override
    public Map<String, List<String>> getRequestMetadata(URI uri) throws IOException {
      if (failing.get()) throw new IOException("token unavailable");
      return Collections.singletonMap("authorization", Collections.singletonList(BEARER));
    }

    @Override
    public boolean hasRequestMetadata() {
      return true;
    }

    @Override
    public boolean hasRequestMetadataOnly() {
      return true;
    }

    @Override
    public void refresh() {}
  }

  private static final class Rig implements AutoCloseable {
    final BigtableRecordingProxy proxy;
    final FixedBearerCredentials credentials = new FixedBearerCredentials();
    final BigtableDataClient data;
    final BigtableTableAdminClient admin;

    Rig() throws IOException {
      proxy = new BigtableRecordingProxy(EMULATOR.host(), EMULATOR.port());
      FixedCredentialsProvider provider = FixedCredentialsProvider.create(credentials);
      data = BigtableEmulator.newDataClient("127.0.0.1", proxy.port(), provider);
      admin = BigtableEmulator.newAdminClient("127.0.0.1", proxy.port(), provider);
    }

    BigtableStorage open(BigtableEmulator.Tables t) {
      return BigtableStorage.open(data, admin, settings(t, RetentionPolicy.none()));
    }

    @Override
    public void close() {
      data.close();
      admin.close();
      proxy.close();
    }
  }

  private static ByteString startKey(RowRange range) {
    return range.getStartKeyCase() == RowRange.StartKeyCase.START_KEY_CLOSED
        ? range.getStartKeyClosed()
        : range.getStartKeyOpen();
  }

  private static List<ReadRowsRequest> readRowsOn(Rig rig, String table) throws Exception {
    List<ReadRowsRequest> result = new ArrayList<>();
    for (BigtableRecordingProxy.Call call : rig.proxy.callsTo(BigtableRecordingProxy.READ_ROWS)) {
      for (byte[] request : call.requests) {
        ReadRowsRequest parsed = ReadRowsRequest.parseFrom(request);
        if (parsed.getTableName().endsWith("/" + table)) result.add(parsed);
      }
    }
    return result;
  }

  @Test
  void theCallersBearerTokenIsSentOnEveryAdminAndDataRequest() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    try (Rig rig = new Rig()) {
      EventStore<String, String> store = BigtableEventStore.create(rig.open(t), config());
      store.persistEvent(event(1));
      store.persistEvent(event(2));
      store.getEventsByIdSinceSeqNr(AID, 1);
      store.getLatestSnapshotById(AID);

      List<BigtableRecordingProxy.Call> calls = rig.proxy.calls();
      assertFalse(rig.proxy.callsTo(BigtableRecordingProxy.GET_TABLE).isEmpty(), "admin GetTable");
      assertFalse(rig.proxy.callsTo(BigtableRecordingProxy.READ_ROWS).isEmpty(), "data ReadRows");
      assertFalse(
          rig.proxy.callsTo(BigtableRecordingProxy.CHECK_AND_MUTATE).isEmpty(),
          "data CheckAndMutateRow");
      for (BigtableRecordingProxy.Call call : calls) {
        assertEquals(BEARER, call.authorization, call.method);
      }
    }
  }

  @Test
  void aFailingCredentialIsAStorageErrorAndNothingIsWritten() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    try (Rig rig = new Rig()) {
      EventStore<String, String> store = BigtableEventStore.create(rig.open(t), config());
      store.persistEvent(event(1));
      rig.credentials.failing.set(true);
      int rowsBefore = EMULATOR.rows(t.head).size() + EMULATOR.rows(t.journal).size();

      assertThrows(StorageException.class, () -> store.persistEvent(event(2)));

      rig.credentials.failing.set(false);
      assertEquals(rowsBefore, EMULATOR.rows(t.head).size() + EMULATOR.rows(t.journal).size());
      assertEquals(headSeqNr(1), EMULATOR.text(t.head, prefix("Order-1"), "head", "seq_nr"));
    }
  }

  @Test
  void aFailingCredentialAtOpenIsAStorageErrorNotAConfigurationError() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    try (Rig rig = new Rig()) {
      rig.credentials.failing.set(true);

      assertThrows(StorageException.class, () -> rig.open(t));
    }
  }

  @Test
  void anInterruptedReadRowsResumesAfterTheLastCompletedRowWithoutGapsOrDuplicates()
      throws Exception {
    BigtableEmulator.Tables t = noneStore();
    try (Rig rig = new Rig()) {
      EventStore<String, String> store = BigtableEventStore.create(rig.open(t), config());
      String big = "q".repeat(300_000);
      for (long seq = 1; seq <= 12; seq++) {
        store.persistEvent(
            EventEnvelope.<String>builder()
                .aggregateId(AID)
                .seqNr(seq)
                .occurredAt(Instant.EPOCH)
                .manifest("event")
                .payload(big + seq)
                .build());
      }
      int before = readRowsOn(rig, t.journal).size();
      rig.proxy.cutReadRowsAfterMessages(1, 1);

      List<EventEnvelope<String>> events = store.getEventsByIdSinceSeqNr(AID, 1);

      assertEquals(
          java.util.stream.LongStream.rangeClosed(1, 12).boxed().collect(Collectors.toList()),
          events.stream().map(EventEnvelope::seqNr).collect(Collectors.toList()));
      List<ReadRowsRequest> journalReads = readRowsOn(rig, t.journal);
      List<ReadRowsRequest> thisRead = journalReads.subList(before, journalReads.size());
      assertTrue(thisRead.size() >= 2, "the stream was cut, so the read had to be resumed");
      ByteString first = startKey(thisRead.get(0).getRows().getRowRanges(0));
      ByteString resumed = startKey(thisRead.get(thisRead.size() - 1).getRows().getRowRanges(0));
      assertTrue(
          ByteString.unsignedLexicographicalComparator().compare(resumed, first) >= 0,
          "the resumed range never starts before the original one");
    }
  }

  @Test
  void aLostConditionalUpdateResponseIsNeverResentAndTheOutcomeIsUnknown() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    try (Rig rig = new Rig()) {
      EventStore<String, String> store = BigtableEventStore.create(rig.open(t), config());
      int before = rig.proxy.callsTo(BigtableRecordingProxy.CHECK_AND_MUTATE).size();
      rig.proxy.dropNextCheckAndMutateResponse();

      assertThrows(StorageException.class, () -> store.persistEvent(event(1)));

      assertEquals(
          before + 1,
          rig.proxy.callsTo(BigtableRecordingProxy.CHECK_AND_MUTATE).size(),
          "CheckAndMutateRow must be sent exactly once");
      assertEquals(
          headSeqNr(1),
          EMULATOR.text(t.head, prefix("Order-1"), "head", "seq_nr"),
          "the emulator did apply the update");
      assertThrows(OptimisticLockException.class, () -> store.persistEvent(event(1)));
      assertEquals(1, store.getEventsByIdSinceSeqNr(AID, 1).size());
    }
  }

  @Test
  void theLibraryNeverUsesMutateRowForItsOwnWrites() throws Exception {
    BigtableEmulator.Tables t = noneStore();
    try (Rig rig = new Rig()) {
      EventStore<String, String> store = BigtableEventStore.create(rig.open(t), config());
      store.persistEvent(event(1));
      store.persistEvent(event(2));
      store.persistEvent(event(3));

      assertTrue(rig.proxy.callsTo("google.bigtable.v2.Bigtable/MutateRow").isEmpty());
      assertTrue(rig.proxy.callsTo("google.bigtable.v2.Bigtable/MutateRows").isEmpty());
    }
  }
}
