package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.github.j5ik2o.event_store_adatpter_java.EventStore;
import java.util.Optional;
import javax.annotation.Nonnull;

public final class UserAccountRepository {
  @Nonnull private final EventStore<UserAccountId, UserAccount, UserAccountEvent> eventStore;

  public UserAccountRepository(
      @Nonnull EventStore<UserAccountId, UserAccount, UserAccountEvent> eventStore) {
    this.eventStore = eventStore;
  }

  public void store(@Nonnull UserAccountEvent event, long version) {
    eventStore.persistEvent(event, version);
  }

  public void store(@Nonnull UserAccountEvent event, @Nonnull UserAccount aggregate) {
    eventStore.persistEventAndSnapshot(event, aggregate);
  }

  @Nonnull
  public Optional<UserAccount> findById(@Nonnull UserAccountId id) {
    var snapshot = eventStore.getLatestSnapshotById(UserAccount.class, id);
    if (snapshot.isEmpty()) {
      return Optional.empty();
    } else {
      var events =
          eventStore.getEventsByIdSinceSequenceNumber(
              UserAccountEvent.class, id, snapshot.get().getAggregate().getSequenceNumber());
      return Optional.of(
          UserAccount.replay(events, snapshot.get().getAggregate(), snapshot.get().getVersion()));
    }
  }
}
