# HAND_OFF: #807 DynamoDbItemSize を上界に揃える

Issue: https://github.com/j5ik2o/event-store-adapter-java/issues/807

## いまの状態（赤）

- ブランチ `t3/fix-dynamodb-item-size-estimate` に、境界テストだけをコミットした（`test(dynamodb): ...`）。
- 修正はまだ積んでいない。`DynamoDbItemSize` は旧式（有効桁2桁ごとに1バイト+1）のままで、対象の4クラスは 83 件中 18 件が失敗する。
- 失敗の中身は、数値を常に21バイトとして数える期待値と、旧式の見積もりのずれである。上限の数バイト上（+1、+3、+8）のケースで、旧式は D-7 を通してしまう。
- 共通 repo 側の PR はまだ出していない。

## 次の一手

1. `DynamoDbItemSize.valueSize` の数値を21バイト固定にして、修正をテストの上に積む。
2. 対象の4クラスが緑になることを確認し、全テストを流す。
3. java の PR をドラフトで出す。
4. 共通 repo の `conformance/dynamodb/write-errors.json` に境界ケースを足し、rs・go・js の見積もりで読み合わせる。

## 未決の判断

- 共通 repo の境界ケースの余白。rs・go と js は上界の数え方が違い、js は同じ項目を rs・go より小さく数える。余白の幅は読み合わせの結果で決める。
- java の適合テストデータの取り込み（`conformance/`）は、共通 repo の PR がマージされてから別に行う。この PR 群には含めない。

## PR

- java: まだなし
- 共通 repo（conformance）: まだなし
