package com.github.j5ik2o.event.store.adapter.java.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.j5ik2o.event.store.adapter.java.sqlite.ConformanceSqliteOperations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** 一時ディレクトリの実際の SQLite ファイルで操作を実行し、結果を共通データの期待値と比較する。 */
final class SqliteCaseRunner {
  private SqliteCaseRunner() {}

  static final String REASON_CHANGE_FEED =
      "公開設定（SqliteStorageConfig・EventStoreConfig）に変更フィードを求める項目がなく、change_feed=true を表せない";

  static boolean supports(ConformanceCase c) {
    return c.file().startsWith("scenarios/core/")
        || c.file().startsWith("sqlite/")
        || c.file().equals("scenarios/stores/shared.json")
        || c.operation().map("validateOccurredAt"::equals).orElse(false);
  }

  static CaseResult run(ConformanceCase c) {
    Path directory;
    try {
      directory = Files.createTempDirectory("conformance-sqlite-");
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
    try {
      return ScenarioComparison.run(
          c,
          Backend.SQLITE,
          REASON_CHANGE_FEED,
          new ScenarioComparison.Execution() {
            public ObjectNode scenario(JsonNode scenario) {
              return ConformanceSqliteOperations.executeScenario(scenario, directory);
            }

            public ObjectNode occurredAt(JsonNode input, String caseId) {
              return ConformanceSqliteOperations.validateOccurredAt(input, caseId, directory);
            }
          });
    } catch (RuntimeException failure) {
      return new CaseResult(
          c.id(),
          c.file(),
          c.rules(),
          Backend.SQLITE,
          ConformanceStatus.FAILED,
          "実行に失敗した: " + failure,
          null,
          c.materialized(),
          null);
    } finally {
      deleteRecursively(directory);
    }
  }

  private static void deleteRecursively(Path directory) {
    try (Stream<Path> paths = Files.walk(directory)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 一時ディレクトリの後始末の失敗は、結果に影響させない。
                }
              });
    } catch (IOException ignored) {
      // 一時ディレクトリの後始末の失敗は、結果に影響させない。
    }
  }
}
