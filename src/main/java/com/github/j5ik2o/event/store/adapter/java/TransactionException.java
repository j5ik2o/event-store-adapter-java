package com.github.j5ik2o.event.store.adapter.java;

public final class TransactionException extends EventStoreBaseException {
  public TransactionException() {
    super();
  }

  public TransactionException(String message) {
    super(message);
  }

  public TransactionException(String message, Throwable cause) {
    super(message, cause);
  }

  public TransactionException(Throwable cause) {
    super(cause);
  }
}
