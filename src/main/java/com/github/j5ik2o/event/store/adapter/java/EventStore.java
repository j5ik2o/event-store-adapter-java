package com.github.j5ik2o.event.store.adapter.java;

import com.github.j5ik2o.event.store.adapter.java.internal.EventStoreForDynamoDB;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nonnull;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Represents an event store. / イベントストアを表します。
 *
 * @param <AID> AggregateId
 * @param <A> Aggregate
 * @param <E> Event
 */
public interface EventStore<
        AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
    extends EventStoreOptions<EventStore<AID, A, E>, AID, A, E> {
  /**
   * Creates an instance of {@link EventStore} for DynamoDB. / DynamoDB用の{@link
   * EventStore}のインスタンスを作成します。
   *
   * @param dynamoDbClient {@link DynamoDbClient}
   * @param journalTableName journal table name / ジャーナルテーブル名
   * @param snapshotTableName snapshot table name / スナップショットテーブル名
   * @param journalAidIndexName journal aggregate id index name / ジャーナル集約IDインデックス名
   * @param snapshotAidIndexName snapshot aggregate id index name / スナップショット集約IDインデックス名
   * @param shardCount shard count / シャード数
   * @return {@link EventStore}
   * @param <AID> aggregate id type / 集約IDの型
   * @param <A> aggregate type / 集約の型
   * @param <E> event type / イベントの型
   */
  @Nonnull
  static <AID extends AggregateId, A extends Aggregate<A, AID>, E extends Event<AID>>
      EventStore<AID, A, E> ofDynamoDB(
          @Nonnull DynamoDbClient dynamoDbClient,
          @Nonnull String journalTableName,
          @Nonnull String snapshotTableName,
          @Nonnull String journalAidIndexName,
          @Nonnull String snapshotAidIndexName,
          long shardCount) {
    return EventStoreForDynamoDB.create(
        dynamoDbClient,
        journalTableName,
        snapshotTableName,
        journalAidIndexName,
        snapshotAidIndexName,
        shardCount);
  }

  /**
   * Gets the latest snapshot by the aggregate id. / 集約IDによる最新のスナップショットを取得します。
   *
   * @param clazz class of {@link Aggregate} A to be serialized / シリアライズ対象の集約Aのクラス
   * @param aggregateId　id of {@link Aggregate} A / 集約AのID
   * @return latest snapshot / 最新のスナップショット
   * @throws EventStoreReadException if an error occurred during reading from the event store /
   *     イベントストアからの読み込み中にエラーが発生した場合
   * @throws DeserializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
   */
  @Nonnull
  Optional<A> getLatestSnapshotById(@Nonnull Class<A> clazz, @Nonnull AID aggregateId)
      throws EventStoreReadException, DeserializationException;

  /**
   * Gets the events by the aggregate id and since the sequence number. / IDとシーケンス番号以降のイベントを取得します。
   *
   * @param clazz class of {@link Event} E to be serialized / シリアライズ対象のイベントEのクラス
   * @param aggregateId id of {@link Aggregate} A / 集約AのID
   * @param sequenceNumber sequence number / シーケンス番号
   * @return events / イベント
   * @throws EventStoreReadException if an error occurred during reading from the event store /
   *     イベントストアからの読み込み中にエラーが発生した場合
   * @throws DeserializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
   */
  @Nonnull
  List<E> getEventsByIdSinceSequenceNumber(
      @Nonnull Class<E> clazz, @Nonnull AID aggregateId, long sequenceNumber)
      throws EventStoreReadException, DeserializationException;

  /**
   * Persists an event only. / イベントのみを永続化します。
   *
   * @param event {@link Event}
   * @param version バージョン
   * @throws EventStoreWriteException if an error occurred during writing to the event store /
   *     イベントストアへの書き込み中にエラーが発生した場合
   * @throws SerializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
   * @throws OptimisticLockException if an error occurred during optimistic lock / 楽観的ロック中にエラーが発生した場合
   */
  void persistEvent(@Nonnull E event, long version)
      throws EventStoreWriteException, SerializationException, OptimisticLockException;

  /**
   * Persists an event and a snapshot. / イベントとスナップショットを永続化します。
   *
   * @param event {@link Event}
   * @param aggregate {@link Aggregate}
   * @throws EventStoreWriteException if an error occurred during writing to the event store /
   *     イベントストアへの書き込み中にエラーが発生した場合
   * @throws SerializationException if an error occurred during serialization / シリアライズ中にエラーが発生した場合
   * @throws OptimisticLockException if an error occurred during optimistic lock / 楽観的ロック中にエラーが発生した場合
   */
  void persistEventAndSnapshot(@Nonnull E event, @Nonnull A aggregate)
      throws EventStoreWriteException, SerializationException, OptimisticLockException;
}
