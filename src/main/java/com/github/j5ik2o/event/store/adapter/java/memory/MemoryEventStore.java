package com.github.j5ik2o.event.store.adapter.java.memory;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreInputValidation;
import com.github.j5ik2o.event.store.adapter.java.core.OptimisticLockException;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage.AggregateState;
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage.StoredEvent;
import com.github.j5ik2o.event.store.adapter.java.memory.MemoryStorage.StoredSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** メモリ保存先の同期・非同期入口。 */
public final class MemoryEventStore {
  private static final Logger LOG = LoggerFactory.getLogger(MemoryEventStore.class);

  private MemoryEventStore() {}

  public static <P, A> EventStore<P, A> create(
      MemoryStorage storage, EventStoreConfig<P, A> config) {
    return new Sync<>(storage, config);
  }

  public static <P, A> AsyncEventStore<P, A> createAsync(
      MemoryStorage storage, EventStoreConfig<P, A> config) {
    return new Async<>(create(storage, config));
  }

  private static final class Sync<P, A> implements EventStore<P, A> {
    private final MemoryStorage storage;
    private final EventStoreConfig<P, A> config;

    Sync(MemoryStorage storage, EventStoreConfig<P, A> config) {
      this.storage = Objects.requireNonNull(storage, "storage");
      this.config = Objects.requireNonNull(config, "config");
    }

    public void persistEvent(EventEnvelope<P> event) {
      EventStoreInputValidation.checkEvent(event);
      append(event, null);
    }

    public void persistEventAndSnapshot(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      EventStoreInputValidation.checkEventAndSnapshot(event, snapshot);
      append(event, snapshot);
    }

    private void append(EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      StoredEvent storedEvent =
          new StoredEvent(
              event.aggregateId(),
              event.seqNr(),
              event.occurredAt(),
              event.manifest(),
              serialize(config.payloadSerializer(), event.payload()));
      StoredSnapshot storedSnapshot =
          snapshot == null
              ? null
              : new StoredSnapshot(
                  snapshot.seqNr(),
                  snapshot.manifest(),
                  serialize(config.snapshotSerializer(), snapshot.aggregate()));
      RetentionFailure retentionFailure = null;
      storage.lock.lock();
      try {
        AggregateState previous = storage.aggregates.get(event.aggregateId().asString());
        long head = previous == null ? 0 : previous.journal.lastKey();
        if (event.seqNr() <= head) {
          throw new OptimisticLockException(
              event.aggregateId(), event.seqNr(), OptionalLong.of(head), null);
        }
        if (event.seqNr() != head + 1) {
          throw new ContractViolationException(
              "W-8",
              OptionalLong.of(event.seqNr()),
              "event sequence must immediately follow the head");
        }
        AggregateState state = previous == null ? new AggregateState() : previous;
        storage.hooks.beforeCommit(event.aggregateId());
        // 検査・直列化・確定前フックの成功後、同じロック内で必要な状態だけを更新する。
        state.journal.put(storedEvent.seqNr, storedEvent);
        if (storedSnapshot != null) {
          state.snapshot = storedSnapshot;
          if (storage.policy.keepCount().isPresent()) {
            state.history.put(storedSnapshot.seqNr, storedSnapshot);
          }
        }
        if (previous == null) {
          storage.aggregates.put(event.aggregateId().asString(), state);
        }
        if (storage.policy.keepCount().isPresent()) {
          try {
            retain(event.aggregateId(), state, storedSnapshot);
          } catch (Exception failure) {
            retentionFailure =
                new RetentionFailure(event.aggregateId(), RetentionMode.DELETE, failure);
          }
        }
      } finally {
        storage.lock.unlock();
      }
      if (retentionFailure != null) {
        notifyFailure(retentionFailure);
      }
    }

    private void retain(AggregateId id, AggregateState state, StoredSnapshot justWritten) {
      List<Long> actual = new ArrayList<>(state.history.descendingKeySet());
      TreeSet<Long> candidates = new TreeSet<>(Comparator.reverseOrder());
      candidates.addAll(storage.hooks.readHistory(id, actual));
      if (justWritten != null) {
        candidates.add(justWritten.seqNr);
      }
      List<Long> obsolete = new ArrayList<>();
      int index = 0;
      for (Long seqNr : candidates) {
        if (index++ >= storage.policy.keepCount().getAsInt()) {
          obsolete.add(seqNr);
        }
      }
      obsolete.sort(Comparator.naturalOrder());
      if (!obsolete.isEmpty()) {
        storage.hooks.beforeRetentionDelete(id, new ArrayList<>(obsolete));
        for (Long seqNr : obsolete) {
          state.history.remove(seqNr);
        }
      }
    }

    private void notifyFailure(RetentionFailure failure) {
      try {
        LOG.warn(
            "memory retention failed: aid={}", failure.aggregateId().asString(), failure.cause());
      } catch (Exception loggingFailure) {
        // ログ経路の失敗でも、確定済みの追記の成功を維持する。
      }
      try {
        config
            .retentionFailureListener()
            .ifPresent(listener -> listener.onRetentionFailure(failure));
      } catch (Exception listenerFailure) {
        // 通知先の失敗を、確定済みの追記から隔離する。
      }
    }

    public Optional<SnapshotReadResult<A>> getLatestSnapshotById(AggregateId id) {
      EventStoreInputValidation.checkRead(id, 0);
      long head;
      StoredSnapshot snapshot;
      storage.lock.lock();
      try {
        storage.hooks.beforeReadSnapshot(id);
        AggregateState state = storage.aggregates.get(id.asString());
        if (state == null) {
          return Optional.empty();
        }
        head = state.journal.lastKey();
        snapshot = state.snapshot == null ? null : state.snapshot.copy();
      } finally {
        storage.lock.unlock();
      }
      if (snapshot == null) {
        return Optional.of(SnapshotReadResult.withoutSnapshot(head));
      }
      A aggregate = deserialize(config.snapshotSerializer(), snapshot.payload);
      SnapshotEnvelope<A> envelope =
          SnapshotEnvelope.<A>builder()
              .seqNr(snapshot.seqNr)
              .manifest(snapshot.manifest)
              .aggregate(aggregate)
              .build();
      return Optional.of(SnapshotReadResult.of(envelope, head));
    }

    public List<EventEnvelope<P>> getEventsByIdSinceSeqNr(AggregateId id, long seqNr) {
      EventStoreInputValidation.checkRead(id, seqNr);
      List<StoredEvent> captured = new ArrayList<>();
      storage.lock.lock();
      try {
        storage.hooks.beforeReadEvents(id);
        AggregateState state = storage.aggregates.get(id.asString());
        if (state != null) {
          for (StoredEvent event : state.journal.tailMap(seqNr, true).values()) {
            captured.add(event.copy());
          }
        }
      } finally {
        storage.lock.unlock();
      }
      List<EventEnvelope<P>> result = new ArrayList<>();
      for (StoredEvent event : captured) {
        result.add(
            EventEnvelope.<P>builder()
                .aggregateId(event.aggregateId)
                .seqNr(event.seqNr)
                .occurredAt(event.occurredAt)
                .manifest(event.manifest)
                .payload(deserialize(config.payloadSerializer(), event.payload))
                .build());
      }
      return result;
    }
  }

  private static <T> byte[] serialize(PayloadSerializer<T> serializer, T value) {
    try {
      return serializer.serialize(value).clone();
    } catch (SerializationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new SerializationException("failed to serialize payload", failure);
    }
  }

  private static <T> T deserialize(PayloadSerializer<T> serializer, byte[] bytes) {
    try {
      return serializer.deserialize(bytes);
    } catch (SerializationException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new SerializationException("failed to deserialize payload", failure);
    }
  }

  private static final class Async<P, A> implements AsyncEventStore<P, A> {
    private final EventStore<P, A> sync;

    Async(EventStore<P, A> sync) {
      this.sync = sync;
    }

    public CompletableFuture<Void> persistEvent(EventEnvelope<P> event) {
      return complete(
          () -> {
            sync.persistEvent(event);
            return null;
          });
    }

    public CompletableFuture<Void> persistEventAndSnapshot(
        EventEnvelope<P> event, SnapshotEnvelope<A> snapshot) {
      return complete(
          () -> {
            sync.persistEventAndSnapshot(event, snapshot);
            return null;
          });
    }

    public CompletableFuture<Optional<SnapshotReadResult<A>>> getLatestSnapshotById(
        AggregateId id) {
      return complete(() -> sync.getLatestSnapshotById(id));
    }

    public CompletableFuture<List<EventEnvelope<P>>> getEventsByIdSinceSeqNr(
        AggregateId id, long seqNr) {
      return complete(() -> sync.getEventsByIdSinceSeqNr(id, seqNr));
    }

    private <T> CompletableFuture<T> complete(Supplier<T> action) {
      CompletableFuture<T> result = new CompletableFuture<>();
      try {
        result.complete(action.get());
      } catch (Exception failure) {
        result.completeExceptionally(failure);
      }
      return result;
    }
  }
}
