package com.github.j5ik2o.event_store_adatpter_java.internal;

import com.fasterxml.jackson.annotation.*;
import com.github.j5ik2o.event_store_adatpter_java.Event;
import java.time.Instant;
import javax.annotation.Nonnull;
import org.jetbrains.annotations.NotNull;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(name = "created", value = UserAccountEvent.Created.class),
  @JsonSubTypes.Type(name = "renamed", value = UserAccountEvent.Renamed.class)
})
public sealed interface UserAccountEvent extends Event<UserAccountId>
    permits UserAccountEvent.Created, UserAccountEvent.Renamed {

  @Nonnull
  UserAccountId getAggregateId();

  long getSeqNr();

  @JsonTypeName("created")
  @JsonIgnoreProperties(
      value = {"created"},
      allowGetters = true)
  final class Created implements UserAccountEvent {

    private final String id;
    private final UserAccountId aggregateId;

    private final long seqNr;

    private final String name;

    private final Instant occurredAt;

    public Created(
        @JsonProperty("id") String id,
        @JsonProperty("aggregateId") UserAccountId aggregateId,
        @JsonProperty("seqNr") long seqNr,
        @JsonProperty("name") String name,
        @JsonProperty("occurredAt") Instant occurredAt) {
      this.id = id;
      this.aggregateId = aggregateId;
      this.seqNr = seqNr;
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
    public long getSeqNr() {
      return seqNr;
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
    private final long seqNr;
    private final String name;
    private final Instant occurredAt;

    public Renamed(
        @JsonProperty("id") String id,
        @JsonProperty("aggregateId") UserAccountId aggregateId,
        @JsonProperty("seqNr") long seqNr,
        @JsonProperty("name") String name,
        @JsonProperty("occurredAt") Instant occurredAt) {
      this.id = id;
      this.aggregateId = aggregateId;
      this.seqNr = seqNr;
      this.name = name;
      this.occurredAt = occurredAt;
    }

    @Override
    public boolean isCreated() {
      return false;
    }

    @NotNull
    @Override
    public String getId() {
      return id;
    }

    @NotNull
    @Override
    public UserAccountId getAggregateId() {
      return aggregateId;
    }

    @NotNull
    public String getName() {
      return name;
    }

    @Override
    public long getSeqNr() {
      return seqNr;
    }

    @Override
    public Instant getOccurredAt() {
      return occurredAt;
    }
  }
}
