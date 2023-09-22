package com.github.j5ik2o.event.store.adapter.java;

import javax.annotation.Nonnull;

/**
 * This is a class for representing aggregates and events. / 集約とイベントを表すためのクラスです。
 *
 * @param <AID> Aggregate ID type / 集約IDの型
 * @param <A> Aggregate type / 集約の型
 * @param <E> Event type / イベントの型
 */
public final class AggregateAndEvent<
    AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>> {
  @Nonnull private final A aggregate;
  @Nonnull private final E event;

  /**
   * Creates a new instance. / 新しいインスタンスを生成します。
   *
   * @param aggregate aggregate / 集約
   * @param event event / イベント
   */
  public AggregateAndEvent(@Nonnull A aggregate, @Nonnull E event) {
    this.aggregate = aggregate;
    this.event = event;
  }

  /**
   * Returns the aggregate. / 集約を返します。
   *
   * @return aggregate / 集約
   */
  @Nonnull
  public A getAggregate() {
    return aggregate;
  }

  /**
   * Returns the event. / イベントを返します。
   *
   * @return event / イベント
   */
  @Nonnull
  public E getEvent() {
    return event;
  }
}
