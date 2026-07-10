# Gemini v0.2.1 差分レビュー結果 & v0.2.2 パッチ記録

**Date**: 2026-04-22
**Reviewer**: Gemini
**Claude 裁定**: 全 4 件受理、v0.2.2 として反映済み

---

## Gemini 指摘サマリー

| # | 重要度 | 該当 § | 要旨 |
|---|---|---|---|
| 1 | **Critical** | §6.3.1 | Dedup 失敗時の 404/409 が overwrite 拒否との区別不能 → クライアント永久デッドロック |
| 2 | **Major** | §6.3.2 | Idempotent retry のコンテンツ盲目性が最終 SHA 検証失敗時のデバッグを困難化 |
| 3 | **Major** | §6.3 全般 | 同一 `path` への並行アップロード（マルチデバイス競合）未定義 → last-write-wins |
| 4 | **Minor** | §4.2 / §4.3 | CSPRNG 要件が未記述 → `math/rand` 実装の可能性 |

**Gemini 結論**: 「このまま実装フェーズに投げるのは許可できない」

---

## Claude 裁定

全 4 件 **有効**、受理。特に:

- **#1 Critical**: cross-device link 問題は多ディスクホームサーバー（まさに Synctuary のユースケース）で常態。409 のセマンティクス衝突は Claude も見落としていた。**見落としの理由**は「dedup 失敗 = サーバー側の致命的エラー」と暗黙に仮定していたが、実際には FS の機能不足という recoverable な状況が主因。
- **#2 Major**: 最終 SHA で落ちる挙動は「正しいが UX 最悪」の典型。明文化は必須。
- **#3 Major**: Claude の一次精査で抜けていた。マルチデバイス同期の中核シナリオなのにポリシー未定義は確かに致命的。案 1（init 時 409）を採用、v0.3+ で conflict rename を capability 制にする方針を明記。
- **#4 Minor**: 経験則では自明だが、CC-BY-4.0 でサードパーティ実装を受け入れる以上、明記義務あり。`math/rand` 使用は即セキュリティ破綻。

---

## v0.2.2 パッチ内容（PROTOCOL.md 反映済み）

### §6.3.1 Dedup 失敗時フォールバック（Critical 対応）

- 404/409 は返さない。代わりに MUST fallback:
  - **(a) RECOMMENDED**: 通常 upload に fall through（`upload_id` を新規発行、クライアントは chunk upload を通常通り実行）
  - **(b) 任意**: サーバー側で同期コピー実行後 `"deduplicated"` を返す（ただしタイムアウト防止）
- `404 not_found` は親ディレクトリ不在のみに限定
- `409 file_exists` は `overwrite=false` + 異なる SHA のファイル存在時のみ

### §6.3.2 Idempotent retry Implementation Note（Major 対応）

- 「サーバーは retry chunk の内容検証を行わない。ファイル変更が発生した場合は最終 SHA 検証で `422 upload_hash_mismatch` となる」と明記
- クライアント責務: アップロード中のファイル安定性保証（単一ファイルハンドル、staging 領域へのコピー等）
- サーバー SHOULD: 「idempotent retry 後の 422」を区別可能なエラーで UI に出す

### §6.3.5 同一 path への並行アップロード排他（Major 対応、新設）

- **Single-active-session rule**: 同一 path にアクティブな upload_id が存在する間、新規 init は `409 upload_in_progress` を返す
- エラーレスポンスには `active_upload: { created_at, uploaded_bytes, size, expires_at }` を含める（他セッションの `upload_id` は漏洩させない）
- Init 時点でアトミックチェック必須（DB トランザクション等）
- `§8` に `upload_in_progress` エラーコード追加
- v0.3+: `conflict_resolution` capability で自動 rename ポリシー追加予定

### §4.2 / §4.3 CSPRNG 明記（Minor 対応）

- nonce: CSPRNG MUST（`/dev/urandom`, `getrandom(2)`, `crypto/rand`, `SecureRandom` 等を例示）、`math/rand` 等の非暗号 PRNG 禁止
- device_token: 同様 CSPRNG MUST、サーバーは SHA-256 hash 保存推奨（ログリーク耐性）

---

## アーキテクチャ設計への影響（arch_saya_go_server_v2.md への差分）

v0.2.2 パッチで interface に以下の影響:

### UploadSession.Init の挙動追加

- 実装は「同一 path でアクティブセッション検索 → あれば `ErrUploadInProgress` + `ActiveUploadInfo`」の責務追加
- `UploadInitResult` に `Deduplicated bool` と別に `Fallthrough` 概念は不要（サーバー内部で dedup 試行→失敗時は通常 upload_id 発行に分岐、usecase の single error-free path）
- DB トランザクション必須（`uploads` テーブルに `(path, active)` の UNIQUE 部分インデックスを v2 DDL に追加検討）

### FileStorage.DeduplicateLink のエラーセマンティクス

- 戻り値: `error`。以下 3 種を区別:
  - `nil`: dedup 成功
  - `ErrDedupUnsupported`: 回復可能（usecase は (a) 通常 upload にフォールスルー or (b) 同期コピー実行）
  - その他 `error`: 回復不能（404 親ディレクトリ不在等は Put/FS 層で別途判定）

### CSPRNG 遵守

- `crypto/rand.Read` を device_token / nonce 生成に使用
- `math/rand` import 禁止をリンタ（例: `gocritic` or custom `forbidigo` ルール）で強制推奨

### 追加 DB インデックス案

```sql
-- uploads テーブルに「同一 path にアクティブセッション一つだけ」制約
CREATE UNIQUE INDEX idx_uploads_path_active
  ON uploads(path)
  WHERE expires_at > strftime('%s','now')
    AND completed = 0;
```

（SQLite の部分インデックスで実装可能。定期クリーンアップと合わせて運用）

---

## 次のアクション

1. PROTOCOL.md v0.2.2 Draft は反映済み
2. arch_saya_go_server_v2.md → v3 として上記差分を統合（軽量な編集、Claude 直接対応）
3. Gemini に v0.2.2 が許容できるか再確認（もし必要なら）
4. 合意後、Status `Draft` → `Final` で v0.2.2 確定、実装フェーズへ
