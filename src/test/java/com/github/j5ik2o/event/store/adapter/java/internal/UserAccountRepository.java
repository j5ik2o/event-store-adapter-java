package com.github.j5ik2o.event.store.adapter.java.internal;

import com.github.j5ik2o.event.store.adapter.java.*;
import java.util.Optional;
import javax.annotation.Nonnull;

public final class UserAccountRepository {
  @Nonnull private final EventStore<UserAccountId, UserAccount, UserAccountEvent> eventStore;

  public UserAccountRepository(
      @Nonnull EventStore<UserAccountId, UserAccount, UserAccountEvent> eventStore) {
    this.eventStore = eventStore;
  }

  public void store(@Nonnull UserAccountEvent event, long version) {
    try {
      eventStore.persistEvent(event, version);
    } catch (EventStoreWriteException | SerializationException e) {
      throw new RepositoryException(e);
    } catch (com.github.j5ik2o.event.store.adapter.java.OptimisticLockException e) {
      throw new OptimisticLockException(e);
    }
  }

  public void store(@Nonnull UserAccountEvent event, @Nonnull UserAccount aggregate) {
    try {
      eventStore.persistEventAndSnapshot(event, aggregate);
    } catch (EventStoreWriteException | SerializationException e) {
      throw new RepositoryException(e);
    } catch (com.github.j5ik2o.event.store.adapter.java.OptimisticLockException e) {
      throw new OptimisticLockException(e);
    }
  }

  @Nonnull
  public Optional<UserAccount> findById(@Nonnull UserAccountId id) {
    try {
      var snapshot = eventStore.getLatestSnapshotById(UserAccount.class, id);
      if (snapshot.isEmpty()) {
        return Optional.empty();
      } else {
        var events =
            eventStore.getEventsByIdSinceSequenceNumber(
                UserAccountEvent.class, id, snapshot.get().getSequenceNumber() + 1);
        return Optional.of(UserAccount.replay(events, snapshot.get()));
      }
    } catch (EventStoreReadException | DeserializationException e) {
      throw new RepositoryException(e);
    }
  }
}
