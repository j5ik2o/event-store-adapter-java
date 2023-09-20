package com.github.j5ik2o.event.store.adapter.java;

public final class EventStoreReadException extends Exception {
  public EventStoreReadException() {
    super();
  }

  public EventStoreReadException(String message) {
    super(message);
  }

  public EventStoreReadException(String message, Throwable cause) {
    super(message, cause);
  }

  public EventStoreReadException(Throwable cause) {
    super(cause);
  }
}
