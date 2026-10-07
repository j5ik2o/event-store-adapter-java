package com.github.j5ik2o.event.store.adapter.java.core;

import java.time.Instant;
import java.util.OptionalLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Event envelope. / イベント封筒。 */
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

  /**
   * Returns a builder. / ビルダーを返します。
   *
   * @param <P> payload type / payload の型
   * @return builder / ビルダー
   */
  @Nonnull
  public static <P> Builder<P> builder() {
    return new Builder<>();
  }

  @Nonnull
  public AggregateId aggregateId() {
    return aggregateId;
  }

  public long seqNr() {
    return seqNr;
  }

  @Nonnull
  public Instant occurredAt() {
    return occurredAt;
  }

  @Nonnull
  public String manifest() {
    return manifest;
  }

  @Nonnull
  public P payload() {
    return payload;
  }

  /** Builder. / ビルダー。 */
  public static final class Builder<P> {
    private AggregateId aggregateId;
    private long seqNr;
    private boolean seqNrSet;
    private Instant occurredAt;
    private String manifest = "";
    private P payload;

    private Builder() {}

    @Nonnull
    public Builder<P> aggregateId(@Nullable AggregateId v) {
      this.aggregateId = v;
      return this;
    }

    @Nonnull
    public Builder<P> seqNr(long v) {
      this.seqNr = v;
      this.seqNrSet = true;
      return this;
    }

    @Nonnull
    public Builder<P> occurredAt(@Nullable Instant v) {
      this.occurredAt = v;
      return this;
    }

    @Nonnull
    public Builder<P> manifest(@Nullable String v) {
      this.manifest = v == null ? "" : v;
      return this;
    }

    @Nonnull
    public Builder<P> payload(@Nullable P v) {
      this.payload = v;
      return this;
    }

    /**
     * Builds the envelope. / 封筒を作ります。
     *
     * @return envelope / 封筒
     * @throws ContractViolationException when a required element is missing or out of range /
     *     必須要素の欠落や値域の違反のとき
     */
    @Nonnull
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
      Validation.checkSeqNr(seqNr, Validation.Context.EVENT);
      Validation.checkOccurredAt(occurredAt, seqNr);
      return new EventEnvelope<>(aggregateId, seqNr, occurredAt, manifest, payload);
    }

    private static ContractViolationException missing(String element, OptionalLong seqNr) {
      return new ContractViolationException(
          "T-2", seqNr, "required element is missing: " + element);
    }
  }
}
