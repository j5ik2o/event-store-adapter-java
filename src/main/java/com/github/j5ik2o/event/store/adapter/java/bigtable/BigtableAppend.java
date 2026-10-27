package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.google.cloud.bigtable.data.v2.models.Mutation;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.protobuf.ByteString;
import java.util.Optional;
import java.util.OptionalLong;
import javax.annotation.Nullable;

/**
 * Appends by BT-7: read the head, write the previous event out, conditionally update the head (the
 * commit point), then write the new tail out. / BT-7 の追記。head を読み、直前のイベントを書き出し、head
 * を条件付きで更新し（確定点）、新しい末尾を書き出す。
 */
final class BigtableAppend {
  private BigtableAppend() {}

  static final class StoredSnapshot {
    final long seqNr;
    final String manifest;
    final byte[] payload;

    StoredSnapshot(long seqNr, String manifest, byte[] payload) {
      this.seqNr = seqNr;
      this.manifest = manifest;
      this.payload = payload;
    }
  }

  /**
   * Appends and returns the failure of the tail write-out, if any. A tail failure never changes the
   * committed append.
   */
  static Optional<RuntimeException> append(
      BigtableStorage storage,
      EventEnvelope<?> event,
      byte[] payload,
      @Nullable StoredSnapshot snapshot) {
    AggregateId id = event.aggregateId();
    String aid = id.asString();
    long seqNr = event.seqNr();
    int limit = storage.config.envelopeLimitBytes();
    // BT-16: check the size before anything is read or written.
    checkLimit(BigtableEnvelope.eventLength(aid, event.manifest(), payload.length), limit, seqNr);
    if (snapshot != null) {
      checkLimit(
          BigtableEnvelope.snapshotLength(snapshot.manifest, snapshot.payload.length),
          limit,
          seqNr);
    }
    byte[] eventBytes =
        BigtableEnvelope.encodeEvent(
            aid,
            seqNr,
            BigtableEnvelope.epochNanoseconds(event.occurredAt()),
            event.manifest(),
            payload);
    byte[] snapshotBytes =
        snapshot == null
            ? null
            : BigtableEnvelope.encodeSnapshot(snapshot.seqNr, snapshot.manifest, snapshot.payload);
    boolean history = storage.config.retentionPolicy().keepCount().isPresent();

    ByteString prefix = storage.prefix(aid);
    BigtableRead.HeadState head = BigtableRead.readHead(storage, prefix, aid);
    long current = head == null ? 0 : head.seqNr;
    if (seqNr <= current) {
      throw new OptimisticLockException(id, seqNr, OptionalLong.of(current), null);
    }
    if (seqNr != current + 1) {
      throw new ContractViolationException(
          "W-8", OptionalLong.of(seqNr), "event sequence must immediately follow the head");
    }
    if (head != null) {
      storage.hooks.journalWritePrevious(id, head.seqNr);
      writeJournalRow(
          storage,
          prefix,
          head.seqNr,
          head.lastEventBytes,
          head.hasSnapshot && history ? head.currentSnapshotBytes : null,
          head.hasSnapshot);
    }
    storage.hooks.beforeCommit(id, seqNr);
    commitHead(storage, id, prefix, seqNr, head, eventBytes, snapshotBytes);
    storage.hooks.afterCommit(id, seqNr);
    try {
      storage.hooks.journalWriteTail(id, seqNr);
      writeJournalRow(
          storage,
          prefix,
          seqNr,
          eventBytes,
          history ? snapshotBytes : null,
          snapshotBytes != null);
      return Optional.empty();
    } catch (RuntimeException failure) {
      return Optional.of(failure);
    }
  }

  private static void checkLimit(long length, int limit, long seqNr) {
    if (length > limit) {
      throw new ContractViolationException(
          "BT-16",
          OptionalLong.of(seqNr),
          "serialized envelope is " + length + " bytes, over the limit of " + limit);
    }
  }

  /**
   * Creates a journal row (event, flag and history) in one conditional update, only when {@code
   * event:envelope} is absent (BT-5). An existing row is never overwritten; it is verified. A
   * retired row is accepted as it is, so a delayed writer cannot revive deleted history.
   */
  static void writeJournalRow(
      BigtableStorage storage,
      ByteString prefix,
      long seqNr,
      byte[] event,
      @Nullable byte[] historyEnvelope,
      boolean hasSnapshot) {
    String table = storage.config.journalTableId();
    ByteString key = BigtableKey.journalRowKey(prefix, seqNr);
    ByteString eventValue = ByteString.copyFrom(event);
    Mutation creation = Mutation.create();
    BigtableRows.setCell(creation, BigtableRows.EVENT, BigtableRows.ENVELOPE, eventValue);
    BigtableRows.setCell(
        creation, BigtableRows.EVENT, BigtableRows.HAS_SNAPSHOT, BigtableRows.flag(hasSnapshot));
    ByteString historyValue = historyEnvelope == null ? null : ByteString.copyFrom(historyEnvelope);
    if (historyValue != null) {
      BigtableRows.setCell(creation, BigtableRows.HISTORY, BigtableRows.STATE, BigtableRows.ACTIVE);
      BigtableRows.setCell(creation, BigtableRows.HISTORY, BigtableRows.ENVELOPE, historyValue);
    }
    boolean existed =
        BigtableRows.checkAndMutate(
            storage.data,
            table,
            key,
            BigtableRows.latestCellExists(BigtableRows.EVENT, BigtableRows.ENVELOPE),
            null,
            creation);
    if (!existed) {
      return;
    }
    Row row = BigtableRows.readRow(storage.data, table, key, BigtableRows.latestCell());
    if (row == null) {
      throw BigtableRead.invalid("an existing journal row disappeared while it was verified");
    }
    if (!eventValue.equals(BigtableRows.value(row, BigtableRows.EVENT, BigtableRows.ENVELOPE))) {
      throw BigtableRead.invalid("an existing journal row holds a different event envelope");
    }
    if (!BigtableRows.flag(hasSnapshot)
        .equals(BigtableRows.value(row, BigtableRows.EVENT, BigtableRows.HAS_SNAPSHOT))) {
      throw BigtableRead.invalid("an existing journal row holds a different has_snapshot flag");
    }
    if (historyValue == null) {
      return;
    }
    ByteString state = BigtableRows.value(row, BigtableRows.HISTORY, BigtableRows.STATE);
    if (state == null || state.equals(BigtableRows.RETIRED)) {
      // Written before the retention count was set, or already deleted: never recreate it.
      return;
    }
    if (!state.equals(BigtableRows.ACTIVE)) {
      throw BigtableRead.invalid("an existing journal row holds an unknown history state");
    }
    if (!historyValue.equals(
        BigtableRows.value(row, BigtableRows.HISTORY, BigtableRows.ENVELOPE))) {
      throw BigtableRead.invalid(
          "an existing journal row holds a missing or different history envelope");
    }
  }

  /**
   * The conditional update of the head. Its success is the commit point (H-1). A mismatch means
   * another writer committed after the head that was read, so it is an optimistic lock without the
   * head number. A response that never arrives is a storage error and is not resent.
   */
  private static void commitHead(
      BigtableStorage storage,
      AggregateId id,
      ByteString prefix,
      long seqNr,
      @Nullable BigtableRead.HeadState previous,
      byte[] eventBytes,
      @Nullable byte[] snapshotBytes) {
    String table = storage.config.headTableId();
    ByteString seqValue = ByteString.copyFromUtf8(BigtableRead.formatHeadSeqNr(seqNr));
    ByteString eventValue = ByteString.copyFrom(eventBytes);
    ByteString snapshotValue = snapshotBytes == null ? null : ByteString.copyFrom(snapshotBytes);
    Mutation mutation = Mutation.create();
    if (previous == null) {
      BigtableRows.setCell(mutation, BigtableRows.HEAD, BigtableRows.SEQ_NR, seqValue);
      BigtableRows.setCell(mutation, BigtableRows.HEAD, BigtableRows.LAST_EVENT, eventValue);
      BigtableRows.setCell(
          mutation,
          BigtableRows.HEAD,
          BigtableRows.HAS_SNAPSHOT,
          BigtableRows.flag(snapshotValue != null));
      if (snapshotValue != null) {
        BigtableRows.setCell(mutation, BigtableRows.SNAPSHOT, BigtableRows.CURRENT, snapshotValue);
      }
    } else {
      BigtableRows.replaceCell(mutation, BigtableRows.HEAD, BigtableRows.SEQ_NR, seqValue);
      BigtableRows.replaceCell(mutation, BigtableRows.HEAD, BigtableRows.LAST_EVENT, eventValue);
      BigtableRows.replaceCell(
          mutation,
          BigtableRows.HEAD,
          BigtableRows.HAS_SNAPSHOT,
          BigtableRows.flag(snapshotValue != null));
      if (snapshotValue != null) {
        BigtableRows.replaceCell(
            mutation, BigtableRows.SNAPSHOT, BigtableRows.CURRENT, snapshotValue);
      }
    }
    boolean succeeded;
    if (previous == null) {
      // A new head is initialized on the side where seq_nr does not exist yet.
      succeeded =
          !BigtableRows.checkAndMutate(
              storage.data,
              table,
              prefix,
              BigtableRows.latestCellExists(BigtableRows.HEAD, BigtableRows.SEQ_NR),
              null,
              mutation);
    } else {
      // An update applies only where the latest seq_nr cell is the head that was read.
      succeeded =
          BigtableRows.checkAndMutate(
              storage.data,
              table,
              prefix,
              BigtableRows.latestCellEquals(
                  BigtableRows.HEAD,
                  BigtableRows.SEQ_NR,
                  ByteString.copyFromUtf8(BigtableRead.formatHeadSeqNr(previous.seqNr))),
              mutation,
              null);
    }
    if (!succeeded) {
      throw new OptimisticLockException(id, seqNr, OptionalLong.empty(), null);
    }
  }
}
