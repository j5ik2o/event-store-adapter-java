package com.github.j5ik2o.event.store.adapter.java.internal;

import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

public class DynamoDBUtils {
  public static DynamoDbClient createDynamoDbClient(LocalStackContainer localstack) {
    return DynamoDbClient.builder()
        .endpointOverride(localstack.getEndpoint())
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
        .region(Region.of(localstack.getRegion()))
        .build();
  }

  public static void createSnapshotTable(
      DynamoDbClient client, String tableName, String indexName) {
    var pt = ProvisionedThroughput.builder().readCapacityUnits(10L).writeCapacityUnits(5L).build();
    var response =
        client.createTable(
            CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(
                    AttributeDefinition.builder()
                        .attributeName("pkey")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName("skey")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName("aid")
                        .attributeType(ScalarAttributeType.S)
                        .build(),
                    AttributeDefinition.builder()
                        .attributeName("seq_nr")
                        .attributeType(ScalarAttributeType.N)
                        .build())
                .keySchema(
                    KeySchemaElement.builder().attributeName("pkey").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("skey").keyType(KeyType.RANGE).build())
                .globalSecondaryIndexes(
                    GlobalSecondaryIndex.builder()
                        .indexName(indexName)
                        .keySchema(
                            KeySchemaElement.builder()
                                .attributeName("aid")
                                .keyType(KeyType.HASH)
                                .build(),
                            KeySchemaElement.builder()
                                .attributeName("seq_nr")
                                .keyType(KeyType.RANGE)
                                .build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .provisionedThroughput(pt)
                        .build())
                .provisionedThroughput(pt)
                .build());
    client.updateTimeToLive(
        UpdateTimeToLiveRequest.builder()
            .tableName(tableName)
            .timeToLiveSpecification(
                TimeToLiveSpecification.builder().enabled(true).attributeName("ttl").build())
            .build());
  }

  public static void createJournalTable(DynamoDbClient client, String tableName, String indexName) {
    var pt = ProvisionedThroughput.builder().readCapacityUnits(10L).writeCapacityUnits(5L).build();
    client.createTable(
        CreateTableRequest.builder()
            .tableName(tableName)
            .attributeDefinitions(
                AttributeDefinition.builder()
                    .attributeName("pkey")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("skey")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("aid")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("seq_nr")
                    .attributeType(ScalarAttributeType.N)
                    .build())
            .keySchema(
                KeySchemaElement.builder().attributeName("pkey").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("skey").keyType(KeyType.RANGE).build())
            .globalSecondaryIndexes(
                GlobalSecondaryIndex.builder()
                    .indexName(indexName)
                    .keySchema(
                        KeySchemaElement.builder()
                            .attributeName("aid")
                            .keyType(KeyType.HASH)
                            .build(),
                        KeySchemaElement.builder()
                            .attributeName("seq_nr")
                            .keyType(KeyType.RANGE)
                            .build())
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                    .provisionedThroughput(pt)
                    .build())
            .provisionedThroughput(pt)
            .build());
  }
}
