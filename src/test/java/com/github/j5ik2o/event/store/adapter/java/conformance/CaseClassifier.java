package com.github.j5ik2o.event.store.adapter.java.conformance;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 中核の値のケースと接続済みのメモリケースを実行し、保存先と表現能力に応じてケースの状態を分類する。 */
final class CaseClassifier {

  static final String REASON_HASH =
      "最初のメジャーにはハッシュを使う保存先がないため（DY-16。実装計画 5 章の受け入れ条件 1、2026-10-06 の決定）";
  static final String REASON_BACKEND = "保存先が backends に含まれない（設計文書 5.3 の 1）";
  static final String REASON_LAYOUT = "DynamoDB の配置の照合であり、メモリに該当しない（設計文書 5.4）";
  static final String REASON_LAYOUT_SQLITE = "DynamoDB の配置の照合であり、SQLite に該当しない";
  static final String REASON_MILLISECONDS =
      "標準時刻型 Instant はナノ秒を表せるため、milliseconds のケースは対象外（設計文書 5.2）";
  static final String REASON_NOT_EXECUTED = "この分類経路では保存先の公開操作を実行していない（設計文書 5.3）";

  static final String REASON_UNPROVIDED_BACKEND =
      "この言語はこの保存先（Spanner・Bigtable）を提供しない（適合テストデータ 1.3.0）";

  // 値の表は backends を持たないので、操作名で分類する。
  private static final Set<String> BIGTABLE_VALUE_OPERATIONS =
      Set.of(
          "encodeBigtableEventEnvelope",
          "encodeBigtableSnapshotEnvelope",
          "decodeBigtableEnvelope");
  private static final Set<String> PROVIDED_BACKENDS =
      Arrays.stream(Backend.values()).map(Backend::dataName).collect(Collectors.toSet());

  private CaseClassifier() {}

  static CaseResult classify(ConformanceCase c, Backend backend) {
    Optional<CaseResult> excluded = exclusion(c, backend);
    if (excluded.isPresent()) return excluded.get();
    if (ValueCaseRunner.supports(c)) {
      return ValueCaseRunner.run(c, backend);
    }
    if (backend == Backend.MEMORY && MemoryCaseRunner.supports(c)) {
      return MemoryCaseRunner.run(c);
    }
    if (backend == Backend.SQLITE && SqliteCaseRunner.supports(c)) {
      return SqliteCaseRunner.run(c);
    }
    return result(c, backend, ConformanceStatus.UNVERIFIED, REASON_NOT_EXECUTED);
  }

  /** Capability selection is independent of which cases have been connected to a runner. */
  static Optional<CaseResult> exclusion(ConformanceCase c, Backend backend) {
    if (c.operation().map("fnv1a64"::equals).orElse(false)) {
      return Optional.of(result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_HASH));
    }
    if (c.operation().map(BIGTABLE_VALUE_OPERATIONS::contains).orElse(false)) {
      return Optional.of(
          result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_UNPROVIDED_BACKEND));
    }
    if (c.backends()
        .map(names -> names.stream().noneMatch(PROVIDED_BACKENDS::contains))
        .orElse(false)) {
      return Optional.of(
          result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_UNPROVIDED_BACKEND));
    }
    if (c.backends().map(names -> !names.contains(backend.dataName())).orElse(false)) {
      return Optional.of(result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_BACKEND));
    }
    if (c.changeFeedRequested()) {
      return Optional.of(
          result(
              c,
              backend,
              ConformanceStatus.UNREPRESENTABLE,
              backend == Backend.SQLITE
                  ? SqliteCaseRunner.REASON_CHANGE_FEED
                  : MemoryCaseRunner.REASON_CHANGE_FEED));
    }
    if ("layout".equals(c.format()) && backend == Backend.MEMORY) {
      return Optional.of(result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_LAYOUT));
    }
    if ("layout".equals(c.format()) && backend == Backend.SQLITE) {
      return Optional.of(
          result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_LAYOUT_SQLITE));
    }
    if (c.timePrecision().map("milliseconds"::equals).orElse(false)) {
      return Optional.of(result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_MILLISECONDS));
    }
    return Optional.empty();
  }

  /** その保存先の一覧にあるのに成功していないケースか。判定はケース ID と保存先の組で行う。 */
  static boolean violatesRequirement(CaseResult r, Map<Backend, Set<String>> required) {
    return required.getOrDefault(r.backend(), Set.of()).contains(r.caseId())
        && r.status() != ConformanceStatus.PASSED;
  }

  /** データに存在しない必須の ID。綴りの誤りで必須のケースが黙って飛ばされることを防ぐ。 */
  static List<String> unknownRequiredIds(Set<String> required, List<ConformanceCase> cases) {
    Set<String> known = cases.stream().map(ConformanceCase::id).collect(Collectors.toSet());
    return required.stream()
        .filter(id -> !known.contains(id))
        .sorted()
        .collect(Collectors.toList());
  }

  /** 必須の一覧と、その保存先の対象ケースの食い違い。 */
  static final class RequiredMismatch {
    private final Set<String> missing;
    private final Set<String> extra;

    RequiredMismatch(Set<String> missing, Set<String> extra) {
      this.missing = missing;
      this.extra = extra;
    }

    /** 対象なのに必須の一覧にないケース ID。 */
    Set<String> missing() {
      return missing;
    }

    /** 必須の一覧にあるのに対象でないケース ID。 */
    Set<String> extra() {
      return extra;
    }
  }

  static RequiredMismatch requiredMismatch(
      Backend backend, Set<String> required, List<ConformanceCase> cases) {
    Set<String> targets =
        cases.stream()
            .filter(c -> exclusion(c, backend).isEmpty())
            .map(ConformanceCase::id)
            .collect(Collectors.toSet());
    Set<String> missing = new java.util.TreeSet<>(targets);
    missing.removeAll(required);
    Set<String> extra = new java.util.TreeSet<>(required);
    extra.removeAll(targets);
    return new RequiredMismatch(missing, extra);
  }

  private static CaseResult result(
      ConformanceCase c, Backend backend, ConformanceStatus status, String reason) {
    return new CaseResult(c.id(), c.file(), c.rules(), backend, status, reason, null, null, null);
  }
}
