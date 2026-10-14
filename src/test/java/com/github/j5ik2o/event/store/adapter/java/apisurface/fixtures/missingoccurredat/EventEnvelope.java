package com.github.j5ik2o.event.store.adapter.java.apisurface.fixtures.missingoccurredat;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.ContractViolationException;
import java.time.Instant;
import java.util.OptionalLong;

/** 否定の試験用に、形を 1 か所だけずらしたイベント封筒。 */
public final class EventEnvelope<P> {

  private final AggregateId aggregateId;
  private final long seqNr;
  private final Instant occurredAt;
  private final String manifest;
  private final P payload;

  private EventEnvelope(
      AggregateId aggregateId, long seqNr, Instant occurredAt, String manifest, P payload) {
    this.aggregateId = aggregateId;
    this.seqNr = seqNr;
    this.occurredAt = occurredAt;
    this.manifest = manifest;
    this.payload = payload;
  }

  public static <P> Builder<P> builder() {
    return new Builder<>();
  }

  public AggregateId aggregateId() {
    return aggregateId;
  }

  public long seqNr() {
    return seqNr;
  }

  public String manifest() {
    return manifest;
  }

  public P payload() {
    return payload;
  }

  /** ビルダー。 */
  public static final class Builder<P> {
    private AggregateId aggregateId;
    private long seqNr;
    private boolean seqNrSet;
    private Instant occurredAt;
    private String manifest = "";
    private P payload;

    private Builder() {}

    public Builder<P> aggregateId(AggregateId v) {
      this.aggregateId = v;
      return this;
    }

    public Builder<P> seqNr(long v) {
      this.seqNr = v;
      this.seqNrSet = true;
      return this;
    }

    public Builder<P> occurredAt(Instant v) {
      this.occurredAt = v;
      return this;
    }

    public Builder<P> manifest(String v) {
      this.manifest = v == null ? "" : v;
      return this;
    }

    public Builder<P> payload(P v) {
      this.payload = v;
      return this;
    }

    public EventEnvelope<P> build() {
      OptionalLong related = seqNrSet ? OptionalLong.of(seqNr) : OptionalLong.empty();
      if (aggregateId == null) {
        throw missing("aggregate_id", related);
      }
      if (!seqNrSet) {
        throw missing("seq_nr", related);
      }
      if (occurredAt == null) {
        throw missing("occurred_at", related);
      }
      if (payload == null) {
        throw missing("payload", related);
      }
      return new EventEnvelope<>(aggregateId, (long) seqNr, occurredAt, manifest, payload);
    }

    private static ContractViolationException missing(String element, OptionalLong seqNr) {
      return new ContractViolationException(
          "T-2", seqNr, "required element is missing: " + element);
    }
  }
}
