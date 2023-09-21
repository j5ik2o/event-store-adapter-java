package com.github.j5ik2o.event.store.adapter.java;

public final class EventStoreWriteRuntimeException extends EventStoreBaseRuntimeException {
  public EventStoreWriteRuntimeException() {
    super();
  }

  public EventStoreWriteRuntimeException(String message) {
    super(message);
  }

  public EventStoreWriteRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  public EventStoreWriteRuntimeException(Throwable cause) {
    super(cause);
  }
}
