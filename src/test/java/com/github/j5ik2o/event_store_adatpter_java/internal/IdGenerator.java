package com.github.j5ik2o.event_store_adatpter_java.internal;

import de.huxhorn.sulky.ulid.ULID;

public final class IdGenerator {
  private static final ULID ulid = new ULID();
  private static ULID.Value prevValue;

  public static synchronized ULID.Value generate() {
    if (prevValue == null) {
      prevValue = ulid.nextValue();
    } else {
      prevValue = ulid.nextMonotonicValue(prevValue);
    }
    return prevValue;
  }
}
