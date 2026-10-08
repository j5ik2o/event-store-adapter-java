package com.github.j5ik2o.event.store.adapter.java.dynamodbtest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FaultRegistryTest {
  @Test
  void finiteCountShortfallFailsEvenWithAnUnappliedReservationAndUnsupportedFault() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Fault fault =
        registry.register(
            1,
            "read-events",
            2,
            FaultRegistry.Injection.REPLACE_RESPONSE,
            DynamoDbFaultEffects.response(value -> value));
    registry.unsupported(1, "deserialize-event", "Serializer hook is not connected");
    FaultRegistry.Operation operation = registry.begin(1, false);
    registry.applied(registry.select(operation, "read-events"), 11);
    FaultRegistry.Selection reserved = registry.select(operation, "read-events");
    assertEquals(1, registry.applications(fault));
    registry.release(reserved);
    assertEquals(1, registry.applications(fault));
    FaultRegistry.Result result = registry.finish(operation);
    assertEquals("failed", result.status);
    assertEquals(1, result.reasons.size());
  }

  @Test
  void reservationsEnforceTheFiniteLimitWithoutBecomingApplications() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Fault fault =
        registry.register(
            1,
            "read-events",
            2,
            FaultRegistry.Injection.REPLACE_RESPONSE,
            DynamoDbFaultEffects.response(value -> value));
    FaultRegistry.Operation operation = registry.begin(1, false);
    FaultRegistry.Selection first = registry.select(operation, "read-events");
    FaultRegistry.Selection second = registry.select(operation, "read-events");
    assertNull(registry.select(operation, "read-events"));
    assertEquals(0, registry.applications(fault));
    assertFalse(registry.isApplied(first));
    assertFalse(registry.isApplied(second));
    registry.applied(first, 11);
    registry.release(second);
    assertTrue(registry.isApplied(first));
    assertFalse(registry.isApplied(second));
    FaultRegistry.Selection replacement = registry.select(operation, "read-events");
    assertNotNull(replacement);
    assertFalse(registry.isApplied(replacement));
    assertEquals(1, registry.applications(fault));
    registry.applied(replacement, 12);
    assertTrue(registry.isApplied(replacement));
    assertNull(registry.select(operation, "read-events"));
    assertEquals(2, registry.applications(fault));
    assertEquals("passed", registry.finish(operation).status);
  }

  @Test
  void continuousFaultWithNoApplicationsFails() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Fault fault =
        registry.register(
            1,
            "read-events",
            -1,
            FaultRegistry.Injection.REPLACE_REQUEST,
            DynamoDbFaultEffects.sdkError("InternalServerError"));
    assertEquals("failed", registry.finish(registry.begin(1, false)).status);
    assertEquals(0, registry.applications(fault));
  }

  @Test
  void unknownSdkErrorCodeHasNoAssumedHttpStatus() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DynamoDbFaultEffects.sdkError("UnconfirmedError").reply(null));
  }

  @Test
  void selectionDoesNotFabricateApplicationsAndCountUsesArrayOrder() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Fault first =
        registry.register(
            1,
            "commit",
            2,
            FaultRegistry.Injection.REPLACE_REQUEST,
            DynamoDbFaultEffects.sdkError("InternalServerError"));
    FaultRegistry.Fault second =
        registry.register(
            1,
            "commit",
            1,
            FaultRegistry.Injection.REPLACE_REQUEST,
            DynamoDbFaultEffects.sdkError("InternalServerError"));
    FaultRegistry.Operation operation = registry.begin(1, true);

    FaultRegistry.Selection selection = registry.select(operation, "commit");
    assertSame(first, selection.fault);
    assertEquals(0, registry.applications(first));
    registry.applied(selection, 1);
    FaultRegistry.Selection again = registry.select(operation, "commit");
    assertSame(first, again.fault);
    registry.applied(again, 2);
    FaultRegistry.Selection last = registry.select(operation, "commit");
    assertSame(second, last.fault);
    registry.applied(last, 3);

    assertNull(registry.select(operation, "commit"));
    assertEquals(2, registry.applications(first));
    assertEquals(1, registry.applications(second));
    assertThrows(IllegalStateException.class, () -> registry.applied(last, 4));
    assertEquals("passed", registry.finish(operation).status);
  }

  @Test
  void continuousFaultEndsWithItsOperationOnTheSameRegistry() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Fault fault =
        registry.register(
            0,
            "configuration-read",
            -1,
            FaultRegistry.Injection.REPLACE_REQUEST,
            DynamoDbFaultEffects.sdkError("InternalServerError"));
    FaultRegistry.Operation initialization = registry.begin(0, false);
    for (int i = 0; i < 3; i++)
      registry.applied(registry.select(initialization, "configuration-read"), i);
    assertEquals("passed", registry.finish(initialization).status);

    FaultRegistry.Operation next = registry.begin(1, false);
    assertNull(registry.select(next, "configuration-read"));
    assertThrows(
        IllegalStateException.class, () -> registry.select(initialization, "configuration-read"));
    assertEquals(3, registry.applications(fault));
    assertEquals("passed", registry.finish(next).status);
  }

  @Test
  void unusedConnectedFaultFailsAndUnsupportedFaultIsUnverified() {
    FaultRegistry registry = new FaultRegistry();
    registry.register(
        1,
        "commit",
        1,
        FaultRegistry.Injection.REPLACE_REQUEST,
        DynamoDbFaultEffects.sdkError("InternalServerError"));
    registry.unsupported(2, "serialize-event", "Serializer hook is not connected in this probe");
    FaultRegistry.Result unused = registry.finish(registry.begin(1, true));
    assertEquals("failed", unused.status);
    assertEquals(1, unused.reasons.size());
    FaultRegistry.Result unsupported = registry.finish(registry.begin(2, true));
    assertEquals("unverified", unsupported.status);
    assertTrue(unsupported.reasons.get(0).contains("not connected"));
  }

  @Test
  void operationCannotFinishWhileARequestIsPending() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Operation first = registry.begin(1, false);
    FaultRegistry.Operation request = registry.requestStarted();
    assertThrows(IllegalStateException.class, () -> registry.finish(first));
    assertThrows(IllegalStateException.class, () -> registry.begin(2, false));
    registry.requestFinished(request);
    assertEquals("passed", registry.finish(first).status);
    assertThrows(IllegalStateException.class, () -> registry.requestFinished(request));
    assertThrows(IllegalStateException.class, registry::requestStarted);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.register(
                -1,
                "commit",
                1,
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.sdkError("InternalServerError")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            registry.register(
                3,
                "commit",
                0,
                FaultRegistry.Injection.REPLACE_REQUEST,
                DynamoDbFaultEffects.sdkError("InternalServerError")));
  }

  @Test
  void anUnappliedReservationIsReleasedForTheNextAttempt() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Fault fault =
        registry.register(
            1,
            "read-events",
            1,
            FaultRegistry.Injection.REPLACE_RESPONSE,
            DynamoDbFaultEffects.response(value -> value));
    FaultRegistry.Operation operation = registry.begin(1, false);
    FaultRegistry.Selection first = registry.select(operation, "read-events");
    registry.release(first);
    registry.release(first);
    assertEquals(0, registry.applications(fault));
    assertThrows(IllegalStateException.class, () -> registry.applied(first, 1));
    FaultRegistry.Selection next = registry.select(operation, "read-events");
    assertSame(fault, next.fault);
    registry.applied(next, 2);
    assertEquals("passed", registry.finish(operation).status);
  }

  @Test
  void unsupportedInjectionIsUnverifiedAndNeverBecomesAnApplication() {
    FaultRegistry registry = new FaultRegistry();
    FaultRegistry.Effect[] effects = {
      DynamoDbFaultEffects.sdkError("InternalServerError"),
      DynamoDbFaultEffects.response(value -> value),
      new FaultRegistry.Effect() {}
    };
    FaultRegistry.Injection[] modes = {
      FaultRegistry.Injection.REPLACE_RESPONSE,
      FaultRegistry.Injection.REPLACE_REQUEST,
      FaultRegistry.Injection.REPLACE_REQUEST
    };
    for (int i = 0; i < effects.length; i++) {
      FaultRegistry.Fault unavailable =
          registry.register(i, "read-events", 1, modes[i], effects[i]);
      FaultRegistry.Operation operation = registry.begin(i, false);
      assertNull(registry.select(operation, "read-events"));
      assertEquals(0, registry.applications(unavailable));
      FaultRegistry.Result result = registry.finish(operation);
      assertEquals("unverified", result.status);
      assertTrue(result.reasons.get(0).contains(modes[i].name()));
    }
  }
}
