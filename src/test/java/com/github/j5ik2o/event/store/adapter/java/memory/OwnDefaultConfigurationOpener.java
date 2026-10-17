package com.github.j5ik2o.event.store.adapter.java.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.EventStore;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreConfig;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotEnvelope;
import com.github.j5ik2o.event.store.adapter.java.core.SnapshotReadResult;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 共有された 2 つ目のストアに、保存先の設定を使わせず、自分の既定の設定（保持なし）を持たせてしまう偽の開き方。
 *
 * <p>記録の表は共有先と同期するので、記録と排他制御の共有だけを確かめる試験は通ってしまう。保持なしでは履歴を書かないため、2
 * つ目のストアからの追記では履歴が整理されず、共有先の履歴の観測が期待と食い違って失敗になる。
 */
public final class OwnDefaultConfigurationOpener
    implements ConformanceMemoryOperations.StoreOpener {
  private final Set<MemoryStorage> opened = Collections.newSetFromMap(new IdentityHashMap<>());

  @Override
  public EventStore<JsonNode, JsonNode> open(
      MemoryStorage storage, EventStoreConfig<JsonNode, JsonNode> config) {
    if (opened.add(storage)) {
      return MemoryEventStore.create(storage, config);
    }
    MemoryStorage own = MemoryStorage.create(RetentionPolicy.none(), storage.hooks);
    return new RecordSharingStore(storage, own, MemoryEventStore.create(own, config));
  }

  /** 操作の前後で、自分の記録の表を共有先と同じ内容に保つ。 */
  private static final class RecordSharingStore implements EventStore<JsonNode, JsonNode> {
    private final MemoryStorage shared;
    private final MemoryStorage own;
    private final EventStore<JsonNode, JsonNode> delegate;

    RecordSharingStore(
        MemoryStorage shared, MemoryStorage own, EventStore<JsonNode, JsonNode> delegate) {
      this.shared = shared;
      this.own = own;
      this.delegate = delegate;
    }

    private void pull() {
      own.aggregates.clear();
      own.aggregates.putAll(shared.aggregates);
    }

    private void push() {
      shared.aggregates.putAll(own.aggregates);
    }

    @Override
    public void persistEvent(EventEnvelope<JsonNode> event) {
      pull();
      try {
        delegate.persistEvent(event);
      } finally {
        push();
      }
    }

    @Override
    public void persistEventAndSnapshot(
        EventEnvelope<JsonNode> event, SnapshotEnvelope<JsonNode> snapshot) {
      pull();
      try {
        delegate.persistEventAndSnapshot(event, snapshot);
      } finally {
        push();
      }
    }

    @Override
    public Optional<SnapshotReadResult<JsonNode>> getLatestSnapshotById(AggregateId aggregateId) {
      pull();
      return delegate.getLatestSnapshotById(aggregateId);
    }

    @Override
    public List<EventEnvelope<JsonNode>> getEventsByIdSinceSeqNr(
        AggregateId aggregateId, long seqNr) {
      pull();
      return delegate.getEventsByIdSinceSeqNr(aggregateId, seqNr);
    }
  }
}
