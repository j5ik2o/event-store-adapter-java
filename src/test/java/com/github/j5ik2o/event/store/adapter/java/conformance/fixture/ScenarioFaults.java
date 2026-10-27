package com.github.j5ik2o.event.store.adapter.java.conformance.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.core.JsonPayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.PayloadSerializer;
import com.github.j5ik2o.event.store.adapter.java.core.SerializationException;
import com.github.j5ik2o.event.store.adapter.java.core.StorageException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 適合データの障害の登録・適用・実適用回数の検査。保存先の差し込み口から呼ぶ。 */
public final class ScenarioFaults {
  final List<JsonNode> registered = new ArrayList<>();
  final List<Integer> fired = new ArrayList<>();
  private int operation;

  public int operation() {
    return operation;
  }

  public void startOperation() {
    operation++;
  }

  public ScenarioFaults(JsonNode faults) {
    faults.forEach(
        fault -> {
          registered.add(fault);
          fired.add(0);
        });
  }

  public boolean supported() {
    return supported(false);
  }

  /**
   * @param spannerCommitFaults Spanner の commit の差し込み口（中断・応答の喪失）を持つ保存先か
   */
  public boolean supported(boolean spannerCommitFaults) {
    Set<String> phases =
        Set.of(
            "serialize-event",
            "serialize-snapshot",
            "deserialize-event",
            "deserialize-snapshot",
            "commit",
            "read-events",
            "read-snapshot",
            "retention-query",
            "retention-delete");
    for (JsonNode fault : registered) {
      String kind = fault.path("kind").asText(), phase = fault.path("phase").asText();
      if (!phases.contains(phase)) return false;
      Set<String> details = new HashSet<>();
      fault.path("details").fieldNames().forEachRemaining(details::add);
      if (spannerCommitFaults
          && kind.equals("transaction-aborted")
          && phase.equals("commit")
          && Set.of("message").containsAll(details)) continue;
      if (kind.equals("serialization-error")
          && phase.matches("(serialize|deserialize)-(event|snapshot)")
          && Set.of("message").containsAll(details)) continue;
      if (kind.equals("storage-error")
          && Set.of("commit", "read-events", "read-snapshot", "retention-query", "retention-delete")
              .contains(phase)
          && Set.of("message", "scope").containsAll(details)
          && (!details.contains("scope")
              || fault.at("/details/scope").asText().equals("final-retention-failure"))) continue;
      if (kind.equals("sdk-response")
          && phase.equals("retention-query")
          && details.equals(Set.of("history_pages"))) continue;
      return false;
    }
    return true;
  }

  public JsonNode fire(String phase) {
    return fire(phase, null);
  }

  /**
   * @param response true なら応答の置き換え、false なら要求の置き換えだけを対象にする。null は両方。
   */
  private JsonNode fire(String phase, Boolean response) {
    for (int i = 0; i < registered.size(); i++) {
      JsonNode fault = registered.get(i);
      boolean repeated =
          fault.path("repeat").path("mode").asText().equals("until-operation-finishes")
              || fired.get(i) < fault.path("repeat").path("count").asInt(1);
      boolean replacesResponse = fault.path("injection").asText().equals("replace-response");
      if (fault.path("operation").asInt() == operation
          && fault.path("phase").asText().equals(phase)
          && (response == null || response == replacesResponse)
          && repeated) {
        fired.set(i, fired.get(i) + 1);
        String kind = fault.path("kind").asText();
        String message = fault.path("details").path("message").asText();
        if (kind.equals("serialization-error")) {
          throw new SerializationException(message);
        }
        if (kind.equals("storage-error")) {
          throw new StorageException(message);
        }
        return fault.path("details");
      }
    }
    return null;
  }

  public void finishOperation(ObjectNode result) {
    for (int i = 0; i < registered.size(); i++) {
      if (registered.get(i).path("operation").asInt() == operation) {
        result
            .withArray("faults")
            .addObject()
            .put("operation", operation)
            .put("phase", registered.get(i).path("phase").asText())
            .put("applications", fired.get(i))
            .set("repeat", registered.get(i).path("repeat"));
        checkApplications(i, result);
      }
    }
  }

  private void checkApplications(int index, ObjectNode result) {
    JsonNode fault = registered.get(index);
    int count = fired.get(index);
    boolean finite = fault.at("/repeat/mode").asText().equals("count");
    if ((count == 0 || (finite && count != fault.at("/repeat/count").intValue()))
        && !result.has("fault_failure")) {
      result.put("fault_failure", "障害の実適用回数が指定と一致しない: " + fault);
      result.put("fault_operation", fault.path("operation").intValue());
    }
  }

  public void finish(ObjectNode result) {
    for (int i = 0; i < registered.size(); i++) {
      checkApplications(i, result);
    }
  }

  public PayloadSerializer<JsonNode> serializer(String name) {
    PayloadSerializer<JsonNode> delegate = JsonPayloadSerializer.of(JsonNode.class);
    return new PayloadSerializer<JsonNode>() {
      public byte[] serialize(JsonNode value) {
        fire("serialize-" + name);
        return delegate.serialize(value);
      }

      public JsonNode deserialize(byte[] bytes) {
        fire("deserialize-" + name);
        return delegate.deserialize(bytes);
      }
    };
  }

  /** 要求の置き換えを適用する。中断（transaction-aborted）が発火したら true を返す。 */
  public boolean beforeCommit() {
    return fire("commit", false) != null;
  }

  /** 応答の置き換えを適用する。保存先エラーの障害なら StorageException を投げる。 */
  public void afterCommit() {
    fire("commit", true);
  }

  public void beforeReadEvents() {
    fire("read-events");
  }

  public void beforeReadSnapshot() {
    fire("read-snapshot");
  }

  public void beforeRetentionDelete() {
    fire("retention-delete");
  }

  public List<Long> readHistory(List<Long> actualHistory) {
    JsonNode response = fire("retention-query");
    if (response == null) {
      return actualHistory;
    }
    List<Long> candidates = new ArrayList<>();
    for (JsonNode page : response.path("history_pages")) {
      for (JsonNode seq : page) {
        candidates.add(seq.bigIntegerValue().longValueExact());
      }
    }
    return candidates;
  }
}
