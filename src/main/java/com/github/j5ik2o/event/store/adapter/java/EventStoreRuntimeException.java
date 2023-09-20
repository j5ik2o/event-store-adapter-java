package com.github.j5ik2o.event.store.adapter.java;

public final class EventStoreRuntimeException extends RuntimeException {
  public EventStoreRuntimeException() {
    super();
  }

  public EventStoreRuntimeException(String message) {
    super(message);
  }

  public EventStoreRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  public EventStoreRuntimeException(Throwable cause) {
    super(cause);
  }
}
