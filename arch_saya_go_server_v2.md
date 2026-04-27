# サヤ Go サーバーアーキテクチャ提案 v2（Claude 統合版）

**Date**: 2026-04-21 朝
**Base**: `arch_saya_go_server.md` (v1) + サヤ修正版 + Claude 追加補正
**Target**: PROTOCOL.md v0.2.1 準拠

---

## Change Log

### v1 → v2
- [M1] `UploadSession.AppendChunk`: `chunkIndex int` → `rangeStart int64`（仕様 byte-range 整合）
- [M2] `DeviceRepository.GetByID` 追加
- [M3] レート制御を `NonceStore` から分離、`RateLimiter` interface を新設
- [M4] `UploadSession.Init` の引数を `UploadInitParams` / 戻り値を `UploadInitResult` に集約
- [M5] **`UploadSession.Progress` メソッド復活**（GET /upload/<id> 対応、Claude 補正）
- [M6] **`UploadInitParams.Filename` → `Path`**（PROTOCOL §6.3.1 整合、Claude 補正）
- [m1] `SecretStore` interface 新設（master_key 暗号化 at rest への移行路）
- [m2] マイグレーションツールに `github.com/pressly/goose/v3` 採用
- [m3] `FileStorage.DeduplicateLink` 新設
- [n1] `internal/domain/file/` を `meta.go` + `upload.go` に分割
- [n2] `//go:embed bip39_english.txt` を `internal/domain/device/bip39.go` に配置
- [n3] `UploadInitResult.ChunkSizeMax` を `int` → `int64`（Claude 補正）

---

## 1. ディレクトリ構成（v1 からの差分適用後、完全版）

```text
synctuary-server/
├── cmd/synctuaryd/main.go
├── internal/
│   ├── domain/
│   │   ├── device/
│   │   │   ├── entity.go               # Device, KeyPair
│   │   │   ├── repository.go           # DeviceRepository interface
│   │   │   └── bip39.go                # //go:embed bip39_english.txt + derivation util [n2]
│   │   ├── file/
│   │   │   ├── meta.go                 # FileMeta [n1]
│   │   │   └── upload.go               # UploadState, UploadInitParams, UploadInitResult [n1]
│   │   ├── nonce/
│   │   │   └── store.go                # NonceStore interface
│   │   └── rate/
│   │       └── limiter.go              # RateLimiter interface [M3]
│   ├── usecase/
│   │   ├── pairing.go                  # nonce 発行＋RateLimiter.Allow 呼び出し
│   │   ├── file_service.go
│   │   └── device_service.go
│   └── adapter/
│       ├── interface/http/
│       │   ├── handler.go
│       │   └── middleware.go
│       └── infrastructure/
│           ├── db/
│           │   └── sqlite.go           # modernc.org/sqlite 接続 + goose 埋込マイグレーション実行
│           ├── fs/
│           │   └── file_storage.go     # FileStorage 実装 (reflink/hardlink dedup)
│           ├── secret/
│           │   └── file_store.go       # FileSecretStore (0600 perms) [m1]
│           ├── rate/
│           │   └── memory_limiter.go   # in-memory sliding window
│           └── crypto.go
├── pkg/config/
│   └── config.go                       # config.Upload.ChunkSizeMax = 32 MiB 等
├── migrations/
│   ├── 001_init.sql                    # goose embed 対象
│   └── ...
├── go.mod
└── PROTOCOL.md
```

---

## 2. Interface 定義（完全版）

```go
// internal/domain/device/repository.go
type DeviceRepository interface {
    Create(ctx context.Context, d *Device) error
    GetByID(ctx context.Context, id []byte) (*Device, error)                    // [M2]
    GetByTokenHash(ctx context.Context, hash []byte) (*Device, error)
    UpdateLastSeen(ctx context.Context, id []byte, ts int64) error
    Revoke(ctx context.Context, id []byte, revokedAt int64) error
    ListActive(ctx context.Context) ([]*Device, error)
    Delete(ctx context.Context, id []byte) error
}

// internal/domain/nonce/store.go
type NonceStore interface {
    // Issue: nonce 永続化のみ。sourceIP は監査・重複登録検知用メタデータ
    Issue(ctx context.Context, sourceIP string) (nonce []byte, expiresAt int64, err error)
    // Consume: nonce 検証＋消費。期限切れ／未発行／消費済みなら ErrNonceInvalid
    Consume(ctx context.Context, nonce []byte) error
}

// internal/domain/rate/limiter.go
type RateLimiter interface {
    // Allow: key ごとに window 内で max 回まで true を返す。超過時 (false, nil)
    Allow(ctx context.Context, key string, window time.Duration, max int) (bool, error)
}

// internal/domain/file/upload.go
type UploadSession interface {
    Init(ctx context.Context, params *UploadInitParams) (*UploadInitResult, error) // [M4]
    // AppendChunk: rangeStart は Content-Range 由来のバイトオフセット
    //   idempotent retry: rangeStart < uploaded_bytes かつ rangeStart+len(data) <= uploaded_bytes → 成功応答
    //   範囲不整合 (rangeStart < uploaded_bytes <= end) → ErrRangeMismatch (409)
    AppendChunk(ctx context.Context, id string, rangeStart int64, data []byte) error // [M1]
    // Progress: GET /upload/<id> 対応、再接続時の再開点取得
    Progress(ctx context.Context, id string) (uploadedBytes int64, completed bool, expiresAt int64, err error) // [M5]
    // Abort: クライアントからの明示的キャンセル (DELETE /upload/<id>)
    Abort(ctx context.Context, id string) error
}

// internal/domain/file/meta.go
type FileStorage interface {
    Put(ctx context.Context, path string, r io.Reader) error
    Get(ctx context.Context, path string, rangeStart, rangeEnd int64) (io.ReadCloser, error) // Range 対応
    Delete(ctx context.Context, path string, recursive bool) error
    Move(ctx context.Context, from, to string, overwrite bool) error
    // DeduplicateLink: 既存 SHA256 のコンテンツを targetPath にリンク（reflink → hardlink → copy）[m3]
    DeduplicateLink(ctx context.Context, existingSHA256 []byte, targetPath string) error
}

// internal/adapter/infrastructure/secret/file_store.go 実装対象
type SecretStore interface { // [m1]
    SaveMasterKey(ctx context.Context, key []byte) error
    LoadMasterKey(ctx context.Context) ([]byte, error)
}
```

---

## 3. 新規 struct 定義

```go
// internal/domain/file/upload.go
type UploadInitParams struct {
    Path      string  // [M6] PROTOCOL §6.3.1 "path" (フルパス)
    Size      int64
    SHA256    []byte
    Overwrite bool    // [M4]
}

type UploadInitResult struct {
    SessionID    string
    ChunkSize    int64  // 推奨チャンクサイズ (PROTOCOL §6.3.1 chunk_size)
    ChunkSizeMax int64  // [n3] int → int64
    Existing     *FileMeta // nil: 新規 / non-nil: 既存衝突 (overwrite=false 時 409 判定材料)
    Deduplicated bool   // true: サーバー側で既存コンテンツと一致、クライアントはアップロード不要 (PROTOCOL §6.3.1 dedup 応答)
}
```

---

## 4. DB スキーマ

**変更なし**（v1 の `devices` / `pair_nonces` / `uploads` / `server_meta` スキーマで要件充足）

---

## 5. 外部依存（v1 追加分）

- `github.com/pressly/goose/v3` — SQL マイグレーション管理、`//go:embed` による単一バイナリ埋込 [m2]
- `golang.org/x/crypto/hkdf` — HKDF-SHA256 (v1 の crypto/ed25519 と組み合わせて使用)

v1 選定（変更なし）:
- `modernc.org/sqlite` (pure Go)
- `github.com/go-chi/chi/v5`
- `log/slog` (標準)
- `github.com/knadh/koanf/v2`
- `crypto/ed25519` (標準) + 埋込 BIP39 wordlist

---

## 6. 設計判断ノート

### 6.1 レート制御の責務分離

`NonceStore` は CRUD（永続化）のみ、レート制御は `usecase/pairing.go` が `RateLimiter.Allow(sourceIP, 1*time.Minute, 5)` を呼び出して判定。実装は in-memory sliding window で十分（同時接続 <10）。

### 6.2 Idempotent chunk retry 判定

```go
func (s *uploadSession) AppendChunk(ctx, id string, rangeStart int64, data []byte) error {
    state := s.get(id)
    rangeEnd := rangeStart + int64(len(data)) - 1

    // idempotent retry: 既受信範囲に完全に収まる
    if rangeStart < state.UploadedBytes && rangeEnd < state.UploadedBytes {
        return nil // 200 応答、上書きしない
    }
    // 境界またぎ → 409
    if rangeStart < state.UploadedBytes && rangeEnd >= state.UploadedBytes {
        return ErrRangeMismatch
    }
    // start == uploaded_bytes: 通常進行
    if rangeStart == state.UploadedBytes {
        // write...
        state.UploadedBytes = rangeEnd + 1
        return nil
    }
    // start > uploaded_bytes: 先走り禁止 (sequential 必須)
    return ErrRangeMismatch
}
```

### 6.3 Dedup 実装優先度（`DeduplicateLink`）

1. `reflink` (Linux btrfs/xfs `FICLONE` ioctl、macOS APFS `clonefile`) — CoW、容量効率最大
2. `hardlink` (POSIX `link`、Windows `CreateHardLinkW`) — 同一 inode、削除時 reference count 管理に注意
3. `copy` (`io.Copy`) — フォールバック、容量 2x 消費

サーバーは起動時に対象 FS の reflink 能力を検知（`statfs` / FS type）し、最適手段を選ぶ。

---

## 7. 要確認（次ラウンドで解消予定）

- v0.2.1 の `chunk_size` 推奨値（PROTOCOL §6.3.1 example では 8 MiB = 8388608、`chunk_size_max` は 32 MiB = 33554432）を config デフォルトとして採用予定
- `server_meta` の master_key は v0.2.1 では平文 BLOB + ファイルパーミッション 0600、v0.4 で OS-level secret store 移行予定（PROTOCOL §3.1 に追記必要）
