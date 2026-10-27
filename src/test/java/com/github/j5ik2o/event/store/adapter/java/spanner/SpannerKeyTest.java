package com.github.j5ik2o.event.store.adapter.java.spanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** The four reference values of the shared hash specification (K-1). */
class SpannerKeyTest {
  private static byte[] eightBytes(String hex) {
    byte[] result = new byte[8];
    byte[] source = new BigInteger(hex, 16).toByteArray();
    // BigInteger may add a leading zero byte or drop leading zeros; right-align it.
    int length = Math.min(source.length, 8);
    System.arraycopy(source, source.length - length, result, 8 - length, length);
    return result;
  }

  @Test
  void hashPrefixMatchesTheSpecificationReferenceValuesAsEightBigEndianBytes() {
    assertArrayEquals(eightBytes("af63dc4c8601ec8c"), SpannerKey.hashPrefix("a"));
    assertArrayEquals(
        eightBytes("adb103af5fe2a91a"), SpannerKey.hashPrefix("01H42K4ABWQ5V2XQEP3A48VE0Z"));
    // The leading zero byte must be kept: 0x0aa990285e0388ca is not shortened or sign-converted.
    assertArrayEquals(
        eightBytes("0aa990285e0388ca"), SpannerKey.hashPrefix("01H42KBHCW1BZG504J4ZXKA2F2"));
    assertArrayEquals(eightBytes("309f20171820053e"), SpannerKey.hashPrefix("ユーザー"));
  }

  @Test
  void hashPrefixIsAlwaysEightBytes() {
    assertEquals(8, SpannerKey.hashPrefix("01H42KBHCW1BZG504J4ZXKA2F2").length);
    assertEquals(8, SpannerKey.hashPrefix("a").length);
  }

  @Test
  void keyOfAnAggregateUsesTheAidTextAndItsHash() {
    SpannerKey key = SpannerKey.of(AggregateId.of("Order", "1"));

    assertEquals("Order-1", key.aid());
    assertArrayEquals(SpannerKey.hashPrefix("Order-1"), key.hashPrefix());
  }

  @Test
  void differentAggregatesAreDifferentKeysEvenIfTheyShareAHashPrefix() {
    SpannerKey one = SpannerKey.of(AggregateId.of("Order", "1"));
    SpannerKey two = SpannerKey.of(AggregateId.of("Order", "2"));

    assertNotEquals(one.aid(), two.aid());
  }
}
