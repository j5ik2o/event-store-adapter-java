package com.github.j5ik2o.event.store.adapter.java.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class RetentionPolicyTest {

  @Test
  void deleteRequiresKeepCountOfAtLeastOne() {
    assertThrows(ConfigurationException.class, () -> RetentionPolicy.delete(0));
    assertThrows(ConfigurationException.class, () -> RetentionPolicy.delete(-1));
    assertEquals(RetentionMode.DELETE, RetentionPolicy.delete(1).mode().get());
  }

  @Test
  void ttlRequiresKeepCountOfAtLeastOneAndNonNegativeGrace() {
    assertThrows(ConfigurationException.class, () -> RetentionPolicy.ttl(0, 0));
    assertThrows(ConfigurationException.class, () -> RetentionPolicy.ttl(1, -1));
    assertEquals(RetentionMode.TTL, RetentionPolicy.ttl(1, 0).mode().get());
  }

  @Test
  void graceOfZeroExpiresAtTheMarkedTime() {
    assertEquals(BigInteger.valueOf(1000), RetentionPolicy.ttl(1, 0).expiresAtEpochSeconds(1000));
  }

  @Test
  void graceHasNoUpperBoundAndExpiryDoesNotOverflow() {
    BigInteger expected = BigInteger.valueOf(1000).add(BigInteger.valueOf(Long.MAX_VALUE));

    BigInteger actual = RetentionPolicy.ttl(1, Long.MAX_VALUE).expiresAtEpochSeconds(1000);

    assertEquals(expected, actual);
    assertEquals(1, actual.signum());
  }
}
