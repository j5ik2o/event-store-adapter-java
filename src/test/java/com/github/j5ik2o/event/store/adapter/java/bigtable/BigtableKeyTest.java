package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.journalKey;
import static com.github.j5ik2o.event.store.adapter.java.bigtable.BigtableTestFixtures.prefix;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** BT-3 / BT-4: the row keys are byte-for-byte what the normative text says. */
class BigtableKeyTest {
  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void fnv1a64MatchesTheReferenceValues() {
    assertEquals(0xcbf29ce484222325L, BigtableKey.fnv1a64(new byte[0]));
    assertEquals(0xaf63dc4c8601ec8cL, BigtableKey.fnv1a64(utf8("a")));
    assertEquals(0x85944171f73967e8L, BigtableKey.fnv1a64(utf8("foobar")));
  }

  @Test
  void theConfigurationRowKeyIsTheSingleByteZero() {
    assertEquals(ByteString.copyFrom(new byte[] {0x00}), BigtableKey.CONFIG_ROW_KEY);
  }

  @Test
  void theHeadKeyOfOrderDash1IsHashThenLengthSevenThenTheUtf8Bytes() {
    long hash = BigtableKey.fnv1a64(utf8("Order-1"));
    ByteString expected =
        ByteString.copyFrom(
                new byte[] {
                  (byte) (hash >>> 56),
                  (byte) (hash >>> 48),
                  (byte) (hash >>> 40),
                  (byte) (hash >>> 32),
                  (byte) (hash >>> 24),
                  (byte) (hash >>> 16),
                  (byte) (hash >>> 8),
                  (byte) hash,
                  0x00,
                  0x07
                })
            .concat(ByteString.copyFromUtf8("Order-1"));

    assertEquals(expected, BigtableKey.prefix(hash, "Order-1"));
    assertEquals(expected, prefix("Order-1"));
  }

  @Test
  void theJournalKeyAppendsEightBigEndianBytesOfTheSequenceNumber() {
    ByteString p = BigtableKey.prefix(BigtableKey.fnv1a64(utf8("Order-1")), "Order-1");

    ByteString key = BigtableKey.journalRowKey(p, 256);

    assertEquals(p.concat(ByteString.copyFrom(new byte[] {0, 0, 0, 0, 0, 0, 1, 0})), key);
    assertEquals(journalKey("Order-1", 256), key);
  }

  @Test
  void theLengthIsTheUtf8ByteLengthNotTheCharacterCount() {
    String aid = "A-ユーザー";
    ByteString p = BigtableKey.prefix(BigtableKey.fnv1a64(utf8(aid)), aid);

    int length = utf8(aid).length;
    assertEquals((byte) (length >>> 8), p.byteAt(8));
    assertEquals((byte) length, p.byteAt(9));
    assertEquals(10 + length, p.size());
  }

  @Test
  void journalKeyOrderMatchesSequenceNumberOrderAcrossByteBoundaries() {
    ByteString p = prefix("Order-1");
    long[] seqs = {1, 2, 9, 10, 255, 256, 65_535, 65_536, 4_294_967_296L, (1L << 53) - 1};
    for (int i = 1; i < seqs.length; i++) {
      assertTrue(
          ByteString.unsignedLexicographicalComparator()
                  .compare(
                      BigtableKey.journalRowKey(p, seqs[i - 1]),
                      BigtableKey.journalRowKey(p, seqs[i]))
              < 0,
          seqs[i - 1] + " < " + seqs[i]);
    }
  }

  @Test
  void journalSeqNrReadsBackTheNumberOnlyForKeysOfTheSamePrefix() {
    ByteString p = prefix("Order-1");
    ByteString other = prefix("Order-2");

    assertEquals(
        OptionalLong.of(42), BigtableKey.journalSeqNr(p, BigtableKey.journalRowKey(p, 42)));
    assertEquals(
        OptionalLong.empty(), BigtableKey.journalSeqNr(p, BigtableKey.journalRowKey(other, 42)));
    assertEquals(OptionalLong.empty(), BigtableKey.journalSeqNr(p, p));
    assertEquals(
        OptionalLong.empty(),
        BigtableKey.journalSeqNr(
            p, BigtableKey.journalRowKey(p, 42).concat(ByteString.copyFrom(new byte[] {1}))));
  }

  @Test
  void theHeadKeyNeverEqualsTheConfigurationRowKey() {
    assertFalse(prefix("").equals(BigtableKey.CONFIG_ROW_KEY));
    assertTrue(prefix("").size() >= 10);
  }
}
