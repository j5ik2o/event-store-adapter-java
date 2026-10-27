package com.github.j5ik2o.event.store.adapter.java.bigtable;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

/**
 * Row keys (BT-3, BT-4). {@code P(aid) = H(aid) || L16(len(A)) || A}; the journal key appends the
 * 8-byte big-endian seq_nr. / 行キー。{@code P(aid)} はハッシュ、16 ビット長、aid の UTF-8 の順で、journal の行キーは その後ろに
 * 8 バイト big-endian の番号を付けます。
 */
final class BigtableKey {
  /** The configuration row key is the single byte 0x00. */
  static final ByteString CONFIG_ROW_KEY = ByteString.copyFrom(new byte[] {0x00});

  private BigtableKey() {}

  /** FNV-1a 64 over the bytes. */
  static long fnv1a64(byte[] bytes) {
    long hash = 0xcbf29ce484222325L;
    for (byte b : bytes) {
      hash ^= (b & 0xffL);
      hash *= 0x100000001b3L;
    }
    return hash;
  }

  static ByteString prefix(long hash, String aid) {
    byte[] a = aid.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[8 + 2 + a.length];
    for (int i = 0; i < 8; i++) {
      out[i] = (byte) (hash >>> (56 - 8 * i));
    }
    out[8] = (byte) (a.length >>> 8);
    out[9] = (byte) a.length;
    System.arraycopy(a, 0, out, 10, a.length);
    return ByteString.copyFrom(out);
  }

  static ByteString journalRowKey(ByteString prefix, long seqNr) {
    byte[] n = new byte[8];
    for (int i = 0; i < 8; i++) {
      n[i] = (byte) (seqNr >>> (56 - 8 * i));
    }
    return prefix.concat(ByteString.copyFrom(n));
  }

  /** The seq_nr of a journal row key of this prefix, or empty for any other key. */
  static OptionalLong journalSeqNr(ByteString prefix, ByteString key) {
    if (key.size() != prefix.size() + 8 || !key.startsWith(prefix)) {
      return OptionalLong.empty();
    }
    long value = 0;
    for (int i = prefix.size(); i < key.size(); i++) {
      value = (value << 8) | (key.byteAt(i) & 0xffL);
    }
    return OptionalLong.of(value);
  }
}
