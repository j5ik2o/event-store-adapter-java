package com.github.j5ik2o.event.store.adapter.java;

public final class TransactionRuntimeException extends EventStoreBaseRuntimeException {
  public TransactionRuntimeException() {
    super();
  }

  public TransactionRuntimeException(String message) {
    super(message);
  }

  public TransactionRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  public TransactionRuntimeException(Throwable cause) {
    super(cause);
  }
}
