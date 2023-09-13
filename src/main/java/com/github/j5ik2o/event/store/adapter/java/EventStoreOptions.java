package com.github.j5ik2o.event.store.adapter.java;

import java.time.Duration;
import javax.annotation.Nonnull;

public interface EventStoreOptions<
    This extends EventStoreOptions<This, AID, A, E>,
    AID extends AggregateId,
    A extends Aggregate<AID>,
    E extends Event<AID>> {
  @Nonnull
  This withKeepSnapshotCount(long keepSnapshotCount);

  @Nonnull
  This withDeleteTtl(@Nonnull Duration deleteTtl);

  @Nonnull
  This withKeyResolver(@Nonnull KeyResolver<AID> keyResolver);

  @Nonnull
  This withEventSerializer(@Nonnull EventSerializer<AID, E> eventSerializer);

  @Nonnull
  This withSnapshotSerializer(@Nonnull SnapshotSerializer<AID, A> snapshotSerializer);
}
