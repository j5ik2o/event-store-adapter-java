package com.github.j5ik2o.event.store.adapter.java;

public abstract class EventStoreBaseException extends Exception {
  protected EventStoreBaseException() {
    super();
  }

  protected EventStoreBaseException(String message) {
    super(message);
  }

  protected EventStoreBaseException(String message, Throwable cause) {
    super(message, cause);
  }

  protected EventStoreBaseException(Throwable cause) {
    super(cause);
  }
}
