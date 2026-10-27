package com.github.j5ik2o.event.store.adapter.java.spanner;

import com.github.j5ik2o.event.store.adapter.java.core.AggregateId;
import com.github.j5ik2o.event.store.adapter.java.core.EventStoreException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import com.google.cloud.spanner.AbortedException;
import com.google.cloud.spanner.ErrorCode;
import com.google.cloud.spanner.Options;
import com.google.cloud.spanner.SpannerException;
import com.google.cloud.spanner.TransactionContext;
import com.google.cloud.spanner.TransactionManager;
import com.google.spanner.v1.TransactionOptions.IsolationLevel;
import javax.annotation.Nullable;

/** Runs a body in a SERIALIZABLE read-write transaction with a bounded retry of aborts. */
final class SpannerTransactions {
  private SpannerTransactions() {}

  /**
   * Work done inside one attempt. It must not notify or number anything outside the transaction.
   */
  interface Body<T> {
    T run(TransactionContext transaction);
  }

  /**
   * Runs the body once per attempt, each attempt in a fresh transaction.
   *
   * @param hookId aggregate passed to the commit hooks, or null to skip them
   */
  static <T> T runReadWrite(
      SpannerStorage storage, @Nullable AggregateId hookId, String failureMessage, Body<T> body) {
    int retries = 0;
    while (true) {
      boolean committed = false;
      T result;
      try (TransactionManager manager =
          storage.client.transactionManager(Options.isolationLevel(IsolationLevel.SERIALIZABLE))) {
        TransactionContext transaction = manager.begin();
        result = body.run(transaction);
        if (hookId != null) {
          storage.hooks.beforeCommit(hookId);
        }
        manager.commit();
        committed = true;
      } catch (EventStoreException failure) {
        throw failure;
      } catch (SpannerException failure) {
        if (failure.getErrorCode() == ErrorCode.ABORTED || failure instanceof AbortedException) {
          if (retries >= storage.config.transactionRetryLimit()) {
            throw new StorageException(
                failureMessage + ": transaction aborted more than the retry limit", failure);
          }
          retries++;
          continue;
        }
        throw new StorageException(failureMessage, failure);
      }
      if (committed && hookId != null) {
        try {
          storage.hooks.afterCommit(hookId);
        } catch (RuntimeException failure) {
          throw new StorageException(failureMessage + ": the commit outcome is unknown", failure);
        }
      }
      return result;
    }
  }
}
