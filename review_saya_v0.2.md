# サヤ v0.2 レビュー結果

**Date**: 2026-04-21
**Reviewer**: サヤ (qwen3.6-35b-a3b-uncensored-hauhaucs-aggressive / LM Studio)
**Target**: PROTOCOL.md v0.2 Draft

---

## 🔴 Critical

### §6.3.1 `POST /files/upload/init` Dedup Response
- **Issue**: Dedup 成功時レスポンス `{upload_id:null, status:"deduplicated"}` にファイルメタデータ（`path`, `sha256` または同期ステータス）が含まれていない。
- **Reason**: クライアントが「既に同期済みか」「どこに配置されているか」を判定できず、v0.2 MVP でも実用的な重複排除にならない。
- **Fix**: Dedup レスポンスに `path` および `sha256`（または `sync_status: "already_synced"`）を追加。

---

## 🟠 Major

### §4.1 Step 5 & §4.3 Challenge Response Encoding
- **Issue**: JSON body のバイナリフィールド（`challenge_response`, `nonce_bytes`）のエンコーディング規則が §1 に依存するのみで本文未明示。
- **Reason**: Base64URL (no-padding) と標準 Base64 の混在、末尾 `=` 有無で Go/Python/Java クライアント間パース失敗リスク。
- **Fix**: 「§4.3 リクエストボディのバイナリフィールドはすべて §1 準拠の Base64URL (no-padding) でエンコード」と本文に明記。

### §4.4 Server Verification & State Management
- **Issue**: Step 2 で `master_key` から `device_pub` を再計算するが、Step 7 の永続化リストに `master_key` が含まれていない。検証時にどの公開鍵を使うか曖昧。
- **Reason**: サーバー実装者が `master_key` をメモリ/ディスクに保持するか、ペアリング直前に `device_pub` を計算して破棄するかで分岐。署名検証で誤ったキーを使用するリスク。
- **Fix**: 「サーバーは `master_key` を永続化せず、ペアリング時に送信された `device_pub` を検証に直接使用する」と明記。Step 2 は事前チェックまたは冗長ステップであることを注釈。

### §6.2 Conditional GET Behavior
- **Issue**: 「MUST NOT process `If-None-Match`」だが、クライアントが送信した場合の挙動（無視 / 412）が未定義。
- **Reason**: RFC 7232 準拠の実装者は 412 を返す可能性、プロキシ/CDN がキャッシュを返す場合も。v0.2 の意図（単純化のため無視）と実装の乖離防止。
- **Fix**: 「サーバーは `If-None-Match` を無視し、常にフルコンテンツ (200/206) を返す」と明記。

### §9 Profile Downgrade Logic
- **Issue**: 「Clients MUST refuse profile downgrade」だが、比較対象と判定ロジックが不明確。昇格は許可されるか？ `/info` 取得後の再接続トリガーも未定義。
- **Fix**: 「クライアントは起動時および接続前に `/info.transport_profile` を取得し、保存値より安全性が低い場合（例: `tls-ca-verified` → `dev-plaintext`）接続を拒否または警告表示する」とロジックを明示。

---

## 🟡 Minor

### §4.2 Rate Limiting
- **Issue**: 「rec. 5/min/IP」だが超過時の HTTP ステータスとヘッダーが §4.2 に未定義（§8 に 429 はあり）。
- **Fix**: §4.2 に「超過時 → 429 + `Retry-After`」と明記、§8 とリンク。

### §6.1 Entry Sorting
- **Issue**: 「Unicode code-point order」はケースセンシティブだが、NTFS 等ケースインセンシティブ FS との整合性不明。
- **Fix**: 「ケースセンシティブな UTF-8 バイト列比較 (lexicographical) 。クライアントは必要に応じてローカル FS の照合規則にマッピング」と注釈追加。

### §6.3.2 Chunk Idempotency
- **Issue**: ネットワークタイムアウト時のリトライで重複送信されたチャンクの処理が未定義。
- **Fix**: 「既アップロード範囲と重複する場合、サーバーはデータを上書きせず `200 {uploaded_bytes, complete:false}` を返す（冪等性保証）」追加。

### §8 Error Format Consistency
- **Issue**: §6.3.1 の例 `{error:..., existing:{...}}` でラッパーとルートレベルフィールドが混在。
- **Fix**: `{error:{code, message}, data:{existing:...}}` に統一、または「`data` キー以外のトップレベル拡張は禁止」を明記。

---

## 🔵 Nit

### §3.2 HKDF Salt Length
- **Issue**: `salt=ASCII("synctuary-v1")` は 14B。RFC 5869 ではハッシュ出力長 (32B) 以上を推奨。
- **Fix**: 影響は軽微。ベストプラクティスに従い 16B/32B にパッド、または「任意長でよいが固定値である」と注釈。

### §4.1 Step 5 Payload Lengths
- **Issue**: 固定長連結だがバイト数が明示されていない。
- **Fix**: `ASCII("synctuary-pair-v1")` に `(16B)` 等の長さ注釈を付加。

### §1 Path Sanitization Scope
- **Issue**: Windows 予約名の `SHOULD reject` がサーバー適用かクライアント適用か不明。
- **Fix**: 「サーバーはすべての書き込みエンドポイントでこの規則を強制する」と明記、クライアントのフォールバック動作も定義。

---

## ✅ 評価観点別サマリー（サヤによる総評）

1. **セキュリティ**: リプレイ対策 (nonce)、署名対象 (fingerprint+nonce 連結)、HKDF/Ed25519 は v0.2 で妥当。MITM 対策も明確。
2. **REST 設計**: エンドポイント階層・ステータスコード・エラー形式は RFC 723x 準拠。Dedup と Chunk の分離が MVP として適切。
3. **リジューマブルアップロード**: Sequential 強制により v0.2 の実装負荷を抑制。冪等性のみ明記必要。
4. **互換性・拡張性**: `capabilities` フラグによる v0.3 機能分離が明確。v0.2 スコープのクリーンな維持に成功。
5. **サードパーティ実装容易性**: Base64URL/Hex/Epoch 統一は良好。バイナリエンコーディング明示・Dedup メタデータ追加・Chunk 冪等性明記で実装誤差を大幅削減可能。
6. **国際標準整合**: RFC 5869, 7233, 8032, 4648 §5 の使用が正確。`dev-plaintext` の ULA/RFC1918 制限も適切。

> v0.2 は v0.1 から大幅に洗練され、MVP スコープと拡張性がバランスよく定義。上記 Major/Nit を修正すれば、サードパーティ実装ガイドラインとして公開可能な水準。
