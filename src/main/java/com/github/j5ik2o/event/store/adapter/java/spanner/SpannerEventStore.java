package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.AsyncEventStore;
import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreInputValidation;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionFailure;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerAppend.StoredSnapshot;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerRead.HeadAndSnapshot;
import com.github.j5ik2o.event.store.adapter.java.spanner.SpannerRead.StoredEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Spanner storage entry points, synchronous and asynchronous. / Spanner 保存先の同期・非同期の入口。 */
public final class SpannerEventStore {
  private static final Logger LOG = LoggerFactory.getLogger(SpannerEventStore.class);

  private SpannerEventStore() {}

  /**
   * Creates a synchronous event store. / 同期のイベントストアを作ります。
   *
   * @param storage opened storage / 開いた保存先
   * @param config event store settings / イベントストアの設定
   * @param <P> event payload type / イベントのペイロード型
   * @param <A> aggregate type / 集約の型
   * @return event store / イベントストア
   */
  @Nonnull
  public static <P, A> EventStore<P, A> create(
      SpannerStorage storage, EventStoreConfig<P, A> config) {
    return new Sync<>(storage, config);
  }

  /**
   * Creates an asynchronous event store. It runs on the calling thread and returns a completed
   * future, like the memory store. / 非同期のイベントストアを作ります。メモリ保存先と同じく呼び出し スレッドで実行し、完了済みの Future を返します。
   *
   * @param storage opened storage / 開いた保存先
   * @param config event store settings / イベントストアの設定
   * @param <P> event payload type / イベントのペイロード型
   * @param <A> aggregate type / 集約の型
   * @return async event store / 非同期イベントストア
   */
  @Nonnull
  public static <P, A> AsyncEventStore<P, A> createAsync(
      SpannerStorage storage, EventStoreConfig<P, A> config) {
    return new Async<>(create(storage, config));
  }

  private static final class Sync<P, A> implements EventStore<P, A> {
    private final SpannerStorage storage;
    private final EventStoreConfig<P, A> config;

    Sync(SpannerStorage storage, EventStoreConfig<P, A> config) {
      if (storage == null) {
        throw new ConfigurationException("spanner storage is required");
      }
      if (config == null) {
        throw new ConfigurationException("event store config is required");
      }
      this.storage = storage;
      this.config = config;
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
      byte[] payload = serialize(config.payloadSerializer(), event.payload());
      StoredSnapshot stored =
          snapshot == null
              ? null
              : new StoredSnapshot(
                  snapshot.seqNr(),
                  snapshot.manifest(),
                  serialize(config.snapshotSerializer(), snapshot.aggregate()));
      SpannerAppend.append(storage, event, payload, stored);
      if (stored != null && storage.config.retentionPolicy().keepCount().isPresent()) {
        Optional<RetentionFailure> failure =
            SpannerRetention.retain(storage, event.aggregateId(), stored.seqNr);
        failure.ifPresent(this::notifyFailure);
      }
    }

    private void notifyFailure(RetentionFailure failure) {
      try {
        LOG.warn(
            "spanner retention failed: aid={}", failure.aggregateId().asString(), failure.cause());
      } catch (Throwable loggingFailure) {
        // ログ経路の失敗でも、確定済みの追記の成功を維持する。
      }
      try {
        config
            .retentionFailureListener()
            .ifPresent(listener -> listener.onRetentionFailure(failure));
      } catch (Throwable listenerFailure) {
        // 通知先の失敗を、確定済みの追記から隔離する。
      }
    }

    public Optional<SnapshotReadResult<A>> getLatestSnapshotById(AggregateId id) {
      EventStoreInputValidation.checkRead(id, 0);
      Optional<HeadAndSnapshot> read = SpannerRead.readSnapshot(storage, id);
      if (!read.isPresent()) {
        return Optional.empty();
      }
      HeadAndSnapshot value = read.get();
      if (value.snapshot == null) {
        return Optional.of(SnapshotReadResult.withoutSnapshot(value.head));
      }
      A aggregate = deserialize(config.snapshotSerializer(), value.snapshot.payload);
      SnapshotEnvelope<A> envelope =
          SnapshotEnvelope.<A>builder()
              .seqNr(value.snapshot.seqNr)
              .manifest(value.snapshot.manifest)
              .aggregate(aggregate)
              .build();
      return Optional.of(SnapshotReadResult.of(envelope, value.head));
    }

    public List<EventEnvelope<P>> getEventsByIdSinceSeqNr(AggregateId id, long seqNr) {
      EventStoreInputValidation.checkRead(id, seqNr);
      List<StoredEvent> stored = SpannerRead.readEvents(storage, id, seqNr);
      List<EventEnvelope<P>> result = new ArrayList<>();
      for (StoredEvent event : stored) {
        result.add(
            EventEnvelope.<P>builder()
                .aggregateId(id)
                .seqNr(event.seqNr)
                .occurredAt(Instant.ofEpochSecond(0, event.occurredAtNanos))
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
      T value = serializer.deserialize(bytes);
      if (value == null) {
        throw new SerializationException("failed to deserialize payload: result is null");
      }
      return value;
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
