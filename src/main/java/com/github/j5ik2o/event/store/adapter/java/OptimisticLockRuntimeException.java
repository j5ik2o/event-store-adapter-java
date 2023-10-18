package com.github.j5ik2o.event.store.adapter.java;

public final class OptimisticLockRuntimeException extends EventStoreBaseRuntimeException {
  public OptimisticLockRuntimeException() {
    super();
  }

  public OptimisticLockRuntimeException(String message) {
    super(message);
  }

  public OptimisticLockRuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  public OptimisticLockRuntimeException(Throwable cause) {
    super(cause);
  }
}
