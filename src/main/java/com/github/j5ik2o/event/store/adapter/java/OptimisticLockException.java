package com.github.j5ik2o.event.store.adapter.java;

public final class OptimisticLockException extends EventStoreBaseException {
  public OptimisticLockException() {
    super();
  }

  public OptimisticLockException(String message) {
    super(message);
  }

  public OptimisticLockException(String message, Throwable cause) {
    super(message, cause);
  }

  public OptimisticLockException(Throwable cause) {
    super(cause);
  }
}
