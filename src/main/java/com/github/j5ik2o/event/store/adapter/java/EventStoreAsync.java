package com.github.j5ik2o.event.store.adapter.java;

import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreAsyncForDynamoDB;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;

/**
 * Asynchronous version of {@link EventStore}. / {@link EventStore}の非同期版。
 *
 * @param <AID> Aggregate ID type / 集約IDの型
 * @param <A> Aggregate type / 集約の型
 * @param <E> Event type / イベントの型
 */
public interface EventStoreAsync<
        AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
    extends EventStoreOptions<EventStoreAsync<AID, A, E>, AID, A, E> {
  static <AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
      EventStoreAsync<AID, A, E> ofDynamoDB(
          @Nonnull DynamoDbAsyncClient dynamoDbAsyncClient,
          @Nonnull String journalTableName,
          @Nonnull String snapshotTableName,
          @Nonnull String journalAidIndexName,
          @Nonnull String snapshotAidIndexName,
          long shardCount) {
    return EventStoreAsyncForDynamoDB.create(
        dynamoDbAsyncClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount);
  }

  /**
   * Gets the latest snapshot by the aggregate id. / 集約IDによる最新のスナップショットを取得します。
   *
   * @param clazz class of {@link Aggregate} A to be deserialized / デシリアライズ対象の集約Aのクラス
   * @param aggregateId id of {@link Aggregate} A / 集約AのID
   * @return latest snapshot wrapped by {@link CompletableFuture} / {@link
   *     CompletableFuture}でラップされた最新のスナップショット
   */
  @Nonnull
  CompletableFuture<Optional<A>> getLatestSnapshotById(
      @Nonnull Class<A> clazz, @Nonnull AID aggregateId);

  /**
   * Gets the events by the aggregate id and since the sequence number. / 集約IDとシーケンス番号以降のイベントを取得します。
   *
   * @param clazz class of {@link Event} E to be deserialized / デシリアライズ対象のイベントEのクラス
   * @param aggregateId {@link Aggregate} id / 集約のID
   * @param sequenceNumber sequence number / シーケンス番号
   * @return list of {@link Event} wrapped by {@link CompletableFuture} / {@link
   *     CompletableFuture}でラップされた{@link Event}のリスト
   */
  @Nonnull
  CompletableFuture<List<E>> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber);

  /**
   * Persists an event only. / イベントのみを永続化します。
   *
   * @param event {@link Event} / イベント
   * @param version version / バージョン
   * @return {@link CompletableFuture} without result / 結果を持たない{@link CompletableFuture}
   */
  @Nonnull
  CompletableFuture<Void> persistEvent(@Nonnull E event, long version);

  /**
   * Persists an event and a snapshot. / イベントとスナップショットを永続化します。
   *
   * @param event {@link Event} / イベント
   * @param aggregate {@link Aggregate} / 集約
   * @return {@link CompletableFuture} without result / 結果を持たない{@link CompletableFuture}
   */
  @Nonnull
  CompletableFuture<Void> persistEventAndSnapshot(@Nonnull E event, @Nonnull A aggregate);
}
