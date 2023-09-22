package com.github.j5ik2o.event.store.adapter.java;

public final class DeserializationException extends EventStoreBaseException {
  public DeserializationException() {
    super();
  }

  public DeserializationException(String message) {
    super(message);
  }

  public DeserializationException(String message, Throwable cause) {
    super(message, cause);
  }

  public DeserializationException(Throwable cause) {
    super(cause);
  }
}
