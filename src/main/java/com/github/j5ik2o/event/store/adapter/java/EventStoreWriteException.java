package com.github.j5ik2o.event.store.adapter.java;

public final class EventStoreWriteException extends Exception {
  public EventStoreWriteException() {
    super();
  }

  public EventStoreWriteException(String message) {
    super(message);
  }

  public EventStoreWriteException(String message, Throwable cause) {
    super(message, cause);
  }

  public EventStoreWriteException(Throwable cause) {
    super(cause);
  }
}
