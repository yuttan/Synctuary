# サヤ Go サーバーアーキテクチャ提案 v3（PROTOCOL v0.2.2 準拠）

**Date**: 2026-04-22
**Base**: `arch_saya_go_server_v2.md` + Gemini v0.2.1 差分レビュー 4 件反映
**Target**: PROTOCOL.md v0.2.2 準拠

---

## Change Log

### v2 → v3（PROTOCOL v0.2.2 差分取り込み）

- [V1] **`UploadSession.Init` に単一アクティブセッション排他ロジック追加**（PROTOCOL §6.3.5 対応）
  - 同一 `path` でアクティブセッション存在時は `ErrUploadInProgress` + `ActiveUploadInfo` 返却
  - Init はアトミックトランザクション（INSERT ... WHERE NOT EXISTS ... または SELECT FOR UPDATE 相当）
- [V2] **`FileStorage.DeduplicateLink` エラーセマンティクス明文化**（PROTOCOL §6.3.1 dedup fallback 対応）
  - `nil` / `ErrDedupUnsupported`（回復可能）/ その他（回復不能）の 3 値返却
  - usecase 層は `ErrDedupUnsupported` 受信時、通常アップロードにフォールスルー
- [V3] **CSPRNG 強制**（PROTOCOL §4.2 / §4.3 対応）
  - `crypto/rand.Read` を device_token / nonce 生成に使用
  - リンタルール（`forbidigo`）で `math/rand` の import 禁止
- [V4] **DB スキーマに `uploads` テーブルの部分ユニークインデックス追加**（V1 サポート）
- [V5] **`ActiveUploadInfo` struct 新設**（409 レスポンス用 DTO）

### v1 → v2（参考）

- [M1] `AppendChunk`: `chunkIndex int` → `rangeStart int64`
- [M2] `DeviceRepository.GetByID` 追加
- [M3] `RateLimiter` interface 分離
- [M4] `UploadInitParams` / `UploadInitResult` 集約
- [M5] `UploadSession.Progress` 復活
- [M6] `UploadInitParams.Filename` → `Path`
- [m1] `SecretStore` interface 新設
- [m2] `github.com/pressly/goose/v3` 採用
- [m3] `FileStorage.DeduplicateLink` 新設
- [n1-n3] 軽微な構成・型補正

---

## 1. ディレクトリ構成（v2 からの差分適用後）

```text
synctuary-server/
├── cmd/synctuaryd/main.go
├── internal/
│   ├── domain/
│   │   ├── device/
│   │   │   ├── entity.go
│   │   │   ├── repository.go
│   │   │   └── bip39.go
│   │   ├── file/
│   │   │   ├── meta.go                 # FileMeta
│   │   │   └── upload.go               # UploadState, UploadInitParams, UploadInitResult, ActiveUploadInfo [V5]
│   │   ├── nonce/
│   │   │   └── store.go
│   │   └── rate/
│   │       └── limiter.go
│   ├── usecase/
│   │   ├── pairing.go
│   │   ├── file_service.go             # dedup fallthrough 分岐実装 [V2]
│   │   └── device_service.go
│   └── adapter/
│       ├── interface/http/
│       │   ├── handler.go              # 409 upload_in_progress レスポンス生成 [V1]
│       │   └── middleware.go
│       └── infrastructure/
│           ├── db/
│           │   └── sqlite.go
│           ├── fs/
│           │   └── file_storage.go     # ErrDedupUnsupported 返却ロジック [V2]
│           ├── secret/
│           │   └── file_store.go
│           ├── rate/
│           │   └── memory_limiter.go
│           └── crypto.go               # crypto/rand.Read ラッパ [V3]
├── pkg/config/
│   └── config.go
├── migrations/
│   ├── 001_init.sql
│   └── 002_uploads_active_unique.sql   # 部分 UNIQUE INDEX [V4]
├── go.mod
└── PROTOCOL.md
```

---

## 2. Interface 定義（v3 更新部分）

### 2.1 UploadSession（V1 差分）

```go
// internal/domain/file/upload.go

type UploadSession interface {
    // Init: 同一 path にアクティブセッション存在時は (nil, ErrUploadInProgress) を返す。
    //   usecase 側は別途 SessionID を取得し *ActiveUploadInfo を handler に渡す。
    //   実装はアトミック（SQLite: INSERT with partial unique index / BEGIN IMMEDIATE トランザクション）
    Init(ctx context.Context, params *UploadInitParams) (*UploadInitResult, error)

    AppendChunk(ctx context.Context, id string, rangeStart int64, data []byte) error
    Progress(ctx context.Context, id string) (uploadedBytes int64, completed bool, expiresAt int64, err error)
    Abort(ctx context.Context, id string) error

    // [V1] 同一 path の現アクティブセッション情報を取得（409 応答生成用）
    //   ErrUploadInProgress ハンドリング後に handler が呼び出す
    ActiveByPath(ctx context.Context, path string) (*ActiveUploadInfo, error)
}

// エラー定義
var (
    ErrRangeMismatch       = errors.New("upload_range_mismatch")
    ErrUploadInProgress    = errors.New("upload_in_progress")    // [V1]
    ErrDedupUnsupported    = errors.New("dedup_unsupported")     // [V2]
)
```

### 2.2 FileStorage（V2 差分）

```go
// internal/domain/file/meta.go

type FileStorage interface {
    Put(ctx context.Context, path string, r io.Reader) error
    Get(ctx context.Context, path string, rangeStart, rangeEnd int64) (io.ReadCloser, error)
    Delete(ctx context.Context, path string, recursive bool) error
    Move(ctx context.Context, from, to string, overwrite bool) error

    // DeduplicateLink: 既存 SHA256 のコンテンツを targetPath にリンク
    //
    // 戻り値セマンティクス [V2]:
    //   nil                   : dedup 成功（reflink / hardlink 成立）
    //   ErrDedupUnsupported   : 回復可能（cross-device / FS が link 非対応 等）
    //                           → usecase は通常 upload にフォールスルー（§6.3.1 (a)）
    //                              or 同期コピー実行（§6.3.1 (b)、timeout 付き）
    //   その他 error           : 回復不能（親ディレクトリ不在→404、overwrite=false 衝突→409 等は
    //                           呼び出し側で別途判定）
    DeduplicateLink(ctx context.Context, existingSHA256 []byte, targetPath string) error
}
```

### 2.3 SecretStore / DeviceRepository / NonceStore / RateLimiter

**変更なし**（v2 から踏襲）

---

## 3. 新規 / 更新 struct 定義

```go
// internal/domain/file/upload.go

type UploadInitParams struct {
    Path      string
    Size      int64
    SHA256    []byte
    Overwrite bool
}

type UploadInitResult struct {
    SessionID    string
    ChunkSize    int64
    ChunkSizeMax int64
    Existing     *FileMeta
    Deduplicated bool
}

// [V5] 409 upload_in_progress レスポンスボディ用
//   PROTOCOL §6.3.5 準拠: 他セッションの upload_id は漏洩させない
type ActiveUploadInfo struct {
    CreatedAt     int64 `json:"created_at"`
    UploadedBytes int64 `json:"uploaded_bytes"`
    Size          int64 `json:"size"`
    ExpiresAt     int64 `json:"expires_at"`
}
```

---

## 4. DB スキーマ差分（V4）

### 4.1 既存 `uploads` テーブル（v2 から継続）

変更なし。

### 4.2 追加マイグレーション `migrations/002_uploads_active_unique.sql`（実装確定版）

```sql
-- [V4] PROTOCOL §6.3.5 single-active-session-per-path 制約
CREATE UNIQUE INDEX idx_uploads_path_active
  ON uploads(path)
  WHERE completed = 0;
```

**設計上の重要な補正（初期案からの変更）**:

初期案では `WHERE completed = 0 AND expires_at > strftime('%s','now')` としていたが、SQLite の partial index は **WHERE 句を INSERT/UPDATE 時にのみ評価** し、既存行に対して時間経過で再評価しない（`https://www.sqlite.org/partialindex.html`）。そのため `expires_at > now` を入れると:

- 挿入時点で有効だった行は以後もずっとインデックスに残る
- 期限切れ後に同一 path で legitimate な再 Init が来ると、残存行と衝突して誤って 409 を返す

結論: 述語は `completed = 0` のみとし、**expiry ハンドリングは Init トランザクション内で明示的に行う**:

```sql
BEGIN IMMEDIATE;
DELETE FROM uploads
  WHERE path = :path AND completed = 0 AND expires_at <= :now;
INSERT INTO uploads (...) VALUES (...);
  -- 非期限切れのアクティブセッション存在時は UNIQUE 違反 → ErrUploadInProgress
COMMIT;
```

これにより:
- partial index の述語は単純で安定（INSERT 時評価でも問題なし）
- 期限切れ処理は単一コードパスに集約
- `BEGIN IMMEDIATE` で書き込みロックを早期取得し、listing → insert の race を排除

**`CHECK (length(device_id) = 16)` 等のバリデーションは `001_init.sql` 側に集約**（migration 002 は制約追加のみ）。

---

## 5. usecase 層 Dedup フォールスルー実装（V2）

```go
// internal/usecase/file_service.go

func (s *fileService) InitUpload(ctx context.Context, params *UploadInitParams) (*UploadInitResult, error) {
    // 1. 同一 SHA の既存コンテンツがあれば dedup 試行
    if existing, err := s.fileRepo.FindBySHA(ctx, params.SHA256); err == nil && existing != nil {
        err := s.storage.DeduplicateLink(ctx, params.SHA256, params.Path)
        switch {
        case err == nil:
            // dedup 成功: §6.3.1 「deduplicated」応答
            return &UploadInitResult{Deduplicated: true}, nil

        case errors.Is(err, file.ErrDedupUnsupported):
            // [V2] §6.3.1 (a) fallthrough: 通常 upload に落とす
            //   サーバーは (b) 同期コピーも選択可能だが、config.Upload.DedupFallback で切り替え
            if s.cfg.Upload.DedupFallback == "sync_copy" {
                if copyErr := s.syncCopyWithTimeout(ctx, existing.Path, params.Path, 30*time.Second); copyErr == nil {
                    return &UploadInitResult{Deduplicated: true}, nil
                }
                // timeout/copy 失敗時は通常 upload にフォールバック
            }
            // 通常 upload 経路へ落とす（break して下に続く）

        default:
            return nil, err // 回復不能エラー
        }
    }

    // 2. 通常 upload init（単一アクティブセッション排他は UploadSession.Init 内で実装）
    result, err := s.uploads.Init(ctx, params)
    if errors.Is(err, file.ErrUploadInProgress) {
        return nil, err // handler が ActiveByPath を追加取得し 409 を組み立てる
    }
    return result, err
}
```

---

## 6. HTTP handler 層の 409 応答組み立て（V1）

```go
// internal/adapter/interface/http/handler.go

func (h *handler) PostUploadInit(w http.ResponseWriter, r *http.Request) {
    params, err := parseInitBody(r)
    if err != nil { writeError(w, 400, "bad_request", err); return }

    result, err := h.fileService.InitUpload(r.Context(), params)
    switch {
    case err == nil:
        writeJSON(w, 200, result)

    case errors.Is(err, file.ErrUploadInProgress):
        active, aerr := h.uploads.ActiveByPath(r.Context(), params.Path)
        if aerr != nil { writeError(w, 500, "internal", aerr); return }
        writeJSON(w, 409, map[string]any{
            "error":         "upload_in_progress",
            "active_upload": active,
        })

    default:
        writeError(w, 500, "internal", err)
    }
}
```

---

## 7. CSPRNG 強制（V3）

### 7.1 crypto ユーティリティ

```go
// internal/adapter/infrastructure/crypto.go

import cryptorand "crypto/rand"

// GenerateRandomBytes: CSPRNG (/dev/urandom or getrandom(2)) 経由で乱数生成
//   device_token (32B) / nonce (32B) 共通で利用
func GenerateRandomBytes(n int) ([]byte, error) {
    b := make([]byte, n)
    if _, err := cryptorand.Read(b); err != nil {
        return nil, err
    }
    return b, nil
}
```

### 7.2 リンタ設定（`.golangci.yml` 抜粋）

```yaml
linters:
  enable:
    - forbidigo
linters-settings:
  forbidigo:
    forbid:
      - p: '^math/rand(\.|$)'
        msg: "use crypto/rand for security-sensitive randomness (PROTOCOL §4.2 / §4.3 CSPRNG requirement)"
```

### 7.3 device_token ハッシュ保存

PROTOCOL v0.2.2 §4.3 推奨に従い、`devices` テーブルの `token_hash` カラムには SHA-256 ハッシュを格納（平文 token はレスポンスで一度だけクライアントに返却後、サーバーに保持しない）。

```go
// DeviceRepository.Create 内
tokenRaw, _ := GenerateRandomBytes(32)
h := sha256.Sum256(tokenRaw)
device.TokenHash = h[:]
// client には base64url(tokenRaw) を返却、サーバーは TokenHash のみ永続化
```

---

## 8. 設計判断ノート（v2 から継続 + v3 追加分）

### 8.1 単一アクティブセッション排他: DB 制約 vs アプリ層ロック（V1）

**採用: DB 部分 UNIQUE INDEX（V4）+ アプリ層 BEGIN IMMEDIATE トランザクション併用**

理由:
- SQLite の部分インデックスは宣言的で race condition に強い（エンジンレベルで担保）
- 将来 PostgreSQL 等へ移行する際も部分インデックスは移植可能（PostgreSQL も UNIQUE ... WHERE 対応）
- BEGIN IMMEDIATE で書き込みロックを早期取得し、listing → insert の間に他接続が割り込む余地を消す

### 8.2 Dedup フォールバック戦略の選択（V2）

**デフォルト: (a) fallthrough（通常 upload に落とす）**

`config.Upload.DedupFallback` で `"fallthrough"` (default) / `"sync_copy"` を切り替え可能。

理由:
- (a) は実装単純でクライアント挙動が予測可能（dedup 試行失敗 → 通常 PUT 連鎖）
- (b) は巨大ファイル同期コピーで Init レスポンスが長時間ブロックされうる → タイムアウト 30s で fail fast → (a) へ落ちる二段構え
- home server 環境では cross-device link はむしろ常態（複数 HDD）なので (a) のほうが現実的

### 8.3 CSPRNG 強制の実装選択肢（V3）

**採用: forbidigo による静的解析 + code review でのダブルチェック**

理由:
- `math/rand` は Go 標準ライブラリで、ランタイムに発見するのは困難
- CI に `golangci-lint` を組み込めば PR 時点で検出可能
- `crypto/rand.Read` はエラー返却するため、必ずエラーチェックせよ（ラッパで徹底）

---

## 9. 要確認（次ラウンド）

- `DedupFallback` 設定値の default 確定（`"fallthrough"` 推奨）
- `sync_copy` タイムアウト値の config 化（`config.Upload.DedupSyncCopyTimeout = "30s"`）
- 部分インデックスの SQLite バージョン依存確認（3.8.0+ で partial index サポート、modernc.org/sqlite は十分新しい）
- `devices.token_hash` 既存カラム名確認（v1 DDL で `token_hash` になっているはず）

---

## 10. 実装委譲タスク分解（サヤ向け、Final 化後）

PROTOCOL v0.2.2 Final 化後に以下をサヤへ委譲可能:

1. `migrations/001_init.sql` — 初期 DDL（devices / pair_nonces / uploads / server_meta）
2. `migrations/002_uploads_active_unique.sql` — 部分 UNIQUE INDEX [V4]
3. `cmd/synctuaryd/main.go` — skeleton（config load → DB open → goose migrate → router → graceful shutdown）
4. `internal/adapter/infrastructure/crypto.go` — CSPRNG ラッパ + BIP39 → HKDF → Ed25519 派生関数 [V3]
5. `internal/domain/device/bip39.go` — `//go:embed bip39_english.txt` + wordlist 検索
6. `internal/adapter/infrastructure/fs/file_storage.go` — reflink/hardlink/copy の 3 段 dedup 実装 [V2]

各タスクは単独で委譲可（依存: 1 → 2, 3 → 4 → 5）。Claude が Interface 提示、サヤが実装、Claude がレビュー＋セキュリティ監査。
