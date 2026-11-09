package com.github.j5ik2o.event.store.adapter.java.core;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.OptionalLong;

/** Checks shared by all core types. / 中核の型が共有する検査。 */
final class Validation {

  static final long MAX_SEQ_NR = (1L << 53) - 1;
  static final int MAX_AID_BYTES = 1024;
  static final Instant MIN_OCCURRED_AT = Instant.ofEpochSecond(0, Long.MIN_VALUE);
  static final Instant MAX_OCCURRED_AT = Instant.ofEpochSecond(0, Long.MAX_VALUE);

  enum Context {
    VALUE,
    EVENT
  }

  private Validation() {}

  static void checkSeqNr(long seqNr, Context context) {
    if (seqNr < 0 || seqNr > MAX_SEQ_NR) {
      throw new ContractViolationException(
          "T-9", OptionalLong.of(seqNr), "seq_nr must be between 0 and " + MAX_SEQ_NR);
    }
    if (context == Context.EVENT && seqNr == 0) {
      throw new ContractViolationException(
          "W-6", OptionalLong.of(seqNr), "seq_nr of an event must be 1 or greater");
    }
  }

  static void checkOccurredAt(Instant occurredAt, long seqNr) {
    if (occurredAt.isBefore(MIN_OCCURRED_AT) || occurredAt.isAfter(MAX_OCCURRED_AT)) {
      throw new ContractViolationException(
          "T-13",
          OptionalLong.of(seqNr),
          "occurred_at is out of range of signed 64-bit epoch nanoseconds: " + occurredAt);
    }
  }

  static void checkAidString(String typeName, String value) {
    if (typeName.indexOf('-') >= 0) {
      throw new ContractViolationException(
          "T-11", OptionalLong.empty(), "type name must not contain '-': " + typeName);
    }
    int bytes =
        typeName.getBytes(StandardCharsets.UTF_8).length
            + 1
            + value.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > MAX_AID_BYTES) {
      throw new ContractViolationException(
          "T-12",
          OptionalLong.empty(),
          "aid must be at most " + MAX_AID_BYTES + " bytes in UTF-8: " + bytes);
    }
  }

  static void checkSnapshotSeqNr(long eventSeqNr, long snapshotSeqNr) {
    if (eventSeqNr != snapshotSeqNr) {
      throw new ContractViolationException(
          "W-9",
          OptionalLong.of(eventSeqNr),
          "snapshot seq_nr " + snapshotSeqNr + " differs from event seq_nr " + eventSeqNr);
    }
  }
}
