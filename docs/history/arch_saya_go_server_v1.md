# サヤ Go サーバーアーキテクチャ提案

**Date**: 2026-04-21
**Target**: PROTOCOL.md v0.2.1 準拠 Go サーバー基礎設計
**Delegator**: サヤ（qwen3.6-35b-a3b / LM Studio）

---

## Task 1: ディレクトリ構成案

```text
synctuary-server/
├── cmd/synctuaryd/main.go          # エントリポイント。設定読み込み・DIワイヤリング・サーバー起動
├── internal/
│   ├── domain/                     # ドメイン層：エンティティ、値オブジェクト、ドメインエラー
│   │   ├── device/entity.go        # Device, KeyPair 構造体
│   │   └── file/entity.go          # FileMeta, UploadState 構造体
│   ├── usecase/                    # アプリケーション層：ユースケース（ビジネスルール）
│   │   ├── pairing.go              # nonce発行・登録フロー
│   │   ├── file_service.go         # ファイルCRUD・アップロードオーケストレーション
│   │   └── device_service.go       # デバイス管理ロジック
│   └── adapter/                    # インフラ層・インターフェース層
│       ├── interface/http/         # HTTPハンドラ、ミドルウェア、ルーティング定義
│       │   ├── handler.go          # ルーター設定 & エンドポイントマッピング
│       │   └── middleware.go       # Auth, Range, Logging, Recovery
│       └── infrastructure/         # 外部リソース実装（DB, FS, Crypto）
│           ├── db/                 # SQLite接続、トランザクションラッパー
│           ├── fs/                 # ファイルストレージ抽象化・Chunk書き込み
│           └── crypto.go           # HKDF/Ed25519 具象実装
├── pkg/config/                     # 再利用可能設定パッケージ（struct定義 + ローダー）
├── migrations/                     # SQLマイグレーションファイル（001_init.sql等）
├── go.mod
└── PROTOCOL.md
```

**レイヤー間依存方向**
- `adapter/interface/http` → `usecase` → `domain`（一方向のみ）
- `infrastructure/` は `usecase`/`domain` で定義された interface を実装（逆依存：interface は内側で定義、具象は外側で配置）
- `cmd/` は全レイヤーを結合する wiring 専用。ドメインロジックを含まない

---

## Task 2: 主要 interface シグネチャ

```go
// DeviceRepository: デバイスメタデータ永続化
type DeviceRepository interface {
    Create(ctx context.Context, d *domain.Device) error
    GetByTokenHash(ctx context.Context, hash []byte) (*domain.Device, error)
    UpdateLastSeen(ctx context.Context, id []byte, ts int64) error
    Revoke(ctx context.Context, id []byte, revokedAt int64) error
    ListActive(ctx context.Context) ([]*domain.Device, error)
    Delete(ctx context.Context, id []byte) error
}

// NonceStore: ペアリング用 nonce 発行/検証/消費
type NonceStore interface {
    Issue(ctx context.Context, sourceIP string) (nonce []byte, expiresAt int64, err error)
    VerifyAndConsume(ctx context.Context, nonce []byte) error // 期限切れ／未消費なら ErrNonceInvalid
}

// UploadSession: チャンクアップロード状態管理（idempotent retry対応）
type UploadSession interface {
    Init(ctx context.Context, id string, path string, size int64, sha256Expected []byte) error
    AppendChunk(ctx context.Context, id string, chunkIndex int, data []byte) error // 同一chunk再書き込みでも安全
    Progress(ctx context.Context, id string) (uploadedBytes int64, completed bool, err error)
    Cancel(ctx context.Context, id string) error
}
```

---

## Task 3: DB スキーマ（SQLite DDL）

```sql
CREATE TABLE devices (
    device_id          BLOB(16) PRIMARY KEY,
    device_pub         BLOB(32) NOT NULL,
    device_token_hash  BLOB(32) UNIQUE NOT NULL, -- ログリーク耐性：生トークンではなくSHA-256ハッシュを保存
    device_name        TEXT,
    platform           TEXT,
    created_at         INTEGER NOT NULL,
    last_seen_at       INTEGER NOT NULL DEFAULT 0,
    revoked            INTEGER DEFAULT 0,
    revoked_at         INTEGER
);

CREATE TABLE pair_nonces (
    nonce      BLOB(32) PRIMARY KEY,
    issued_at  INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed   INTEGER DEFAULT 0,
    source_ip  TEXT     -- レート制御・重複登録検知用IP保持
);

CREATE TABLE uploads (
    upload_id        TEXT PRIMARY KEY,
    path             TEXT NOT NULL,
    size             INTEGER NOT NULL,
    sha256_expected  BLOB(32),
    uploaded_bytes   INTEGER DEFAULT 0,
    staging_path     TEXT NOT NULL, -- アップロード中の一時格納先（完了時アトミックリネーム）
    created_at       INTEGER NOT NULL,
    last_write_at    INTEGER NOT NULL,
    expires_at       INTEGER NOT NULL -- 未完了セッションのGC用
);

CREATE TABLE server_meta (
    key   TEXT PRIMARY KEY,
    value BLOB
);
```

**設計判断コメント**（サヤ）
- `device_token_hash` UNIQUE: トークンログリーク時の重複登録防止。生トークンはメモリ/キャッシュのみ
- `pair_nonces.expires_at`, `uploads.expires_at`: 定期クリーンアップ（CRON or 起動時）用インデックス対象
- `staging_path`: チャンク書き込み中サーバー停止しても `uploaded_bytes` と整合、完了時 `path` にアトミックリネーム

---

## Task 4: DB 推奨

`modernc.org/sqlite` を推す。Pure Go 実装のため CGO 不要で単一バイナリ・クロスコンパイル容易。SQLite の SQL 互換性で DDL/インデックス設計がそのまま適用可能。同時書き込み<10件なら WAL モードで十分。`mattn/go-sqlite3` は CGO 依存でビルド環境が重く、`BoltDB` は SQL/範囲クエリ未対応のため除外。

---

## Task 5: 主要外部依存候補

| カテゴリ | 推奨 | 根拠 |
|:---|:---|:---|
| HTTP ルーター | `github.com/go-chi/chi/v5` | stdlib 互換の軽量ミドルウェアチェーン、外部依存ゼロに近い |
| 構造化ロガー | `log/slog` (標準) | Go 1.21+ 成熟、JSON ハンドラ内蔵、外部依存不要 |
| 設定読み込み | `github.com/knadh/koanf/v2` | グローバル状態なし struct 明示マッピング、env/file 優先順位制御が柔軟 |
| BIP39/HKDF/Ed25519 | `crypto/ed25519`, `crypto/hkdf` (標準) + 埋め込み BIP39 wordlist | 外部 crypto 依存排除、単一バイナリ配布を簡素化 |

---

## サヤの要確認事項

- `PROTOCOL.md v0.2.1` のアップロードチャンクサイズ上限・最大ファイルサイズが未定義 → `uploads.size` と `staging_path` 配下のディレクトリ分割戦略は実装時確定予定
- BIP39 seed から Ed25519 keypair を派生する際、固定 salt/path (`synctuary/device`) を HKDF に渡すか、seed そのものを HKDF-IN に使うかは PROTOCOL.md の暗号化フロー次第で調整必要
