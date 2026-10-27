package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** The primary key prefix of an aggregate. / 集約の主キーの前半。 */
final class SpannerKey {
  private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
  private static final long PRIME = 0x100000001b3L;

  private final byte[] hashPrefix;
  private final String aid;

  private SpannerKey(byte[] hashPrefix, String aid) {
    this.hashPrefix = hashPrefix;
    this.aid = aid;
  }

  static SpannerKey of(AggregateId id) {
    String aid = id.asString();
    return new SpannerKey(hashPrefix(aid), aid);
  }

  /** FNV-1a 64 of the UTF-8 bytes as 8 big-endian bytes, unsigned and never reduced. */
  static byte[] hashPrefix(String aid) {
    long hash = OFFSET_BASIS;
    for (byte b : aid.getBytes(StandardCharsets.UTF_8)) {
      hash ^= (b & 0xffL);
      hash *= PRIME;
    }
    return ByteBuffer.allocate(8).putLong(hash).array();
  }

  byte[] hashPrefix() {
    return hashPrefix.clone();
  }

  String aid() {
    return aid;
  }
}
