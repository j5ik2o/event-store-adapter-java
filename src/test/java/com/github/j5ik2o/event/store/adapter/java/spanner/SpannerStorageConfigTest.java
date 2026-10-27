package com.github.j5ik2o.event.store.adapter.java.spanner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class SpannerStorageConfigTest {
  @Test
  void defaultsAreNoRetentionAndFiveRetries() {
    SpannerStorageConfig config = SpannerStorageConfig.builder().tablePrefix("esa").build();

    assertFalse(config.retentionPolicy().keepCount().isPresent());
    assertEquals(5, config.transactionRetryLimit());
  }

  @Test
  void configuredValuesAreKeptAndGraceIsCountedInDays() {
    SpannerStorageConfig config =
        SpannerStorageConfig.builder()
            .tablePrefix("esa")
            .retentionPolicy(SpannerRetentionPolicy.ttl(3, 7))
            .transactionRetryLimit(2)
            .build();

    assertEquals(OptionalInt.of(3), config.retentionPolicy().keepCount());
    assertEquals(OptionalLong.of(7), config.retentionPolicy().graceDays());
    assertEquals(2, config.transactionRetryLimit());
  }

  @Test
  void tablePrefixThatIsNotAnSqlIdentifierIsRejectedAtBuild() {
    for (String bad : new String[] {"", "1abc", "a-b", "a b", "a;DROP TABLE x", "a`b", "é"}) {
      assertThrows(
          ConfigurationException.class,
          () -> SpannerStorageConfig.builder().tablePrefix(bad).build(),
          bad);
    }
    assertThrows(
        ConfigurationException.class,
        () -> SpannerStorageConfig.builder().tablePrefix(null).build());
  }

  @Test
  void aDerivedTableNameLongerThan128CharactersIsRejectedAndTheBoundaryIsAccepted() {
    // The longest derived name is "<prefix>_snapshot_current" (17 characters of suffix).
    String longest = "a".repeat(128 - "_snapshot_current".length());
    assertDoesNotThrow(() -> SpannerStorageConfig.builder().tablePrefix(longest).build());
    assertThrows(
        ConfigurationException.class,
        () -> SpannerStorageConfig.builder().tablePrefix(longest + "a").build());
  }

  @Test
  void keepCountBelowOneAndGraceDaysBelowOneAreRejected() {
    assertThrows(ConfigurationException.class, () -> SpannerRetentionPolicy.delete(0));
    assertThrows(ConfigurationException.class, () -> SpannerRetentionPolicy.ttl(0, 7));
    assertThrows(ConfigurationException.class, () -> SpannerRetentionPolicy.ttl(1, 0));
    assertThrows(ConfigurationException.class, () -> SpannerRetentionPolicy.ttl(1, -1));
  }

  @Test
  void aNegativeRetryLimitIsRejectedAndZeroIsAccepted() {
    assertThrows(
        ConfigurationException.class,
        () -> SpannerStorageConfig.builder().tablePrefix("esa").transactionRetryLimit(-1).build());
    assertDoesNotThrow(
        () -> SpannerStorageConfig.builder().tablePrefix("esa").transactionRetryLimit(0).build());
  }

  @Test
  void aNullRetentionPolicyIsRejected() {
    assertThrows(
        ConfigurationException.class,
        () -> SpannerStorageConfig.builder().tablePrefix("esa").retentionPolicy(null).build());
  }

  @Test
  void requestingAChangeFeedIsAConfigurationErrorAtBuild() {
    assertThrows(
        ConfigurationException.class,
        () -> SpannerStorageConfig.builder().tablePrefix("esa").changeFeed(true).build());
    assertDoesNotThrow(
        () -> SpannerStorageConfig.builder().tablePrefix("esa").changeFeed(false).build());
  }
}
