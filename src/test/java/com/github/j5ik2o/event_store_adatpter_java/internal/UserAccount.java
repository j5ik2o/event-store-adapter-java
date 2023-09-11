package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.j5ik2o.event_store_adatpter_java.Aggregate;
import com.github.j5ik2o.event_store_adatpter_java.AggregateAndEvent;
import java.time.Instant;
import java.util.List;

public class UserAccount implements Aggregate<UserAccountId> {
  private final UserAccountId id;
  private long sequenceNumber;
  private final String name;

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
      @JsonProperty("id") UserAccountId id,
      @JsonProperty("sequenceNumber") long sequenceNumber,
      @JsonProperty("name") String name,
      @JsonProperty("version") long version) {
    this.id = id;
    this.sequenceNumber = sequenceNumber;
    this.name = name;
    this.version = version;
  }

  public static UserAccount replay(
      List<UserAccountEvent> events, UserAccount snapshot, long version) {
    UserAccount userAccount =
        events.stream().reduce(snapshot, UserAccount::applyEvent, (u1, u2) -> u2);
    userAccount.version = version;
    return userAccount;
  }

  public UserAccount applyEvent(UserAccountEvent event) {
    if (event instanceof UserAccountEvent.Renamed) {
      var result = changeName(((UserAccountEvent.Renamed) event).getName());
      return result.getAggregate();
    } else {
      throw new IllegalArgumentException();
    }
  }

  public static AggregateAndEvent<UserAccountId, UserAccount, UserAccountEvent> create(
      UserAccountId id, String name) {
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

  public AggregateAndEvent<UserAccountId, UserAccount, UserAccountEvent> changeName(String name) {
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

  public String getName() {
    return name;
  }
}
