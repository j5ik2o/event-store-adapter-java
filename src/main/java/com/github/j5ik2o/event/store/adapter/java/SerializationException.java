package com.github.j5ik2o.event.store.adapter.java;

public final class SerializationException extends EventStoreBaseException {
  public SerializationException() {
    super();
  }

  public SerializationException(String message) {
    super(message);
  }

  public SerializationException(String message, Throwable cause) {
    super(message, cause);
  }

  public SerializationException(Throwable cause) {
    super(cause);
  }
}
