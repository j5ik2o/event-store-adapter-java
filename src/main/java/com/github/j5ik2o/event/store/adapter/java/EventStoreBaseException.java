package com.github.j5ik2o.event.store.adapter.java;

public abstract class EventStoreBaseException extends Exception {
  public EventStoreBaseException() {
    super();
  }

  public EventStoreBaseException(String message) {
    super(message);
  }

  public EventStoreBaseException(String message, Throwable cause) {
    super(message, cause);
  }

  public EventStoreBaseException(Throwable cause) {
    super(cause);
  }
}
