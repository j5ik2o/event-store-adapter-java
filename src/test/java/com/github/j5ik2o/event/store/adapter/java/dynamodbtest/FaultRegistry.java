package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;

/** Scenario-local faults. Selection reserves a slot; only a boundary effect records consumption. */
final class FaultRegistry {
  enum Injection {
    REPLACE_REQUEST,
    REPLACE_RESPONSE
  }

  interface Effect {
    default boolean supports(Injection injection) {
      return false;
    }

    default SdkRequest prepare(SdkRequest request, Injection injection) {
      return request;
    }

    default void beforeTransmission(DynamoDbRequestRecorder.Request request) {}

    default HttpReply reply(DynamoDbRequestRecorder.Request request) {
      throw new IllegalStateException("No request replacement implementation");
    }

    default SdkResponse response(DynamoDbRequestRecorder.Request request, SdkResponse response) {
      throw new IllegalStateException("No response replacement implementation");
    }

    default SdkResponse afterResponse(
        DynamoDbRequestRecorder.Request request, SdkResponse response) {
      return response;
    }

    default RuntimeException serializationError() {
      throw new IllegalStateException("No serializer replacement implementation");
    }
  }

  static final class Fault {
    final int operation;
    final String phase;
    final int count; // -1 is until-operation-finishes
    final Injection injection;
    final Effect effect;
    final String unsupportedReason;
    private final List<Long> applications = new ArrayList<>();
    private int reservations;

    private Fault(
        int operation,
        String phase,
        int count,
        Injection injection,
        Effect effect,
        String unsupportedReason) {
      if (operation < 0 || (count < 1 && count != -1)) {
        throw new IllegalArgumentException("Invalid operation or repeat count");
      }
      this.operation = operation;
      this.phase = Objects.requireNonNull(phase);
      this.count = count;
      this.injection = injection;
      this.effect = effect;
      this.unsupportedReason = unsupportedReason;
    }
  }

  static final class Selection {
    final Fault fault;
    private boolean applied;
    private boolean released;

    private Selection(Fault fault) {
      this.fault = fault;
    }
  }

  static final class Operation {
    final int number;
    final boolean writing;
    boolean transactionSeen;
    private int pending;

    private Operation(int number, boolean writing) {
      this.number = number;
      this.writing = writing;
    }
  }

  static final class Result {
    final String status;
    final List<String> reasons;

    private Result(String status, List<String> reasons) {
      this.status = status;
      this.reasons = List.copyOf(reasons);
    }
  }

  private final List<Fault> faults = new ArrayList<>();
  private final Set<Integer> completed = new HashSet<>();
  private Operation current;

  synchronized Fault register(
      int operation, String phase, int count, Injection injection, Effect effect) {
    Fault fault =
        new Fault(
            operation,
            phase,
            count,
            Objects.requireNonNull(injection),
            Objects.requireNonNull(effect),
            effect.supports(injection) ? null : "Effect does not implement " + injection);
    add(fault);
    return fault;
  }

  synchronized void unsupported(int operation, String phase, String reason) {
    if (reason == null || reason.isBlank())
      throw new IllegalArgumentException("Reason is required");
    add(new Fault(operation, phase, 1, null, null, reason));
  }

  private void add(Fault fault) {
    if (completed.contains(fault.operation)
        || (current != null && current.number == fault.operation)) {
      throw new IllegalStateException("Register faults before starting the operation");
    }
    faults.add(fault);
  }

  synchronized Operation begin(int number, boolean writing) {
    if (number < 0 || current != null || completed.contains(number)) {
      throw new IllegalStateException("Operation already active or completed");
    }
    current = new Operation(number, writing);
    return current;
  }

  synchronized Operation requestStarted() {
    if (current == null) throw new IllegalStateException("No operation active");
    current.pending++;
    return current;
  }

  synchronized Operation operation() {
    if (current == null) throw new IllegalStateException("No operation active");
    return current;
  }

  synchronized void requestFinished(Operation operation) {
    if (operation.pending <= 0) throw new IllegalStateException("Request already finished");
    operation.pending--;
  }

  synchronized Selection select(Operation operation, String phase) {
    if (operation != current) throw new IllegalStateException("Operation is not active");
    for (Fault fault : faults) {
      if (fault.operation == operation.number
          && fault.phase.equals(phase)
          && fault.unsupportedReason == null
          && (fault.count == -1 || fault.applications.size() + fault.reservations < fault.count)) {
        fault.reservations++;
        return new Selection(fault);
      }
    }
    return null;
  }

  synchronized void applied(Selection selection, long requestId) {
    if (selection.applied || selection.released)
      throw new IllegalStateException("Fault selection already completed");
    selection.applied = true;
    selection.fault.reservations--;
    selection.fault.applications.add(requestId);
  }

  synchronized void release(Selection selection) {
    if (selection != null && !selection.applied && !selection.released) {
      selection.released = true;
      selection.fault.reservations--;
    }
  }

  synchronized boolean isApplied(Selection selection) {
    return selection.applied;
  }

  synchronized int applications(Fault fault) {
    return fault.applications.size();
  }

  synchronized int reservations(Fault fault) {
    return fault.reservations;
  }

  synchronized int pending(Operation operation) {
    return operation.pending;
  }

  synchronized Result finish(Operation operation) {
    if (operation != current || operation.pending != 0) {
      throw new IllegalStateException("Operation is not ready to finish");
    }
    List<String> failures = new ArrayList<>();
    List<String> unsupported = new ArrayList<>();
    for (Fault fault : faults) {
      if (fault.operation != operation.number) continue;
      if (fault.unsupportedReason != null)
        unsupported.add(fault.phase + ": " + fault.unsupportedReason);
      else if (fault.applications.isEmpty()) failures.add("Fault did not fire: " + fault.phase);
      else if (fault.count != -1 && fault.applications.size() != fault.count)
        failures.add(
            "Fault applied "
                + fault.applications.size()
                + " of "
                + fault.count
                + " times: "
                + fault.phase);
    }
    completed.add(operation.number);
    current = null;
    if (!failures.isEmpty()) return new Result("failed", failures);
    if (!unsupported.isEmpty()) return new Result("unverified", unsupported);
    return new Result("passed", List.of());
  }
}
