> この文書は [BIGTABLE_DATABASE_SCHEMA.md](BIGTABLE_DATABASE_SCHEMA.md) の日本語版です。英語版が正本で、食い違うときは英語版に従います。

# Bigtable の保存先のスキーマ

この文書は `com.github.j5ik2o.event.store.adapter.java.bigtable` が使う配置を説明します。`5.0.1` には含まれず、次のリリースから使えます。ライブラリは[共通契約 v5](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/README.md)と、その [Bigtable プロファイル](https://github.com/j5ik2o/event-store-adapter/blob/main/docs/spec/storage/bigtable.md)に従います。適合データは 1.3.0 です。保存する `layout_version` は **1** で、保存形式を表す別の番号です。表の配置は Rust v5 の実装と同じです。

公開する型は [README](../README.ja.md) を、版の違いは [MIGRATION_GUIDE](MIGRATION_GUIDE.ja.md) を見てください。設計記録は [bigtable-backend](design/bigtable-backend.ja.md) です。

## 配置

この節は、`BigtableStorage.open(...)` が期待する 2 つの表を説明します。

保存先は 1 つの Bigtable インスタンスの 2 つの表を使います。**journal** 表と **head** 表です。2 つの表 ID は利用者が決め、互いに異なる必要があり、`BigtableStorageConfig.Builder.journalTableId` と `headTableId` に渡します。`BigtableStorage.open(...)` は表も列ファミリも行も作らず、照合するだけです。表は次の手順で利用者が作ります。例は ID `esa_journal` と `esa_head` を使います。自分の ID に読み替えてください。

| 表 | 列ファミリ | データのセル |
|:--|:--|:--|
| journal | `event`、`history`、`config` | `event:envelope`、`event:has_snapshot`、`history:state`、`history:envelope` |
| head | `head`、`snapshot`、`config` | `head:seq_nr`、`head:last_event`、`head:has_snapshot`、`snapshot:current` |

ガベージコレクション（BT-2）:

- データの列ファミリ（`event`、`history`、`head`、`snapshot`）は、必ず「最大 1 版を残す」という版数の規則だけを持ちます。年齢による規則、`and` や `or` で組み合わせた規則、規則がない状態は、すべて設定エラーになります。
- `config` には、年齢で削除する規則を付けません。付けると設定行がいずれ消え、その後の `open` がすべて失敗します。

```sh
# PROJECT と INSTANCE を置き換えます。cbt は Bigtable のコマンドラインツールです。
cbt -project PROJECT -instance INSTANCE createtable esa_journal
cbt -project PROJECT -instance INSTANCE createfamily esa_journal event
cbt -project PROJECT -instance INSTANCE createfamily esa_journal history
cbt -project PROJECT -instance INSTANCE createfamily esa_journal config
cbt -project PROJECT -instance INSTANCE setgcpolicy esa_journal event maxversions=1
cbt -project PROJECT -instance INSTANCE setgcpolicy esa_journal history maxversions=1

cbt -project PROJECT -instance INSTANCE createtable esa_head
cbt -project PROJECT -instance INSTANCE createfamily esa_head head
cbt -project PROJECT -instance INSTANCE createfamily esa_head snapshot
cbt -project PROJECT -instance INSTANCE createfamily esa_head config
cbt -project PROJECT -instance INSTANCE setgcpolicy esa_head head maxversions=1
cbt -project PROJECT -instance INSTANCE setgcpolicy esa_head snapshot maxversions=1
```

1 つのクラスタ（BT-1）: 保存先の読み取り、書き込み、保持、設定の照合は、すべて同じクラスタに固定した app profile を通します。ライブラリはこれを確かめられないので、渡すクライアントに利用者が設定します。複製のクラスタがあっても、複数のクラスタへの同時書き込み、複数クラスタへのルーティング、自動のフェイルオーバーは対象外です。

## 設定

設定行の行キーは 1 バイトの `0x00` です。データの行キーは必ず 8 バイトのハッシュと 2 バイトの長さで始まるので、これと等しくなりません。行には `config:metadata` のセルが 1 つあり、時刻 0 で書きます。値は次の項目を持つ UTF-8 の JSON オブジェクトです。

- `store_id`: 小文字の UUID 文字列。2 つの表で同じ値にします。
- `layout_version`: JSON の整数 `1`。
- `keep_snapshot_count`: スナップショットの履歴を持たないときは `null`。持つときは、正の整数の **10 進の文字列**で、符号、先頭のゼロ、指数表記は使いません（例 `"3"`）。JSON の読み手が浮動小数点にしてしまわないよう、文字列にします。すべてのクライアントの `RetentionPolicy` の保持件数と等しくします。`3` のような JSON の数値は拒否されます。
- `retention_mode`: 文字列 `delete`。スナップショットの履歴を持たなくても `delete` です。TTL は提供しません。
- `envelope_limit_bytes`: 17 から 8388608 の JSON の整数。直列化した封筒 1 つの上限（BT-16）で、すべてのクライアントの `BigtableStorageConfig.envelopeLimitBytes()` と等しくします。既定は 1048576 です。

キーの順序と空白は比べません。項目の欠落、型の誤り、同じキーが 2 回ある（値が同じでも）、2 つの表で値が違う、のいずれも設定エラーです。設定行が片方の表にしかないときも設定エラーで、自動では補いません。

```json
{"store_id":"5f0c8b8e-3c1a-4d7e-9a4b-2f6d1c0e7a11","layout_version":1,"keep_snapshot_count":"3","retention_mode":"delete","envelope_limit_bytes":1048576}
```

この版は変更フィードを提供しません。`BigtableStorageConfig.Builder.changeFeed(true)` は build で設定エラーになり、`RetentionPolicy.ttl(...)` も同じです。

## 初期化

アプリケーションの外で、どのクライアントも書き込んでいないときに実行します。

1. 保存先のすべての書き手を止めます。
2. 上のコマンドで 2 つの表と列ファミリを作り、GC の規則を設定します。
3. 両方の表が空であることを確かめます。
4. `BigtableStorage.initialize(dataClient, config)` を 1 回呼びます。どちらの表にも設定行がないときだけ、新しい同じ設定行（`store_id` は無作為な小文字の UUID）を両方の表に書きます。どちらかに設定行が既にあれば `ConfigurationException` を投げ、何も書きません。表は作りません。
5. 書き手を起動します。`BigtableStorage.open(dataClient, adminClient, config)` が配置を照合します。

`open` は、確かめる項目が欠けているか一致しないと `ConfigurationException` を投げます。対象は、存在しない表、足りない列ファミリ、GC の規則が「最大 1 版」でないデータの列ファミリ、設定行の欠落や不正、クライアントの設定と違う値です。Bigtable に届かないときは `StorageException` を投げます。何も修復しません。利用中の表や列ファミリの変更、ライブラリを通らないデータの変更は対象外です。

### 途中で止まった初期化からの戻し方

`BigtableStorage.initialize` は 2 つの設定行を、先に journal の表、次に head の表へ、順に書きます。Bigtable には行や表をまたぐトランザクションがありません。1 つ目の書き込みの後で呼び出しが止まる（クラッシュ、接続の切断、2 つ目の表からの `StorageException`）と、journal の表にだけ設定行が残ります。ライブラリは、自分が今書いたのではない設定は信用できないので、足りない行を自分では補いません。この状態では、設定行が既にあるため `BigtableStorage.initialize` は `ConfigurationException` を投げ、head の表に行がないため `BigtableStorage.open` も `ConfigurationException` を投げます。

戻し方:

1. 2 つの表のすべての書き手と読み手を止めたままにします。
2. 両方の表で行キー `0x00` の行を読みます。両方にあれば初期化は終わっているので、何も消しません。片方にしかないときは、どちらの表にもデータの行がないこと、つまり保存先が一度も使われていないことを確かめます。データの行があれば、途中で止まった初期化ではないので、この手順は当てはまりません。その行を消してはいけません。
3. 行キー `0x00` の残った行（`config:metadata` のセル）を、それがある表から消します。任意の Bigtable のクライアント（たとえば `DeleteFromRow` の変更）を使います。
4. 同じ設定で `BigtableStorage.initialize` をもう一度呼びます。

## 記録

行キー（BT-3、BT-4）。`A` は `aid` の UTF-8 のバイト列、`H` は `A` の FNV-1a 64 のハッシュを 8 バイトの big-endian にしたもの、`L16` は `A` のバイト長を符号なし 16 ビットの big-endian にしたものです。

```text
head の行キー    = H || L16(len(A)) || A
journal の行キー = H || L16(len(A)) || A || U64BE(seq_nr)
```

長さを含めるので、ハッシュが衝突しても、別の `aid` のキーがこのキーの接頭辞になることはありません。範囲の組み立てに、10 進の文字列、区切り文字、`prefix + 0xff` は使いません。`aid` は `typeName-value` で、UTF-8 で 1024 バイト以下です。

封筒（BT-D6a）。整数はすべて big-endian です。

```text
event    = 0x01 || U16 len(aid) || aid || U64 seq_nr || I64 occurred_at_ns
           || U32 len(manifest) || manifest || U32 len(payload) || payload
snapshot = 0x01 || U64 seq_nr || U32 len(manifest) || manifest || U32 len(payload) || payload
```

末尾の余り、長さの不一致、未知の版のバイト、不正な UTF-8 は保存先エラーです。`occurred_at_ns` は、Unix エポックからのナノ秒を符号付き 64 ビットで表した値です。

セル。すべてのセルを時刻 0 で書き、列ごとにセルはちょうど 1 つです。

| 表とセル | 値 |
|:--|:--|
| head / `head:seq_nr` | 20 桁のゼロ詰めの ASCII 数字。1 以上 2^53 - 1 以下 |
| head / `head:last_event` | その `seq_nr` のイベントの封筒 |
| head / `head:has_snapshot` | その追記がスナップショットを伴うなら ASCII の `1`、イベントだけなら `0` |
| head / `snapshot:current` | 現在のスナップショットの封筒。スナップショットを書いたことがなければ、セルもない |
| journal / `event:envelope` | 行の `seq_nr` のイベントの封筒。削除しない |
| journal / `event:has_snapshot` | ASCII の `1` または `0`。head と同じ。削除しない |
| journal / `history:state` | 履歴を書き出したら `active`、削除したら `retired` |
| journal / `history:envelope` | 履歴のスナップショットの封筒。`retired` にするとき削除する |

保持件数を設定していないときは、履歴のセルを作りません。`has_snapshot = 1` の head では、`snapshot:current` の `seq_nr` が head の `seq_nr` と等しく、`has_snapshot = 0` ではそれより小さくなります。

## 書き込みと読み取り

追記は次の順に進みます（BT-7）。

1. head の行を読みます。行がなければ `h = 0` です。必要なセルが欠けた行は新規の集約ではなく、`StorageException` です。
2. 新しい `seq_nr` が `h` 以下なら `OptimisticLockException`、`h + 1` より大きければ `ContractViolationException`（規則 `W-8`）です。
3. `h > 0` なら、イベント `h` を journal へ書き出します。必要なら履歴も同じ行に書きます。行は `event:envelope` がないときだけ、1 回の条件付き更新で作ります。既にある行は上書きせず、イベントとフラグを照合します。書き出しを確認できなければ `StorageException` で、head は進みません。
4. head の行を 1 回の条件付き更新で更新します。条件は、`head:seq_nr` の最新のセルが読んだ `h` であること（新規の集約では、そのセルがまだないこと）です。置き換える列は、全版を削除してから新しい値を同じ変更の中で書きます。**この更新の成功が追記の確定点です。** 条件が成り立たなければ、先に別の書き手が確定したので、head の番号を含まない `OptimisticLockException` になります。応答が届かなければ、成否不明の `StorageException` になり、再送しません。同じ封筒を送り直すと、最初の更新が通っていた場合は `OptimisticLockException` になります。
5. 新しいイベントを journal へ書き出し、保持を実行します。ここでの失敗は通知して後回しにし、確定した追記は変わりません。

封筒の大きさは、スナップショットの封筒も含め、直列化の直後、head を読む前に `envelope_limit_bytes` と比べます。超えると、`seq_nr` を伴う規則 `BT-16` の `ContractViolationException` になり、何も書きません。

読み取り。最新のスナップショットは head の 1 行から読むので、head の番号と現在のスナップショットは常に対になります。イベントは、先に head を読み、その `h` を読み取りの間ずっと使います。`start = max(1, seqNr)` として次のとおり分岐します。

- head がない、または `start > h`: 空の結果。journal は読みません。
- `start = h`: head のイベントだけ。journal は読みません。
- `start < h`: journal の行 `[start, h)` を 1 つの範囲（開始を含み、終端を含まない）で読み、その後ろに head のイベントを付けます。範囲に欠けた行があれば `StorageException` です。

読み取り中に head が進んでも、結果には混ざりません。Bigtable のクライアントが分割されたセルや行を組み立て、途中で切れた読み取りは、最後に完成した行の次から再開します。

## 保持

保持は、集約ごとに新しい `n` 件のスナップショットを残します。`n` は `RetentionPolicy.delete(n)` の件数です。スナップショットを伴う追記が確定した後に実行します。

1. 集約の journal の行 `[1, h]` の `history:state` のセルだけを読みます。
2. 状態が `active` の行に head の `h` を加え、新しい `n` 件を残し、残りを対象にします。
3. 対象の行ごとに、状態が `active` の間だけ成り立つ 1 回の条件付き更新で、`history:envelope` を削除し、`history:state` を `retired` にします。イベントのセルは残します。

journal の行は `event:envelope` がないときだけ作り、`retired` の行はそのまま受け入れるので、遅れた書き手が削除済みの履歴を復活させることはありません。保持の失敗は確定した追記を変えません。`BigtableEventStore` のロガーに WARN で記録し、任意の `EventStoreConfig.Builder.retentionFailureListener` へ 1 回通知します。次のスナップショットを伴う追記で再び実行します。

## 運用

### 失敗

確定後に新しい末尾のイベントを書き出せなかった失敗は、ロガー `com.github.j5ik2o.event.store.adapter.java.bigtable.journal` に WARN で記録します。リスナーは呼びません。次の追記が、head を進める前に、足りない行を書きます。

### 保持件数の変更

`keep_snapshot_count` の変更は、すべてのクライアントを止めて、ライブラリの外で行う保守の手順です。ライブラリは道具を提供しません。

1. 保存先のすべてのクライアントを止め、実行中の処理が終わったことを確かめます。変更前と変更後の設定と進み具合を、再開に使える場所に記録します。
2. 集約ごとに、変更前の件数で、末尾の行の書き出しが残っていれば完了させます。次に、新しい件数の外にある履歴を retired にします。active の行のうち新しい `n` 件を残し、残りを「保持」の節と同じ方法で retired にします。イベント、フラグ、head、現在のスナップショットは変えず、削除済みの履歴は作り直しません。
3. すべての集約を処理したら、両方の表の設定行の `keep_snapshot_count` を新しい値にし、クライアントを再起動して、それぞれが新しい設定を照合するようにします。

途中で失敗したら、クライアントを止めたまま、同じ目標で再実行します。既に retired の行は処理されません。件数を増やすとき、またはなかった件数を設定するとき、削除済みの履歴は元に戻りません。新しい設定は次のスナップショットを伴う追記から効きます。
