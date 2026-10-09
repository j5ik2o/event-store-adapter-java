package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.dynamodb.model.TableDescription;

class DynamoDbJsonTest {
  @Test
  void streamingEvidencePreservesBinaryAndFullJson(@TempDir Path directory) throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode actual = DynamoDbJson.object();
    actual.put("binary", new byte[] {0, (byte) 255, 7});
    actual.put("text", "x".repeat(320000));
    actual.putArray("values").add(true).addNull().add(9223372036854775807L);
    Path output = directory.resolve("result.json");
    DynamoDbJson.write(output, actual);
    assertEquals(
        DynamoDbJson.read(DynamoDbJson.bytes(actual)),
        DynamoDbJson.read(Files.readAllBytes(output)));
  }

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
