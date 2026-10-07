package com.github.j5ik2o.event.store.adapter.java.memory;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/** 明示的に共有する、独立した JVM ヒープ内の保存先。 */
public final class MemoryStorage {
  final ReentrantLock lock = new ReentrantLock();
  final RetentionPolicy policy;
  final MemoryStorageHooks hooks;
  final Map<String, AggregateState> aggregates = new HashMap<>();

  private MemoryStorage(RetentionPolicy policy, MemoryStorageHooks hooks) {
    this.policy = Objects.requireNonNull(policy, "policy");
    this.hooks = Objects.requireNonNull(hooks, "hooks");
    if (policy.mode().orElse(null) == RetentionMode.TTL) {
      throw new ConfigurationException("memory storage does not support TTL");
    }
  }

  public static MemoryStorage create() {
    return create(RetentionPolicy.none());
  }

  public static MemoryStorage create(RetentionPolicy policy) {
    return create(policy, MemoryStorageHooks.NONE);
  }

  static MemoryStorage create(RetentionPolicy policy, MemoryStorageHooks hooks) {
    return new MemoryStorage(policy, hooks);
  }

  List<Long> historySeqNrs(AggregateId aggregateId) {
    lock.lock();
    try {
      AggregateState state = aggregates.get(aggregateId.asString());
      return state == null ? new ArrayList<>() : new ArrayList<>(state.history.descendingKeySet());
    } finally {
      lock.unlock();
    }
  }

  static final class AggregateState {
    final TreeMap<Long, StoredEvent> journal = new TreeMap<>();
    StoredSnapshot snapshot;
    final TreeMap<Long, StoredSnapshot> history = new TreeMap<>();
  }

  static final class StoredEvent {
    final AggregateId aggregateId;
    final long seqNr;
    final Instant occurredAt;
    final String manifest;
    final byte[] payload;

    StoredEvent(
        AggregateId aggregateId, long seqNr, Instant occurredAt, String manifest, byte[] payload) {
      this.aggregateId = aggregateId;
      this.seqNr = seqNr;
      this.occurredAt = occurredAt;
      this.manifest = manifest;
      this.payload = payload.clone();
    }

    StoredEvent copy() {
      return new StoredEvent(aggregateId, seqNr, occurredAt, manifest, payload);
    }
  }

  static final class StoredSnapshot {
    final long seqNr;
    final String manifest;
    final byte[] payload;

    StoredSnapshot(long seqNr, String manifest, byte[] payload) {
      this.seqNr = seqNr;
      this.manifest = manifest;
      this.payload = payload.clone();
    }

    StoredSnapshot copy() {
      return new StoredSnapshot(seqNr, manifest, payload);
    }
  }
}
