package com.github.j5ik2o.event.store.adapter.java.internal;

public class OptimisticLockException extends RuntimeException {
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
