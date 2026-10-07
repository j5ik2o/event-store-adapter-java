package com.github.j5ik2o.event.store.adapter.java.core;

import java.util.Optional;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Per-store configuration. / ストアごとの設定。 */
public final class EventStoreConfig<P, A> {

  private final PayloadSerializer<P> payloadSerializer;
  private final PayloadSerializer<A> snapshotSerializer;
  private final RetentionFailureListener retentionFailureListener;

  private EventStoreConfig(
      PayloadSerializer<P> payloadSerializer,
      PayloadSerializer<A> snapshotSerializer,
      RetentionFailureListener retentionFailureListener) {
    this.payloadSerializer = payloadSerializer;
    this.snapshotSerializer = snapshotSerializer;
    this.retentionFailureListener = retentionFailureListener;
  }

  /**
   * Returns a builder. / ビルダーを返します。
   *
   * @param <P> payload type / payload の型
   * @param <A> aggregate type / 集約の型
   * @return builder / ビルダー
   */
  @Nonnull
  public static <P, A> Builder<P, A> builder() {
    return new Builder<>();
  }

  @Nonnull
  public PayloadSerializer<P> payloadSerializer() {
    return payloadSerializer;
  }

  @Nonnull
  public PayloadSerializer<A> snapshotSerializer() {
    return snapshotSerializer;
  }

  @Nonnull
  public Optional<RetentionFailureListener> retentionFailureListener() {
    return Optional.ofNullable(retentionFailureListener);
  }

  /** Builder. / ビルダー。 */
  public static final class Builder<P, A> {
    private PayloadSerializer<P> payloadSerializer;
    private PayloadSerializer<A> snapshotSerializer;
    private RetentionFailureListener retentionFailureListener;

    private Builder() {}

    @Nonnull
    public Builder<P, A> payloadSerializer(@Nullable PayloadSerializer<P> v) {
      this.payloadSerializer = v;
      return this;
    }

    @Nonnull
    public Builder<P, A> snapshotSerializer(@Nullable PayloadSerializer<A> v) {
      this.snapshotSerializer = v;
      return this;
    }

    @Nonnull
    public Builder<P, A> retentionFailureListener(@Nullable RetentionFailureListener v) {
      this.retentionFailureListener = v;
      return this;
    }

    /**
     * Builds the configuration. / 設定を作ります。
     *
     * @return configuration / 設定
     * @throws ConfigurationException when a serializer is missing / シリアライザが欠けているとき
     */
    @Nonnull
    public EventStoreConfig<P, A> build() {
      if (payloadSerializer == null) {
        throw new ConfigurationException("payloadSerializer is required");
      }
      if (snapshotSerializer == null) {
        throw new ConfigurationException("snapshotSerializer is required");
      }
      return new EventStoreConfig<>(
          payloadSerializer, snapshotSerializer, retentionFailureListener);
    }
  }
}
