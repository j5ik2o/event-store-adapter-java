package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.github.j5ik2o.event_store_adatpter_java.EventStore;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class UserAccountRepository {

  private final EventStore<UserAccountId, UserAccount, UserAccountEvent> eventStore;

  public UserAccountRepository(
      EventStore<UserAccountId, UserAccount, UserAccountEvent> eventStore) {
    this.eventStore = eventStore;
  }

  public CompletableFuture<Void> store(UserAccountEvent event, long version) {
    return eventStore.persistEvent(event, version);
  }

  public CompletableFuture<Void> store(UserAccountEvent event, UserAccount aggregate) {
    return eventStore.persistEventAndSnapshot(event, aggregate);
  }

  public CompletableFuture<Optional<UserAccount>> findById(UserAccountId id) {
    return eventStore
        .getLatestSnapshotById(UserAccount.class, id)
        .thenCompose(
            result -> {
              if (result.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
              } else {
                return eventStore
                    .getEventsByIdSinceSeqNr(
                        UserAccountEvent.class, id, result.get().aggregate().getSequenceNumber())
                    .thenApply(
                        events ->
                            Optional.of(
                                UserAccount.replay(
                                    events, result.get().aggregate(), result.get().version())));
              }
            });
  }
}
