package com.github.j5ik2o.event.store.adapter.java.coreonly;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

final class SpannerCoreOnlyTest {

  @Test
  void exposesCoreAndSpannerButNotTheOtherDriversOrTheirSdks() {
    ClassLoader loader = SpannerCoreOnlyTest.class.getClassLoader();
    assertDoesNotThrow(() ->
        Class.forName("com.github.j5ik2o.event.store.adapter.java.core.EventStore", false, loader));
    assertDoesNotThrow(() ->
        Class.forName(
            "com.github.j5ik2o.event.store.adapter.java.spanner.SpannerStorage", false, loader));
    assertDoesNotThrow(() -> Class.forName("com.google.cloud.spanner.Spanner", false, loader));
    for (String unavailableClass :
        List.of(
            "com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbEventStore",
            "com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableStorage",
            "com.github.j5ik2o.event.store.adapter.java.sqlite.SqliteStorage",
            "software.amazon.awssdk.services.dynamodb.DynamoDbClient",
            "com.google.cloud.bigtable.data.v2.BigtableDataClient",
            "org.sqlite.JDBC")) {
      assertThrows(
          ClassNotFoundException.class, () -> Class.forName(unavailableClass, false, loader));
    }
  }
}
