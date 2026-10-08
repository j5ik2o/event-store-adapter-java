package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig;
import java.util.List;
import software.amazon.awssdk.services.dynamodb.model.*;

/**
 * Independent provisioning definitions; conformance JSON is never an input to these definitions.
 */
final class DynamoDbConfigurationTables {
  private DynamoDbConfigurationTables() {}

  static void create(DynamoDbTestContext c, RetentionPolicy policy) {
    c.acquire(definition(c.journal, "seq_nr").build());
    c.acquire(
        definition(c.snapshot, "skey")
            .attributeDefinitions(
                attribute("aid", ScalarAttributeType.S),
                attribute("skey", ScalarAttributeType.N),
                attribute("active_history_seq_nr", ScalarAttributeType.N))
            .globalSecondaryIndexes(
                GlobalSecondaryIndex.builder()
                    .indexName(c.historyIndex)
                    .keySchema(
                        key("aid", KeyType.HASH), key("active_history_seq_nr", KeyType.RANGE))
                    .projection(
                        Projection.builder().projectionType(ProjectionType.KEYS_ONLY).build())
                    .build())
            .build());
    c.acquire(
        definition(c.head, null)
            .streamSpecification(
                StreamSpecification.builder()
                    .streamEnabled(true)
                    .streamViewType(StreamViewType.NEW_IMAGE)
                    .build())
            .build());
    if (policy.mode().orElse(null) == RetentionMode.TTL) {
      c.admin.updateTimeToLive(
          UpdateTimeToLiveRequest.builder()
              .tableName(c.snapshot)
              .timeToLiveSpecification(
                  TimeToLiveSpecification.builder().enabled(true).attributeName("ttl").build())
              .build());
    }
  }

  private static CreateTableRequest.Builder definition(String name, String sort) {
    CreateTableRequest.Builder b =
        CreateTableRequest.builder().tableName(name).billingMode(BillingMode.PAY_PER_REQUEST);
    if (sort == null)
      return b.attributeDefinitions(attribute("aid", ScalarAttributeType.S))
          .keySchema(key("aid", KeyType.HASH));
    return b.attributeDefinitions(
            attribute("aid", ScalarAttributeType.S), attribute(sort, ScalarAttributeType.N))
        .keySchema(key("aid", KeyType.HASH), key(sort, KeyType.RANGE));
  }

  private static AttributeDefinition attribute(String name, ScalarAttributeType type) {
    return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
  }

  private static KeySchemaElement key(String name, KeyType type) {
    return KeySchemaElement.builder().attributeName(name).keyType(type).build();
  }

  static DynamoDbTableConfig.Builder config(DynamoDbTestContext c) {
    return DynamoDbTableConfig.builder()
        .journalTableName(c.journal)
        .snapshotTableName(c.snapshot)
        .headTableName(c.head)
        .snapshotAidIndexName(c.historyIndex);
  }

  static List<String> names(DynamoDbTestContext c) {
    return List.of(c.journal, c.snapshot, c.head);
  }
}
