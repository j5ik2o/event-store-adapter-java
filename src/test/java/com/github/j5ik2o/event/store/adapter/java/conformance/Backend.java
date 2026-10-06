package com.github.j5ik2o.event.store.adapter.java.conformance;

/** 実行する保存先。dataName は適合テストデータの backends の表記、reportName は報告の表記。 */
enum Backend {
  MEMORY("memory", "memory"),
  DYNAMODB("dynamodb", "dynamodb-local");

  private final String dataName;
  private final String reportName;

  Backend(String dataName, String reportName) {
    this.dataName = dataName;
    this.reportName = reportName;
  }

  String dataName() {
    return dataName;
  }

  String reportName() {
    return reportName;
  }
}
