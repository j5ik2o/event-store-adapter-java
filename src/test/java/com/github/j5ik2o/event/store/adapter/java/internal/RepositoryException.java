package com.github.j5ik2o.event.store.adapter.java.internal;

public final class RepositoryException extends RuntimeException {
  public RepositoryException() {
    super();
  }

  public RepositoryException(String message) {
    super(message);
  }

  public RepositoryException(String message, Throwable cause) {
    super(message, cause);
  }

  public RepositoryException(Throwable cause) {
    super(cause);
  }
}
