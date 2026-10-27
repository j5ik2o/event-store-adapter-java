package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.google.cloud.ByteArray;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.Statement;
import com.google.cloud.spanner.TransactionContext;
import java.time.Instant;
import java.util.OptionalLong;
import javax.annotation.Nullable;

/** Appends in one SERIALIZABLE read-write transaction: head check, journal, head, snapshots. */
final class SpannerAppend {
  private SpannerAppend() {}

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

  static void append(
      SpannerStorage storage,
      EventEnvelope<?> event,
      byte[] payload,
      @Nullable StoredSnapshot snapshot) {
    AggregateId id = event.aggregateId();
    SpannerKey key = SpannerKey.of(id);
    ByteArray hashPrefix = ByteArray.copyFrom(key.hashPrefix());
    SpannerTableNames names = storage.config.tableNames;
    boolean history = storage.config.retentionPolicy().keepCount().isPresent();
    long seqNr = event.seqNr();
    long nanos = epochNanoseconds(event.occurredAt());
    ByteArray eventPayload = ByteArray.copyFrom(payload);
    SpannerTransactions.runReadWrite(
        storage,
        id,
        "failed to append to spanner",
        tx -> {
          boolean headExists = false;
          long head = 0;
          try (ResultSet rs =
              tx.executeQuery(
                  Statement.newBuilder(
                          "SELECT seq_nr FROM "
                              + names.head
                              + " WHERE hash_prefix = @hp AND aid = @aid")
                      .bind("hp")
                      .to(hashPrefix)
                      .bind("aid")
                      .to(key.aid())
                      .build())) {
            if (rs.next()) {
              headExists = true;
              head = rs.getLong(0);
            }
          }
          if (seqNr <= head) {
            throw new OptimisticLockException(id, seqNr, OptionalLong.of(head), null);
          }
          if (seqNr != head + 1) {
            throw new ContractViolationException(
                "W-8", OptionalLong.of(seqNr), "event sequence must immediately follow the head");
          }
          OptionalLong knownHead = headExists ? OptionalLong.of(head) : OptionalLong.empty();
          insertOrConflict(
              tx,
              Statement.newBuilder(
                      "INSERT INTO "
                          + names.journal
                          + " (hash_prefix, aid, seq_nr, occurred_at_ns, manifest, payload)"
                          + " VALUES (@hp, @aid, @seq, @nanos, @manifest, @payload)")
                  .bind("hp")
                  .to(hashPrefix)
                  .bind("aid")
                  .to(key.aid())
                  .bind("seq")
                  .to(seqNr)
                  .bind("nanos")
                  .to(nanos)
                  .bind("manifest")
                  .to(event.manifest())
                  .bind("payload")
                  .to(eventPayload)
                  .build(),
              id,
              seqNr,
              knownHead);
          if (headExists) {
            tx.executeUpdate(
                Statement.newBuilder(
                        "UPDATE "
                            + names.head
                            + " SET seq_nr = @seq, event_occurred_at_ns = @nanos,"
                            + " event_manifest = @manifest, event_payload = @payload"
                            + " WHERE hash_prefix = @hp AND aid = @aid")
                    .bind("seq")
                    .to(seqNr)
                    .bind("nanos")
                    .to(nanos)
                    .bind("manifest")
                    .to(event.manifest())
                    .bind("payload")
                    .to(eventPayload)
                    .bind("hp")
                    .to(hashPrefix)
                    .bind("aid")
                    .to(key.aid())
                    .build());
          } else {
            insertOrConflict(
                tx,
                Statement.newBuilder(
                        "INSERT INTO "
                            + names.head
                            + " (hash_prefix, aid, type_name, seq_nr, event_occurred_at_ns,"
                            + " event_manifest, event_payload)"
                            + " VALUES (@hp, @aid, @type, @seq, @nanos, @manifest, @payload)")
                    .bind("hp")
                    .to(hashPrefix)
                    .bind("aid")
                    .to(key.aid())
                    .bind("type")
                    .to(id.typeName())
                    .bind("seq")
                    .to(seqNr)
                    .bind("nanos")
                    .to(nanos)
                    .bind("manifest")
                    .to(event.manifest())
                    .bind("payload")
                    .to(eventPayload)
                    .build(),
                id,
                seqNr,
                knownHead);
          }
          if (snapshot != null) {
            ByteArray snapshotPayload = ByteArray.copyFrom(snapshot.payload);
            tx.executeUpdate(
                snapshotStatement(
                    "INSERT OR UPDATE INTO " + names.snapshotCurrent,
                    hashPrefix,
                    key.aid(),
                    snapshot,
                    snapshotPayload,
                    false));
            if (history) {
              tx.executeUpdate(
                  snapshotStatement(
                      "INSERT INTO " + names.snapshotHistory,
                      hashPrefix,
                      key.aid(),
                      snapshot,
                      snapshotPayload,
                      true));
            }
          }
          return null;
        });
  }

  private static Statement snapshotStatement(
      String target,
      ByteArray hashPrefix,
      String aid,
      StoredSnapshot snapshot,
      ByteArray payload,
      boolean withExpiredAt) {
    return Statement.newBuilder(
            target
                + " (hash_prefix, aid, seq_nr, manifest, payload"
                + (withExpiredAt ? ", expired_at" : "")
                + ") VALUES (@hp, @aid, @seq, @manifest, @payload"
                + (withExpiredAt ? ", NULL" : "")
                + ")")
        .bind("hp")
        .to(hashPrefix)
        .bind("aid")
        .to(aid)
        .bind("seq")
        .to(snapshot.seqNr)
        .bind("manifest")
        .to(snapshot.manifest)
        .bind("payload")
        .to(payload)
        .build();
  }

  /** Only a primary key collision on the journal or the head is an optimistic lock conflict. */
  private static void insertOrConflict(
      TransactionContext tx, Statement statement, AggregateId id, long seqNr, OptionalLong head) {
    try {
      tx.executeUpdate(statement);
    } catch (SpannerException failure) {
      if (failure.getErrorCode() == ErrorCode.ALREADY_EXISTS) {
        throw new OptimisticLockException(id, seqNr, head, failure);
      }
      throw failure;
    }
  }

  static long epochNanoseconds(Instant instant) {
    long seconds = instant.getEpochSecond();
    return seconds >= 0
        ? Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), instant.getNano())
        : Math.addExact(
            Math.multiplyExact(seconds + 1, 1_000_000_000L), instant.getNano() - 1_000_000_000L);
  }
}
