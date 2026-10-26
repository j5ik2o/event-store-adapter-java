package com.github.j5ik2o.event.store.adapter.java.sqlite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.ConfigurationException;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionMode;
import com.github.j5ik2o.event.store.adapter.java.core.RetentionPolicy;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class SqliteStorageConfigTest {
  @Test
  void defaultsAreNoRetentionAndFiveSecondLockWait() {
    SqliteStorageConfig config = SqliteStorageConfig.builder().build();

    assertEquals(RetentionPolicy.none(), config.retentionPolicy());
    assertEquals(Duration.ofSeconds(5), config.lockWait());
  }

  @Test
  void configuredValuesAreKept() {
    SqliteStorageConfig config =
        SqliteStorageConfig.builder()
            .retentionPolicy(RetentionPolicy.delete(3))
            .lockWait(Duration.ofMillis(250))
            .build();

    assertEquals(OptionalInt.of(3), config.retentionPolicy().keepCount());
    assertEquals(Optional.of(RetentionMode.DELETE), config.retentionPolicy().mode());
    assertEquals(Duration.ofMillis(250), config.lockWait());
  }

  @Test
  void ttlRetentionIsRejectedAtBuildBeforeAnyFileIsTouched() {
    ConfigurationException e =
        assertThrows(
            ConfigurationException.class,
            () ->
                SqliteStorageConfig.builder().retentionPolicy(RetentionPolicy.ttl(1, 60)).build());

    assertEquals("sqlite storage does not support TTL", e.getMessage());
  }

  @Test
  void nullRetentionNullAndNegativeLockWaitAreRejected() {
    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorageConfig.builder().retentionPolicy(null).build());
    assertThrows(
        ConfigurationException.class, () -> SqliteStorageConfig.builder().lockWait(null).build());
    assertThrows(
        ConfigurationException.class,
        () -> SqliteStorageConfig.builder().lockWait(Duration.ofMillis(-1)).build());
  }

  @Test
  void lockWaitAboveInt32MillisecondsIsRejectedAndTheBoundaryIsAccepted() {
    assertThrows(
        ConfigurationException.class,
        () ->
            SqliteStorageConfig.builder()
                .lockWait(Duration.ofMillis((long) Integer.MAX_VALUE + 1))
                .build());
    assertDoesNotThrow(
        () -> SqliteStorageConfig.builder().lockWait(Duration.ofMillis(Integer.MAX_VALUE)).build());
    assertDoesNotThrow(() -> SqliteStorageConfig.builder().lockWait(Duration.ZERO).build());
  }
}
