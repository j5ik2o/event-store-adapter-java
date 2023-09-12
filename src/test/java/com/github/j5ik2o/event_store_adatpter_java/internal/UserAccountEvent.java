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
public interface UserAccountEvent extends Event<UserAccountId> {

  @Nonnull
  UserAccountId getAggregateId();

  long getSequenceNumber();

  @JsonTypeName("created")
  @JsonIgnoreProperties(
      value = {"created"},
      allowGetters = true)
  final class Created implements UserAccountEvent {

    @Nonnull private final String id;
    @Nonnull private final UserAccountId aggregateId;

    private final long sequenceNumber;

    @Nonnull private final String name;

    @Nonnull private final Instant occurredAt;

    public Created(
        @Nonnull @JsonProperty("id") String id,
        @Nonnull @JsonProperty("aggregateId") UserAccountId aggregateId,
        @JsonProperty("sequenceNumber") long sequenceNumber,
        @Nonnull @JsonProperty("name") String name,
        @Nonnull @JsonProperty("occurredAt") Instant occurredAt) {
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

    @Nonnull private final String id;
    @Nonnull private final UserAccountId aggregateId;
    private final long sequenceNumber;
    @Nonnull private final String name;
    @Nonnull private final Instant occurredAt;

    public Renamed(
        @Nonnull @JsonProperty("id") String id,
        @Nonnull @JsonProperty("aggregateId") UserAccountId aggregateId,
        @JsonProperty("sequenceNumber") long sequenceNumber,
        @Nonnull @JsonProperty("name") String name,
        @Nonnull @JsonProperty("occurredAt") Instant occurredAt) {
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
