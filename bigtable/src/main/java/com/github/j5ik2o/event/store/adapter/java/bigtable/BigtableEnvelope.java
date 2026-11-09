package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * The fixed binary envelope (BT-D6a, ADR-0008). All integers are big-endian.
 *
 * <pre>
 * event    = 0x01 || U16 len(aid) || aid || U64 seq_nr || I64 occurred_at_ns
 *            || U32 len(manifest) || manifest || U32 len(payload) || payload
 * snapshot = 0x01 || U64 seq_nr || U32 len(manifest) || manifest || U32 len(payload) || payload
 * </pre>
 */
final class BigtableEnvelope {
  private static final byte VERSION = 0x01;
  private static final int EVENT_FIXED = 27;
  private static final int SNAPSHOT_FIXED = 17;

  private BigtableEnvelope() {}

  /** A decoded event envelope. */
  static final class Event {
    private final String aid;
    private final long seqNr;
    private final long occurredAtNs;
    private final String manifest;
    private final byte[] payload;

    Event(String aid, long seqNr, long occurredAtNs, String manifest, byte[] payload) {
      this.aid = aid;
      this.seqNr = seqNr;
      this.occurredAtNs = occurredAtNs;
      this.manifest = manifest;
      this.payload = payload;
    }

    String aid() {
      return aid;
    }

    long seqNr() {
      return seqNr;
    }

    long occurredAtNs() {
      return occurredAtNs;
    }

    String manifest() {
      return manifest;
    }

    byte[] payload() {
      return payload;
    }
  }

  /** A decoded snapshot envelope. */
  static final class Snapshot {
    private final long seqNr;
    private final String manifest;
    private final byte[] payload;

    Snapshot(long seqNr, String manifest, byte[] payload) {
      this.seqNr = seqNr;
      this.manifest = manifest;
      this.payload = payload;
    }

    long seqNr() {
      return seqNr;
    }

    String manifest() {
      return manifest;
    }

    byte[] payload() {
      return payload;
    }
  }

  static long eventLength(String aid, String manifest, int payloadLength) {
    return (long) EVENT_FIXED + utf8(aid).length + utf8(manifest).length + payloadLength;
  }

  static long snapshotLength(String manifest, int payloadLength) {
    return (long) SNAPSHOT_FIXED + utf8(manifest).length + payloadLength;
  }

  static byte[] encodeEvent(
      String aid, long seqNr, long occurredAtNs, String manifest, byte[] payload) {
    byte[] a = utf8(aid);
    byte[] m = utf8(manifest);
    ByteBuffer out = ByteBuffer.allocate(EVENT_FIXED + a.length + m.length + payload.length);
    out.put(VERSION).putShort((short) a.length).put(a).putLong(seqNr).putLong(occurredAtNs);
    out.putInt(m.length).put(m).putInt(payload.length).put(payload);
    return out.array();
  }

  static byte[] encodeSnapshot(long seqNr, String manifest, byte[] payload) {
    byte[] m = utf8(manifest);
    ByteBuffer out = ByteBuffer.allocate(SNAPSHOT_FIXED + m.length + payload.length);
    out.put(VERSION).putLong(seqNr).putInt(m.length).put(m).putInt(payload.length).put(payload);
    return out.array();
  }

  static Event decodeEvent(byte[] bytes) {
    ByteBuffer in = ByteBuffer.wrap(bytes);
    version(in);
    String aid = text(in, unsignedShort(in), "aid");
    long seqNr = in.remaining() >= 8 ? in.getLong() : truncated();
    long occurredAtNs = in.remaining() >= 8 ? in.getLong() : truncated();
    String manifest = text(in, length(in), "manifest");
    byte[] payload = bytes(in, length(in));
    finish(in);
    if (seqNr < 0) {
      throw invalid("the event seq_nr is out of range");
    }
    return new Event(aid, seqNr, occurredAtNs, manifest, payload);
  }

  static Snapshot decodeSnapshot(byte[] bytes) {
    ByteBuffer in = ByteBuffer.wrap(bytes);
    version(in);
    long seqNr = in.remaining() >= 8 ? in.getLong() : truncated();
    String manifest = text(in, length(in), "manifest");
    byte[] payload = bytes(in, length(in));
    finish(in);
    if (seqNr < 0) {
      throw invalid("the snapshot seq_nr is out of range");
    }
    return new Snapshot(seqNr, manifest, payload);
  }

  /** Nanoseconds since the epoch, exact for the instants the common contract admits. */
  static long epochNanoseconds(Instant instant) {
    long seconds = instant.getEpochSecond();
    return seconds >= 0
        ? Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), instant.getNano())
        : Math.addExact(
            Math.multiplyExact(seconds + 1, 1_000_000_000L), instant.getNano() - 1_000_000_000L);
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static void version(ByteBuffer in) {
    if (!in.hasRemaining()) {
      truncated();
    }
    byte version = in.get();
    if (version != VERSION) {
      throw invalid("unknown envelope format version: " + (version & 0xff));
    }
  }

  private static int unsignedShort(ByteBuffer in) {
    if (in.remaining() < 2) {
      truncated();
    }
    return in.getShort() & 0xffff;
  }

  private static int length(ByteBuffer in) {
    if (in.remaining() < 4) {
      truncated();
    }
    int length = in.getInt();
    if (length < 0) {
      throw invalid("an envelope length field is out of range");
    }
    return length;
  }

  private static byte[] bytes(ByteBuffer in, int length) {
    if (in.remaining() < length) {
      truncated();
    }
    byte[] out = new byte[length];
    in.get(out);
    return out;
  }

  private static String text(ByteBuffer in, int length, String field) {
    byte[] raw = bytes(in, length);
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(raw))
          .toString();
    } catch (CharacterCodingException failure) {
      throw new StorageException("the envelope " + field + " is not valid UTF-8", failure);
    }
  }

  private static void finish(ByteBuffer in) {
    if (in.hasRemaining()) {
      throw invalid("the envelope has unused trailing bytes");
    }
  }

  private static long truncated() {
    throw invalid("the envelope is shorter than its length fields say");
  }

  private static StorageException invalid(String detail) {
    return new StorageException("invalid bigtable envelope: " + detail);
  }
}
