package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.github.j5ik2o.event_store_adatpter_java.EventStoreAsync;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;

public final class UserAccountRepositoryAsync {

  @Nonnull private final EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> eventStore;

  public UserAccountRepositoryAsync(
      @Nonnull EventStoreAsync<UserAccountId, UserAccount, UserAccountEvent> eventStore) {
    this.eventStore = eventStore;
  }

  @Nonnull
  public CompletableFuture<Void> store(@Nonnull UserAccountEvent event, long version) {
    return eventStore.persistEvent(event, version);
  }

  @Nonnull
  public CompletableFuture<Void> store(
      @Nonnull UserAccountEvent event, @Nonnull UserAccount aggregate) {
    return eventStore.persistEventAndSnapshot(event, aggregate);
  }

  @Nonnull
  public CompletableFuture<Optional<UserAccount>> findById(@Nonnull UserAccountId id) {
    return eventStore
        .getLatestSnapshotById(UserAccount.class, id)
        .thenCompose(
            result -> {
              if (result.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.empty());
              } else {
                return eventStore
                    .getEventsByIdSinceSequenceNumber(
                        UserAccountEvent.class, id, result.get().getAggregate().getSequenceNumber())
                    .thenApply(
                        events ->
                            Optional.of(
                                UserAccount.replay(
                                    events,
                                    result.get().getAggregate(),
                                    result.get().getVersion())));
              }
            });
  }
}
