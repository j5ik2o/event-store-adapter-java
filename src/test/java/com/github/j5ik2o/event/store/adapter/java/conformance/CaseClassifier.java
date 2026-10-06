package com.github.j5ik2o.event.store.adapter.java.conformance;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** ケースの状態を決める。この段階ではケースを実行しないので、成功にも失敗にもしない。 */
final class CaseClassifier {

  static final String REASON_HASH =
      "最初のメジャーにはハッシュを使う保存先がないため（DY-16。実装計画 5 章の受け入れ条件 1、2026-10-06 の決定）";
  static final String REASON_BACKEND = "保存先が backends に含まれない（設計文書 5.3 の 1）";
  static final String REASON_LAYOUT = "DynamoDB の配置の照合であり、メモリに該当しない（設計文書 5.4）";
  static final String REASON_MILLISECONDS =
      "標準時刻型 Instant はナノ秒を表せるため、milliseconds のケースは対象外（設計文書 5.2）";
  static final String REASON_NOT_EXECUTED = "実行器の基盤の段階であり、ケースを実行していない（設計文書 7.1 の 2・7.3）";

  private CaseClassifier() {}

  static CaseResult classify(ConformanceCase c, Backend backend) {
    if (c.operation().map("fnv1a64"::equals).orElse(false)) {
      return result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_HASH);
    }
    if (c.backends().map(names -> !names.contains(backend.dataName())).orElse(false)) {
      return result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_BACKEND);
    }
    if ("layout".equals(c.format()) && backend == Backend.MEMORY) {
      return result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_LAYOUT);
    }
    if (c.timePrecision().map("milliseconds"::equals).orElse(false)) {
      return result(c, backend, ConformanceStatus.NOT_APPLICABLE, REASON_MILLISECONDS);
    }
    return result(c, backend, ConformanceStatus.UNVERIFIED, REASON_NOT_EXECUTED);
  }

  /** 一覧にあるのに成功していないケースか。 */
  static boolean violatesRequirement(CaseResult r, Set<String> required) {
    return required.contains(r.caseId()) && r.status() != ConformanceStatus.PASSED;
  }

  /** データに存在しない必須の ID。綴りの誤りで必須のケースが黙って飛ばされることを防ぐ。 */
  static List<String> unknownRequiredIds(Set<String> required, List<ConformanceCase> cases) {
    Set<String> known = cases.stream().map(ConformanceCase::id).collect(Collectors.toSet());
    return required.stream()
        .filter(id -> !known.contains(id))
        .sorted()
        .collect(Collectors.toList());
  }

  private static CaseResult result(
      ConformanceCase c, Backend backend, ConformanceStatus status, String reason) {
    return new CaseResult(c.id(), c.file(), c.rules(), backend, status, reason, null, null, null);
  }
}
