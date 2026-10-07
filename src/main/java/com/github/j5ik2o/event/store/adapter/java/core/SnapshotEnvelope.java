package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.OptionalLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Snapshot envelope. / スナップショット封筒。 */
public final class SnapshotEnvelope<A> {

  private final A aggregate;
  private final long seqNr;
  private final String manifest;

  private SnapshotEnvelope(A aggregate, long seqNr, String manifest) {
    this.aggregate = aggregate;
    this.seqNr = seqNr;
    this.manifest = manifest;
  }

  /**
   * Returns a builder. / ビルダーを返します。
   *
   * @param <A> aggregate type / 集約の型
   * @return builder / ビルダー
   */
  @Nonnull
  public static <A> Builder<A> builder() {
    return new Builder<>();
  }

  @Nonnull
  public A aggregate() {
    return aggregate;
  }

  public long seqNr() {
    return seqNr;
  }

  @Nonnull
  public String manifest() {
    return manifest;
  }

  /** Builder. / ビルダー。 */
  public static final class Builder<A> {
    private A aggregate;
    private long seqNr;
    private boolean seqNrSet;
    private String manifest = "";

    private Builder() {}

    @Nonnull
    public Builder<A> aggregate(@Nullable A v) {
      this.aggregate = v;
      return this;
    }

    @Nonnull
    public Builder<A> seqNr(long v) {
      this.seqNr = v;
      this.seqNrSet = true;
      return this;
    }

    @Nonnull
    public Builder<A> manifest(@Nullable String v) {
      this.manifest = v == null ? "" : v;
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
    public SnapshotEnvelope<A> build() {
      OptionalLong related = seqNrSet ? OptionalLong.of(seqNr) : OptionalLong.empty();
      if (aggregate == null) {
        throw new ContractViolationException(
            "T-10", related, "required element is missing: aggregate");
      }
      if (!seqNrSet) {
        throw new ContractViolationException(
            "T-10", related, "required element is missing: seq_nr");
      }
      Validation.checkSeqNr(seqNr, Validation.Context.VALUE);
      return new SnapshotEnvelope<>(aggregate, seqNr, manifest);
    }
  }
}
