package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.annotation.*;
import com.github.j5ik2o.event_store_adatpter_java.Event;
import java.time.Instant;
import javax.annotation.Nonnull;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(name = "created", value = UserAccountEvent.Created.class),
  @JsonSubTypes.Type(name = "renamed", value = UserAccountEvent.Renamed.class)
})
public sealed interface UserAccountEvent extends Event<UserAccountId>
    permits UserAccountEvent.Created, UserAccountEvent.Renamed {

  @Nonnull
  UserAccountId getAggregateId();

  long getSequenceNumber();

  @JsonTypeName("created")
  @JsonIgnoreProperties(
      value = {"created"},
      allowGetters = true)
  final class Created implements UserAccountEvent {

    private final String id;
    private final UserAccountId aggregateId;

    private final long sequenceNumber;

    private final String name;

    private final Instant occurredAt;

    public Created(
        @JsonProperty("id") String id,
        @JsonProperty("aggregateId") UserAccountId aggregateId,
        @JsonProperty("sequenceNumber") long sequenceNumber,
        @JsonProperty("name") String name,
        @JsonProperty("occurredAt") Instant occurredAt) {
      this.id = id;
      this.aggregateId = aggregateId;
      this.sequenceNumber = sequenceNumber;
      this.name = name;
      this.occurredAt = occurredAt;
    }

    @Override
    public boolean isCreated() {
      return true;
    }

    @Nonnull
    @Override
    public String getId() {
      return id;
    }

    @Nonnull
    @Override
    public UserAccountId getAggregateId() {
      return aggregateId;
    }

    @Override
    public long getSequenceNumber() {
      return sequenceNumber;
    }

    @Nonnull
    public String getName() {
      return name;
    }

    @Nonnull
    @Override
    public Instant getOccurredAt() {
      return occurredAt;
    }
  }

  @JsonTypeName("renamed")
  @JsonIgnoreProperties(
      value = {"created"},
      allowGetters = true)
  final class Renamed implements UserAccountEvent {

    private final String id;
    private final UserAccountId aggregateId;
    private final long sequenceNumber;
    private final String name;
    private final Instant occurredAt;

    public Renamed(
        @JsonProperty("id") String id,
        @JsonProperty("aggregateId") UserAccountId aggregateId,
        @JsonProperty("sequenceNumber") long sequenceNumber,
        @JsonProperty("name") String name,
        @JsonProperty("occurredAt") Instant occurredAt) {
      this.id = id;
      this.aggregateId = aggregateId;
      this.sequenceNumber = sequenceNumber;
      this.name = name;
      this.occurredAt = occurredAt;
    }

    @Override
    public boolean isCreated() {
      return false;
    }

    @Nonnull
    @Override
    public String getId() {
      return id;
    }

    @Nonnull
    @Override
    public UserAccountId getAggregateId() {
      return aggregateId;
    }

    @Nonnull
    public String getName() {
      return name;
    }

    @Override
    public long getSequenceNumber() {
      return sequenceNumber;
    }

    @Nonnull
    @Override
    public Instant getOccurredAt() {
      return occurredAt;
    }
  }
}
