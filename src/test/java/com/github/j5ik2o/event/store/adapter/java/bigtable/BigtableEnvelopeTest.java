package com.github.j5ik2o.event.store.adapter.java.bigtable;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * BT-D6a / ADR-0008: the fixed binary envelope. The hex values below are copied from the
 * distributed conformance data (values/bigtable-envelope.json) and act as the oracle.
 */
class BigtableEnvelopeTest {
  private static byte[] hex(String s) {
    byte[] out = new byte[s.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }

  @Test
  void anEmptyEventEnvelopeIsTwentySevenBytePlusTheAidAndMatchesTheReference() {
    byte[] expected = hex("0100074f726465722d39000000000000000100000000000000000000000000000000");

    byte[] actual = BigtableEnvelope.encodeEvent("Order-9", 1, 0L, "", new byte[0]);

    assertArrayEquals(expected, actual);
    assertEquals(27 + "Order-9".length(), actual.length);
  }

  @Test
  void anEventWithManifestAndBinaryPayloadMatchesTheReference() {
    byte[] expected =
        hex("0100074f726465722d390000000000000002000000003b9aca000000000276310000000600010203feff");

    byte[] actual =
        BigtableEnvelope.encodeEvent("Order-9", 2, 1_000_000_000L, "v1", hex("00010203feff"));

    assertArrayEquals(expected, actual);
  }

  @Test
  void aMultibyteAidAndTheMaximumSequenceNumberAndNegativeTimeMatchTheReference() {
    byte[] expected =
        hex("010008e6b3a8e696872d31001fffffffffffffffffffffffffffff00000002c3a900000001ff");

    byte[] actual =
        BigtableEnvelope.encodeEvent("注文-1", 9_007_199_254_740_991L, -1L, "é", hex("ff"));

    assertArrayEquals(expected, actual);
  }

  @Test
  void theTimeExtremesAreSignedBigEndianTwosComplement() {
    assertArrayEquals(
        hex("0100074f726465722d39000000000000000180000000000000000000000000000000"),
        BigtableEnvelope.encodeEvent("Order-9", 1, Long.MIN_VALUE, "", new byte[0]));
    assertArrayEquals(
        hex("0100074f726465722d3900000000000000017fffffffffffffff0000000000000000"),
        BigtableEnvelope.encodeEvent("Order-9", 1, Long.MAX_VALUE, "", new byte[0]));
  }

  @Test
  void snapshotEnvelopesAreSeventeenBytesWhenEmptyAndMatchTheReference() {
    byte[] empty = BigtableEnvelope.encodeSnapshot(1, "", new byte[0]);
    byte[] utf8 =
        BigtableEnvelope.encodeSnapshot(
            9_007_199_254_740_991L, "スナップ", "{}".getBytes(StandardCharsets.UTF_8));

    assertArrayEquals(hex("0100000000000000010000000000000000"), empty);
    assertEquals(17, empty.length);
    assertArrayEquals(hex("01001fffffffffffff0000000ce382b9e3838ae38383e38397000000027b7d"), utf8);
  }

  @Test
  void decodingARoundTripRestoresEveryFieldOfAnEvent() {
    byte[] bytes = BigtableEnvelope.encodeEvent("注文-1", 77, -123L, "é", hex("00ff"));

    BigtableEnvelope.Event decoded = BigtableEnvelope.decodeEvent(bytes);

    assertEquals("注文-1", decoded.aid());
    assertEquals(77, decoded.seqNr());
    assertEquals(-123L, decoded.occurredAtNs());
    assertEquals("é", decoded.manifest());
    assertArrayEquals(hex("00ff"), decoded.payload());
  }

  @Test
  void decodingARoundTripRestoresEveryFieldOfASnapshot() {
    byte[] bytes = BigtableEnvelope.encodeSnapshot(5, "スナップ", hex("0102"));

    BigtableEnvelope.Snapshot decoded = BigtableEnvelope.decodeSnapshot(bytes);

    assertEquals(5, decoded.seqNr());
    assertEquals("スナップ", decoded.manifest());
    assertArrayEquals(hex("0102"), decoded.payload());
  }

  @Test
  void unusedTrailingBytesAreAStorageError() {
    assertThrows(
        StorageException.class,
        () ->
            BigtableEnvelope.decodeEvent(
                hex("0100074f726465722d3900000000000000010000000000000000000000000000000000")));
    assertThrows(
        StorageException.class,
        () -> BigtableEnvelope.decodeSnapshot(hex("010000000000000001000000000000000000")));
  }

  @Test
  void aPayloadLengthFieldThatDisagreesWithTheActualBytesIsAStorageError() {
    assertThrows(
        StorageException.class,
        () ->
            BigtableEnvelope.decodeEvent(
                hex(
                    "0100074f726465722d39000000000000000100000000000000000000000000000005"
                        + "00000000")));
  }

  @Test
  void anUnknownFormatVersionIsAStorageError() {
    assertThrows(
        StorageException.class,
        () ->
            BigtableEnvelope.decodeEvent(
                hex("0200074f726465722d39000000000000000100000000000000000000000000000000")));
    assertThrows(
        StorageException.class,
        () -> BigtableEnvelope.decodeSnapshot(hex("0200000000000000010000000000000000")));
  }

  @Test
  void truncatedAndEmptyInputsAreStorageErrorsNotIndexErrors() {
    byte[] full = hex("0100074f726465722d39000000000000000100000000000000000000000000000000");
    for (int cut = 0; cut < full.length; cut++) {
      byte[] truncated = java.util.Arrays.copyOf(full, cut);
      assertThrows(
          StorageException.class, () -> BigtableEnvelope.decodeEvent(truncated), "cut=" + cut);
    }
  }

  @Test
  void anInvalidUtf8AidOrManifestIsAStorageError() {
    // aid length 1, byte 0xff is not valid UTF-8
    assertThrows(
        StorageException.class,
        () ->
            BigtableEnvelope.decodeEvent(
                hex(
                    "010001ff"
                        + "0000000000000001"
                        + "0000000000000000"
                        + "00000000"
                        + "00000000")));
    // manifest length 1, byte 0xc3 alone is a truncated UTF-8 sequence
    assertThrows(
        StorageException.class,
        () ->
            BigtableEnvelope.decodeSnapshot(
                hex("01" + "0000000000000001" + "00000001c3" + "00000000")));
  }

  @Test
  void epochNanosecondsKeepsNanosecondPrecisionAtTheTimeExtremes() {
    assertEquals(0L, BigtableEnvelope.epochNanoseconds(Instant.EPOCH));
    assertEquals(
        1_000_000_000L + 123_456_789L,
        BigtableEnvelope.epochNanoseconds(Instant.ofEpochSecond(1, 123_456_789)));
    assertEquals(-1L, BigtableEnvelope.epochNanoseconds(Instant.ofEpochSecond(-1, 999_999_999)));
    assertEquals(
        Long.MIN_VALUE,
        BigtableEnvelope.epochNanoseconds(Instant.ofEpochSecond(-9_223_372_037L, 145_224_192)));
  }
}
