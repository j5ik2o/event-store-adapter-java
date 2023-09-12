# event-store-adapter-java

[![CI](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml/badge.svg)](https://github.com/j5ik2o/event-store-adapter-java/actions/workflows/ci.yml)
[![tokei](https://tokei.rs/b1/github/j5ik2o/event-store-adapter-java)](https://github.com/XAMPPRocky/tokei)

このライブラリは、DynamoDBをEvent Sourcing用のEvent Storeにするためのものです。

[English](./README.md)

# 使い方

EventStoreを使えば、Event Sourcing対応リポジトリを簡単に実装できます。

```java
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
      .thenCompose(result -> {
        if (result.isEmpty()) {
          return CompletableFuture.completedFuture(Optional.empty());
        } else {
          return eventStore.getEventsByIdSinceSequenceNumber(UserAccountEvent.class,
            id, result.get().getAggregate().getSequenceNumber() + 1)
            .thenApply(events -> Optional.of(UserAccount.replay(
              events, result.get().getAggregate(), result.get().getVersion())));
        }
      });
  }
}
```

以下はリポジトリの使用例です。

```java
var eventStore = EventStoreAsyncForDynamoDB.create<UserAccountId, UserAccount, UserAccountEvent>(
    client,
    JOURNAL_TABLE_NAME,
    SNAPSHOT_TABLE_NAME,
    JOURNAL_AID_INDEX_NAME,
    SNAPSHOT_AID_INDEX_NAME,
    32);
var userAccountRepository = new UserAccountRepositoryAsync(eventStore);

var id = new UserAccountId(IdGenerator.generate().toString());
var aggregateAndEvent1 = UserAccount.create(id, "test-1");
var aggregate1 = aggregateAndEvent1.getAggregate();

var result = userAccountRepository.store(aggregateAndEvent1.getEvent(), aggregate1)
  .thenCompose(r -> {
    var aggregateAndEvent2 = aggregate1.changeName("test-2");
    return userAccountRepository.store(
            aggregateAndEvent2.getEvent(), aggregateAndEvent2.getAggregate().getVersion());
  }).thenCompose(r -> userAccountRepository.findById(id)).join();

if (result.isPresent()) {
  assertEquals(result.get().getId(), aggregateAndEvent2.getAggregate().getId());
  assertEquals(result.get().getName(), "test-2");
} else {
  fail("result is empty");
}
```
