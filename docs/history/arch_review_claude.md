# Claude レビュー: サヤ Go サーバーアーキテクチャ提案

**Date**: 2026-04-21
**Reviewee**: サヤ (arch_saya_go_server.md)
**Reviewer**: Claude (リードアーキテクト)

---

## 総評

全体として **高品質**。Clean Architecture の境界が明確、依存方向がインワードで正しい、DB スキーマはプロトコルと整合、外部依存選定は最小主義で妥当。このまま v0.2.1 FIX 後に実装フェーズで使える土台。

ただし実装に移る前に修正すべき**中小規模の指摘** 6 件と、**要検討** 2 件あり。

---

## 🟠 修正推奨（実装前）

### [M1] `UploadSession.AppendChunk(chunkIndex int)` は仕様と不一致

**Issue**: PROTOCOL §6.3.2 は `Content-Range: bytes <start>-<end>/<total>`（byte-range ベース）で通信する。`chunkIndex` 抽象は、クライアント側のチャンク分割粒度とサーバーのアドレス空間が結合してしまう。

**Fix**: 
```go
AppendChunk(ctx context.Context, id string, rangeStart int64, data []byte) error
```
idempotent retry 判定も `rangeStart < uploaded_bytes && rangeStart+len(data) <= uploaded_bytes` で自然に書ける（v0.2.1 §6.3.2 の idempotent retry 仕様と一致）。

### [M2] `DeviceRepository.GetByID` が欠落

**Issue**: `DELETE /api/v1/devices/<device_id>`（§7.2）で ID 指定削除が必要。また `current: true` 判定のため、現在リクエスト元の device を token_hash 経由で取得した後、enumeration 時に ID 比較が走る。

**Fix**: 追加
```go
GetByID(ctx context.Context, id []byte) (*domain.Device, error)
```

### [M3] `NonceStore` のレート制御責務が不明確

**Issue**: サヤは `Issue(sourceIP)` で IP を受け取るが、5 req/min/IP のレート制御ロジックが interface に組み込まれているのか呼び出し側で行うのかが不定。Clean Arch では Usecase 層が policy を持つべき。

**Fix**: `NonceStore` は pure CRUD に留め、レート制御は `usecase/pairing.go` 側に `RateLimiter` interface を別建て:
```go
type RateLimiter interface {
    Allow(ctx context.Context, key string, window time.Duration, max int) (bool, error)
}
```

### [M4] `UploadSession.Init` の `overwrite` フラグ欠落

**Issue**: PROTOCOL §6.3.1 request に `overwrite: bool` がある。既存ファイルとの衝突時の 409 + `existing` レスポンス制御に必要。

**Fix**: `Init` に `overwrite bool` 引数追加、または `InitParams struct` に集約（可読性向上のため後者推奨）:
```go
type UploadInitParams struct {
    ID             string
    Path           string
    Size           int64
    SHA256Expected []byte
    Overwrite      bool
}
Init(ctx context.Context, p UploadInitParams) (*UploadInitResult, error)
```

---

## 🟡 要改善（Minor）

### [m1] `server_meta.value` の暗号化 at rest

**Issue**: `master_key`（BIP39 seed 由来の 32B）を平文 BLOB で保存する設計。攻撃者がファイル盗めば全デバイスの pub/priv 派生を再現できる（priv は端末にしかないが pairing 偽装のリスクは残る）。

**Fix 選択肢**:
- (a) OS キーチェーン委譲（Win DPAPI / Linux keyring / macOS Keychain）: 配布複雑化
- (b) 起動時パスフレーズ入力 + Argon2id で KDF してマスター暗号化: 無人起動が困難（サーバー用途に不向き）
- (c) v0.2.1 は平文保存、将来 `server_meta` を暗号化 sub-table に分離する移行路を残す: **推奨**

v0.2.1 FIX 時点では (c) を選択し、PROTOCOL.md §3.1 に「server-side master_key storage: v0.2.1 ではファイルパーミッション (0600) で保護、OS-level secret store 移行は v0.4 候補」と明記。

### [m2] `migrations/` の運用ツール未指定

`golang-migrate/migrate` / `pressly/goose` / 自前など選択肢。自前実装は YAGNI なので `pressly/goose` を推奨（embed 可で単一バイナリ維持）。

### [m3] `fs/` が抽象化されているが、reflink/hardlink dedup 能力は interface に現れていない

PROTOCOL v0.2.1 §6.3.1 で dedup 時に reflink/hardlink/内部参照で `path` にエントリ作成する MUST 要件が加わった。`FileStorage` interface に `DeduplicateLink(existingSHA256 []byte, targetPath string) error` のようなメソッドが必要。

---

## 🔵 Nit

### [n1] `internal/domain/file/entity.go` の粒度

`FileMeta`（listing 用の metadata）と `UploadState`（upload session state）は責務が違うので、`file/` の下を `meta.go` と `upload.go` に分けた方が見通しが良い。

### [n2] BIP39 wordlist の埋め込み方

`//go:embed bip39_english.txt` で embed するのが無難。自前実装は `golang.org/x/text/collate` 非依存のシンプルな辞書一致で十分。

---

## サヤの「要確認事項」への回答

### サヤ Q1: チャンクサイズ上限・最大ファイルサイズ

- **`chunk_size_max`**: PROTOCOL §6.3.1 で server が `/upload/init` レスポンスに含める。サーバー config の値として `config.Upload.ChunkSizeMax`（推奨 32 MiB）を持たせる
- **最大ファイルサイズ**: v0.2.1 では明示上限なし。実装は `int64` 上限で十分（ZFS/NTFS 単一ファイル上限に従う）。必要なら将来 `config.Upload.MaxFileSize` 追加

### サヤ Q2: BIP39 seed → Ed25519 派生

PROTOCOL §3.2 / §3.3 に既に明記済み:
```
seed_bytes  = BIP39_mnemonic_to_seed(mnemonic, passphrase="")     // 64 bytes
master_key  = HKDF-SHA256(ikm=seed_bytes, salt=ASCII("synctuary-v1"), info=ASCII("master"), L=32)
device_seed = HKDF-SHA256(ikm=master_key, salt=device_id, info=ASCII("device-ed25519"), L=32)
(device_priv, device_pub) = Ed25519_keypair_from_seed(device_seed)
```

実装は Go stdlib で:
- BIP39: `mnemonic_to_seed` は自前実装（PBKDF2-HMAC-SHA512、2048 iter、salt = "mnemonic" + passphrase）
- HKDF: `golang.org/x/crypto/hkdf`
- Ed25519: `crypto/ed25519`（`ed25519.NewKeyFromSeed(device_seed)` で直接生成）

---

## 明日の作業項目（ユーザー起床後）

1. [ ] 上記 Claude レビュー（M1–M4, m1–m3）をサヤに差し戻して設計修正（30分）
2. [ ] PROTOCOL.md v0.2.1 を Gemini に再レビュー依頼（`review_request.md` 相当を新規作成）
3. [ ] Gemini 合意後、v0.2.1 を **Final** 化
4. [ ] サヤに DB マイグレーション DDL ファイル（`migrations/001_init.sql`）実装を委譲
5. [ ] `cmd/synctuaryd/main.go` の skeleton コード生成をサヤに委譲
