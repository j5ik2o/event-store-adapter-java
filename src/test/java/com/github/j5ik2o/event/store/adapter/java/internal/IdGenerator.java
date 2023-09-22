package com.github.j5ik2o.event.store.adapter.java.internal;

import de.huxhorn.sulky.ulid.ULID;
import javax.annotation.Nonnull;

public final class IdGenerator {
  private static final ULID ulid = new ULID();
  private static ULID.Value prevValue = ulid.nextValue();

  @Nonnull
  public static synchronized ULID.Value generate() {
    var result = ulid.nextMonotonicValue(prevValue);
    if (result == null) {
      throw new IllegalStateException("ULID overflow");
    }
    prevValue = result;
    return result;
  }
}
