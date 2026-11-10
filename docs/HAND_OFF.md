# HAND_OFF: #807 DynamoDbItemSize を上界に揃える

Issue: https://github.com/j5ik2o/event-store-adapter-java/issues/807

## いまの状態（PR 提出済み、レビュー待ち）

- ブランチ `t3/fix-dynamodb-item-size-estimate` に、境界テスト（赤）の上に修正を積んだ。`DynamoDbItemSize` は数値を常に21バイトと数える。
- 対象の4クラスは 83 件すべて通る。全テストは 2238 件で失敗 0（スキップ 440 は修正前と同数）。
- 共通 repo の PR は出した（下記）。新しい適合データを Java に重ねると、DynamoDB Local で4ケースとも通る。修正前の Java では over の2ケースが失敗する。

## 次の一手

1. レビューコメントが付いたら、一つずつ「直す」「理由をつけて直さない」「聞く」に分ける。
2. 共通 repo の PR がマージされたら、Java に適合データを取り込む（別 PR）。必須ケース一覧（`required-cases.json`）とケース数の固定値（`ConformanceDataLoaderTest`、`ConformanceReportTest`）の更新が要る。

## 未決の判断

- 共通データの版を `1.3.0` のまま据え置いた。上げるかはオーナーの判断。
- js の見積もりは rs・go・java より小さい（event の head で27バイト、snapshot で36バイト）。共通の境界ケースを上限の1〜数バイト上に置けないのはこのため。js を21バイトに揃えるかは別の判断（js の repo には手を入れていない）。

## PR

- 共通 repo（conformance）: https://github.com/j5ik2o/event-store-adapter/pull/108（ドラフト）
- java: https://github.com/j5ik2o/event-store-adapter-java/pull/808（ドラフト）
