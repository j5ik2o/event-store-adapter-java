package com.github.j5ik2o.event.store.adapter.java.examples;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** 名前をpayloadと集約状態に使う、公開APIだけで動く小さな利用例。 */
public final class UserAccountExample {
  private UserAccountExample() {}

  public static EventStoreConfig<String, String> config() {
    return EventStoreConfig.<String, String>builder()
        .payloadSerializer(JsonPayloadSerializer.of(String.class))
        .snapshotSerializer(JsonPayloadSerializer.of(String.class))
        .build();
  }

  public static EventEnvelope<String> event(AggregateId id, long seqNr, String name) {
    return EventEnvelope.<String>builder()
        .aggregateId(id)
        .seqNr(seqNr)
        .occurredAt(Instant.now())
        .manifest("user-name")
        .payload(name)
        .build();
  }

  public static SnapshotEnvelope<String> snapshot(long seqNr, String name) {
    return SnapshotEnvelope.<String>builder()
        .seqNr(seqNr)
        .manifest("user-state")
        .aggregate(name)
        .build();
  }

  public static Optional<String> restore(EventStore<String, String> store, AggregateId id) {
    return store
        .getLatestSnapshotById(id)
        .map(
            result -> {
              long start = result.snapshot().map(snapshot -> snapshot.seqNr() + 1).orElse(1L);
              return replay(result, store.getEventsByIdSinceSeqNr(id, start));
            });
  }

  public static CompletableFuture<Optional<String>> restoreAsync(
      AsyncEventStore<String, String> store, AggregateId id) {
    return store
        .getLatestSnapshotById(id)
        .thenCompose(
            result -> {
              if (result.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
              }
              long start = result.get().snapshot().map(snapshot -> snapshot.seqNr() + 1).orElse(1L);
              return store
                  .getEventsByIdSinceSeqNr(id, start)
                  .thenApply(events -> Optional.of(replay(result.get(), events)));
            });
  }

  private static String replay(
      SnapshotReadResult<String> result, List<EventEnvelope<String>> events) {
    Optional<String> name = result.snapshot().map(SnapshotEnvelope::aggregate);
    for (EventEnvelope<String> event : events) {
      name = Optional.of(event.payload());
    }
    return name.orElseThrow(
        () -> new IllegalStateException("An existing account needs a snapshot or events"));
  }
}
