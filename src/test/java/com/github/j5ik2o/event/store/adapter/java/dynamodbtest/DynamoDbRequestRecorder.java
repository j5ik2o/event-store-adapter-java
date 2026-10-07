package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttribute;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.http.SdkHttpRequest;

final class DynamoDbRequestRecorder implements ExecutionInterceptor {
  static final String REQUEST_HEADER = "x-eswa-test-request-id";
  private static final ExecutionAttribute<State> STATE =
      new ExecutionAttribute<>("eswa-test-state");

  static final class Request {
    final long id;
    final int operation;
    final String api;
    final String phase;
    final JsonNode original;
    final JsonNode marshalled;
    final JsonNode structure;
    final int httpAttempts;
    final int transmissions;
    final JsonNode transmitted;

    Request(State state) {
      id = state.id;
      operation = state.operation.number;
      api = state.api;
      phase = state.phase;
      original = state.original.deepCopy();
      marshalled = state.marshalled.deepCopy();
      structure = DynamoDbRequestStructure.parse(marshalled);
      httpAttempts = state.httpAttempts;
      transmissions = state.transmissions;
      transmitted = state.transmitted == null ? null : state.transmitted.deepCopy();
    }
  }

  private static final class State {
    long id;
    FaultRegistry.Operation operation;
    String api;
    String phase;
    JsonNode original;
    JsonNode marshalled;
    JsonNode transmitted;
    FaultRegistry.Selection selection;
    FaultRegistry.Selection continuation;
    int httpAttempts;
    int transmissions;
    final CompletableFuture<Void> termination = new CompletableFuture<>();
  }

  private final FaultRegistry faults;
  private final DynamoDbRequestTargets targets;
  private final Map<Long, State> states = new LinkedHashMap<>();
  private final Map<FaultRegistry.Operation, FaultRegistry.Selection> pages = new LinkedHashMap<>();
  private long nextId;

  DynamoDbRequestRecorder(FaultRegistry faults, DynamoDbRequestTargets targets) {
    this.faults = faults;
    this.targets = targets;
  }

  @Override
  public synchronized void beforeExecution(
      Context.BeforeExecution context, ExecutionAttributes attrs) {
    State state = new State();
    state.operation = faults.requestStarted();
    state.id = ++nextId;
    state.api = attrs.getAttribute(SdkExecutionAttribute.OPERATION_NAME);
    states.put(state.id, state);
    attrs.putAttribute(STATE, state);
  }

  @Override
  public synchronized SdkRequest modifyRequest(
      Context.ModifyRequest context, ExecutionAttributes attrs) {
    State state = attrs.getAttribute(STATE);
    state.original = DynamoDbJson.sdk(context.request());
    state.phase = targets.phase(state.api, state.original, state.operation);
    state.selection = faults.select(state.operation, state.phase);
    if (state.phase.equals("retention-query")) {
      FaultRegistry.Selection active = pages.get(state.operation);
      if (active != null && ((DynamoDbFaultEffects.HistoryPages) active.fault.effect).hasNext()) {
        if (state.selection != null) throw new IllegalStateException("Overlapping history plans");
        state.continuation = active;
      } else if (state.selection != null
          && state.selection.fault.effect instanceof DynamoDbFaultEffects.HistoryPages) {
        pages.put(state.operation, state.selection);
      }
    }
    return state.selection == null
        ? context.request()
        : state.selection.fault.effect.prepare(context.request());
  }

  @Override
  public void afterMarshalling(Context.AfterMarshalling context, ExecutionAttributes attrs) {
    State state = attrs.getAttribute(STATE);
    if (context.requestBody().isPresent()) {
      try (InputStream body = context.requestBody().get().contentStreamProvider().newStream()) {
        state.marshalled = DynamoDbJson.read(body.readAllBytes());
      } catch (IOException error) {
        throw new IllegalStateException("Cannot observe SDK request", error);
      }
    } else if (context.asyncRequestBody().isPresent()) {
      state.marshalled =
          DynamoDbJson.read(HttpBodies.collect(context.asyncRequestBody().get()).join());
    } else throw new IllegalStateException("Missing DynamoDB request body");
    DynamoDbRequestStructure.parse(state.marshalled); // Fail before sending unsupported structures.
  }

  @Override
  public SdkHttpRequest modifyHttpRequest(
      Context.ModifyHttpRequest context, ExecutionAttributes attrs) {
    return context.httpRequest().toBuilder()
        .putHeader(REQUEST_HEADER, Long.toString(attrs.getAttribute(STATE).id))
        .build();
  }

  @Override
  public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attrs) {
    State state = attrs.getAttribute(STATE);
    if (state.selection != null)
      state.selection.fault.effect.beforeTransmission(new Request(state));
    else if (state.continuation != null)
      state.continuation.fault.effect.beforeTransmission(new Request(state));
  }

  synchronized HttpReply replacement(SdkHttpRequest request) {
    State state = state(request);
    state.httpAttempts++;
    if (state.continuation != null
        && state.continuation.fault.injection == FaultRegistry.Injection.REPLACE_REQUEST)
      return state.continuation.fault.effect.reply(new Request(state));
    if (state.selection == null
        || state.selection.fault.injection != FaultRegistry.Injection.REPLACE_REQUEST) return null;
    HttpReply reply = state.selection.fault.effect.reply(new Request(state));
    if (reply != null) faults.applied(state.selection, state.id);
    return reply;
  }

  synchronized void transmitted(SdkHttpRequest request, byte[] body) {
    State state = state(request);
    state.transmissions++;
    state.transmitted = DynamoDbJson.read(body);
  }

  private State state(SdkHttpRequest request) {
    long id =
        Long.parseLong(
            request
                .firstMatchingHeader(REQUEST_HEADER)
                .orElseThrow(() -> new IllegalStateException("Missing request correlation")));
    State state = states.get(id);
    if (state == null) throw new IllegalStateException("Unknown request correlation");
    return state;
  }

  @Override
  public SdkResponse modifyResponse(Context.ModifyResponse context, ExecutionAttributes attrs) {
    State state = attrs.getAttribute(STATE);
    if (state.continuation != null
        && state.continuation.fault.injection == FaultRegistry.Injection.REPLACE_RESPONSE)
      return state.continuation.fault.effect.response(new Request(state), context.response());
    if (state.selection == null) return context.response();
    if (state.selection.fault.injection == FaultRegistry.Injection.REPLACE_RESPONSE) {
      // The response boundary was actually reached. An injected exception is also an application.
      faults.applied(state.selection, state.id);
      return state.selection.fault.effect.response(new Request(state), context.response());
    }
    SdkResponse response =
        state.selection.fault.effect.afterResponse(new Request(state), context.response());
    // Partial batches are applied only after their actual remainder completed.
    if (state.transmissions > 0) faults.applied(state.selection, state.id);
    return response;
  }

  private synchronized void terminal(ExecutionAttributes attrs) {
    State state = attrs.getAttribute(STATE);
    if (state != null && !state.termination.isDone()) {
      faults.release(state.selection);
      faults.requestFinished(state.operation);
      state.termination.complete(null);
    }
  }

  @Override
  public void afterExecution(Context.AfterExecution context, ExecutionAttributes attrs) {
    terminal(attrs);
  }

  @Override
  public void onExecutionFailure(Context.FailedExecution context, ExecutionAttributes attrs) {
    terminal(attrs);
  }

  synchronized List<Request> requests() {
    List<Request> result = new ArrayList<>();
    for (State state : states.values())
      if (state.marshalled != null) result.add(new Request(state));
    return result;
  }

  synchronized CompletableFuture<Void> requestsFinished(FaultRegistry.Operation operation) {
    return CompletableFuture.allOf(
        states.values().stream()
            .filter(state -> state.operation == operation)
            .map(state -> state.termination)
            .toArray(CompletableFuture<?>[]::new));
  }
}
