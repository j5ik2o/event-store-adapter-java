# API の形の照合（java）

共通契約 v5 が公開 API に求める「形」を、ハブの [`api-surface/surface.json`](https://github.com/j5ik2o/event-store-adapter/tree/main/api-surface)（形式 0.1.0）と照合します。

| ファイル | 内容 |
|---|---|
| `surface.json` | ハブの定義をそのまま写したもの。SHA-256 は `37c91f8f94e10c73513a9c1deef990ea4deea96d5001ae700d6bf799658d0e32` |
| `java.json` | 共通の名前と java の公開 API（`com.github.j5ik2o.event.store.adapter.java.core`）の対応 |

## 照合のしかた

```sh
echo "37c91f8f94e10c73513a9c1deef990ea4deea96d5001ae700d6bf799658d0e32  api-surface/surface.json" | sha256sum -c -
./gradlew test --tests '*apisurface*'
```

SHA-256 の照合は CI の release-tools ジョブでも実行します。形の照合は `src/test/java/.../apisurface/` の JUnit の試験で、コンパイル済みのクラスをリフレクション（総称型を含む）で読み、`surface.json` と `java.json` を突き合わせます。

## 確かめていること

- 3 つの型（`EventEnvelope<P>`・`SnapshotEnvelope<A>`・`SnapshotReadResult<A>`）と、要素の取得のメソッドがあり、戻り値の型が値の種類と合う。
- 型の引数が共通の名前（`P`・`A`）で、`extends` による上限の制約がない（2 つのストアにも同じ検査）。
- 同期の `EventStore<P, A>` と非同期の `AsyncEventStore<P, A>` の、4 つの操作の引数と戻り値（非同期は `CompletableFuture` で包む）。
- 5 つの分類が、`EventStoreException` の下位の検査しない例外のクラスと、`ErrorCategory` の値として存在する。
- ビルダーを実際に呼び、必須の要素を 1 つ外した `build()` が `ContractViolationException` になること、省略できる `manifest` を外しても `build()` でき、値が `""` になること。
- 対応表（`java.json`）の抜け、解決できないクラスは、黙って飛ばさず報告する。
- 否定の試験（`ApiSurfaceNegativeTest`）で、形をわざとずらした 7 つのクラス（`occurredAt()` なし・`manifest()` の改名・`seqNr()` が `int`・`seqNr` なしで `build()` できるビルダー・`getEventsByIdSinceSeqNr` が `List<P>`・`StorageException` なし・`P extends Comparable<P>`）を、それぞれ報告し、関係のないずれは報告しない。

## 確かめられないこと

- 振る舞い（保持・照合・どの操作がどの分類を投げるか）。適合テストデータ（`conformance/`）で確かめます。
- メモリ・DynamoDB・SQLite の実装。公開の型と操作だけが対象です。
- surface の型を参照する箇所は、クラスの単純名と型の引数で比べるため、同じ名前の別のクラスは区別しません。
