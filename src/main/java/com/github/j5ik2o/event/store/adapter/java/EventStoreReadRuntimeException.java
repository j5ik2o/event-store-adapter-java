package com.github.j5ik2o.event.store.adapter.java;

public final class EventStoreReadRuntimeException extends EventStoreBaseRuntimeException {
  public EventStoreReadRuntimeException() {
    super();
  }

  public EventStoreReadRuntimeException(String message) {
    super(message);
  }

  public EventStoreReadRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  public EventStoreReadRuntimeException(Throwable cause) {
    super(cause);
  }
}
