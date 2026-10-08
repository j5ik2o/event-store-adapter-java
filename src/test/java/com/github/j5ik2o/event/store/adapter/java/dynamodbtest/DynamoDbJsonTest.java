package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

class DynamoDbJsonTest {
  @Test
  void sdkTimestampsCanBeRecordedInsideNestedResponses() {
    Instant time = Instant.parse("2026-10-08T00:00:00.123456789Z");
    TableDescription description =
        TableDescription.builder().tableName("journal").creationDateTime(time).build();
    assertEquals(
        time.toString(),
        DynamoDbJson.sdk(Map.of("tables", List.of(description)))
            .at("/tables/0/CreationDateTime")
            .asText());
  }
}
