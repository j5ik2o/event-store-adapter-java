package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** BT-D1 / BT-13 / BT-16 / BT-17 / S-1: invalid settings fail at build, before any request. */
class BigtableStorageConfigTest {
  private static BigtableStorageConfig.Builder valid() {
    return BigtableStorageConfig.builder().journalTableId("journal").headTableId("head");
  }

  @Test
  void defaultsAreNoRetentionAndOneMebibyte() {
    BigtableStorageConfig config = valid().build();

    assertEquals("journal", config.journalTableId());
    assertEquals("head", config.headTableId());
    assertEquals(Optional.empty(), config.retentionPolicy().mode());
    assertEquals(1_048_576, config.envelopeLimitBytes());
  }

  @Test
  void limitsOfSeventeenAndEightMebibytesAreAccepted() {
    assertEquals(17, valid().envelopeLimitBytes(17).build().envelopeLimitBytes());
    assertEquals(8_388_608, valid().envelopeLimitBytes(8_388_608).build().envelopeLimitBytes());
  }

  @Test
  void limitBelowTheSmallestEnvelopeOrAboveTheMaximumIsAConfigurationError() {
    assertThrows(ConfigurationException.class, () -> valid().envelopeLimitBytes(16).build());
    assertThrows(ConfigurationException.class, () -> valid().envelopeLimitBytes(8_388_609).build());
  }

  @Test
  void aChangeFeedRequestIsAConfigurationError() {
    assertThrows(ConfigurationException.class, () -> valid().changeFeed(true).build());
  }

  @Test
  void aTtlRetentionPolicyIsAConfigurationError() {
    assertThrows(
        ConfigurationException.class,
        () -> valid().retentionPolicy(RetentionPolicy.ttl(2, 3600)).build());
  }

  @Test
  void aNullRetentionPolicyIsAConfigurationError() {
    assertThrows(ConfigurationException.class, () -> valid().retentionPolicy(null).build());
  }

  @Test
  void missingOrEmptyTableIdentifiersAreConfigurationErrors() {
    assertThrows(
        ConfigurationException.class,
        () -> BigtableStorageConfig.builder().headTableId("head").build());
    assertThrows(
        ConfigurationException.class,
        () -> BigtableStorageConfig.builder().journalTableId("journal").build());
    assertThrows(ConfigurationException.class, () -> valid().journalTableId("").build());
    assertThrows(ConfigurationException.class, () -> valid().headTableId(null).build());
  }

  @Test
  void journalAndHeadMustBeDifferentTables() {
    assertThrows(
        ConfigurationException.class,
        () -> valid().journalTableId("same").headTableId("same").build());
  }

  @Test
  void aKeepCountOfZeroIsAConfigurationError() {
    assertThrows(
        ConfigurationException.class,
        () -> valid().retentionPolicy(RetentionPolicy.delete(0)).build());
  }

  @Test
  void deleteRetentionWithAPositiveCountIsAccepted() {
    RetentionPolicy policy =
        valid().retentionPolicy(RetentionPolicy.delete(3)).build().retentionPolicy();

    assertEquals(RetentionMode.DELETE, policy.mode().orElseThrow());
    assertEquals(3, policy.keepCount().getAsInt());
  }
}
