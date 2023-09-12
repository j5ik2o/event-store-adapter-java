package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.j5ik2o.event_store_adatpter_java.Aggregate;
import com.github.j5ik2o.event_store_adatpter_java.AggregateAndEvent;
import java.time.Instant;
import java.util.List;
import javax.annotation.Nonnull;

public final class UserAccount implements Aggregate<UserAccountId> {
  @Nonnull private final UserAccountId id;
  private long sequenceNumber;
  @Nonnull private final String name;

  private long version;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    UserAccount that = (UserAccount) o;

    if (sequenceNumber != that.sequenceNumber) return false;
    if (version != that.version) return false;
    if (!id.equals(that.id)) return false;
    return name.equals(that.name);
  }

  @Override
  public int hashCode() {
    int result = id.hashCode();
    result = 31 * result + (int) (sequenceNumber ^ (sequenceNumber >>> 32));
    result = 31 * result + name.hashCode();
    result = 31 * result + (int) (version ^ (version >>> 32));
    return result;
  }

  private UserAccount(
      @Nonnull @JsonProperty("id") UserAccountId id,
      @JsonProperty("sequenceNumber") long sequenceNumber,
      @Nonnull @JsonProperty("name") String name,
      @JsonProperty("version") long version) {
    this.id = id;
    this.sequenceNumber = sequenceNumber;
    this.name = name;
    this.version = version;
  }

  @Nonnull
  public static UserAccount replay(
      @Nonnull List<UserAccountEvent> events, @Nonnull UserAccount snapshot, long version) {
    UserAccount userAccount =
        events.stream().reduce(snapshot, UserAccount::applyEvent, (u1, u2) -> u2);
    userAccount.version = version;
    return userAccount;
  }

  @Nonnull
  public UserAccount applyEvent(@Nonnull UserAccountEvent event) {
    if (event instanceof UserAccountEvent.Renamed) {
      var result = changeName(((UserAccountEvent.Renamed) event).getName());
      return result.getAggregate();
    } else {
      throw new IllegalArgumentException();
    }
  }

  @Nonnull
  public static AggregateAndEvent<UserAccountId, UserAccount, UserAccountEvent> create(
      @Nonnull UserAccountId id, @Nonnull String name) {
    var userAccount = new UserAccount(id, 0L, name, 1L);
    userAccount.sequenceNumber++;
    return new AggregateAndEvent<>(
        userAccount,
        new UserAccountEvent.Created(
            IdGenerator.generate().toString(),
            userAccount.id,
            userAccount.sequenceNumber,
            name,
            Instant.now()));
  }

  @Nonnull
  public AggregateAndEvent<UserAccountId, UserAccount, UserAccountEvent> changeName(
      @Nonnull String name) {
    var userAccount = new UserAccount(id, sequenceNumber, name, version);
    userAccount.sequenceNumber++;
    return new AggregateAndEvent<>(
        userAccount,
        new UserAccountEvent.Renamed(
            IdGenerator.generate().toString(),
            userAccount.id,
            userAccount.sequenceNumber,
            name,
            Instant.now()));
  }

  @Nonnull
  @Override
  public UserAccountId getId() {
    return id;
  }

  @Override
  public long getSequenceNumber() {
    return sequenceNumber;
  }

  @Override
  public long getVersion() {
    return version;
  }

  @Nonnull
  public String getName() {
    return name;
  }
}
