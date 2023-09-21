package com.github.j5ik2o.event.store.adapter.java;

public abstract class EventStoreBaseRuntimeException extends RuntimeException {
  protected EventStoreBaseRuntimeException() {
    super();
  }

  protected EventStoreBaseRuntimeException(String message) {
    super(message);
  }

  protected EventStoreBaseRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  protected EventStoreBaseRuntimeException(Throwable cause) {
    super(cause);
  }
}
