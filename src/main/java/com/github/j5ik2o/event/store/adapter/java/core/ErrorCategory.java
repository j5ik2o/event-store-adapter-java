package com.github.j5ik2o.event.store.adapter.java.core;

/** Error categories. / エラーの分類。 */
public enum ErrorCategory {
  OPTIMISTIC_LOCK,
  CONTRACT_VIOLATION,
  SERIALIZATION,
  CONFIGURATION,
  STORAGE
}
